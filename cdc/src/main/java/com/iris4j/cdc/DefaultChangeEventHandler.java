package com.iris4j.cdc;

import com.iris4j.application.cache.CacheService;
import com.iris4j.application.query.EntityVersionService;
import com.iris4j.context.model.EntityProjection;
import com.iris4j.context.repository.EntityProjectionRepository;
import com.iris4j.context.schema.EntitySchema;
import com.iris4j.context.schema.SchemaProvider;
import com.iris4j.shared.model.EntityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 变更事件处理器的默认实现：把 Debezium envelope 投影为 RedisJSON 实体，
 * 并在投影成功后失效该实体的查询缓存（精确缓存主动失效）。
 *
 * <p><b>这是 CDC 链路的转换核心</b>：Debezium 只负责把 binlog 搬进 Redis Stream，
 * "Stream 里的 envelope 变成可查询的投影"全靠这里。
 *
 * <p><b>为什么 DLQ 重放也走这里</b>：{@code DefaultDlqAdminService#replay} 直接调用
 * {@link #handle}，与正常消费走完全相同的投影路径。重放与实时消费行为一致，
 * 就不会出现"重放能成功但实时消费失败"这类难以复现的差异。
 */
@Component
public class DefaultChangeEventHandler implements ChangeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultChangeEventHandler.class);

    private final EntityProjectionRepository repository;
    private final SchemaProvider schemaProvider;
    private final CacheService cacheService;
    private final EntityVersionService versionService;

    /**
     * 是否在每次投影后做实体缓存失效。
     *
     * <p><b>失效成本</b>：{@code invalidateEntity} 读 {@code iris:{ns}:cacheidx:{entity}}
     * 索引集合，按成员逐个 DEL（而非 SCAN 遍历全 keyspace），代价 = O(该实体缓存条目数)，
     * 与全库规模彻底解耦；该实体没有存活缓存条目时只花一次 SMEMBERS 即返回。
     * 逐条调用没有性能风险。
     *
     * <p><b>为什么仍保留开关</b>：全量导入 / Redis 重建场景下可再省掉这一次
     * SMEMBERS（百万级事件下仍是可观的往返量），且此时缓存必然为空、失效无意义。
     * 关闭它只是纯优化手段，不是正确性要求；
     * 导入完成后改回 true 并手动清一次 {@code iris:{ns}:cache:*} 即可。
     */
    private final boolean cacheInvalidationEnabled;

    public DefaultChangeEventHandler(
            EntityProjectionRepository repository,
            SchemaProvider schemaProvider,
            CacheService cacheService,
            EntityVersionService versionService,
            @Value("${iris.cdc.cache-invalidation-enabled:true}") boolean cacheInvalidationEnabled) {
        this.repository = repository;
        this.schemaProvider = schemaProvider;
        this.cacheService = cacheService;
        this.versionService = versionService;
        this.cacheInvalidationEnabled = cacheInvalidationEnabled;
    }

    /**
     * 处理一条变更事件。
     *
     * <p><b>op 语义（Debezium 约定）</b>：
     * <ul>
     *   <li>{@code c} create / {@code u} update / {@code r} snapshot read：
     *       取 {@code after}（变更后的完整行）做 upsert；</li>
     *   <li>{@code d} delete：取 {@code before} 的主键做 delete。</li>
     * </ul>
     *
     * <p><b>为什么 c/u/r 合并处理</b>：三者都是"用 after 覆盖投影"，
     * 语义完全一致——Redis 侧没有"插入"和"更新"的区别，都是整文档 JSON.SET。
     *
     * <p><b>异常策略</b>：数据不完整（after 为空、缺主键）时<b>记 warn 并跳过</b>，
     * 不抛异常。这类事件重放一万次也不会成功，抛异常只会让它反复重试、
     * 最终占满 PEL 再进 DLQ，属于无效告警。真正需要重试的是
     * Redis 连接失败这类瞬时故障——那由 upsert 抛异常来触发。
     */
    @Override
    public void handle(ChangeEvent event, String namespace, String entity) {
        // 取 Schema 只为拿主键字段名；Schema 不存在会抛异常（进入失败重试链路）
        EntitySchema schema = schemaProvider.get(namespace, entity);
        String pk = schema.primaryKeys().get(0);

        switch (event.op()) {
            case "c", "u", "r" -> {
                Map<String, Object> after = event.after();
                if (after == null) {
                    log.warn("op={} 但 after 为空，跳过: {}/{}", event.op(), namespace, entity);
                    return;
                }
                Object pkValue = after.get(pk);
                if (pkValue == null) {
                    log.warn("after 缺少主键字段 {}，跳过: {}/{}", pk, namespace, entity);
                    return;
                }
                repository.upsert(new EntityProjection(
                        new EntityKey(namespace, entity, String.valueOf(pkValue)), after));
                log.debug("事件已投影 op={} {}/{} pk={}", event.op(), namespace, entity, pkValue);
            }
            case "d" -> {
                Map<String, Object> before = event.before();
                Object pkValue = before == null ? null : before.get(pk);
                if (pkValue == null) {
                    log.warn("delete 事件 before 缺少主键字段 {}，跳过: {}/{}", pk, namespace, entity);
                    return;
                }
                repository.delete(new EntityKey(namespace, entity, String.valueOf(pkValue)));
                log.debug("事件已投影 op=d {}/{} pk={}", namespace, entity, pkValue);
            }
            // 未知 op 必须与其他跳过分支一样 return：什么数据都没改却触发
            // invalidateEntity + 版本自增，会把数据版本守卫下的 LLM 缓存误判
            // 「数据过期」整体失效（envelope 缺 op 字段时 String.valueOf 还会产出 "null"）
            default -> {
                log.warn("未知 op={}，跳过: {}/{}", event.op(), namespace, entity);
                return;
            }
        }

        // 投影成功后失效该实体的查询缓存，避免 TTL 窗口内返回旧数据。
        // 放在 switch 之后统一执行：三种数据变更都会让该实体的查询结果失效，
        // 但 warn 跳过的分支（after 为空等）不应该触发失效——那些分支已 return。
        // cache-invalidation-enabled=false（全量导入场景）时跳过：见字段注释
        if (cacheInvalidationEnabled) {
            cacheService.invalidateEntity(namespace, entity);
        }
        // 数据版本守卫：版本自增，LLM 缓存条目命中后据此校验数据新鲜度。
        // INCR 是 O(1)，逐条执行无性能压力；且关闭缓存失效时版本也必须保持正确
        versionService.bump(namespace, entity);
    }
}
