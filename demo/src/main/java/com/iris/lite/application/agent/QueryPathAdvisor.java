package com.iris.lite.application.agent;

/**
 * 查询路径规划端口：接入期预生成的「意图 → 推荐查询路径」配方
 * 注入 Agent system prompt，把模型现场推理关联链的发现抖动前置到接入期。
 *
 * <p><b>解决什么问题</b>：MCP 两跳模式下模型要自己 search_entity_tools 试错找
 * 实体专属工具（「手机号查订单」类问题可达 6 轮 6 工具，含静态兜底与重复搜索）；
 * 直连模式也要靠规则 13 现场推理关联链。配方命中后第 1 轮就知道完整路径，
 * 同类问题收敛到 2 轮 2 工具。
 *
 * <p><b>注入方式取舍</b>：配方规模小（个位数~十几条），直接拼进 system prompt
 * 而非提供「路径推荐工具」——省一次工具往返；配方超限时只注入部分条目。
 *
 * <p><b>边界</b>：只注入提示词，不参与鉴权；配方描述的路径执行仍走完整治理链
 * （AccessControlled → 缓存 → 默认查询）。字段名/实体名必须与 Schema 一致
 * （fail-closed 世界，写错字段=模型白跑）。
 */
public interface QueryPathAdvisor {

    /**
     * 渲染指定 namespace 的推荐路径提示词块。
     *
     * @param namespace 命名空间
     * @return 多行文本（含标题行）；无配方时返回空串（调用方直接拼接，无副作用）
     */
    String recipesBlock(String namespace);
}
