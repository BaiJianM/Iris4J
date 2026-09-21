package com.iris.lite.infrastructure.memory.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.memory.LlmClient;
import com.iris.lite.memory.MemoryType;
import com.iris.lite.memory.strategy.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 策略执行器：四个记忆策略共用的"LLM 调用 + JSON 容错解析"底座。
 *
 * <p><b>职责切分</b>：策略实现只负责"给什么 prompt、对结果做什么校验"；
 * LLM 阻塞调用、输出格式容错、候选字段映射全部收敛在这里，一处维护。
 *
 * <p><b>容错约定</b>：LLM 输出可能是干净 JSON、
 * {@code ```json} 围栏、前后带解释文字、或 {@code {"memories":[...]}} 对象包裹。
 * 解析策略：剥围栏 → 截首个 '[' 到末个 ']' → 兜底取对象形态；
 * 全部失败按空结果处理（抽取是尽力而为，绝不因解析失败炸掉调用方）。
 *
 * <p><b>条件装配</b>：{@code iris.memory.extractor.enabled=true} 才创建——
 * 开了抽取却没配 LLM 会因依赖缺失启动失败，属于刻意的 fail-fast。
 */
@Component
@ConditionalOnProperty(name = "iris.memory.extractor.enabled", havingValue = "true")
public class LlmStrategyExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmStrategyExecutor.class);

    /** 统一的输出格式约束（system 层）：只输出 JSON，杜绝解释性前后缀。 */
    private static final String FORMAT_RULE =
            "只输出一个 JSON 对象：{\"memories\":[...]}，不要输出任何解释、前后缀或代码围栏。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmStrategyExecutor(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        log.info("LLM 记忆策略执行器已启用");
    }

    /**
     * 执行一次策略抽取：组装 system/user prompt → LLM → 解析为候选列表。
     *
     * <p><b>异常语义</b>：LLM 调用失败抛 {@link IllegalStateException}
     * （由调用方决定重试/降级——异步 worker 会重试一次）；JSON 解析失败
     * 归约为空列表（输出格式问题重试也无济于事）。
     */
    public List<Candidate> execute(String systemPrompt, String userPrompt) {
        String raw;
        try {
            raw = llmClient.complete(systemPrompt + "\n" + FORMAT_RULE, userPrompt);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 记忆抽取调用失败: " + e.getMessage(), e);
        }
        return parseCandidates(raw);
    }

    /** 解析 LLM 输出为候选列表，多级容错，失败返回空列表。 */
    private List<Candidate> parseCandidates(String raw) {
        String json = extractJsonArray(raw);
        if (json == null) {
            log.warn("LLM 抽取输出无法定位 JSON，按无候选处理，原始输出前200字: {}",
                    raw.length() > 200 ? raw.substring(0, 200) : raw);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            // 数组形态与 {"memories":[...]} 对象形态都接受
            JsonNode arr = root.isArray() ? root : root.path("memories");
            List<Candidate> candidates = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    Candidate candidate = toCandidate(node);
                    // text 为空的脏项直接丢弃（LLM 偶尔输出占位项）
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
            log.debug("LLM 策略抽取完成 候选数={}", candidates.size());
            return candidates;
        } catch (Exception e) {
            log.warn("LLM 抽取输出 JSON 解析失败，按无候选处理: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /** 单条 JSON 对象 → Candidate；type 非法默认 semantic（宽容解析，让校验层决定去留）。 */
    private Candidate toCandidate(JsonNode node) {
        String text = node.path("text").asText("").trim();
        if (text.isEmpty()) {
            // 兼容旧字段名 content
            text = node.path("content").asText("").trim();
        }
        if (text.isEmpty()) {
            return null;
        }
        MemoryType type = "episodic".equalsIgnoreCase(node.path("type").asText(""))
                ? MemoryType.EPISODIC : MemoryType.SEMANTIC;
        return new Candidate(
                type,
                text,
                toStringList(node.path("topics")),
                toStringList(node.path("entities")),
                node.path("event_date").asText("").trim());
    }

    /** JsonNode 字符串数组 → List&lt;String&gt;（非数组/缺省返回空列表）。 */
    private List<String> toStringList(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        });
        return values;
    }

    /**
     * 从原始输出中定位 JSON 片段：
     * 先剥 ```json 围栏，再截首个 '[' 到末个 ']'；截不到再试 {"...":[...]} 对象形态。
     */
    private String extractJsonArray(String raw) {
        String text = raw.trim();
        // 剥代码围栏：```json ... ``` 或 ``` ... ```
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        // 兜底：对象包裹形态 {"memories":[...]} —— 取整个对象
        int objStart = text.indexOf('{');
        int objEnd = text.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            return text.substring(objStart, objEnd + 1);
        }
        return null;
    }
}
