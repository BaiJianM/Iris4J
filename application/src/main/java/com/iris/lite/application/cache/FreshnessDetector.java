package com.iris.lite.application.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 时效词检测器：用户问题含「最新 / 现在 / 今天」类指示词时，
 * 该问题隐含「以说话时刻为准」的语义，任何缓存条目（本质是历史快照）都
 * 可能已过期——必须绕过缓存强制走真实查询/LLM 调用。
 *
 * <p><b>为什么是启发式而不是 LLM 判定</b>：检测发生在每次缓存查找的最前面，
 * 引入 LLM 往返等于「为了省一次调用先花一次调用」；关键词表是确定性规则，
 * 零成本、可解释、可配置。漏判方向安全（多命中一次缓存，最坏 TTL/守卫兜底），
 * 误判方向代价可控（多一次真实查询，答案只会更新鲜）。
 *
 * <p><b>与既有新鲜度机制的关系</b>：版本守卫只在「数据变了」时拦截缓存，
 * 对「问题本身要求此刻标准」无能为力（如 MCP 模式下依赖未声明、或数据恰好
 * 没变但用户语义上要求现算）；本检测器补的是「问题侧的实时性意图」这一维。
 *
 * <p><b>消费方</b>：
 * <ul>
 *   <li>{@link LlmCacheService#lookup}——prompt 命中时效词 → 返回
 *       reason=fresh-keyword 的未命中（REST /lookup、MCP 工具、Agent 三条
 *       入口共用同一必经点）；</li>
 *   <li>{@code DefaultAgentService}——问题命中时效词时给工具执行上下文
 *       打 fresh 标记，经 {@code QueryRequest.fresh} 穿透语义/精确两层
 *       查询缓存，直达 Query Engine。</li>
 * </ul>
 *
 * <p><b>配置</b>：{@code iris.cache.freshness-bypass.enabled}（默认开）、
 * {@code iris.cache.freshness-bypass.keywords}（逗号分隔覆盖默认词表）。
 */
@Component
public class FreshnessDetector {

    private static final Logger log = LoggerFactory.getLogger(FreshnessDetector.class);

    /**
     * 默认词表：指示词（所指依赖说话时刻的词）。
     * 「今天/今日/刚刚/刚才」刻意包含——它们与「现在」同属以当下为锚的时间指称，
     * 「今天有多少订单」语义上就是「截到现在有多少」。误伤方向（多查一次真实数据）
     * 比漏放方向（回放过期答案）安全。
     */
    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "最新", "现在", "目前", "当前", "实时", "此刻", "今天", "今日", "刚刚", "刚才");

    private final boolean enabled;
    private final List<String> keywords;

    public FreshnessDetector(
            @Value("${iris.cache.freshness-bypass.enabled:true}") boolean enabled,
            @Value("${iris.cache.freshness-bypass.keywords:}") String keywords) {
        this.enabled = enabled;
        List<String> parsed = keywords == null || keywords.isBlank()
                ? List.of()
                : Arrays.stream(keywords.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        this.keywords = parsed.isEmpty() ? DEFAULT_KEYWORDS : List.copyOf(parsed);
        log.info("FreshnessDetector 已装配 enabled={} keywords={}", this.enabled, this.keywords);
    }

    /** 开关是否打开（关闭时全部消费方按原行为走缓存）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 判定文本是否命中时效词。
     *
     * @return true = 含时效指示词，缓存查找方应绕过缓存强制现算；
     *         enabled=false 或文本为空恒 false
     */
    public boolean isFreshSensitive(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
