package com.iris4j.context.repository;

import java.util.List;
import java.util.Map;

/**
 * 源库表读取端口（源库-投影一致性校验）。
 *
 * <p><b>为什么是端口而非直连</b>：application 层的校验服务需要"源库的当前状态"
 * 做比对基准，但按模块边界，application 不得依赖 JDBC/驱动/连接池细节。
 * 定义读取端口，JDBC 实现放 infrastructure，条件装配
 * （{@code iris.consistency.jdbc-url} 未配置时不创建 bean，校验端点显式报错）。
 *
 * <p><b>规模边界</b>：这是管理面低频操作（怀疑漂移时人工触发），
 * 接口按"单表全量拉取"设计；百万级大表应先看 count 档再抽样，
 * 真全量比对属于离线数据核对工具的范畴，不在 context 引擎的在线路径上。
 */
public interface SourceTableReader {

    /**
     * 源表行数（{@code SELECT COUNT(*)}）。
     *
     * @param table 表名（只允许字母数字下划线，实现侧校验防注入）
     */
    long count(String table);

    /**
     * 拉取源表全部行的字段映射（{@code SELECT * FROM table}）。
     *
     * @param table 表名（同上校验）
     * @return 行列表；每行是 列名 -> 值 的原始映射
     */
    List<Map<String, Object>> fetchAll(String table);
}
