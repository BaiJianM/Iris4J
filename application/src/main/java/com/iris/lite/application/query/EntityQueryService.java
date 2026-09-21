package com.iris.lite.application.query;

import com.iris.lite.context.model.AggregateRequest;
import com.iris.lite.context.model.AggregateResult;
import com.iris.lite.context.model.QueryRequest;
import com.iris.lite.shared.model.Page;

import java.util.Map;

/**
 * 统一实体查询服务，REST 与 MCP 共用。
 *
 * <p><b>装饰器链</b>：本接口有四个实现，按此顺序组装成一条链：
 * <ol>
 *   <li>{@code AccessControlledEntityQueryService}（{@code @Primary}）：
 *       行级/字段级访问控制，链头——行过滤在进缓存前注入，不同 tags 缓存天然隔离；</li>
 *   <li>{@code SemanticCachedEntityQueryService}：语义缓存，Embedder 缺失或开关
 *       关闭时整体旁路（退化为精确缓存）；</li>
 *   <li>{@code CachedEntityQueryService}：精确缓存；</li>
 *   <li>{@code DefaultEntityQueryService}：真正执行查询。</li>
 * </ol>
 * 入口（REST Controller / MCP Tool）只注入本接口，拿到的就是装配好的链头
 * （{@code @Primary} 的访问控制层），完全不必知道有几层缓存。
 */
public interface EntityQueryService {

    /**
     * 执行查询，返回分页后的字段裁剪结果。
     *
     * @param request 统一查询请求（REST 与 MCP 共用同一模型）
     * @return 分页结果；主键查询无结果时抛 ENTITY_NOT_FOUND
     */
    Page<Map<String, Object>> query(QueryRequest request);

    /**
     * 服务端聚合：GROUPBY + COUNT/SUM/AVG/MIN/MAX + TopN。
     *
     * <p>走同一条装饰器链——access control（行级/字段级）与租户隔离
     * 与 {@link #query} 同级别生效，Agent 不存在绕过治理的聚合通路。
     * 缓存装饰器<b>不缓存聚合结果</b>：聚合本身是服务端一次命令（微秒~毫秒级），
     * 而统计值对 CDC 新鲜度最敏感，缓存收益为负。
     *
     * @param request 聚合请求（字段索引校验 fail-closed）
     * @return totalGroups + 组行
     */
    AggregateResult aggregate(AggregateRequest request);
}
