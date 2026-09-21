package com.iris.lite.infrastructure.memory.strategy;

import com.iris.lite.memory.strategy.MemoryStrategy;
import com.iris.lite.memory.strategy.MemoryStrategyRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 记忆策略装配：把四个策略实现收进注册表（discrete/summary/preferences/custom）。
 *
 * <p>注册表本身是纯 Java 类（memory 模块不依赖 Spring），
 * Spring 装配归 infrastructure——策略实现加 @Component 即自动入表。
 */
@Configuration
public class MemoryStrategyConfig {

    @Bean
    @ConditionalOnProperty(name = "iris.memory.extractor.enabled", havingValue = "true")
    public MemoryStrategyRegistry memoryStrategyRegistry(List<MemoryStrategy> strategies) {
        return new MemoryStrategyRegistry(strategies);
    }
}
