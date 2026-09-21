# iris-lite

基于 **Redis 8** 的 AI Agent 数据访问层（Java 复刻版），对标 [Redis Iris](https://github.com/redis/iris) 官方四大服务，全部能力均有实现承载：

| 官方服务 | iris-lite 对应能力 |
|---|---|
| **Data Integration** | MySQL/PostgreSQL → Debezium Server → Redis Stream → 实时投影（hash/JSON）+ FT 二级索引 + 缓存失效 + 数据版本 bump |
| **Context Retriever** | YAML 声明式 Schema（热载）→ 治理链（字段裁剪/租户隔离/access tags/索引校验）→ Query Engine 索引查询 |
| **LangCache** | 实体查询两层缓存（精确 + 语义 0.85）+ LLM 响应语义缓存（两级命中 + 槽位校验 + 交叉编码器重排 + 数据版本守卫） |
| **Agent Memory** | 工作记忆（TTL + Lua 原子 append）→ 渐进摘要晋升长期记忆（KNN 语义索引、两级去重、ownerId 隔离） |

Agent 侧通过 **REST**（`/api/v1`）或 **MCP Streamable-HTTP**（`/mcp`，on-demand 动态工具发现）访问数据；附带一个 14 页真数据管理控制台（React 19）与 SSE 流式 Agent 演示页。

## 架构

```
MySQL/PG --Debezium--> Redis Stream --DefaultChangeEventHandler-->
  投影 hash/JSON + FT 索引 + invalidateEntity + 版本 bump(ver:{entity})
                                                      |
Agent <--MCP(/mcp, 动态工具) / REST(/api/v1)-- 查询链路（三层装饰器：
  授权裁剪 → 语义缓存 → 精确缓存），Query Engine 索引查询，分页下推 LIMIT
```

- **查询红线**：一律走 Redis Query Engine 二级索引，禁止 SCAN + 逐条 GET；索引默认 TAG/NUMERIC，TEXT 按字段显式开启。
- **安全模型**：授权集合与字段约束取交集（防 TAG OR 越权）、无授权 fail-closed、tags 由鉴权层注入不可伪造、语义缓存中集合值绝不参与模糊匹配（防跨 Agent 越权）、动态 agent key 存 SHA-256 指纹不落明文。
- **数据版本守卫**：CDC 投影成功即 bump 实体版本；LLM 语义缓存命中后校验依赖实体版本，数据变更即 stale/fresh bypass，杜绝返回旧答案。

## 模块结构

严格单向依赖 `api → web → application → context`，infrastructure 实现端口：

```
shared          错误码 / Redis key 策略 / 指标
context         Schema 与查询模型
memory          Agent Memory（V0 对齐）
cache           实体缓存 / LLM 语义缓存
cdc             ChangeEventHandler（只消费不查询）
infrastructure  Redis(Lettuce) / 文件 / 远程模型客户端
application     用例编排（无 Redis 命令）
web             中间件 HTTP 面：REST + MCP 端点 + 安全（普通 JAR）
api             中间件启动模块（fat jar，不含演示）
demo            Agent 演示模块（boot jar：聊天循环 + console 前端，独立部署体系）
deploy/         docker compose、Debezium 配置、Schema YAML
```

## 技术栈

| 组件 | 版本 |
|---|---|
| Java | JDK 21（虚拟线程） |
| Spring Boot | 4.1.1（Spring AI 2.0.1、Lettuce 7.5.2、Micrometer 1.17） |
| Redis | 8.x（AOF，Query Engine 二级索引） |
| CDC | Debezium Server 3.6.2 → Redis Stream |
| 源库 | MySQL 8.4 / PostgreSQL（docker compose 共 5 容器） |
| 远程模型 | Qwen3-Embedding-0.6B（embedder，1024 维）· Qwen3-Reranker-0.6B（缓存重排），llama-server 项目外部署（默认指向 Mac mini :8081/:8082） |

## 快速开始

```bash
# 1. 起基础设施（redis / mysql / postgres / debezium x2）
docker compose up -d

# 2. 准备远程模型服务（llama-server，embed + rerank 两个进程，OpenAI 兼容协议）
#    地址经 iris.embedder.remote.base-url / iris.llm-cache.rerank.base-url 配置；
#    无模型环境可用降级配置跳过：iris.embedder.type=bm25

# 3. 构建（验收/发布必须 clean package，增量打包会嵌旧 jar）
export JAVA_HOME=/path/to/jdk-21
./mvnw -DskipTests clean package

# 4. 运行（中间件 fat jar，默认 :8080）
java -jar web/target/iris-lite-web-*-exec.jar

# 5. 验证
curl http://127.0.0.1:8080/actuator/health
```

控制台：独立前端（React 19 + Vite，**开发需 Node 20+**），位于 `demo/console`，开发模式 `cd demo/console && npm run dev`（:5173，代理到 :8080），生产模式 `npm run build` 后自行托管静态产物。

> **演示数据集**：完整 ecomm 数据集（135 表，约 3.67M 行，1.1 GB）不随仓库分发。仓库内置 FK 闭包抽样版 `deploy/mysql/init/sample-data/iris_demo_sample.sql`（约 19 MB，11 万行，零孤儿行、确定性可复现，由 `scripts/sample-demo-dataset.py` 从运行中的演示库生成），导入方式：`mysql -D iris_demo -uroot -p < deploy/mysql/init/sample-data/iris_demo_sample.sql`。也可使用自己的业务库或自备脱敏数据；Schema 声明样例见 `deploy/schema/`。

### 关键配置

| 环境变量 | 说明 |
|---|---|
| `IRIS_API_KEY` | REST/MCP 的 X-API-Key 鉴权；空 = 关闭（本地开发默认，启动时打 WARN）。**生产必设** |
| `IRIS_LLM_API_KEY` | OpenAI 兼容 LLM 的 key（Agent 对话/记忆抽取/LLM 语义缓存用） |
| `IRIS_CONSISTENCY_JDBC_URL` | 一致性校验 JDBC 读源库地址（默认 `jdbc:mysql://127.0.0.1:3306/iris_demo`） |
| `IRIS_CONSISTENCY_JDBC_USER` | 一致性校验 JDBC 用户（默认 `root`） |
| `IRIS_CONSISTENCY_JDBC_PASS` | 一致性校验 JDBC 密码（仓库不落明文；演示 compose 的 MySQL 默认密码为 `iris-root`，本地按此设置） |
| `iris.embedder.type` | `remote`（默认，远程 embedding 服务）/ `bm25`（本地 BM25 词法降级，冒烟用） |
| `iris.llm-cache.rerank.enabled` | LLM 缓存重排门槛开关（false 回落纯余弦） |
| `iris.rag.*` | 混合检索（记忆 HYBRID 检索 + LLM 缓存查找）：`lexical.enabled` BM25 词法通道、`rrf-k`/`candidates` RRF 融合参数、`multi-query.mode` LLM 改写触发（`off` 缺省 / `on-miss` / `always`） |
| `iris.cache.freshness-bypass.*` | 时效词旁路：问题含「最新/现在/今天」类指示词时 LLM 缓存强制 miss、数据查询穿透两层缓存强制现算（`enabled` 缺省 true、`keywords` 可覆盖默认词表） |
| `iris.cdc.cache-invalidation-enabled` | CDC 逐条缓存失效开关（全量导入期间建议关闭，见下方提示） |
| `iris.mcp.dynamic-tools.mode` | `on-demand`（默认，2 个发现工具）/ `all`（全量注册） |
| `iris.schema.dir` | 外部 Schema 目录（覆盖 classpath 基线，mtime 热载） |

> **提示**：全量导入（百万级行）期间建议 `iris.cdc.cache-invalidation-enabled=false`，追平后再开启并手动清一次 `iris:{ns}:cache:*`——逐条失效在大 keyspace 下是 O(全库键数) 的 SCAN，性能红线详见 `docs/iris-lite项目总览.md`。

## Schema 声明

YAML 声明实体字段与索引，无需写代码：

```yaml
# deploy/schema/ecomm.pay_channel.yml
namespace: ecomm
entity: pay_channel
table: pay_channel
fields:
  - name: id
    type: LONG
    indexed: true
  - name: channel_code
    type: STRING
    indexed: tag        # 行级 access tag 的取值来源
  - name: channel_name
    type: STRING
    index: text         # TEXT 索引需显式开启
  - name: merchant_id
    type: LONG
    indexed: numeric
    relatedEntity: mch_merchant   # 外键关系，自动生成正/反向导航
```

支持字段裁剪、租户隔离、access tags（行级/字段级）、外键关系导航；控制台 Schema 页可直接编辑关系映射并回盘 YAML。

## MCP 接入

```
http://127.0.0.1:8080/mcp   (Streamable-HTTP, 需 X-API-Key 时透传)
```

`on-demand` 模式下 `tools/list` 恒 25 个（23 静态 + `search_entity_tools` / `call_entity_tool` 两跳发现），Schema 热载后自动广播 `tools/list_changed`——数百实体不会撑爆 Agent 上下文。

## 文档

- [docs/iris-lite项目总览.md](docs/iris-lite项目总览.md) — 对标范围、架构、安全红线、踩坑清单、端点速查（必读）
- [llama-server 部署指引](https://github.com/ggml-org/llama.cpp) — 远程模型（Qwen3-Embedding / Qwen3-Reranker）服务化
- [deploy/schema/](deploy/schema/) — 135 个电商演示实体 Schema

## License

[Apache-2.0](LICENSE)
