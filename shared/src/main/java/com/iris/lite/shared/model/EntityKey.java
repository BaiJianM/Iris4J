package com.iris.lite.shared.model;

/**
 * 实体唯一键：namespace + entity + 主键值。
 *
 * <p>作为实体投影 upsert / delete 的统一定位标识，跨模块传递。
 * 实际 Redis key 由 {@link com.iris.lite.shared.key.KeyStrategy} 生成，禁止模块自行拼 key。
 *
 * <p><b>为什么用 record</b>：三元组不可变、值语义（equals/hashCode 自动生成），
 * 在 CDC 事件、仓储、缓存之间传递时不会被意外修改。
 *
 * <p><b>紧凑构造器做必填校验</b>：三个字段任一为空都会在构造时立刻抛
 * {@link IllegalArgumentException}，而不是等到写成畸形的 Redis key
 * （例如 {@code iris:demo:entity:customer:null}）才暴露问题。
 * 校验前置是这类"定位标识"类最重要的一条设计原则。
 */
public record EntityKey(String namespace, String entity, String primaryKey) {

    /**
     * 紧凑构造器：校验三元组完整性。
     *
     * <p>用 {@code isBlank} 而非 {@code isEmpty}：主键值前后带空格同样属于非法输入，
     * 会生成语义错误的 key。
     */
    public EntityKey {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (entity == null || entity.isBlank()) {
            throw new IllegalArgumentException("entity 不能为空");
        }
        if (primaryKey == null || primaryKey.isBlank()) {
            throw new IllegalArgumentException("primaryKey 不能为空");
        }
    }
}
