package com.iris.lite.api.lifecycle;

import com.iris.lite.api.security.AgentKeyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动安全姿态自检：API-Key 鉴权未启用时打醒目 WARN。
 *
 * <p><b>为什么在启动时而不是文档里说</b>：鉴权关闭（legacy api-key 空 +
 * 无 agents[] 静态 key + 无动态 key = 全放行 + 匿名即 operator）是刻意的
 * 本地开发默认值——降低上手门槛；但它同时意味着 Schema 编辑、agent key
 * 管理、DLQ 重放等管理面端点全部裸奔。默认姿态可以宽松，但绝不允许
 * "静默地宽松"：任何人把本地配置原样带上生产，必须在启动日志里第一眼
 * 看到这条警告。
 *
 * <p>判定用 {@link AgentKeyRegistry#isAuthEnabled()}（legacy + 静态 + 动态
 * 三来源任一非空即视为已启用）而非只看 legacy api-key——只配 agents[]
 * 时鉴权实际已开启，误报会造成狼来了效应。
 *
 * <p>监听 {@link ApplicationReadyEvent} 而非 @PostConstruct：等全部装配完成、
 * 端口真正开始服务时再评估，避免配置被后续阶段覆盖时误报。
 */
@Component
public class StartupSecurityWarning {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityWarning.class);

    private final AgentKeyRegistry registry;

    public StartupSecurityWarning(AgentKeyRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfAuthDisabled() {
        if (registry.isAuthEnabled()) {
            return;
        }
        log.warn("================================================================");
        log.warn("⚠️  API-Key 鉴权未启用（api-key 与 agents[] key 均为空）");
        log.warn("    所有 REST/MCP 端点匿名可访问，且匿名身份 = operator：");
        log.warn("    Schema 编辑、agent key 管理、DLQ 重放等管理面端点全部开放");
        log.warn("    此姿态仅限本机开发。生产部署必须：");
        log.warn("      1. 设置环境变量 IRIS_API_KEY 启用鉴权");
        log.warn("      2. 经网关限制 /actuator/** 来源（该前缀豁免鉴权）");
        log.warn("      3. 修改 docker-compose 默认密码（iris-root/iris-pg/debezium）");
        log.warn("================================================================");
    }
}
