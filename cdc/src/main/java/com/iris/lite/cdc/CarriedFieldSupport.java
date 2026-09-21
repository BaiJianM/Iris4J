package com.iris.lite.cdc;

import com.iris.lite.application.cache.CacheService;
import com.iris.lite.application.query.EntityVersionService;
import com.iris.lite.application.query.IndexReadinessService;
import com.iris.lite.context.model.EntityProjection;
import com.iris.lite.context.model.ProjectionPage;
import com.iris.lite.context.model.QueryRequest;
import com.iris.lite.context.repository.EntityProjectionRepository;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldSchema;
import com.iris.lite.context.schema.SchemaManager;
import com.iris.lite.infrastructure.schema.SchemaReloadedEvent;
import com.iris.lite.shared.model.EntityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

/**
 * 跨表携带字段（Schema {@code carriedFields} 声明）的投影期支撑：回填 + 回刷。
 *
 * <p><b>解决什么问题</b>：单表聚合引擎没有 JOIN，「明细按主表状态过滤」类口径
 * （商品销量只算已支付订单）跨不过表去。Schema 里声明
 * {@code carriedFrom: "order_id->ord_order.pay_status"} 后，投影链路把主表字段
 * <b>携带</b>进子表文档——查询侧保持单表一跳，模型无需感知跨表。携带什么由
 * 接入方声明（业务口径不进 core），本类只认声明。
 *
 * <p><b>两条写入路径</b>：
 * <ul>
 *   <li><b>子表行写入回填</b>（{@link #fillChild}）：子表事件 upsert 前按声明读
 *       主表投影行（O(1) JSON.GET），把携带字段填进文档。主表行尚未投影
 *       （快照乱序）时跳过该字段——文档里没有该 key，FT 过滤不命中，
 *       等主表事件到达时由回刷补齐；</li>
 *   <li><b>主表变更回刷</b>（{@link #propagateFromParent}）：主表行的携带来源字段
 *       变化（c/r 新行视为变化；u 仅 before/after 值不同才刷）时，按 FK 反查
 *       子表行（FT.SEARCH 下推，fk 字段由 EntitySchema 加载期强制索引），逐行
 *       更新携带字段。子表缓存失效 + 版本自增随行执行，维持数据版本围栏一致性。</li>
 * </ul>
 *
 * <p><b>为什么回刷前查索引就绪</b>：全量快照按表序进行（主表先于子表），主表
 * 快照期子表索引往往尚未建立——此时反查必为空但每次都是一次 FT.SEARCH
 * （5 万主表行 = 5 万次空转）。子表索引未就绪时直接跳过回刷：子表行稍后写入时
 * {@link #fillChild} 读主表自然拿到最新值，正确性不受影响。
 *
 * <p><b>异常策略</b>：回填/回刷失败只 warn 不抛——主事件数据已投影成功，
 * 携带字段是冗余增强；让它拖垮主消费会把增强失败升级成数据丢失。
 * 回刷失败留给主表下次变更自然修复（投影数据以源库为准）。
 */
@Component
public class CarriedFieldSupport {

    private static final Logger log = LoggerFactory.getLogger(CarriedFieldSupport.class);

    /** 单次回刷反查子表行的分页大小（对齐 QueryRequest.MAX_PAGE_SIZE）。 */
    private static final int REFRESH_PAGE_SIZE = QueryRequest.MAX_PAGE_SIZE;

    private final SchemaManager schemaManager;
    private final EntityProjectionRepository repository;
    private final CacheService cacheService;
    private final EntityVersionService versionService;
    private final IndexReadinessService indexReadiness;
    private final boolean cacheInvalidationEnabled;

    /**
     * 携带声明注册表。volatile 引用整体替换：Schema 热重载时重建，
     * 消费线程无锁读旧表直到新表可见——声明切换的原子性与 Schema 仓库一致。
     *
     * <p>key 语义：byChild 为 {@code ns/childEntity}（回填按子表实体查声明）、
     * byParent 为 {@code ns/parentEntity}（回刷按主表实体查声明）。
     */
    private volatile Map<String, List<CarriedRef>> byChild = Map.of();
    private volatile Map<String, List<CarriedRef>> byParent = Map.of();

    /** 一条携带声明（从 Schema 的 carriedFrom 解析而来）。 */
    record CarriedRef(String childEntity, String fkField, String carriedField,
                      String parentEntity, String parentField) {
    }

    public CarriedFieldSupport(
            SchemaManager schemaManager,
            EntityProjectionRepository repository,
            CacheService cacheService,
            EntityVersionService versionService,
            IndexReadinessService indexReadiness,
            @Value("${iris.cdc.cache-invalidation-enabled:true}") boolean cacheInvalidationEnabled) {
        this.schemaManager = schemaManager;
        this.repository = repository;
        this.cacheService = cacheService;
        this.versionService = versionService;
        this.indexReadiness = indexReadiness;
        this.cacheInvalidationEnabled = cacheInvalidationEnabled;
    }

    @PostConstruct
    void init() {
        rebuild(schemaManager.list());
    }

    /** Schema 热重载后重建注册表（声明可能增删）。 */
    @EventListener
    public synchronized void onSchemaReloaded(SchemaReloadedEvent event) {
        rebuild(event.schemas());
    }

    private void rebuild(List<EntitySchema> schemas) {
        Map<String, List<CarriedRef>> child = new HashMap<>();
        Map<String, List<CarriedRef>> parent = new HashMap<>();
        for (EntitySchema schema : schemas) {
            for (FieldSchema f : schema.carriedFields()) {
                String fk = f.fkFieldOfCarriedFrom();
                String pe = f.parentEntityOfCarriedFrom();
                String pf = f.parentFieldOfCarriedFrom();
                if (fk == null || pe == null || pf == null) {
                    log.warn("carriedFrom 格式非法（应为 fk字段->主表实体.主表字段），已忽略: {}/{} {}",
                            schema.namespace(), schema.entity(), f.carriedFrom());
                    continue;
                }
                // 目标实体存在性由运行期 repository.get 的 null 语义兜底（声明可为
                // 尚未加载的实体），这里不做跨实体校验——避免加载顺序耦合
                CarriedRef ref = new CarriedRef(schema.entity(), fk, f.name(), pe, pf);
                child.computeIfAbsent(schema.namespace() + "/" + schema.entity(),
                        k -> new ArrayList<>()).add(ref);
                parent.computeIfAbsent(schema.namespace() + "/" + pe,
                        k -> new ArrayList<>()).add(ref);
            }
        }
        this.byChild = new ConcurrentHashMap<>(child);
        this.byParent = new ConcurrentHashMap<>(parent);
        log.info("携带字段注册表重建：携带实体 {} 个，被携带主表 {} 个",
                child.size(), parent.size());
    }

    /**
     * 子表行写入回填：按声明读主表投影行，把携带字段填进 {@code after}（原地修改）。
     * 主表行不存在或字段缺失时跳过（留待主表事件回刷补齐）。
     */
    public void fillChild(String namespace, String entity, Map<String, Object> after) {
        List<CarriedRef> refs = byChild.get(namespace + "/" + entity);
        if (refs == null || refs.isEmpty() || after == null) {
            return;
        }
        for (CarriedRef ref : refs) {
            Object fkValue = after.get(ref.fkField());
            if (fkValue == null) {
                continue;
            }
            try {
                Map<String, Object> parentRow =
                        repository.get(new EntityKey(namespace, ref.parentEntity(), String.valueOf(fkValue)));
                if (parentRow == null) {
                    continue;
                }
                Object value = parentRow.get(ref.parentField());
                if (value != null) {
                    after.put(ref.carriedField(), value);
                }
            } catch (Exception e) {
                log.warn("携带字段回填失败（跳过，等主表事件回刷）{}/{} field={} fk={}: {}",
                        namespace, entity, ref.carriedField(), fkValue, e.getMessage());
            }
        }
    }

    /**
     * 主表变更回刷子表：op 为 c/r（新行）必刷；u 仅当来源字段值实际变化才刷。
     * 子表索引未就绪时跳过（快照期空转防护，见类注释）。
     */
    public void propagateFromParent(String namespace, String entity,
                                    Map<String, Object> before, Map<String, Object> after, String op) {
        List<CarriedRef> refs = byParent.get(namespace + "/" + entity);
        if (refs == null || refs.isEmpty() || after == null) {
            return;
        }
        EntitySchema parentSchema;
        try {
            parentSchema = schemaManager.get(namespace, entity);
        } catch (Exception e) {
            return; // schema 缺失属主链路问题，这里不重复报错
        }
        String parentPkField = parentSchema.primaryKeys().get(0);
        Object pkValue = after.get(parentPkField);
        if (pkValue == null) {
            return;
        }
        for (CarriedRef ref : refs) {
            Object newValue = after.get(ref.parentField());
            // 值未变化的 update 不回刷：值相同刷一万次结果也一样，纯写放大
            if ("u".equals(op) && before != null
                    && Objects.equals(before.get(ref.parentField()), newValue)) {
                continue;
            }
            refreshChildren(namespace, parentSchema, ref, String.valueOf(pkValue), newValue);
        }
    }

    /** 反查子表行并逐行更新携带字段。 */
    private void refreshChildren(String namespace, EntitySchema parentSchema,
                                 CarriedRef ref, String pkValue, Object newValue) {
        // 索引未就绪（快照期子表还没建索引/没数据）跳过——子表行写入时
        // fillChild 会从主表拿最新值，这里刷了也是空转
        if (!indexReadiness.isReady(namespace, ref.childEntity())) {
            return;
        }
        try {
            EntitySchema childSchema = schemaManager.get(namespace, ref.childEntity());
            String childPkField = childSchema.primaryKeys().get(0);
            QueryRequest req = new QueryRequest(namespace, ref.childEntity(), null,
                    Map.of(ref.fkField(), pkValue),
                    1, REFRESH_PAGE_SIZE, null, null, null, null, null, false, false);
            ProjectionPage page = repository.find(req);
            if (page.total() > REFRESH_PAGE_SIZE) {
                log.warn("子表行数超过单次回刷上限，仅刷前 {} 行 {}/{} fk={} total={}",
                        REFRESH_PAGE_SIZE, namespace, ref.childEntity(), pkValue, page.total());
            }
            for (EntityProjection row : page.items()) {
                Map<String, Object> doc = new HashMap<>(row.data());
                if (newValue == null) {
                    doc.remove(ref.carriedField());
                } else {
                    doc.put(ref.carriedField(), newValue);
                }
                Object childPk = doc.get(childPkField);
                if (childPk == null) {
                    continue;
                }
                repository.upsert(new EntityProjection(
                        new EntityKey(namespace, ref.childEntity(), String.valueOf(childPk)), doc));
            }
            int refreshed = page.items().size();
            if (refreshed > 0) {
                if (cacheInvalidationEnabled) {
                    cacheService.invalidateEntity(namespace, ref.childEntity());
                }
                versionService.bump(namespace, ref.childEntity());
            }
            log.debug("携带字段回刷完成 {}/{} {}={} field={} 行数={}",
                    namespace, ref.childEntity(), parentSchema.primaryKeys().get(0),
                    pkValue, ref.carriedField(), refreshed);
        } catch (Exception e) {
            // 回刷失败不影响主事件（数据已投影）；主表下次变更会再触发修复
            log.warn("携带字段回刷失败（等主表下次变更修复）{}/{} fk={}: {}",
                    namespace, ref.childEntity(), pkValue, e.getMessage());
        }
    }
}
