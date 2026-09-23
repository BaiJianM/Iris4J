package com.iris.lite.java.cdc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.application.ops.DlqAdminService;
import com.iris.lite.java.application.ops.DlqEntry;
import com.iris.lite.java.infrastructure.redis.RedisAdapter;
import io.lettuce.core.StreamMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DLQ 管理默认实现。
 *
 * <p><b>DLQ 流结构</b>：{@code {originStream}:dlq}，字段式条目由
 * {@code CdcConsumer#moveToDlq} 写入（ns/entity/originStream/originGroup/msgId/
 * msgKey/payload/deliveryCount/failedAt/error）。
 *
 * <p><b>重放路径</b>：直接走 {@link ChangeEventHandler}——与正常消费<b>同一条投影路径</b>，
 * 保证"重放成功但实时消费失败"这类差异不会出现。
 * 成功后 XDEL 出 DLQ；载荷损坏或投影异常的条目保留，等待修复后再次重放。
 */
@Service
public class DefaultDlqAdminService implements DlqAdminService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDlqAdminService.class);


    /** 单次重放批量上限：重放是同步操作，一次搬太多会长时间占住调用方。 */
    private static final int REPLAY_BATCH = 100;

    private final RedisAdapter redis;
    private final ChangeEventHandler handler;
    private final CdcProperties props;
    private final ObjectMapper objectMapper;

    public DefaultDlqAdminService(
            RedisAdapter redis,
            ChangeEventHandler handler,
            CdcProperties props,
            ObjectMapper objectMapper) {
        this.redis = redis;
        this.handler = handler;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * 查看 DLQ 条目。
     *
     * <p><b>limit 是跨源总量而非每源条数</b>：多源场景下如果每个源都取 limit 条，
     * 总量会翻倍，调用方拿到的条数不可预期。这里用 remaining 递减保证总量不超。
     */
    @Override
    public List<DlqEntry> list(String namespace, String entity, int limit) {
        List<DlqEntry> result = new ArrayList<>();
        int remaining = limit <= 0 ? 50 : limit;
        for (CdcProperties.Source source : matchedSources(namespace, entity)) {
            String dlqStream = source.stream() + CdcConsumer.DLQ_SUFFIX;
            // XRANGE 对不存在的 key 返回空列表，无需特判
            List<StreamMessage<String, String>> messages =
                    redis.xrange(dlqStream, "-", "+", remaining);
            for (StreamMessage<String, String> m : messages) {
                Map<String, String> f = m.getBody();
                result.add(new DlqEntry(
                        // 字段缺失时用 source 配置兜底（早期格式条目可能没写 ns/entity）
                        field(f, "ns", source.namespace()),
                        field(f, "entity", source.entity()),
                        dlqStream,
                        m.getId(),
                        field(f, "originStream", source.stream()),
                        field(f, "originGroup", source.group()),
                        field(f, "msgId", m.getId()),
                        field(f, "msgKey", ""),
                        field(f, "payload", ""),
                        parseCount(field(f, "deliveryCount", "0")),
                        field(f, "failedAt", ""),
                        field(f, "error", "")));
            }
            remaining -= messages.size();
            if (remaining <= 0) {
                break;
            }
        }
        log.debug("DLQ 查询 ns={} entity={} 匹配源={} 返回条目={}",
                namespace, entity, matchedSources(namespace, entity).size(), result.size());
        return result;
    }

    /**
     * 人工重放 DLQ：把载荷按正常投影路径处理一遍。
     *
     * <p><b>失败保留而非丢弃</b>：重放失败（载荷损坏/投影异常）的条目留在 DLQ，
     * 等修复后再次重放。绝不静默删除——DLQ 里的每条都代表一条没落库的数据变更，
     * 删掉等于数据永久丢失。
     *
     * @return 重放成功并已从 DLQ 移除的条数
     */
    @Override
    public long replay(String namespace, String entity) {
        long replayed = 0;
        for (CdcProperties.Source source : matchedSources(namespace, entity)) {
            String dlqStream = source.stream() + CdcConsumer.DLQ_SUFFIX;
            List<StreamMessage<String, String>> messages =
                    redis.xrange(dlqStream, "-", "+", REPLAY_BATCH);
            for (StreamMessage<String, String> m : messages) {
                Map<String, String> f = m.getBody();
                String payload = field(f, "payload", "");
                String ns = field(f, "ns", source.namespace());
                String ent = field(f, "entity", source.entity());
                if (!payload.trim().startsWith("{")) {
                    log.warn("DLQ 重放跳过非 JSON 载荷 dlq={} id={}", dlqStream, m.getId());
                    continue;
                }
                try {
                    Map<String, Object> envelope = objectMapper.readValue(
                            payload, new TypeReference<Map<String, Object>>() {
                            });
                    String op = String.valueOf(envelope.get("op"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> before = (Map<String, Object>) envelope.get("before");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> after = (Map<String, Object>) envelope.get("after");
                    handler.handle(new ChangeEvent(op, before, after), ns, ent);
                    redis.xdel(dlqStream, m.getId());
                    replayed++;
                    log.info("DLQ 重放成功 dlq={} id={} ns={} entity={}", dlqStream, m.getId(), ns, ent);
                } catch (Exception e) {
                    // 重放失败：条目保留在 DLQ，等修复后再次重放
                    log.warn("DLQ 重放失败，条目保留 dlq={} id={}: {}", dlqStream, m.getId(), e.getMessage(), e);
                }
            }
        }
        log.info("DLQ 重放完成 ns={} entity={} 成功={}", namespace, entity, replayed);
        return replayed;
    }

    /** 按 namespace/entity 过滤出匹配的 CDC 源；两个条件都为 null 时返回全部源。 */
    private List<CdcProperties.Source> matchedSources(String namespace, String entity) {
        List<CdcProperties.Source> matched = new ArrayList<>();
        for (CdcProperties.Source source : props.sources()) {
            if (namespace != null && !namespace.isBlank() && !namespace.equals(source.namespace())) {
                continue;
            }
            if (entity != null && !entity.isBlank() && !entity.equals(source.entity())) {
                continue;
            }
            matched.add(source);
        }
        return matched;
    }

    /** 取字段值，空值回落到默认值。 */
    private static String field(Map<String, String> f, String key, String def) {
        String v = f.get(key);
        return v == null || v.isBlank() ? def : v;
    }

    /** 安全解析投递次数：非法值按 0 处理（展示字段，不应因它抛异常）。 */
    private static int parseCount(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // 展示字段降级为 0，留 debug 痕迹便于排查异常载荷
            log.debug("deliveryCount 解析失败 value={}，按 0 处理", value);
            return 0;
        }
    }
}
