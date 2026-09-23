package com.iris.lite.java.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.application.query.ProjectionBatchApplier;
import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.SchemaProvider;
import com.iris.lite.java.shared.key.KeyStrategy;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisException;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量投影提交的 Lettuce pipeline 实现。
 *
 * <p><b>两段 pipeline 压缩往返</b>：逐条路径每事件 5+N 次往返（JSON.SET +
 * SMEMBERS + N×DEL + INCR + XACK），本实现把整批压成 2 次往返——
 * 第一段发全部 JSON.SET / DEL / INCR，第二段按缓存索引成员发缓存键与索引键的 DEL。
 * 批内实体数通常极少（同一 source 恒为 1 个实体），缓存索引读取用同步 SMEMBERS
 * 每实体 1 次即可，不值得为它引入第三种输出类型。
 *
 * <p><b>语义对齐</b>：见 {@link ProjectionBatchApplier} 接口注释。
 * 关键差异只有一处且是<strong>有意的</strong>：同批同实体的版本 bump 合并为一次 INCR
 * （版本仅用于缓存命中后新鲜度比对，单调即可）。
 *
 * <p><b>失败粒度</b>：连接级故障抛 {@link RedisException}（整批留在 PEL）；
 * 单条命令失败只把对应消息标记为未投影（与逐条路径同粒度）；
 * bump / 缓存失效失败按「该实体全部消息失败」处理——与逐条路径
 * 「handle() 内任何一步抛异常 = 事件失败」的标准一致。
 */
@Component("delegateProjectionBatchApplier")
public class LettuceProjectionBatchApplier implements ProjectionBatchApplier {

    private static final Logger log = LoggerFactory.getLogger(LettuceProjectionBatchApplier.class);

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final SchemaProvider schemaProvider;
    private final ObjectMapper objectMapper;
    private final boolean cacheInvalidationEnabled;

    public LettuceProjectionBatchApplier(
            RedisAdapter redis,
            KeyStrategy keys,
            SchemaProvider schemaProvider,
            ObjectMapper objectMapper,
            @Value("${iris.cdc.cache-invalidation-enabled:true}") boolean cacheInvalidationEnabled) {
        this.redis = redis;
        this.keys = keys;
        this.schemaProvider = schemaProvider;
        this.objectMapper = objectMapper;
        this.cacheInvalidationEnabled = cacheInvalidationEnabled;
    }

    /** 一个 pipeline 计划项：kind 决定命令类型；opIndex&lt;0 表示实体级辅助命令。 */
    private record PipeItem(String kind, int opIndex, String entityTag, String key, String json) {
        static PipeItem jsonSet(int opIndex, String entityTag, String key, String json) {
            return new PipeItem("jsonSet", opIndex, entityTag, key, json);
        }

        static PipeItem del(int opIndex, String entityTag, String key) {
            return new PipeItem("del", opIndex, entityTag, key, null);
        }

        static PipeItem incr(String entityTag, String key) {
            return new PipeItem("incr", -1, entityTag, key, null);
        }
    }

    @Override
    public List<Boolean> apply(List<ProjectionWrite> ops) {
        Boolean[] handled = new Boolean[ops.size()];
        List<PipeItem> plan = new ArrayList<>();
        // 投影涉及的实体：tag("ns\0entity") → 该实体涉及的 op 下标集合
        Map<String, Set<Integer>> touched = new LinkedHashMap<>();

        // ---------- 第一阶段：解析 + 序列化 + 构造计划 ----------
        for (int i = 0; i < ops.size(); i++) {
            ProjectionWrite op = ops.get(i);
            EntitySchema schema = schemaProvider.get(op.namespace(), op.entity());
            String pk = schema.primaryKeys().get(0);
            switch (op.op()) {
                case "c", "u", "r" -> {
                    Map<String, Object> after = op.after();
                    if (after == null) {
                        log.warn("批投影 op={} 但 after 为空，跳过: {}/{}",
                                op.op(), op.namespace(), op.entity());
                        handled[i] = true;
                    } else if (after.get(pk) == null) {
                        log.warn("批投影 after 缺少主键字段 {}，跳过: {}/{}", pk, op.namespace(), op.entity());
                        handled[i] = true;
                    } else {
                        try {
                            String json = objectMapper.writeValueAsString(after);
                            String key = keys.entityKey(op.namespace(), op.entity(),
                                    String.valueOf(after.get(pk)));
                            plan.add(PipeItem.jsonSet(i, tag(op), key, json));
                            touched.computeIfAbsent(tag(op), k -> new LinkedHashSet<>()).add(i);
                        } catch (Exception e) {
                            // 序列化失败：与逐条路径的 IllegalStateException 同语义，该条失败
                            log.warn("批投影序列化失败 {}/{} pk={}: {}",
                                    op.namespace(), op.entity(), after.get(pk), e.getMessage());
                            handled[i] = false;
                        }
                    }
                }
                case "d" -> {
                    Map<String, Object> before = op.before();
                    Object pkValue = before == null ? null : before.get(pk);
                    if (pkValue == null) {
                        log.warn("批投影 delete 缺少主键字段 {}，跳过: {}/{}", pk, op.namespace(), op.entity());
                        handled[i] = true;
                    } else {
                        plan.add(PipeItem.del(i, tag(op),
                                keys.entityKey(op.namespace(), op.entity(), String.valueOf(pkValue))));
                        touched.computeIfAbsent(tag(op), k -> new LinkedHashSet<>()).add(i);
                    }
                }
                default -> {
                    log.warn("批投影未知 op={}，跳过: {}/{}", op.op(), op.namespace(), op.entity());
                    handled[i] = true;
                }
            }
        }

        // ---------- 第二阶段：pipeline（投影写 + 版本 INCR）----------
        for (String entityTag : touched.keySet()) {
            String[] t = splitTag(entityTag);
            plan.add(PipeItem.incr(entityTag, keys.entityVersionKey(t[0], t[1])));
        }
        List<RedisFuture<?>> futures = new ArrayList<>(plan.size());
        var async = redis.asyncCommands();
        for (PipeItem item : plan) {
            switch (item.kind()) {
                case "jsonSet" -> futures.add(async.dispatch(CommandType.JSON_SET,
                        new StatusOutput<>(StringCodec.UTF8),
                        new CommandArgs<>(StringCodec.UTF8)
                                .add(item.key()).add("$").add(item.json())));
                case "del" -> futures.add(async.dispatch(CommandType.DEL,
                        new IntegerOutput<>(StringCodec.UTF8),
                        new CommandArgs<>(StringCodec.UTF8).add(item.key())));
                case "incr" -> futures.add(async.dispatch(CommandType.INCR,
                        new IntegerOutput<>(StringCodec.UTF8),
                        new CommandArgs<>(StringCodec.UTF8).add(item.key())));
                default -> throw new IllegalStateException("未知 pipeline 项: " + item.kind());
            }
        }
        // 收割结果：单条命令失败只标记对应消息；INCR 失败按实体传播
        for (int p = 0; p < plan.size(); p++) {
            PipeItem item = plan.get(p);
            try {
                futures.get(p).get();
                if (item.opIndex() >= 0) {
                    handled[item.opIndex()] = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RedisException("批投影 pipeline 被中断", e);
            } catch (Exception e) {
                failItem(item, handled, touched, e);
            }
        }

        // ---------- 第三阶段：实体缓存失效（同步，批内实体数极少）----------
        if (cacheInvalidationEnabled) {
            for (Map.Entry<String, Set<Integer>> entry : touched.entrySet()) {
                String entityTag = entry.getKey();
                String[] t = splitTag(entityTag);
                try {
                    Set<String> members = redis.smembers(keys.cacheIndexKey(t[0], t[1]));
                    if (members == null || members.isEmpty()) {
                        continue;
                    }
                    // 成员 key 一次 DEL 合并（逐成员 DEL 是 N 次往返，把批 pipeline 的收益吃掉）
                    String[] memberKeys = members.stream()
                            .map(relativeKey -> keys.cacheKey(t[0], relativeKey))
                            .toArray(String[]::new);
                    redis.del(memberKeys);
                    redis.del(keys.cacheIndexKey(t[0], t[1]));
                } catch (Exception e) {
                    // 与逐条路径同标准：失效失败 = 该实体本批全部消息失败（留 PEL 重试）
                    markEntityFailed(entityTag, handled, touched, e);
                }
            }
        }

        List<Boolean> result = new ArrayList<>(ops.size());
        for (int i = 0; i < ops.size(); i++) {
            result.add(Boolean.TRUE.equals(handled[i]));
        }
        return result;
    }

    /** 单条 pipeline 项失败：写命令 → 该 op 失败；incr → 该实体全部 op 失败。 */
    private void failItem(PipeItem item, Boolean[] handled,
                          Map<String, Set<Integer>> touched, Exception e) {
        if (item.opIndex() >= 0) {
            handled[item.opIndex()] = false;
            log.warn("批投影命令失败 kind={} key={}: {}", item.kind(), item.key(), e.getMessage(), e);
        } else {
            markEntityFailed(item.entityTag(), handled, touched, e);
        }
    }

    /** 实体级失败：bump / 缓存失效失败 = 该实体本批全部消息失败（与逐条路径标准一致）。 */
    private void markEntityFailed(String entityTag, Boolean[] handled,
                                  Map<String, Set<Integer>> touched, Exception e) {
        Set<Integer> opIndexes = touched.get(entityTag);
        if (opIndexes != null) {
            for (int i : opIndexes) {
                handled[i] = false;
            }
        }
        log.warn("批投影实体级失败 tag={}: {}", entityTag, e.getMessage(), e);
    }

    private static String tag(ProjectionWrite op) {
        return op.namespace() + "\u0000" + op.entity();
    }

    private static String[] splitTag(String tag) {
        int sep = tag.indexOf('\u0000');
        return new String[]{tag.substring(0, sep), tag.substring(sep + 1)};
    }
}
