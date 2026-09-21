package com.iris.lite.context.schema;

import java.util.List;

/**
 * Schema 管理面（动态 Schema 热加载）。
 *
 * <p><b>为什么与 {@link SchemaProvider} 分开</b>：
 * <ul>
 *   <li>{@link SchemaProvider} 是<b>只读契约</b>，查询链路（仓储、应用服务、
 *       语义缓存分裂过滤器）只依赖它，职责最小；</li>
 *   <li>本接口在只读之上追加<b>运行时重载与状态查询</b>，只有管理入口
 *       （SchemaController / SchemaMcpTool）和动态 MCP 工具注册器会用到。</li>
 * </ul>
 * 分开后，查询代码不可能意外依赖上管理面能力，改热加载逻辑也不会波及查询链路。
 */
public interface SchemaManager extends SchemaProvider {

    /**
     * 强制重新加载外部目录中的 Schema。
     *
     * <p><b>失败语义是关键</b>：解析失败时<b>保留旧 Schema</b>、不抛异常、不让服务降级。
     * 一份写坏的 YAML 不应该让整个查询能力不可用——这是运维友好性和正确性的权衡，
     * 这里明确选择可用性。错误通过 {@link #status()} 的 lastError 暴露。
     */
    void reload();

    /** 返回当前热加载状态（是否启用、目录、实体数、最近重载时间、最近错误）。 */
    SchemaStatus status();

    /**
     * 返回当前生效的全部 Schema（classpath 基线 + 外部覆盖合并）。
     *
     * <p>主要供动态 MCP 工具注册器做枚举与对账：遍历它为每个实体生成
     * {@code query_{entity}} 工具，Schema 增删后 diff 出要注册/注销的工具。
     */
    List<EntitySchema> list();
}
