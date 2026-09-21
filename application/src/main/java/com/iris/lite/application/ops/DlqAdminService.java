package com.iris.lite.application.ops;

import java.util.List;

/**
 * DLQ 管理端口：查看死信条目与人工重放（Stream 重试、DLQ 和人工重放）。
 *
 * <p><b>为什么实现放 cdc 模块</b>：DLQ 流结构由 CDC 消费侧定义
 * （{@code DefaultDlqAdminService} 与 {@code CdcConsumer} 对同一套字段格式），
 * 且重放要复用 {@code ChangeEventHandler} 与正常消费走同一条投影路径。
 * 放在 cdc 模块能天然保证两边结构同步；接口定义在 application 层
 * 是为了让 api 层不直接依赖 cdc 模块。
 */
public interface DlqAdminService {

    /**
     * 查看 DLQ 条目。
     *
     * @param namespace namespace 过滤，null/blank 表示不过滤
     * @param entity    实体名过滤，null/blank 表示不过滤
     * @param limit     最大返回条数（跨源总量，非每源条数），&lt;=0 时取默认 50
     */
    List<DlqEntry> list(String namespace, String entity, int limit);

    /**
     * 人工重放 DLQ：把载荷按正常投影路径处理一遍。
     * 成功的条目从 DLQ 移除（XDEL），失败（载荷损坏/投影异常）的条目保留。
     *
     * @param namespace namespace 过滤，null/blank 表示不过滤
     * @param entity    实体名过滤，null/blank 表示不过滤
     * @return 重放成功并移除的条数
     */
    long replay(String namespace, String entity);
}
