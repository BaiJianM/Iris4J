package com.iris.lite.infrastructure.memory.strategy;

import com.iris.lite.memory.MemoryType;
import com.iris.lite.memory.strategy.Candidate;
import com.iris.lite.memory.strategy.MemoryStrategy;
import com.iris.lite.memory.strategy.StrategyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * custom 策略：调用方自带 prompt 的自定义抽取。
 *
 * <p><b>两道安全闸</b>（prompt 入参校验 + 输出过滤）：
 * <ol>
 *   <li><b>prompt 入参校验</b>：长度上限、控制字符、可疑短语黑名单——
 *       自定义 prompt 来自调用方，属于不可信输入，直接拼进 LLM 请求前必须过滤；</li>
 *   <li><b>输出过滤</b>：text ≤ 1000 字符、命中黑名单的条目丢弃、
 *       topics/entities 每项 ≤ 100 字符、type 仅允许 semantic/episodic。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "iris.memory.extractor.enabled", havingValue = "true")
public class CustomMemoryStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(CustomMemoryStrategy.class);

    /** 自定义 prompt 长度上限（超出视为误用/攻击载荷，直接拒绝）。 */
    private static final int MAX_PROMPT_LENGTH = 2000;

    /** 输出 text 长度上限。 */
    private static final int MAX_TEXT_LENGTH = 1000;

    /** topics/entities 单项长度上限。 */
    private static final int MAX_TAG_LENGTH = 100;

    /**
     * 可疑短语黑名单：自定义 prompt 与抽取输出命中即拦截——
     * 防止把"系统提示词注入/凭据窃取"类内容沉淀为长期记忆并反复注入后续对话。
     */
    private static final List<String> BLACKLIST = List.of(
            "system", "instruction", "ignore", "override", "execute", "eval", "import",
            "__", "subprocess", "os.system", "api_key", "secret", "password",
            "token", "credential", "private_key");

    private static final String SYSTEM_PROMPT =
            "你是记忆抽取器，严格按照用户给定的抽取要求工作。";

    private static final String USER_PROMPT_TEMPLATE = """
            当前时间：%s
            会话：namespace=%s sessionId=%s

            抽取要求（由调用方提供）：
            %s

            输出格式：
            {"memories":[{"type":"semantic或episodic","text":"记忆正文","topics":["标签"],"entities":["实体"],"event_date":"episodic 的 ISO 8601 日期，semantic 填 null"}]}

            会话文本：
            %s""";

    private final LlmStrategyExecutor executor;

    public CustomMemoryStrategy(LlmStrategyExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String name() {
        return "custom";
    }

    @Override
    public List<Candidate> extract(String sessionText, StrategyContext context) {
        String customPrompt = context.customPrompt();
        if (customPrompt == null || customPrompt.isBlank()) {
            log.warn("custom 策略未提供自定义 prompt，按无候选处理");
            return List.of();
        }
        if (!validatePrompt(customPrompt)) {
            return List.of();
        }
        List<Candidate> raw = executor.execute(SYSTEM_PROMPT,
                USER_PROMPT_TEMPLATE.formatted(
                        context.currentDateTime(), context.namespace(), context.sessionId(),
                        customPrompt, sessionText));
        return raw.stream().filter(this::validateOutput).toList();
    }

    /** prompt 入参校验：长度、控制字符、黑名单短语；不通过直接拒绝该次抽取。 */
    private boolean validatePrompt(String prompt) {
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            log.warn("自定义 prompt 超长（{} 字符 > {}），拒绝执行", prompt.length(), MAX_PROMPT_LENGTH);
            return false;
        }
        for (char c : prompt.toCharArray()) {
            if (Character.isISOControl(c) && c != '\n' && c != '\t') {
                log.warn("自定义 prompt 含非法控制字符，拒绝执行");
                return false;
            }
        }
        String lower = prompt.toLowerCase();
        for (String phrase : BLACKLIST) {
            if (lower.contains(phrase)) {
                log.warn("自定义 prompt 命中可疑短语 '{}'，拒绝执行", phrase);
                return false;
            }
        }
        return true;
    }

    /** 输出条目校验：超长截断、命中黑名单丢弃、type 白名单。 */
    private boolean validateOutput(Candidate candidate) {
        if (candidate.text().length() > MAX_TEXT_LENGTH) {
            log.warn("抽取条目超长（{} 字符），丢弃", candidate.text().length());
            return false;
        }
        String lower = candidate.text().toLowerCase();
        for (String phrase : BLACKLIST) {
            if (lower.contains(phrase)) {
                log.warn("抽取条目命中可疑短语 '{}'，过滤: {}", phrase,
                        candidate.text().length() > 50 ? candidate.text().substring(0, 50) : candidate.text());
                return false;
            }
        }
        if (candidate.type() == MemoryType.MESSAGE) {
            return false;
        }
        return candidate.topics().stream().allMatch(t -> t.length() <= MAX_TAG_LENGTH)
                && candidate.entities().stream().allMatch(e -> e.length() <= MAX_TAG_LENGTH);
    }
}
