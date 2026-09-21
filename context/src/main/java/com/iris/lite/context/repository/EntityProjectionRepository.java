package com.iris.lite.context.repository;

import com.iris.lite.context.model.AggregateRequest;
import com.iris.lite.context.model.AggregateResult;
import com.iris.lite.context.model.EntityProjection;
import com.iris.lite.context.model.ProjectionPage;
import com.iris.lite.context.model.QueryRequest;
import com.iris.lite.shared.model.EntityKey;

/**
 * 实体投影仓储接口。
 *
 * <p><b>边界</b>：接口定义在 context 层，实现放 infrastructure 层
 * （{@code LettuceEntityProjectionRepository}）。REST、MCP 与上层 Agent
 * 完全不感知底层用的是 RedisJSON、SCAN 还是别的存储——将来换成
 * Redis Query Engine 的 FT.SEARCH，只需换实现，上层零改动。
 *
 * <p><b>注意 find 的返回语义</b>：返回<b>原始匹配结果</b>，不含字段裁剪与分页。
 * 裁剪和分页是应用服务（{@code DefaultEntityQueryService}）的职责，
 * 这样缓存层缓存的才是"完整结果"，不同 fields/page 的请求各自裁剪。
 */
public interface EntityProjectionRepository {

    /**
     * 写入或更新实体投影。
     *
     * <p>语义是 upsert（整文档覆盖），不是字段级 merge。
     * Debezium 的 update 事件携带完整 after 行，整覆盖即等价于最新状态。
     */
    void upsert(EntityProjection projection);

    /**
     * 删除实体投影。
     *
     * <p>直接 DEL key，不做软删除标记。投影是源库的镜像，
     * 源行删了投影就该消失。
     */
    void delete(EntityKey key);

    /**
     * 按主键读单个投影文档（JSON.GET 一次命中，O(1)）。
     *
     * <p>跨表携带（carriedFields）：写入子表行时按声明回填主表字段、
     * 主表字段变更时回刷子表行，都需要「按主键读投影行」这一原子能力——
     * 复用 find 的主键路径会带上过滤语义（租户/其余条件校验），是查询语义；
     * 这里是<b>裸读</b>，不做任何过滤与裁剪，语义为「存储里是什么就返回什么」。
     *
     * @param key 实体键（namespace/entity/pk）
     * @return 投影文档；不存在返回 null
     */
    java.util.Map<String, Object> get(EntityKey key);

    /**
     * 按查询请求检索实体投影（分页下推后的结果，字段未裁剪）。
     *
     * <p>实现内部分三条路径：
     * <ul>
     *   <li>filters 覆盖全部主键 = 主键精确查询（JSON.GET 一次命中）；</li>
     *   <li>索引可用且过滤字段均可下推 = FT.SEARCH（LIMIT 分页在服务端完成）；</li>
     *   <li>其余情况降级 SCAN + 内存过滤（语义不变，代价与实体总量成正比）。</li>
     * </ul>
     *
     * <p><b>返回语义（T1 变更）</b>：items 是<b>分页后</b>的本页命中（未裁剪字段），
     * total 是<b>匹配总数</b>（不受分页窗口影响）。字段裁剪仍由应用服务负责，
     * 但分页职责已随下推转移到仓储层——分页在存储侧做是性能红线，
     * 100 万条全量进 JVM 堆会直接 OOM。
     */
    ProjectionPage find(QueryRequest request);

    /**
     * 服务端聚合：GROUPBY + COUNT/SUM/AVG/MIN/MAX + TopN。
     *
     * <p>实现走 FT.AGGREGATE（Redis Query Engine 服务端聚合），
     * <b>没有 SCAN 降级路径</b>——请求路径上的全库 SCAN 聚合是本项目红线，
     * 索引未就绪/字段未索引/条件不可下推时直接抛异常，由上层转可读错误。
     *
     * @param request 聚合请求（字段索引校验在应用层完成，这里做命令构造与解析）
     * @return totalGroups 为分组总数（不受 limit 影响），rows 为返回的组行
     */
    AggregateResult aggregate(AggregateRequest request);
}
