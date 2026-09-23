package com.iris.lite.java.cdc;

import com.iris.lite.java.application.ops.CdcInsightService;
import com.iris.lite.java.infrastructure.redis.RedisAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.lettuce.core.models.stream.PendingMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CdcInsightService} 实现：配置 source 清单 + Redis Stream 观测命令。
 *
 * <p><b>为什么放 cdc 模块</b>：取数要同时拿 CdcProperties（只有 cdc 可见）
 * 与 RedisAdapter（XLEN/XPENDING），两者恰好都在 cdc 的依赖闭包内。
 * <b>只读</b>：不提供人工干预入口，防误操作打断投递语义。
 */
@Service
public class DefaultCdcInsightService implements CdcInsightService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCdcInsightService.class);

    private final CdcProperties props;
    private final RedisAdapter redis;

    public DefaultCdcInsightService(CdcProperties props, RedisAdapter redis) {
        this.props = props;
        this.redis = redis;
    }

    @Override
    public List<SourceView> sources() {
        List<SourceView> out = new ArrayList<>();
        List<CdcProperties.Source> sources = props.sources();
        for (int i = 0; i < sources.size(); i++) {
            CdcProperties.Source s = sources.get(i);
            out.add(new SourceView(
                    i, s.namespace(), s.stream(), s.entity(), s.group(), s.consumer(),
                    safeXlen(s.stream()),
                    safeGroupLag(s.stream(), s.group()),
                    safePendingCount(s.stream(), s.group()),
                    safeXlen(s.stream() + CdcConsumer.DLQ_SUFFIX)));
        }
        return out;
    }

    @Override
    public List<PendingView> pending(int index, int limit) {
        List<CdcProperties.Source> sources = props.sources();
        if (index < 0 || index >= sources.size()) {
            throw new IllegalArgumentException("source 序号越界: " + index);
        }
        CdcProperties.Source s = sources.get(index);
        List<PendingView> out = new ArrayList<>();
        try {
            for (PendingMessage pm : redis.xpending(s.stream(), s.group(), limit)) {
                out.add(new PendingView(
                        String.valueOf(pm.getId()),
                        pm.getConsumer() == null ? "-" : pm.getConsumer(),
                        pm.getMsSinceLastDelivery(),
                        pm.getRedeliveryCount()));
            }
        } catch (Exception e) {
            // group 刚建/stream 不存在时 XPENDING 报错：控制台显示空列表即可，不打断页面
            log.debug("PEL 读取失败 stream={} group={} - {}", s.stream(), s.group(), e.getMessage(), e);
        }
        return out;
    }

    private Long safeXlen(String streamKey) {
        try {
            return redis.xlen(streamKey);
        } catch (Exception e) {
            log.debug("xlen 读取失败 stream={} - {}", streamKey, e.getMessage(), e);
            return null;
        }
    }

    private Long safePendingCount(String streamKey, String group) {
        try {
            return redis.xpendingCount(streamKey, group);
        } catch (Exception e) {
            log.debug("xpendingCount 读取失败 stream={} group={} - {}", streamKey, group, e.getMessage(), e);
            return null;
        }
    }

    /** lag 读取失败（stream 不存在/group 未建/Redis 版本无 lag 字段）返回 null，UI 显示"未知"。 */
    private Long safeGroupLag(String streamKey, String group) {
        try {
            return redis.xinfoGroupLag(streamKey, group);
        } catch (Exception e) {
            log.debug("lag 读取失败 stream={} group={} - {}", streamKey, group, e.getMessage(), e);
            return null;
        }
    }
}
