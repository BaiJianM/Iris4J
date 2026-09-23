package com.iris4j.infrastructure.redis;

import com.iris4j.application.query.IndexReadinessService;
import org.springframework.stereotype.Component;

/**
 * {@link IndexReadinessService} 的 infrastructure 侧实现：委托 {@link EntityIndexManager}。
 *
 * <p>放在 infrastructure 是模块边界要求——索引状态探询要读 FT.INFO，属于 Redis
 * 交互细节，application 不应为此新增对 Redis 的依赖。
 */
@Component
public class DefaultIndexReadinessService implements IndexReadinessService {

    private final EntityIndexManager indexManager;

    public DefaultIndexReadinessService(EntityIndexManager indexManager) {
        this.indexManager = indexManager;
    }

    @Override
    public boolean isReady(String namespace, String entity) {
        return indexManager.isQueryReady(namespace, entity);
    }

    @Override
    public String readinessHint(String namespace, String entity) {
        return indexManager.readinessHint(namespace, entity);
    }
}
