package com.iris.lite.application.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.memory.LlmClient;
import com.iris.lite.shared.metrics.IrisMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LLM 介入的多查询改写器（RAG 第三召回通道）。
 *
 * <p><b>解决什么问题</b>：稠密检索对「查询的措辞」敏感——用户问
 * 「上月卖了多少钱」，记忆里存的是「2025年8月销售额」，余弦可能挤不进 top-K。
 * 改写器把原查询变成 N 个措辞不同的等价变体，每个变体各走一次稠密召回，
 * 命中面显著变宽（业界 multi-query / query expansion 范式）。
 *
 * <p><b>触发模式（{@code iris.rag.multi-query.mode}）</b>：
 * <ul>
 *   <li><b>always</b>：每次检索都改写——召回最全，但每次都多一次 LLM 往返；</li>
 *   <li><b>on-miss</b>：常规多路召回有结果就不打扰 LLM；融合池为空时
 *       升级改写抢救一次。缓存命中路径零 LLM 延迟，miss 路径多 ~1s 换「少一次
 *       真实 LLM 调用/查询落空」，期望账是划算的；</li>
 *   <li><b>off</b>（缺省）：通道关闭。改写通道默认关闭——推理型模型
 *       改写一轮耗时数秒（deepseek 约 9.5s）且烧 token，命中与召回的边际收益
 *       不足以覆盖常态延迟；需要召回兜底的部署显式开启 on-miss/always。</li>
 * </ul>
 *
 * <p><b>失败语义（fail-open）</b>：LLM 缺席/超时/输出不合规一律返回空列表——
 * 改写是增强通道，它挂了检索退回两通道，绝不能把主路径拖死。
 *
 * <p><b>延迟注</b>：{@code LlmClient.complete} 是阻塞调用。推理型模型
 * （deepseek-v4-pro 等）改写耗时可能数秒——对延迟敏感的场景把 mode 设为
 * off 或换低延迟模型是调用方的配置责任，本类不偷偷起线程池。
 */
@Component
public class MultiQueryExpander {

    private static final Logger log = LoggerFactory.getLogger(MultiQueryExpander.class);

    private static final String SYSTEM_PROMPT = """
            你是检索查询改写器。把用户的查询改写成若干个语义等价的变体：换用不同的措辞、\
            同义词与句式，但绝不改变查询中的日期、数字、实体、范围与意图。\
            只输出一个 JSON 字符串数组（例如 ["变体一","变体二"]），不要输出解释、\
            前后缀或代码块标记。""";

    public enum Mode {
        ALWAYS, ON_MISS, OFF;

        static Mode of(String value) {
            try {
                return value == null ? OFF : Mode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                // 未知配置值回落 off（缺省语义），而不是猜一个会烧 LLM 的档位
                return OFF;
            }
        }
    }

    private final ObjectProvider<LlmClient> llmProvider;
    private final ObjectMapper objectMapper;
    private final Mode mode;
    private final int variants;

    public MultiQueryExpander(
            ObjectProvider<LlmClient> llmProvider,
            ObjectMapper objectMapper,
            @Value("${iris.rag.multi-query.mode:off}") String mode,
            @Value("${iris.rag.multi-query.variants:2}") int variants) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
        this.mode = Mode.of(mode);
        this.variants = Math.max(1, Math.min(5, variants));
        log.info("MultiQueryExpander 已装配 mode={} variants={}", this.mode, this.variants);
    }

    /**
     * 本次是否应触发改写。
     *
     * @param onMissCondition 「miss 升级条件」是否已成立——语义由调用方定义：
     *                        记忆检索 = 常规通道融合池为空；LLM 缓存 = 主链路
     *                        未命中（KNN 有候选不等于命中，被精判拒绝同样是 miss）。
     *                        ALWAYS 模式恒 true（调用方在常规阶段就应主动触发）。
     */
    public boolean shouldExpand(boolean onMissCondition) {
        if (mode == Mode.OFF) {
            return false;
        }
        return mode == Mode.ALWAYS || onMissCondition;
    }

    /**
     * 把查询改写为变体列表（含去重、剔除与原查询相同者、封顶 variants 条）。
     * 任何失败返回空列表（fail-open），调用方照常两通道检索。
     */
    public List<String> expand(String query) {
        LlmClient llm = llmProvider.getIfAvailable();
        if (llm == null) {
            return List.of();
        }
        try {
            long start = System.currentTimeMillis();
            String out = llm.complete(SYSTEM_PROMPT,
                    "原查询：" + query + "\n生成变体数：" + variants);
            List<String> parsed = parseArray(out);
            Set<String> unique = new LinkedHashSet<>();
            String normalized = query.trim().toLowerCase(Locale.ROOT);
            for (String variant : parsed) {
                if (variant == null || variant.isBlank()) {
                    continue;
                }
                String v = variant.trim();
                if (!v.toLowerCase(Locale.ROOT).equals(normalized)) {
                    unique.add(v);
                }
                if (unique.size() >= variants) {
                    break;
                }
            }
            IrisMetrics.increment("iris.rag.multi-query", "result", "ok");
            log.debug("多查询改写完成 原查询='{}' 变体={} 耗时={}ms",
                    query, unique, System.currentTimeMillis() - start);
            return new ArrayList<>(unique);
        } catch (Exception e) {
            IrisMetrics.increment("iris.rag.multi-query", "result", "error");
            log.warn("多查询改写失败（跳过改写通道）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 宽容解析：剥离代码块围栏/前后缀后取首个 JSON 数组。解析失败由调用方 fail-open。 */
    private List<String> parseArray(String out) throws Exception {
        int left = out.indexOf('[');
        int right = out.lastIndexOf(']');
        if (left < 0 || right <= left) {
            throw new IllegalStateException("LLM 改写输出不含 JSON 数组: "
                    + out.substring(0, Math.min(120, out.length())));
        }
        return objectMapper.readValue(out.substring(left, right + 1),
                new TypeReference<List<String>>() {
                });
    }
}
