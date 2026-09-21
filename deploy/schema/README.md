# 外部 Schema 目录（动态 Schema 热加载）

本目录由 `iris.schema.dir` 指向，用于覆盖/新增实体 Schema，**改动后无需重启服务**。

## 规则

- 只扫描本目录下的 `*.yml` 文件（不递归）。
- 每个文件定义一个实体，键为 `namespace/entity`，与 classpath 下 `iris/schema/**/*.yml` 同名即覆盖。
- 默认每 3 秒轮询一次（`iris.schema.poll-interval-ms`），检测到 mtime 变化后原子重载。
- 任一文件解析失败 → 整批丢弃，**保留旧 Schema**，可通过 `GET /api/v1/schema/status` 查看 `lastError`。

## 控制台编辑

管理控制台 Schema 页支持编辑字段的关系映射（Related Entity）：保存后由服务端
把整份 Schema 回盘为 `{namespace}.{entity}.yml` 写入本目录并立即热载。

- 回盘文件是**机器生成的覆盖副本**：首次编辑来自 classpath 基线的实体时，副本将成为
  生效来源，基线 YAML 的注释不会保留——本目录文件请按"机器管理"对待，手工调整建议
  改 classpath 基线后重建副本。
- 清除映射保留已补的索引声明；删除本目录对应文件并 reload 即可回到 classpath 基线。

## 示例：给 customer 增加一个字段

新建 `customer.yml`：

```yaml
namespace: demo
entity: customer
primaryKeys:
  - id
fields:
  - name: id
    type: LONG
  - name: name
    type: STRING
  - name: level
    type: INT
  - name: city
    type: STRING
  - name: updated_at
    type: STRING
  - name: vip        # 新增字段，热加载后即可被 query_entity 引用
    type: BOOLEAN
```

保存后约 3 秒内生效，无需重启。
