package com.iris.lite.application.query;

/**
 * 索引就绪状态查询端口。
 *
 * <p><b>为什么需要这个端口</b>：FT.CREATE 之后 Redis Query Engine 是<b>异步回填</b>的。
 * 回填窗口内索引"存在但查不到数据"——FT.SEARCH 返回空结果，与"表里真的没数据"
 * 在返回结构上完全一致。上层（尤其 Agent 大模型）无法区分二者，会把暂时性
 * 不可见误判为数据不存在，与历史对话矛盾时陷入推理死循环
 * （例如模型重复「Let's go」数百次直至打满轮数）。
 *
 * <p>仓储层已经在回填期自动降级 SCAN 保证<b>正确性</b>，本端口补的是<b>可解释性</b>：
 * 让工具层能在结果里附带一句"索引回填中，勿据此判断数据不存在"，
 * 模型据此如实回答用户，而不是反复重试。
 *
 * <p><b>边界</b>：本端口只读、只暴露状态与文案，不参与查询路径决策——
 * 路径选择在 infrastructure 的仓储实现内（依赖 {@code EntityIndexManager}）。
 */
public interface IndexReadinessService {

    /**
     * 该实体索引当前是否可安全查询（已就绪）。
     *
     * <p>未就绪的两种情形：索引构建失败（走 SCAN 降级）、引擎仍在回填。
     * 索引不存在或状态探询失败视为就绪（不阻断查询，交由仓储侧异常兜底）。
     */
    boolean isReady(String namespace, String entity);

    /**
     * 就绪状态提示文案，供工具层附在查询结果里透给调用方/模型。
     *
     * @return 已就绪返回 null（无需提示）；未就绪返回中文说明
     */
    String readinessHint(String namespace, String entity);
}
