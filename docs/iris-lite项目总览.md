# iris-lite 项目总览

> **本文档为 `docs/` 下唯一文档**（2026-09-07 整理，取代原架构方案/路线图/对标笔记/审查报告/UI 设计方案等全部历史文档）。
> 内容以当前代码实现为准；更细的增量记录见 `.workbuddy/memory/` 工作日志。代码与本文冲突时，以代码为准。

---

## 1. 定位与官方对标

Java 复刻 Redis 官方 **Redis Iris**（Context Engine），单体 8 业务模块 + web/demo 装配模块（2026-09-13 拆分，详见 §2 模块边界），根包 `com.iris.lite`。**对标官方全部四个服务，均已覆盖**：

| 官方服务 | 官方能力 | iris-lite 承载 |
|---|---|---|
| Context Retriever | 实体/字段建模（PK、Related Entity、Type、Index、Description），自动生成检索工具经 MCP 暴露 | YAML Schema（`iris/schema/**` + 外部目录热载）；`DynamicEntityToolRegistrar` 五类动态工具 + `tools/list_changed`；T20 控制台关系映射编辑 |
| Agent Memory | session memory（TTL）+ long-term memory + 自动摘要 | 双层记忆 + 渐进摘要（8000 字符/40%）+ 四策略注册表 + ownerId 隔离（T17） |
| LangCache | LLM 响应缓存，省 token | T16 两级命中（精确 promptHash → KNN 0.90）+ T19 数据版本围栏/fresh bypass（超出官方：官方只有 TTL+手动删） |
| Data Integration (RDI) | CDC 流式同步关系库 → Redis，全量物化，agent 不回查主库 | MySQL/PostgreSQL → Debezium Server → Redis Stream → 投影 hash/JSON + FT 索引 |

**官方关键结论**（调研过官方文档与全 GitHub，含否定证据）：
- 官方 Context Retriever 的 Related Entity 是**人工下拉选择/声明**（Auto-detect 仅为辅助且官方明示须人工核对）；
- 官方 LangCache 新鲜性只有 TTL + 按 attributes 手动批量删，**无数据变更联动**——T19 围栏为超出官方的补强；官方「省 90% token」是营销口径（up to），文档公式 = 月输出 token × 命中率；
- 能直接抄的官方开源服务端组件只有 Lettuce/Jedis 侧 demo，无完整服务端实现可参照。

## 2. 技术基线与运行环境

| 组件 | 版本/说明 |
|---|---|
| Redis | 8.10.1（AOF 持久化），Query Engine 二级索引 |
| JDK | 21（`/opt/homebrew/opt/openjdk@21`） |
| Spring Boot | 4.1.1；Spring AI 2.0.1；Lettuce 7.5.2；Micrometer 1.17 |
| CDC | Debezium Server 3.6.2 → Redis Stream |
| 源库 | MySQL 8.4、PostgreSQL（docker compose 共 5 容器：redis / mysql / postgres / debezium / debezium-pg） |
| Embedder | RemoteEmbedder（远程 llama-server，Qwen3-Embedding-0.6B，1024 维；2026-09-12 起 ONNX 本地推理全量移除）与 Bm25Embedder（本地 BM25 词法近似）按 `iris.embedder.type` 互斥，remote 缺省。Rerank 同为远程（RemoteCrossEncoderReranker，Qwen3-Reranker-0.6B，双向取 min） |
| LLM | OpenAiCompatibleLlmClient（OpenAI 兼容协议，密钥经环境变量 `IRIS_LLM_API_KEY` 注入，不落仓库）。2026-09-11 起为百炼 DashScope `qwen3.8-max-0902`（base-url 必须含 `/compatible-mode/v1` 前缀；原 qwen3.7-flash 免费额度耗尽 403 后切换，各模型额度独立）；`iris.llm.max-tokens` 默认 8192 |

**模块边界**（依赖严格单向 web→application→context；infrastructure 实现端口）：
`shared`（错误码/key 策略/指标）· `context`（Schema/查询模型）· `memory` · `cache` · `cdc`（只消费不查询）· `infrastructure`（Redis/文件/远程模型客户端）· `application`（无 Redis 命令）· `web`（中间件 HTTP 面：REST + MCP 端点 + 安全 + boot 启动类，fat jar 只装配本链路不碰演示）。**`demo`（2026-09-13 拆分）**：Agent 演示 boot 模块——聊天循环/SSE/配方自进化/console 前端（React 19 + Vite + Tailwind v4，13 页真数据，无 mock 通路）独立部署体系，中间件 fat jar 不含演示（盘点见 `docs/演示与中间件边界盘点.md`）。

**常用命令**：
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./mvnw -DskipTests clean package          # 验收必须 clean package（增量打包嵌旧 jar）
java -jar web/target/iris-lite-web-0.1.0-SNAPSHOT-exec.jar   # 中间件 fat jar :8080（web 兼作依赖库，fat jar 用 -exec 分类器）
curl --noproxy '*' ...                    # 本机代理 59931，curl 必须绕过
/usr/local/bin/docker exec iris-redis redis-cli ...     # redis-cli 走容器
```

## 3. 架构与数据流

```
MySQL/PG --Debezium--> Redis Stream --DefaultChangeEventHandler-->
  投影 hash/JSON + FT 索引 + invalidateEntity + 版本 bump(ver:{entity})
                                                      |
Agent <--MCP(/mcp Streamable-HTTP, 动态工具) / REST(/api/v1)-- 查询链路（三层装饰器：
  授权裁剪→语义缓存(0.85)→精确缓存），Query Engine 索引查询，分页下推 LIMIT
```

- **查询红线**：必须走 Query Engine 索引，禁止 SCAN+逐条 GET（100 万条约 244MB，性能差 4 万倍）；索引默认只建 TAG/NUMERIC，TEXT 按 `index: text` 显式开启（2026-09-05 解禁，对齐官方 search_X_by_text）。
- **缓存三层**：实体精确缓存 / 实体语义缓存（阈值 0.85，容量 1000，TTL 最短优先淘汰）/ LLM 响应语义缓存（T16：两级命中，modelTag=sha256(model)[:8] TAG 精确隔离绝不模糊；T19：store 带 dependencies 存实体版本快照，lookup 命中后校验版本不符 → miss/stale，`fresh: true` 直接 bypass）。
- **记忆**：工作记忆（TTL + Lua 原子 append + 文档化）→ 渐进摘要晋升长期记忆（KNN 语义索引 `wm-`/`v2-` 段名，两级去重 memoryHash→KNN 0.80，ownerId 隔离）。
- **Schema 治理链**：YAML（classpath 基线 + `iris.schema.dir` 外部覆盖，mtime 轮询热载，全量成功才原子替换）→ 字段裁剪/租户隔离/access tags/索引校验全链复用；FK（relatedEntity）强制索引，T20 控制台可编辑关系映射回盘 YAML。
- **Redis key**：统一前缀 `iris:{namespace}:`，精确形态见 `shared/.../key/RedisKeyPatterns.java` 与 `KeyStrategy.java`（实体版本 `iris:{ns}:ver:{entity}`、LLM 缓存 `iris:{ns}:llmcache:{modelTag}:{promptHash}` 等）。

## 4. 功能进度与验收索引

推进序：P0 16/16 → P1 17/17 → T5 11/11 → P2 13/13 → P3 23/23 → MemoryV0 29/29 → T14 21/21 → T15 15/15 → T16 26/26 → T17 44/44 → T18 19/19 → T19 27/27 → T20 21/21 → T21 10/10 → **T22 9/9（2026-09-08 收官）**。

| 里程碑 | 要点 | 验收脚本（deploy/verify/） |
|---|---|---|
| P0-P3 | CDC 查询闭环、工具注册、动态 Schema 热载 + tools/list_changed | verify-p3.sh / verify-tools.sh / verify-index.sh |
| MemoryV0+T17 | 双层记忆、渐进摘要、ownerId 全链路 | verify-memory-v0.sh |
| T14 | agent key 动态管理（指纹存储、热载、operator 校验） | verify-agentkeys.sh |
| T15/T16 | Related Entity 导航；LLM 语义缓存 | （并入后续脚本）verify-llmcache.sh |
| T18 | 管理控制台 13 页真数据接线 | verify-console-api.sh（A-F 段） |
| T19 | 数据版本围栏 + fresh bypass | verify-console-api.sh F 段 |
| T20 | Schema 关系映射编辑 + MCP 描述补关系 | verify-schema-edit.sh |
| T21 | Agent Demo：控制台 Agent 对话（SSE 流式 + 工具循环 + 缓存/围栏联动） | verify-agent-demo.sh |
| T22 | LLM 缓存精判门：rerank 召回放宽 + 槽位校验 + 交叉编码器（防微改写误命中） | verify-llm-cache-precision.sh |
| T25 | 缓存失效索引化：`SCAN` 全库遍历 → `SMEMBERS` 索引集合（单条 5.3 秒 → 一次往返） | verify-cache-invalidation.sh（含单实例 + 无残留扫描客户端 + **目标实体 lag=0** 三重守卫；列名自动推导，任意实体可复用） |
| T26 | 时区口径定论：Debezium 对 DATETIME 写死 UTC，无法配置；Agent 日界随之锁定 UTC | verify-cdc-completeness.sh（T26/T29 共用最终判定） |
| T27 | 消费端多实例隔离：id 带实例标识 + 启动剪枝 pending=0 的陈旧消费端 | verify-cdc-completeness.sh / 启动日志 `陈旧消费端已清理` |
| T28 | 语义缓存路径 O(1) 化：复用 T25 索引 + 客户端前缀过滤，补 `mget` 压掉 N 次往返 | verify-semantic-cache-index.sh（默认用无 CDC 流的探针实体 `t28probe`，与排空进度解耦） |
| T29 | XTRIM 安全上限：`max(stream-maxlen, lag+pending+余量)`，杜绝裁剪未消费事件 | verify-cdc-completeness.sh + safe-trim-streams.sh |
| T30 | reclaim 自身在途消息防护：claim-idle-ms 回归 30000 | （参数侧，见启动日志 `claimIdleMs=`） |
| T31 | LLM 缓存维护路径索引化：`store()` 里的全库 `SCAN`（实测 7.08 秒，挂在**每次回答的请求路径**上）→ 两个 ZSET 索引（score=过期毫秒），`ZCARD`/`ZRANGE` 替 `SCAN` | verify-llm-cache-index.sh（默认用无 CDC 流的探针命名空间 `t31probe`，用 `PUT /api/v1/llm-cache` 直接驱动 `store()`，免调真实 LLM；核心断言**写入路径 scan 增量=0**） |

**CDC 表级排除（2026-09-11）**：高频日志/流水表**不进 CDC 链路**——Debezium 侧 `table.exclude.list`（优先级高于 include）排除 10 张追加型流水表（`ord_order_status_log` 等，名单唯一出处 `deploy/verify/cdc-excluded-tables.txt`），消费端 `iris.cdc.sources` 同步删除对应块（135→125），schema yml 移入 `deploy/backups/log-tables-excluded-20260911/`。**排除前已入库的快照须清理**（`deploy/verify/decommission-log-tables.sh`，默认 dry-run、`IRIS_CONFIRM=yes` 才执行；前置：Debezium 已停），否则 Agent 会查到永不更新的冻结数据。**注意：`log_*` 前缀是物流（logistics）不是日志**——`log_company`/`log_pickup_point` 是维表、`log_logistics*` 是运单/包裹/轨迹业务数据，全部保留。对账脚本自动跳过排除表（非丢失）。

**CDC 运维/验收工具（deploy/verify/）**：`monitor-cdc-import.sh`（全量 lag/pending/DLQ 监控，一次 EVAL 完成，禁止抽样；**判定 DONE 要求 lag=0 且 pending=0**；流名从 application.yml sources 解析，随排除自动缩减）、`stream-stats.lua`（其服务端统计脚本）、`verify-cdc-completeness.sh`（源库行数 vs 索引 num_docs 逐表对账，鉴别静默丢数据，自动跳过排除表）、`safe-trim-streams.sh`（积压期离线安全裁剪，只摘已确认事件以释放内存）、`replay-dlq.sh`（DLQ 原 payload 重投）、`decommission-log-tables.sh`（表级退役清理，dry-run 默认）。

**验收顺序建议**：`run-final-acceptance.sh` 已把顺序编排成一个可后台跑的脚本（等排空 → 逐表对账 → T25 → T28 → Agent 时间问答实测），一次跑完并输出汇总。手工单跑时：先等 `monitor-cdc-import.sh` 报 `DONE`（lag=0 + pending=0），再依次跑 `verify-cdc-completeness.sh`（逐表对账）→ `verify-cache-invalidation.sh`（需目标实体 lag=0）→ `verify-semantic-cache-index.sh`（探针实体，随时可跑）。在排空完成前跑前两个会得到「环境未就绪」型失败，容易被误读为实现缺陷——脚本已内置守卫并给出明确提示。

> 注：上表所列**多数历史脚本已随 demo 命名空间退役移除（2026-09-09）**，里程碑验收口径保留备查。**现仓内 `deploy/verify/` 实际只有 9 个文件**：`run-final-acceptance.sh`（T26-28 编排）、`verify-cache-invalidation.sh`（T25）、`verify-semantic-cache-index.sh`（T28）、`verify-cdc-completeness.sh`（T26/T29）、`verify-llm-cache-index.sh`（T31）、`monitor-cdc-import.sh`、`stream-stats.lua`、`safe-trim-streams.sh`、`replay-dlq.sh`。
>
> **仓内已不存在、且不可从历史恢复的脚本**（本项目非 git 仓库，无版本历史）：verify-p3 / verify-tools / verify-index / verify-p2 / verify-memory-v0 / verify-agentkeys / verify-console-api / verify-schema-edit / verify-agent-demo / verify-llmcache / verify-llm-cache-precision / verify-extractor / verify-embedder。仅 `verify-related.sh` 可从 `deploy/backups/demo-namespace-retired-20260909/` 恢复。**故 §4 表格的「验收脚本」列与 §7 引用的 verify-tools.sh §7 均属历史口径，不能作为可执行依据**；重跑需先按 ecomm 实体重写（见 §9 待办）。

## 5. 安全红线（T11/T14，违反即越权）

- 授权集合与字段约束取**交集**（防 TAG OR 越权）；无授权 **fail-closed**。
- legacy 通配 `*` 严禁配给真实 agent；tags 由鉴权层注入不可伪造（MCP 从 request attribute 取身份）。
- 语义缓存中集合值（TAG/range/text）必须精确匹配，绝不进语义模糊（防跨 Agent 越权）。
- Schema 编辑、agent key 管理等管理面端点须 legacy operator 身份；鉴权关闭（未配 `iris.security.api-key`）时全放行（本地开发姿态）。
- 动态 key 存 SHA-256 指纹不落明文；动态 > 静态 > legacy 优先级；轮换零停机。
- T12 一致性：JDBC 读源库 + count/full 校验 + repair 以源库为准；起 app 先 repair 清漂移。

## 5.5 RAG 混合检索（2026-09-12）

**多路召回 → RRF 融合 → rerank 精判**，生效面 = 长期记忆检索（HYBRID 模式，缺省）+ LLM 缓存语义查找：

1. **存入侧**：记忆/LLM 缓存条目 save 时伴生写 `lex` 派生字段（`LexTerms` 词项化：ASCII 整词 + CJK bigram，语言无关、确定性）；FT TEXT 索引（`iris:{ns}:index:memory-lex` / `llmcache-lex`）覆盖文档前缀，删除/过期随文档自然消失。首次开启自动回填存量（走条目索引/ZSET，不碰 SCAN 红线）。`LexicalIndexManager`。
2. **三通道召回**：稠密（embedding KNN，owner/modelTag TAG 预过滤）+ 词法（BM25，`RedisAdapter.ftSearchRank` 无 SORTBY 默认按相关度降序）+ LLM 多查询改写（`MultiQueryExpander`：`off` 缺省（2026-09-13，deepseek 改写实测 ~9.5s 太贵）——`on-miss` 记忆=融合池为空触发、缓存=未命中触发，命中路径零 LLM 延迟；`always` 可配；fail-open）。
3. **时效词旁路（2026-09-13）**：`FreshnessDetector`（`iris.cache.freshness-bypass.*`，缺省开，词表 最新/现在/目前/当前/实时/此刻/今天/今日/刚刚/刚才 可配）——问题含时效指示词 = 语义锚定说话时刻，缓存条目（历史快照）不可信：LLM 缓存 lookup 咽喉点强制 miss（reason=fresh-keyword，REST/MCP/Agent 三入口全覆盖）；Agent 侧经 ToolContext→QueryRequest.fresh 穿透语义/精确两层查询缓存跳过读强制现算（回写保留，快照对后续普通查询仍有效）。与 T19 围栏互补：围栏管「数据变了」，本机制管「问题要求此刻口径」。
3. **融合**：RRF `score=Σ 1/(k+rank)`（`RrfFusion`，k=60，只认排名不认分数口径）；候选池上限 `iris.rag.candidates`（12）。
4. **精判**：`CrossEncoderReranker.scoreBatch` 批量判定（正向前向 1 次 + 反向逐对 = N+1 次往返）；LLM 缓存保留围栏→槽位→rerank 门序，记忆按 rerank 分重排（失败保持 RRF 序，不不可用）。
5. **域模型**：`LongTermMemory`/`LlmCacheEntry` 加 `@JsonIgnoreProperties(ignoreUnknown=true)`——存储侧派生字段（lex）不属于领域模型，本项目 ObjectMapper 开着 FAIL_ON_UNKNOWN_PROPERTIES，不加必炸（已踩）。

KEYWORD 模式 = 词法 BM25（排序下推），词法不可用/单字 CJK 查询落回旧包含匹配路径；SEMANTIC 模式保持纯 KNN 契约。**查询语义缓存（实体查询）未接入多路召回**——槽位/值级门序敏感，暂维持 exact+KNN 原链路（升级见 §9 待办）。

## 5.6 性能治理：预热与预聚合（均已移除，2026-09-14）

背景与容量账本见 `docs/内存预算与容量推导.md`（冷查询 2s+ 的根因 = 16G Mac 跑 5G 数据集宿主机换页，非聚合算力）。

P1 曾落地两套加速组件，2026-09-14 老板评审后**全量移除**：

1. **~~索引预热 `iris.warmup.*`~~（已移除，2026-09-14）**：曾定时对「实体 × 维度」跑轻量分组聚合焐热索引页（application/warmup）。移除理由：只覆盖显式声明的维度、换维度即失效；演示 spec 的表/字段名（pay_payment/channel_id）渗进配置与日志，违反「演示语义不进中间件」红线。客户端 timeout-ms 8000 + 服务端 FT.CONFIG TIMEOUT 已兜住冷查询体验。
2. **~~预聚合 `iris.preagg.*`~~（已移除，2026-09-14）**：曾落地 CDC 增量 HINCRBY 计数 + `PreAggregatedEntityQueryService` 直读装饰器（链 4→5 层）。移除理由：收益面窄（只覆盖显式声明的单维度、换维度即失效）、CDC 写路径常驻成本不值得。教训沉淀：加速类派生数据必须先算清「查询形状覆盖率」，覆盖率低就不配常驻。
3. **保留的配置治理**：`stream-maxlen 10000→1000`（-780MB）；客户端 `iris.redis.timeout-ms 2000→8000`（冷查询 2s+ 不再被客户端抢先掐死，护栏仍在服务端 FT.TIMEOUT）；AOF 自动重写恢复 percentage=100（忘改回导致 13.9GB 积压，见 docker-compose.yml T26 注释案底）。

验收（历史）：MCP aggregate_entity 直读 2957 组与金标准 FT.AGGREGATE 逐值一致（preagg 验证记录，组件已删）。

## 6. 控制台与身份模型

- 顶栏「key 未配置/••••xxxx」= 浏览器 localStorage 的 `X-API-Key`（设置页配置）；「operator/agent」徽章 = 前端探测 `/api/v1/admin/agent-keys` 的可达性。后端鉴权关闭时匿名即 operator。
- 13 页：Dashboard / Query / Keys / Memory / LLM Cache / Cache / CDC / Schema / Agent Keys / Consistency / Redis / Metrics / Settings；Schema 页支持关系映射编辑（T20，operator 专属）。
- **Agent 演示页（T21，14 页）**：左对话（答案逐字流式 + 6 个预设场景 chips）右调用轨迹（轮次/工具卡片/思考折叠/缓存徽章）；SSE 事件协议 round/reasoning/tool_call/tool_result/cache/answer_delta/answer_reset/done/error + `: ping` 心跳；application 侧 `AgentEventSink` 端口、api 侧 SseEmitter 适配（虚拟线程执行，emitter 上同步串行发送）。
- CDC 积压口径 = XINFO GROUPS 的 **lag**（Debezium 消费不删事件，XLEN 是历史累计只增不减）。
- Micrometer 1.17 起 actuator 不再输出 PERCENTILE_*：延迟分位走 `IrisMetrics.timerPercentiles` + `GET /api/v1/metrics/query-latency`。

## 7. MCP 工具面

Streamable-HTTP `/mcp`。**两种模式（`iris.mcp.dynamic-tools.mode`，默认 `on-demand`，改配置重启生效）**：

- **on-demand（按需发现，默认）**：实体工具**不**直接注册，只注册 2 个发现工具——`search_entity_tools(query, limit)`（多字段加权评分搜索：工具名>实体名>字段名>描述；空 query 返回目录概览；limit 默认 8 上限 20）+ `call_entity_tool(name, arguments)`（按名称分发，执行路径与直调完全一致：身份解析→AccessControlledEntityQueryService→分页 JSON）。内存索引**不截断**（全量候选 ~840 个，只搜不注入 LLM 上下文），Schema 热载后 3 秒对账 + 指纹比对 + volatile swap + 广播 `tools/list_changed`；tools/list 恒 24 个（22 静态 + 2 发现）。未知工具名/固定工具名进 `call_entity_tool` 报「未知工具」并引导先 search。
- **all（全量注册，旧行为）**：按实体直接生成五类工具（`query_{entity}` / `get_{entity}_by_{pk}` / `filter_{entity}_by_{field}` / `find_{entity}_by_{field}_range` / `search_{entity}_by_text`；超 8 个/实体按 主键>numeric>tag>text 截断；跨 namespace 同名自动加 ns 前缀）。

两模式下 Schema 热载均触发对账日志「动态 MCP 工具已更新」并列出 +/-/~ 变更明细；query 工具描述含字段清单、多租户提示与「实体关系: x→y」。验收：verify-tools.sh §7（on-demand）+ §1-6（all 模式回归）。

**Agent 直连工具目录（2026-09-11 全方案落地）**：与 MCP 动态面相互独立，共 **14 个**——新增 `aggregate_entity`（FT.AGGREGATE 统计聚合：group_by/metrics/filters/range_filters/sort/limit≤100，走完整装饰器链含行级鉴权；**统计类问题必须优先用它**，query 工具无聚合能力，逐页拉取被 RepeatGuard 拦截）与 `ask_clarification`（交互澄清，D1：runRound 内拦截 → 发 `clarify` SSE 事件 → 前端点选或「其他」填写 → 以「澄清答复：X」同 sessionId 续跑，澄清问答只入工作记忆不入语义缓存）。两工具经 `LOCAL_DISPATCH_TOOLS` 本地分发，不经 MCP；MCP 动态面 v1 工具清单不变。配套：query 工具描述声明截断（C2，ResultTrimmer 50 行/32KB 双闸门）、助手历史答案降载 ≤200 字符（C3）、RepeatGuard + reasoningLooped + 末轮强制总结三护栏（B3）、提示词规则 11-13，`/api/v1/agent/info` 暴露提示词指纹（SHA-256 前 16 位 + 字符数）。

## 8. 关键踩坑（高频复发，编码前先对照）

- **Lettuce 整数回复必须用 IntegerOutput/ScriptOutputType.INTEGER**——否则命令已执行但客户端报错（假失败，潜伏最危险）。
- FT.SEARCH 按 RESP3 map/RESP2 array 首元素类型分流；TAG 引号值必须 DIALECT 2；KNN 须显式 `SORTBY __vec_score ASC`，空结果不回写语义缓存。
- 中文入库先 `SET NAMES utf8mb4`；Lettuce SCAN 结束判 `!"0".equals(cursor)`；FT 索引对 `--scan` 不可见（断言用 FT.INFO/`FT._LIST`）；Tomcat 拒绝 URL 裸中文（curl 测试要编码）。
- 同文件多个 Edit 并行批次会写覆盖竞争——必须逐个串行并 grep 复核；BSD grep 不支持 `\|` 交替（用 `grep -E`）；zsh 把 `agents[0]` 当 glob（加引号）。
- REST DTO 可缺省数值字段用装箱 Integer（Jackson 3）；Spring 枚举 RequestParam 大小写敏感（控制器收 String 自行 valueOf）；Boot distribution 配置 map key 含点必须方括号 `"[iris.query]"`。
- `Map.copyOf` 拒 null key 抛无消息 NPE——重组 record 时主键字段显式补齐；catch 日志必须带异常对象。
- `IrisMetrics.callUnchecked` 曾把 IrisException 包成 RuntimeException（已修）；fail-closed 短路返回空页不抛异常，上层服务对空页要显式转 404。
- DeepSeek v4-flash 是推理模型：max-tokens 必须 ≥4096，否则 reasoning 耗尽预算 content 为空。**2026-09-11 C4：`iris.llm.max-tokens` 默认上调 8192**——多轮工具调用下 reasoning + 正文共享预算，4096 极易在长答案中途截断。
- **FT.AGGREGATE 的 `DIALECT` 必须是最后一个参数（A1 实锤，2026-09-11）**：放在子句中间会破坏解析——rows 全空 + total 跳变（实测 2957→30412/34212）+ Timeout warning，与 FT.SEARCH 解析器行为不同。另：大 LIMIT+SORTBY 聚合有**偶发超时窗口**（同命令首跑空 + warning、重跑即对；limit 50 触发、limit 15 从不）——`ftAggregate` 已加一次自动重试兜底（rows 空且 limit>1）。RESP3 应答行数据在 `extra_attributes` 键下，解析须双协议（RESP2 首元素数字 = total）。
- SCAN/glob 的 `*` 跨段匹配（含冒号），区分文档/向量要 SCAN 后显式过滤。
- XREADGROUP BLOCK 用独立 `cdcRedisConnection`；同值 UPDATE 不写 ROW binlog。
- FIELD_NOT_FOUND（IRIS-1002）映射 HTTP 400 不是 404；ENTITY_NOT_FOUND（IRIS-1001）才是 404。
- reload 失败会**静默保留旧 Schema**（lastError 上浮），服务层改完必须复核生效，防假成功。
- YAML 外部目录只扫**一层平铺** `*.yml`（不递归），回盘文件须 `{namespace}.{entity}.yml` 平铺。
- **双塔与交叉编码器对数字/日期槽位差异共同失明**（T22 实测，当时为本地 bge 模型）：事故句对 cos 0.916、交叉编码器 rerank 0.9998，与真同义对（rerank 0.999-1.0）完全重叠，无阈值可分——槽位一致性校验（数字序列比对）必须保留在精判链路里，且先于 rerank 执行。
- **FT 索引 DROP+CREATE 在大 keyspace（367 万键）回填可达 3 分钟**：ECOMM 数据入库后任何触发索引重建的场景（Schema 定义变化/启动时定义不一致），回填完成前查询 total=0。验收脚本须先轮询 `FT.INFO num_docs` 等回填就绪再断言（verify-p3.sh §5 已内置）。
- **验收脚本的演示 Schema 必须在应用启动后写入**：启动前写入会被首轮索引/工具初始化静默包含，「新增」日志/断言永远等不到（verify-p3.sh T10 段踩坑）。
- **Debezium 对 MySQL DATETIME 的时区换算是写死 UTC 的，没有任何配置项能改（T26 定论，2026-09-10）**：官方文档《Temporal values without time zones》原文即 "converted into epoch milliseconds … by using UTC"。`debezium.source.connectionTimeZone=Asia/Shanghai` 无效（该键确实存在，见 `MySqlConnectionConfiguration.resolveConnectionTimeZone`，但它只作用于 **TIMESTAMP 列**的 ZonedTimestamp；本库 277 列 DATETIME、0 列 TIMESTAMP，故对本案完全空操作）；`debezium.source.database.serverTimezone` 更是在 connector jar 里**根本不存在**（全 jar 扫描无此字面量，那是 JDBC 驱动时代的 URL 参数），写了会被静默忽略。**故数据基准恒为「DATETIME 字面量按 UTC 解释」**，Agent 侧日界必须同步用 UTC 边界（`DefaultAgentService.nowAnchor()` 的 `DATA_ZONE=ZoneOffset.UTC`）——两侧任一侧改成 +08:00，「8/30 有多少笔订单」就会从 1217 变成 790。确需 +08:00 语义只能在**应用侧**换算（毫秒按 UTC 还原成字面量，再当 +08:00 业务时间用）。**提示词侧还要防 LLM 自算 epoch 差一天（2026-09-10 实测）**：模型把 8/30 换算成了 8/29 的窗口（[1787961600000,1788047999999]），答出 6 笔（8/29 真值恰好 6，错窗口+对查询极具迷惑性）。修法：`nowAnchor()` 附**近 32 天逐日 UTC 日界毫秒表（应用预计算）**，表内日期只许查表不许算术；表外日期自行换算时须自检「相邻两天 00:00 毫秒差=86400000」。
- **XTRIM MAXLEN 会裁掉未消费事件，而 group 的 lag 会显示 0 把丢失完全掩盖（T29，2026-09-10 实测）**：XTRIM 只认「保留最新 N 条」，不区分是否消费过。全量快照产出约 2.5 万行/秒、消费约 2500 条/秒，单表 20 万行的快照瞬间把该流顶到 20 万条，而 `stream-maxlen=100000` → `ord_order_status_log` 源库 202464 行、Redis 只剩 100005 条，**lag 却报 0**（group 的 last-delivered-id 被推到尾部）。全库共少 20.5 万条（源库 3669721 行 vs DBSIZE 3464153），且被裁消息不在 PEL 里，重试与 DLQ 都兜不住。修法：`max(stream-maxlen, lag + pending + 1000)` 作为实际上限（CdcConsumer.trimStream）。**只有「源库行数 vs 索引 num_docs」的逐表口径能发现它**（`deploy/verify/verify-cdc-completeness.sh`）。
- **Docker VM 内存是硬上限，fork 型持久化会瞬时翻倍（T26 内存事故）**：本机 Docker VM 仅 11.67 GiB。导入期每秒数万写入触发镜像默认的 `save 60 10000`，每 60 秒一次 BGSAVE；fork 的 copy-on-write 让内存逼近翻倍，Redis 被 OOMKilled(137)，AOF 重放 5 分钟才恢复（现场留下 1.15 GB `temp-*.rdb`）。已在 compose 关闭周期 RDB（`--save ""`，AOF everysec 已足够），批量导入前再 `CONFIG SET auto-aof-rewrite-percentage 0`。**另一处配置缺陷**：`stream-maxlen` 原为 100000，×135 流 = 最坏 1350 万条 ≈ 9.5 GB，单"保留窗口"就能吃穿 VM，已降为 10000。
- **`claim-idle-ms` 必须显著大于「最坏一批的处理耗时」，否则实例会抢自己的在途消息（T30）**：曾长期用验收提速值 3000ms。全量导入 310 万积压时每批处理远超 3 秒，本实例的 reclaim 就把**自己在处理**的消息当"过期未确认"XCLAIM 再处理一遍——排空速率实测只有 1000 条/秒（单实例能力约 2500 条/秒），且第二次处理撞上 2 秒命令超时后按 max-deliveries 被判毒消息入 DLQ（本次累积 16 条）。已回归生产值 `claim-idle-ms: 30000 / max-deliveries: 3 / reclaim-interval-ms: 15000`。
- **DLQ 条目自带完整 payload，可直接回放，无需回源库（`deploy/verify/replay-dlq.sh`）**：`moveToDlq` 把原始 payload/msgKey/deliveryCount/error 整条落库；消费端 `process()` 取 body 第一个 value 当 envelope，投影是按主键 upsert，故重投幂等。比「UPDATE 源库那一行」更干净——同值 UPDATE 写不写 ROW binlog 取决于 MySQL 是否检测到实际变化，不保证能触发 CDC。
- **重启会在 PEL 里留下挂在「已死消费端」名下的残留，且只能靠 reclaim 慢速消化（2026-09-10 实测）**：实例被停时未确认的消息留在 PEL，owner 是旧消费端（如 `cdc-ecomm-afc_after_sale-<host>-28975`）。T27 的 `pruneStaleConsumers` 因硬约束「只在 pending==0 时删」不会动它，故**陈旧消费端名会一直挂在 XPENDING 里**，这是正确行为而非漏删。消化通道只有 reclaim：`RECLAIM_BATCH=100`、135 流串行、一轮 sweep 约 9.5 分钟 → **每条流约 1 条/5.7 秒**。后果：`lag=0` 会远早于 `pending=0`（本次 lag 归零后又用了约半小时清 PEL）。判「追平」必须同时看 lag 与 pending，只看 lag 会误判。
- **验收脚本必须自带「环境就绪」守卫，否则会把时序问题报成实现缺陷（2026-09-10 实测）**：T25/T28 这类「造缓存条目 → 断言索引成员存在」的脚本，在目标实体还有 CDC 积压时必然失败——CDC 每消费一条事件就调一次 `invalidateEntity`，把刚写下的 `cacheidx:{entity}` 连同成员一起删掉。本次先按 ord_order 跑 T28 得到 3 项 FAIL（索引成员=0/scanned=0/mget=0），一度被误读为 T28 缺陷，真因是排空未完成。两条修法已落地：①**T28 改用无 CDC 流的探针实体**（`t28probe`，`CacheController` 对 namespace/entity 不做存在性校验，走的是同一条 `getAllMatchingWithKeys` 路径，与排空进度彻底解耦）；②**T25 加先决条件守卫**，`lag != 0` 直接 `exit 2`（环境未就绪，不产出假 FAIL），`pending != 0` 降级为 NOTE。另注意 **`commandstats` 的计数可信度不同**：`scan` 与 `mget` 可归因（CDC 路径只做 SMEMBERS+DEL），而 `smembers` 会被 CDC 冲高到上千（每事件一次），只能在 CDC 空闲时才当断言用。

- **请求路径上绝不允许出现全库 `SCAN`——LLM 缓存维护路径第三次踩同一个坑（T31，2026-09-10 实测）**：`LlmCacheService.store()` 在 `repository.save()` 之后**无条件**调 `repository.count()`，而旧实现是 `SCAN MATCH iris:{ns}:llmcache:*:*`。**`MATCH` 只在服务端逐个过滤，游标仍要走完整个 keyspace**，代价与命中数无关（当时 366 万键）——日志硬证据：`21:12:44.482 LLM 缓存已写入` → `21:12:51.564 SCAN 完成 命中=2`，**7.08 秒全花在这次 SCAN 上**；`evictTo()` 里还有第二次。表现是「答案逐字输出已结束，却要再等几秒才出现轮次/用量 footer」——因为该调用发生在最后一段 `answer_delta` 之后、`sink.emit("done", usage)` 之前，而 footer 只在收到 `done` 时渲染，**每次回答都要付一次**（不是仅首次）。
  - **为什么当初会选 SCAN**：Redis 没有「按前缀 O(1) 计数」的原语——`DBSIZE` 不能按前缀过滤，`KEYS` 阻塞且已被 SCAN 取代，`INCR/DECR` 维护的计数器遇到条目 TTL 自动过期会持续漂移，而自建索引若与条目生命周期不一致就会留下脏成员。当时选了「零维护 + 永远准确 + 不阻塞」的 SCAN，代价被误判为可接受。
  - **修法（与 T25 同款索引化）**：新增两个 ZSET——`iris:{ns}:llmcache:idx`（文档）与 `iris:{ns}:llmcache:vecidx`（向量），**score = 过期 epoch 毫秒**。`count` → `ZCARD`（O(1)）；`evictTo` → `ZRANGE(0, total-keep-1)` 直接取「最先过期」的受害者（O(logN)，且**省掉了原实现逐条 `PTTL` 的开销**）；向量清理在向量索引内按后两段后缀匹配（成本 ∝ 缓存规模而非 keyspace）。**用 ZSET 而非 SET 的关键理由**就是「淘汰剩余 TTL 最短者」这一语义由 score 顺序白送。
  - **索引的失效边界（务必守住）**：ZSET 不支持逐成员 TTL，成员过期副本靠每次计数/淘汰前的 `ZREMRANGEBYSCORE -inf now` **惰性清理**；**索引本身不设 TTL**——若给索引设 TTL 而条目还活着，索引先消失会让条目永远无法被计数/淘汰，比留一个空集合危险得多。
  - **`clear()` 刻意保留 SCAN**：它是显式运维操作（验收脚本、手工清缓存），不在请求路径上，可以承受全库遍历；换来的是能顺带清掉「索引化之前写入、尚未入索引」的历史条目。**升级后的注意点**：ecomm 命名空间里改前写入的旧条目不会被淘汰/计数，建议上线后清一次 `DELETE /api/v1/llm-cache?namespace=ecomm`。
  - **验收（2026-09-10 已通过 17/17）**：`deploy/verify/verify-llm-cache-index.sh`，用无 CDC 流的探针命名空间 `t31probe` + `PUT /api/v1/llm-cache` 直接驱动 `store()`（**免调真实 LLM**）。口径关键：快照 A 刻意取在 `DELETE`（clear 会 SCAN，且是唯一合法 SCAN）**之后**，故核心断言 **`Δscan == 0`** 不会被自身污染；另断言 `Δzadd/Δzcard ≥ N`、`ZCARD == min(N, max-entries)`、索引内逐成员 `EXISTS==1`（无悬空）、与真实 keyspace 交叉核对基数。**要覆盖淘汰断言须用 `--iris.llm-cache.max-entries=3` 起应用**（默认 1000 时脚本会自动把淘汰断言降级为 NOTE）。
  - **端到端收益实测**：真问一次 Agent（「8月30日一共有多少笔订单」），逐帧打时间戳得 **回答末段 → done 帧间隔 = 0.166 秒**；改造前同一位置含一次全库 SCAN（7.08 秒），**约 43× 改善**——这正是「答案逐字输出已结束、footer 却要等几秒才出现」的直接原因。答案 1217 正确（T26 时间锚点回归通过）。回归：T25 14/14、T28 9/9 均无退化。

## 9. 待办与已确认不做

**待办**：
- Map → VO 重构（原独立方案文档已并入此处）：五处改动——`SchemaController.entities()` → `List<SchemaSummaryVO>`、`RedisInsightService.stats()` → `RedisStatsVO`、`indexInfo()` → `IndexInfoVO`、`RelatedEntityService.related()` → `RelatedEntityVO+RelationVO`、`LlmCacheService.stats()` → `LlmCacheStatsVO`；B 类 Map（透传/动态形态）明确不动，入参不包装。
- 验收脚本 ecomm 化（**缺口比原先记录的大**）：2026-09-10 全量核验发现，文档/配置共引用 17 个脚本，**其中 9 个在仓内已不存在**——verify-p3 / verify-tools / verify-index / verify-p2 / verify-memory-v0 / verify-agentkeys / verify-console-api / verify-schema-edit / verify-agent-demo / verify-llmcache / verify-llm-cache-precision 均已随 demo 命名空间退役被删，且**本项目不是 git 仓库，无历史可恢复**；仅 verify-related.sh 可从 `deploy/backups/demo-namespace-retired-20260909/` 恢复。
  - 优先补 **verify-llm-cache-precision.sh**：T22 精判门 `threshold: 0.50` 的唯一标定依据就是它，脚本丢失意味着该门限**当前无法复核**。重建需按 ecomm 实体造金标 query 对（同义改写 / 微改写 / 事故原案三类），判据沿用原口径（同义 3/3 命中、微改写 4/4 拒绝、事故原案 slotRejected≥1）。
  - 其余脚本按里程碑价值排序重写（P3/T14/T18/T20/T21/T15 各自的口径在各里程碑行有描述）。
- RAG 混合检索后续完善（2026-09-12 立项，第一轮已落地见 §5.5）：①查询语义缓存（实体查询）接入词法通道 + RRF（需与值级门/槽位门重排协同，未动）；②rerank 阈值在融合候选池分布下复测（现有 0.90 基于纯 KNN 候选标定）；③多查询改写延迟——deepseek-v4-pro 实测 ~9.5s，生产建议给 `iris.rag.multi-query` 配低延迟模型或 off。

**已确认不做 / 未立项**：sensitive-data exclusions（官方自认建议性）；自定义 memory types（内置四类型已覆盖）。~~Agent Memory 自动晋升暂为空接口~~（**过时记录，更正于 2026-09-14**：T5 已完整实现 `MemoryExtractionService`——异步 worker 池 + trigger-every 阈值触发 + 两级去重 memoryHash→KNN + 四策略 discrete/summary/preferences/custom，demo 已开启）。

## 10. 端点速查

- REST：`/api/v1/query`（实体查询）、`/api/v1/entities/{ns}/{entity}/{id}/related`、`/api/v1/agent/**`（tools 工具目录 / info 当前生效模型与温度+系统提示词指纹 systemPromptSha256/Chars / chat SSE）、`/api/v1/memory/**`、`/api/v1/llm-cache/**`、`/api/v1/schema/**`（status/entities/detail/index/reload/PUT related-entity）、`/api/v1/admin/agent-keys`、`/api/v1/admin/redis/keys`、`/api/v1/cdc/**`、`/api/v1/redis/**`、`/api/v1/metrics/query-latency`、`/mcp`。统一 `X-API-Key` 头（actuator 豁免）。
- 部署：`docker-compose.yml`（5 容器）+ `deploy/` 脚本；控制台 `console/`（`npm run build` 产物走 Spring 静态资源或独立 dev server）。
