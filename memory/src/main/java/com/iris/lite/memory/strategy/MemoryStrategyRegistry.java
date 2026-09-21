package com.iris.lite.memory.strategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆策略注册表：按名字定位策略，未知名回落缺省策略。
 *
 * <p><b>回落而非报错</b>：策略名来自配置/请求参数，配错时回落 discrete——
 * 配错时"抽出来的内容不对"远好过"整个记忆晋升链路停摆"。
 *
 * <p><b>纯 Java 类</b>：memory 模块不依赖 Spring/日志框架；由 infrastructure 层
 * 把四个策略实现装配成 Bean 注入本类。
 */
public class MemoryStrategyRegistry {

    /** 四策略的缺省名（缺省 discrete）。 */
    public static final String DEFAULT_STRATEGY = "discrete";

    private final Map<String, MemoryStrategy> strategies = new LinkedHashMap<>();

    public MemoryStrategyRegistry(List<MemoryStrategy> implementations) {
        for (MemoryStrategy strategy : implementations) {
            strategies.put(strategy.name().toLowerCase(), strategy);
        }
        if (!strategies.containsKey(DEFAULT_STRATEGY)) {
            throw new IllegalArgumentException("策略注册表必须包含缺省策略 discrete");
        }
    }

    /**
     * 按名取策略；空/未知名回落 discrete。
     *
     * @param name 策略名（忽略大小写）；null 或未注册时回落
     */
    public MemoryStrategy get(String name) {
        if (name == null || name.isBlank()) {
            return strategies.get(DEFAULT_STRATEGY);
        }
        return strategies.getOrDefault(name.toLowerCase(), strategies.get(DEFAULT_STRATEGY));
    }

    /** 已注册策略名（供管理端点/工具描述展示）。 */
    public Set<String> names() {
        return Set.copyOf(strategies.keySet());
    }
}
