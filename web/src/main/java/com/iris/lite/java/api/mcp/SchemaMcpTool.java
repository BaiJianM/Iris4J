package com.iris.lite.java.api.mcp;

import com.iris.lite.java.application.schema.SchemaAdminService;
import com.iris.lite.java.context.schema.SchemaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP Schema 管理工具入口，与 REST 共用同一个 {@link SchemaAdminService}。
 *
 * <p>配合热加载："改 YAML -> schema_reload -> 动态工具自动增删"，
 * 全程无需重启服务。
 */
@Component
public class SchemaMcpTool {

    private static final Logger log = LoggerFactory.getLogger(SchemaMcpTool.class);

    private final SchemaAdminService adminService;
    private final McpOperatorGuard operatorGuard;

    public SchemaMcpTool(SchemaAdminService adminService, McpOperatorGuard operatorGuard) {
        this.adminService = adminService;
        this.operatorGuard = operatorGuard;
    }

    /**
     * 强制重载外部 Schema，返回重载后的状态。
     *
     * <p>失败时不会抛异常——{@code YamlSchemaProvider} 会保留旧 Schema，
     * 调用方通过返回状态里的 lastError 判断。
     */
    @McpTool(name = "schema_reload", description = "强制重载外部目录中的实体 Schema，解析失败时保留旧 Schema。")
    public SchemaStatus reload() {
        operatorGuard.require("schema_reload");
        log.info("Schema 重载请求（MCP）");
        adminService.reload();
        return adminService.status();
    }

    /** 查询热加载状态：排查"改了 YAML 没生效"的第一步。 */
    @McpTool(name = "schema_status", description = "查询实体 Schema 热加载状态（是否启用、目录、实体数、最近加载时间、最近错误）。")
    public SchemaStatus status() {
        return adminService.status();
    }
}
