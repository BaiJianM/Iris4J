package com.iris.lite.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.infrastructure.config.RedisProperties;
import com.iris.lite.shared.key.DefaultKeyStrategy;
import com.iris.lite.shared.key.KeyStrategy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import java.time.Duration;


/**
 * Redis 基础设施装配。
 *
 * <p><b>隔离原则</b>：Lettuce 的类型（RedisClient / RedisURI / StatefulRedisConnection）
 * 只在本包内出现，对外暴露的 {@link KeyStrategy} 与 {@link RedisAdapter} 都是
 * 与客户端无关的业务抽象。客户端细节不泄漏到业务层。
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * Redis 客户端（连接工厂）。
     *
     * <p>Lettuce 的 {@code RedisClient} 是线程安全的、持有共享资源（线程池、连接），
     * 因此是单例 Bean，且必须注册 destroyMethod 在容器关闭时 shutdown，
     * 否则 JVM 退出时连接线程可能悬挂。
     *
     * <p>密码只在配置非空时设置——本机开发通常无密码，传空密码反而会被 Redis 拒绝。
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(RedisProperties props) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(props.host())
                .withPort(props.port())
                .withDatabase(props.database())
                .withTimeout(Duration.ofMillis(props.timeoutMs()));
        if (props.password() != null && !props.password().isBlank()) {
            builder.withPassword(props.password().toCharArray());
        }
        log.info("Redis 客户端已创建 host={} port={} db={} timeoutMs={}",
                props.host(), props.port(), props.database(), props.timeoutMs());
        return RedisClient.create(builder.build());
    }

    /**
     * 主连接：查询/投影/缓存/运维等普通命令。
     *
     * <p>{@code @Primary} 是因为容器里有两条同类型的
     * {@code StatefulRedisConnection}（主连接 + CDC 连接），
     * 需要给"未指定 qualifier 的注入点"一个默认选择。
     */
    @Bean(destroyMethod = "close")
    @Primary
    public StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        log.debug("创建 Redis 主连接（查询/投影/缓存/运维）");
        return client.connect();
    }

    /**
     * CDC 专用连接：XREADGROUP BLOCK 等阻塞命令会独占连接，
     * 若与查询共用会把 SCAN/JSON.GET 饿死。
     *
     * <p>注意：这里只承载 Stream 的<b>非阻塞</b>命令（XRANGE/XPENDING/XCLAIM/
     * XADD/XDEL/XACK）。真正的阻塞读（XREADGROUP BLOCK）由每个消费线程
     * 通过 {@link RedisAdapter#openBlockingSession()} 再各自开一条连接。
     * 见方案 6.2「CDC 消费并发模型」。
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> cdcRedisConnection(RedisClient client) {
        log.debug("创建 Redis CDC 连接（Stream 非阻塞命令）");
        return client.connect();
    }

    /** key 生成策略：全系统唯一的 key 生成入口。 */
    @Bean
    public KeyStrategy keyStrategy() {
        return new DefaultKeyStrategy();
    }

    /**
     * 全局 ObjectMapper。
     *
     * <p>用原生 Jackson 默认配置，不注册 JavaTimeModule 等扩展——
     * 投影数据是 Map 结构、时间戳以 Long/String 传递，不需要额外模块。
     * 保持简单，避免序列化行为在不同环境出现差异。
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
