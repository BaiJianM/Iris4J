-- PostgreSQL 演示库初始化：逻辑复制（Debezium pgoutput）
-- 说明：容器启动时已设 wal_level=logical（见 docker-compose.yml 的 postgres command）。
-- 本脚本在首次初始化数据库时以 POSTGRES_USER(iris) 身份执行，用于创建 Debezium 用户、
-- 演示表 product 与逻辑复制 publication。

-- Debezium 专用用户（REPLICATION 权限做逻辑复制）
CREATE ROLE debezium WITH LOGIN REPLICATION PASSWORD 'debezium';

-- 演示表：product，与 MySQL 的 customer 对应，验证多源 CDC
CREATE TABLE product (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    price NUMERIC(10,2),
    category VARCHAR(100),
    updated_at VARCHAR(64)
);

INSERT INTO product (id, name, price, category, updated_at) VALUES
    (2001, '无线鼠标', 99.00, '数码', '2026-09-03 12:00:00'),
    (2002, '机械键盘', 399.00, '数码', '2026-09-03 12:00:00'),
    (2003, '显示器支架', 129.00, '办公', '2026-09-03 12:00:00');

-- 授权 Debezium 读取（现有表 + 未来新建表）
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO debezium;

-- 逻辑复制 publication（pgoutput 插件；Debezium 侧 publication.name=dbz_publication 复用）
CREATE PUBLICATION dbz_publication FOR ALL TABLES;
