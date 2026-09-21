-- MySQL 初始化：创建 Debezium 消费用户并授权。
-- 业务库 iris_demo 与 customer 表按方案 7.3 T1 由用户手动创建。

CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'debezium';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
