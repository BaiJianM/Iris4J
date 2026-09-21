package com.iris.lite.context.schema;

/**
 * Schema 提供者。当前固定 Schema，实现可替换（YAML / 代码内建 / 后续动态加载）。
 *
 * <p><b>只读视角</b>：本接口只暴露 {@link #get}，是查询链路（仓储、应用服务、语义缓存）
 * 应该依赖的最小契约。管理面能力（重载、状态、枚举）在 {@link SchemaManager} 里。
 * 依赖最小接口能避免"查询代码意外依赖上管理面能力"。
 */
public interface SchemaProvider {

    /**
     * 获取实体 Schema。
     *
     * <p><b>不存在时抛异常而非返回 null</b>：Schema 不存在意味着查询无法进行
     * （不知道主键、不知道字段、不知道是否多租户），返回 null 只会把错误推迟到
     * 后续 NPE。直接抛 {@link com.iris.lite.shared.error.IrisException}
     * （错误码 IRIS-1001）能让调用方立刻拿到明确的失败原因。
     *
     * @throws com.iris.lite.shared.error.IrisException Schema 不存在时抛 ENTITY_NOT_FOUND
     */
    EntitySchema get(String namespace, String entity);
}
