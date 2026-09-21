package com.iris.lite.infrastructure.jdbc;

import com.iris.lite.context.repository.SourceTableReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC 版 {@link SourceTableReader}——直连源库拉取比对基准。
 *
 * <p><b>为什么 DriverManager 短连接而不是连接池</b>：校验是管理面低频操作
 * （人工触发，怀疑数据漂移时才用），为它常驻一个 HikariCP 池
 * （10 个连接 + 心跳线程）得不偿失。每次校验开一条连接、用完即关，
 * 慢几百毫秒完全可接受。
 *
 * <p><b>SQL 注入防护</b>：表名不允许参数化（标识符不能走 PreparedStatement 占位符），
 * 所以用白名单正则校验——只允许 {@code [A-Za-z0-9_]+}，
 * 拒绝一切含引号/分号/空格/注释符的输入。
 *
 * <p><b>条件装配</b>：{@code iris.consistency.jdbc-url} 未配置时不创建 bean。
 * 校验服务用 ObjectProvider 注入，缺 bean 时端点显式报"未配置源库连接"，
 * 而不是让整个应用因缺 JDBC 配置起不来。
 */
@Component
@ConditionalOnProperty("iris.consistency.jdbc-url")
public class JdbcSourceTableReader implements SourceTableReader {

    private static final Logger log = LoggerFactory.getLogger(JdbcSourceTableReader.class);

    private final String url;
    private final String username;
    private final String password;

    public JdbcSourceTableReader(
            @Value("${iris.consistency.jdbc-url}") String url,
            @Value("${iris.consistency.jdbc-username:}") String username,
            @Value("${iris.consistency.jdbc-password:}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        // 驱动随 java.sql.DriverManager SPI 自动加载（mysql-connector-j 在 classpath 时）
        log.info("源库一致性校验已启用 jdbc-url={} user={}", url, username);
    }

    @Override
    public long count(String table) {
        String sql = "SELECT COUNT(*) FROM " + validateTable(table);
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("源表 COUNT 失败: " + table + " - " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> fetchAll(String table) {
        String sql = "SELECT * FROM " + validateTable(table);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("源表拉取失败: " + table + " - " + e.getMessage(), e);
        }
        log.debug("源表全量拉取完成 table={} rows={}", table, rows.size());
        return rows;
    }

    private Connection connect() throws SQLException {
        if (username == null || username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    /** 表名白名单校验：标识符只允许字母/数字/下划线。 */
    private String validateTable(String table) {
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法表名（只允许字母数字下划线）: " + table);
        }
        return table;
    }
}
