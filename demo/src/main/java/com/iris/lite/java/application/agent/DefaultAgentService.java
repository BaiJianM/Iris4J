package com.iris.lite.java.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.application.cache.LlmCacheService;
import com.iris.lite.java.application.memory.MemoryService;
import com.iris.lite.java.application.query.RecipeDraftStore;
import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.FieldSchema;
import com.iris.lite.java.context.schema.SchemaManager;
import com.iris.lite.java.memory.LlmClient;
import com.iris.lite.java.memory.WorkingMemoryDocument;
import com.iris.lite.java.memory.WorkingMemoryEntry;
import com.iris.lite.java.shared.util.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Agent 工具调用循环的默认编排。
 *
 * <p><b>循环结构</b>（见 AgentChatClient 端口注释）：每轮 = LLM 流式补全 →
 * 有 tool_calls 则逐个分发执行、结果以 role=tool 回填、进入下一轮；
 * 无 tool_calls 即终答。max-iterations 护栏防失控。
 *
 * <p><b>LLM 语义缓存集成</b>：运行前 lookup（modelTag 隔离，
 * 命中已内置版本守卫校验）；命中时把缓存答案分片回放成流式体验；
 * 终答 store 时声明 dependencies = 当前运行工具调用实际触及的实体——
 * 之后 CDC 投影 bump 版本，守卫自动判旧，无需人工失效。
 *
 * <p><b>会话留痕</b>：问答对写入工作记忆（联动工作记忆抽取触发钩子）；
 * Agent 也可自主调 save_working_memory/save_long_term_memory 工具。
 * 留痕失败只记日志，不影响当前回答。
 */
@Service
public class DefaultAgentService implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentService.class);

    private static final String SYSTEM_PROMPT = """
            你是 redis-iris-java 数据平台的管理助手，通过工具访问真实数据回答问题。规则：
            1. 涉及数据的问题必须调用工具获取真实结果，严禁编造数值或臆测数据内容。
            2. 实体 Schema 摘要已附在本提示末尾；若摘要已列出目标实体的字段，直接按字段名/类型构造查询，不要调 get_schema 重复确认；若摘要为两级模式（只含表用途与关联、未列字段），构造查询前必须先调 get_schema(entity) 取字段详情。多租户实体必须带 tenant 参数。
            3. 跨实体问题：先用 query_entity 查源实体拿主键，再用 get_related_entities 导航关联实体。
            4. 多个工具调用之间没有数据依赖时，必须在同一次回复中并行发起（例如同时查两个互不相干的实体）；只有后一个调用需要前一个结果作参数时才分轮。
            5. 工具返回 {"error":true,...} 时，读错误信息修正参数重试（最多换一种思路 1-2 次）。
            6. 数据随时可能变化，回答只能基于本轮工具返回的最新结果。
            7. 回答面向非技术读者，用自然语言呈现：先给结论，再按逻辑归纳分点，可配简短小标题；严禁把查询结果原样贴成数据库表、字段名+值列表或 JSON——那是给你看的原始数据，不是给用户的答案。
            8. 字段名、状态码、缩写必须转成通俗中文并按需一句话解释（如状态码缩写→中文含义、编号类字段说明其业务含义）；关键数字注明来源（哪个数据、共多少条）；用户要求记住的信息调用 save_long_term_memory。
            9. 时间处理：Schema 摘要里名为 *_at / *_time 的 LONG 字段是 Unix 毫秒时间戳（如 created_at:LONG）。日期对应的毫秒区间必须按本提示末尾「数据时间字段换算基准」给出的标准计算，严禁改用本地时区自行换算；「今天/昨天/本周/本月/最近 N 天」先用末尾「当前系统时间」确定是哪一天，再按该基准换算成毫秒区间，不得凭猜测、也不得按数据自行推断。
            10. 目标时间范围内查询无结果时，必须如实回答「该时段无数据」，并可说明数据的最新时间；严禁改用其他时间窗、把多个日期的数量相加、或编造「增长/下降」等对比结论。
            11. 数据事实强制锚定：所有回答必须完全依据本轮工具返回的数据事实生成，检索、分析、推理、总结、引用等任何环节均严禁编造、推测或虚构任何数据；当数据缺失或不足以支撑回答时，不得强行作答或猜测性补充，必须直接回答「信息不足，无法回答」，并简要说明缺少什么数据、现有数据可支持哪些查询标准；严禁拿相似字段、近似标准或部分数据冒充答案。
            12. 需求预判与澄清：回答前先判断需要哪些工具与查询标准；当问题存在歧义或关键信息缺失（时间范围未指明、统计标准两可、分组维度不明、实体指代含糊等）时，调用 ask_clarification 发起澄清（options 给 2-4 个互斥选项，必须来自真实存在的字段/标准）；收到「澄清答复：」开头的消息时，结合会话历史理解它对应你上一条澄清问题的哪个选项，直接按该标准继续执行，严禁重复提问或再次确认已明确的内容。仅关键标准缺失才澄清（同一问题最多一次）；标准可以合理默认时按默认执行并在答案中说明所选标准。
            13. 能力边界与聚合优先：分组统计/求和/平均/最值/排名/分布类问题必须优先用 aggregate_entity 一次调用完成（服务端聚合，严禁逐页拉取全量数据自行累计——单次问题分页拉取不得超过 3 页）；若所需字段不支持聚合（工具会明确报错），直接说明当前工具无法完成该统计，并给出你能回答的替代标准（如总量、按单值过滤查询），严禁编造统计结果。
            14. 派生标准重算：主实体某字段是累计总量（如「累计销量/累计评论数」这类全周期累计值）而问题要求分段标准（某时间段/某店铺/某类目）时，严禁以「字段标准不符」为由回答不支持——应回该业务的明细流水表重算：对明细实体用 aggregate_entity 的 rangeFilters 传时间毫秒区间 + groupBy 分组（明细表的时间戳与外键字段通常均为 numeric 索引，可直接聚合）；group_by 还支持「外键字段->维表实体.维度字段」语法直接按维表字段（如名称、类目）归并分组。
            15. 答案聚焦与标准唯一：用户问"是多少/排前几名"时，最终答案必须给出唯一数值/唯一榜单——严禁并列多套标准或多个数据源的结果让用户挑选；统计标准用一句话说明即可。统计标准默认值的取舍依据：字段/表 Schema 描述与值域声明中已写明的统计标准（如某状态字段注明"金额类统计按此过滤"），按声明执行；未写明的，两可标准按规则 12 澄清或按常识默认并在答案中说明，严禁把多套标准并列当答案。派生汇总数据与明细单据冲突时，以与问题语义直接对应的明细为准，汇总值只能作为参考提及。
            16. 分组排名必须使用唯一键：按某类业务对象分组/排名时，分组键必须是其唯一标识字段（主键或编码类），严禁使用可一对多的名称/描述类字段——同一对象的名称变体会把结果拆散成多行，榜单即失真。
            """;

    /**
     * 当前时间锚点格式（Now-Anchoring 模式）。
     *
     * <p>粒度到分钟：数据问答通常只需定位「今天」是哪一天，但顺带支持「现在几点」类提问。
     * 输出带中文「星期」，避免 3/13 与 13/3 之类日期顺序歧义。
     */
    private static final DateTimeFormatter NOW_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm", Locale.SIMPLIFIED_CHINESE);

    /** 缓存答案回放时的分片长度：模拟流式逐字输出。 */
    private static final int REPLAY_CHUNK_CHARS = 24;

    // ---------- 留痕摘要：摘要进窗口、全文按映射回捞 ----------

    /**
     * 答案长于此值才走 LLM 摘要：短答案全文即最好的摘要（再压缩反而丢信息），
     * 也省一次 LLM 调用与延迟。
     */
    private static final int SUMMARY_MIN_CHARS = 400;

    /**
     * 留痕条目内「完整答案」段标记：条目内容 = {@code 用户: Q\n助手: 摘要\n[完整答案]\n全文}。
     * 注入历史窗口时取标记前的「用户+摘要」段；全文随条目留在工作记忆里，
     * {@code get_working_memory} 工具可回捞。
     */
    private static final String FULL_ANSWER_MARKER = "\n[完整答案]\n";

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是会话留痕摘要器。把助手答案压缩为不超过 200 字的摘要：\
            保留结论、关键数字与统计标准，剔除过程性表述、重复与寒暄。\
            只能引用原文中的数字与事实，严禁新增、改写或推算任何数字。直接输出摘要正文。""";

    /**
     * 摘要回写专用单线程池：串行化保证「同一会话的条目更新按答案产生顺序回写」，
     * 且避免与并发 append/会话摘要的锁竞争放大。daemon 线程随进程退出，无需关停钩子。
     */
    private final ExecutorService historySummaryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-history-summarizer");
        t.setDaemon(true);
        return t;
    });

    private final ObjectProvider<AgentChatClient> chatClientProvider;
    private final ObjectProvider<McpToolPort> mcpToolPortProvider;
    /** 留痕摘要用的补全端口（与 Agent 对话客户端解耦；缺席时摘要降级为截断）。 */
    private final ObjectProvider<LlmClient> llmProvider;
    private final AgentToolDispatcher dispatcher;
    private final LlmCacheService llmCacheService;
    private final MemoryService memoryService;
    private final SchemaManager schemaManager;
    private final ObjectMapper objectMapper;
    private final com.iris.lite.java.application.cache.FreshnessDetector freshnessDetector;
    /** 查询路径规划：接入期预生成的意图→路径配方注入 system prompt；无配方时返回空串。 */
    private final QueryPathAdvisor queryPathAdvisor;
    private final int maxIterations;
    private final String model;
    /** 注入 prompt 的会话历史条数（工作记忆按时间取最近 N 条）；0 = 关闭多轮上下文。 */
    private final int historyEntries;
    /** 单条历史注入上限（字符）：留痕里助手答案可能很长，防 context 膨胀。 */
    private static final int HISTORY_ENTRY_MAX_CHARS = 1200;
    /**
     * 一级 Schema 摘要的实体数阈值：
     * 超过该数走「表级摘要常驻 + get_schema 按需取字段」两级模式，
     * 不超过则保留全字段摘要（小库一步到位更省一跳）。
     */
    private final int schemaDigestFullMax;
    /** 配方自进化草稿池；未装配/未启用时采集关闭。 */
    private final ObjectProvider<RecipeDraftStore> recipeDraftStoreProvider;
    private final boolean recipeDraftsEnabled;
    /** 「顺畅会话」判定阈值：轮数 ≤ 该值的成功会话才值得整理为配方候选。 */
    private final int recipeDraftSmoothMaxRounds;

    public DefaultAgentService(ObjectProvider<AgentChatClient> chatClientProvider,
                               ObjectProvider<McpToolPort> mcpToolPortProvider,
                               ObjectProvider<LlmClient> llmProvider,
                               AgentToolDispatcher dispatcher,
                               LlmCacheService llmCacheService,
                               MemoryService memoryService,
                               SchemaManager schemaManager,
                               ObjectMapper objectMapper,
                               com.iris.lite.java.application.cache.FreshnessDetector freshnessDetector,
                               QueryPathAdvisor queryPathAdvisor,
                               @Value("${iris.agent.max-iterations:6}") int maxIterations,
                               @Value("${iris.llm.model:}") String model,
                               @Value("${iris.agent.history-entries:6}") int historyEntries,
                               @Value("${iris.agent.schema-digest-full-max:12}") int schemaDigestFullMax,
                               ObjectProvider<RecipeDraftStore> recipeDraftStoreProvider,
                               @Value("${iris.agent.recipe-drafts-enabled:true}") boolean recipeDraftsEnabled,
                               @Value("${iris.agent.recipe-draft-smooth-max-rounds:2}") int recipeDraftSmoothMaxRounds) {
        this.chatClientProvider = chatClientProvider;
        this.mcpToolPortProvider = mcpToolPortProvider;
        this.llmProvider = llmProvider;
        this.dispatcher = dispatcher;
        this.llmCacheService = llmCacheService;
        this.memoryService = memoryService;
        this.schemaManager = schemaManager;
        this.objectMapper = objectMapper;
        this.freshnessDetector = freshnessDetector;
        this.queryPathAdvisor = queryPathAdvisor;
        this.maxIterations = Math.max(1, maxIterations);
        this.model = model;
        this.historyEntries = Math.max(0, historyEntries);
        this.schemaDigestFullMax = Math.max(1, schemaDigestFullMax);
        this.recipeDraftStoreProvider = recipeDraftStoreProvider;
        this.recipeDraftsEnabled = recipeDraftsEnabled;
        this.recipeDraftSmoothMaxRounds = Math.max(1, recipeDraftSmoothMaxRounds);
    }

    @Override
    public void run(AgentRunRequest request, AgentEventSink sink) {
        AgentChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            sink.emit("error", Map.of("message",
                    "LLM 客户端未启用（iris.llm.enabled=false），Agent 演示不可用"));
            return;
        }

        // 0. 会话历史：读回工作记忆最近 N 条并注入。多轮 follow-up
        //    如「执行」「继续」对模型是凭空一句话）。历史同时参与缓存 prompt：
        //    同句不同语境绝不能共用缓存条目（防跨轮次串缓存）。
        History history = loadHistory(request);

        // 1. 语义缓存查找（两级命中 + 版本守卫在 lookup 内已校验）
        LlmCacheService.LookupResult cached =
                llmCacheService.lookup(request.namespace(), cachePrompt(request.message(), history), model,
                        null, request.bypassCache());
        if (cached.hit()) {
            log.debug("Agent 缓存命中 ns={} exact={} sim={}",
                    request.namespace(), cached.exact(), cached.similarity());
            sink.emit("cache", Map.of("result", "hit", "exact", cached.exact(),
                    "similarity", cached.similarity()));
            String answer = cached.entry().response();
            for (int i = 0; i < answer.length(); i += REPLAY_CHUNK_CHARS) {
                sink.emit("answer_delta", Map.of("text",
                        answer.substring(i, Math.min(answer.length(), i + REPLAY_CHUNK_CHARS))));
            }
            sink.emit("done", Map.of("usage", Map.of(
                    "rounds", 0, "toolCalls", 0, "cacheResult", "hit")));
            remember(request, answer);
            return;
        }
        sink.emit("cache", Map.of("result",
                cached.reason() == null ? "miss" : cached.reason()));

        // 2. 工具面选择：MCP 模式 = 以真实客户端身份接入 /mcp（每问一个会话，随答随关）；
        //    直连模式 = 内置目录进程内分发。MCP 会话打开失败直接报错终止（返回 null）。
        ToolFace face = openToolFace(request, sink);
        if (face == null) {
            return;
        }
        McpToolPort.McpSession mcpSession = face.session();
        List<AgentChatClient.ToolSpec> catalog = face.catalog();

        // 工具调用循环（Schema 摘要注入 system prompt：省掉开局 get_schema 一轮）
        // 当前运行生效的最大轮数：请求可覆盖启动配置（演示页动态调整），clamp 到 [1,20] 防失控
        int maxRounds = request.maxTurns() == null
                ? maxIterations
                : Math.max(1, Math.min(request.maxTurns(), 20));
        List<AgentChatClient.ChatMessage> messages = new ArrayList<>();
        messages.add(AgentChatClient.ChatMessage.of("system",
                SYSTEM_PROMPT + (mcpSession != null ? MCP_MODE_PROMPT : "") + "\n"
                        + queryPathAdvisor.recipesBlock(request.namespace())
                        + schemaDigest(request.namespace()) + history.promptBlock() + nowAnchor()));
        messages.add(AgentChatClient.ChatMessage.of("user", request.message()));

        AgentToolDispatcher.ToolContext ctx = new AgentToolDispatcher.ToolContext(
                request.namespace(), request.sessionId(), request.agentTags(),
                freshnessDetector.isFreshSensitive(request.message()));
        Set<String> dependencies = new HashSet<>();
        int toolCalls = 0;
        int rounds = 0;
        String finalAnswer = null;
        // 循环护栏：同参重复检测跨轮累计
        RepeatGuard guard = new RepeatGuard(objectMapper);
        // 失败/顺畅信号采集（工具调用序列，name + 参数摘要），供配方自进化评估
        List<String> toolTrace = new ArrayList<>();

        try (mcpSession) {
            for (int round = 1; round <= maxRounds; round++) {
                rounds = round;
                sink.emit("round", Map.of("round", round));
                boolean finalRound = round == maxRounds;
                if (finalRound) {
                    // 轮数用尽给「总结机会」：最后一轮不再提供工具目录，
                    // 强制模型基于已获得的信息给出部分结论——把「报错」变成「有依据的部分回答」
                    messages.add(AgentChatClient.ChatMessage.of("user",
                            "（系统提示：已达最大推理轮数 " + maxRounds + "，本轮不再提供任何工具。"
                                    + "请仅基于以上已获得的工具结果立即给出最终回答；"
                                    + "信息不足以完整回答时，如实说明已确认的部分与还缺少什么。）"));
                }
                RoundOutcome outcome = runRound(client, sink, messages, ctx, dependencies,
                        request.maxTokens(), finalRound ? List.of() : catalog, mcpSession,
                        guard, !finalRound, toolTrace);
                toolCalls += outcome.toolCalls();
                if (outcome.clarify() != null) {
                    // 澄清收尾：不入 LLM 语义缓存（澄清不是答案），但留痕工作记忆
                    // ——用户答复后新一轮的历史块会带上本条，模型才能对上选项指代
                    remember(request, outcome.clarify().display());
                    sink.emit("done", Map.of("usage", Map.of(
                            "rounds", round, "toolCalls", toolCalls, "clarified", true)));
                    return;
                }
                if (outcome.kind() == AgentChatClient.FinishKind.CONTENT) {
                    finalAnswer = outcome.content();
                    break;
                }
                if (finalRound) {
                    // 错误文案区分「0 次工具调用」（预算被思考烧光）与
                    // 「有工具调用但轮数不够」，并指向可操作的调整项
                    sink.emit("error", Map.of("message", toolCalls == 0
                            ? "已达最大轮数 " + maxRounds + "：模型未能发起任何有效工具调用"
                              + "（通常是输出预算被思考耗尽）。可调大本页 ⚙ 输出预算或简化问题后重试"
                            : "已达最大轮数 " + maxRounds + "：已发起 " + toolCalls
                              + " 次工具调用但未能在轮数内完成。可调大本页 ⚙ 最大推理轮数继续，"
                              + "或简化问题/缩小时间范围"));
                    // 失败信号：烧满轮数的会话是配方缺口的第一手证据
                    maybeCollectRecipeDraft(request, toolTrace, rounds, toolCalls, true);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Agent 工具循环异常 ns={} err={}", request.namespace(), safeMsg(e));
            String msg = safeMsg(e);
            // finish_reason=length 不是「链路异常」，是预算耗尽
            if (msg.contains("max_tokens") || msg.contains("截断")) {
                sink.emit("error", Map.of("message",
                        "输出预算耗尽（不是链路故障）：该问题可能超出当前工具能力，或单轮思考过长。"
                                + "建议简化问题、缩小时间范围，或调大本页 ⚙ 输出预算后重试。"
                                + "本轮已完成的工具调用与结果见右侧调用轨迹。"));
                return;
            }
            sink.emit("error", Map.of("message", "工具执行链路异常: " + msg));
            return;
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            sink.emit("error", Map.of("message", "LLM 返回空答案"));
            return;
        }

        // 3. 终答回存语义缓存：声明当前运行触及实体为依赖（守卫数据联动）
        try {
            llmCacheService.store(request.namespace(), cachePrompt(request.message(), history), finalAnswer,
                    model, null, List.copyOf(dependencies));
        } catch (Exception e) {
            log.warn("Agent 终答写入 LLM 缓存失败（不影响回答）ns={} err={}",
                    request.namespace(), e.getMessage());
        }
        // 3.5 澄清回路规范化回写：澄清流终答只入
        // 「澄清答复：…」的缓存 key，用户换问法重问永远不命中——额外回写一条
        // 「被澄清的原始问题为 key、无历史指纹」的规范化条目，让同类问题跨问法/
        // 跨会话可命中。答案过不了自包含门（含指代承接词）则放弃：错放的半截
        // 答案比缓存 miss 危害大，宁可少存。
        maybeStoreCanonicalEntry(request, history, finalAnswer, dependencies);

        // 4. 会话留痕 + 收尾
        remember(request, finalAnswer);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("rounds", rounds);
        usage.put("toolCalls", toolCalls);
        usage.put("cacheResult", "miss");
        usage.put("dependencies", List.copyOf(dependencies));
        sink.emit("done", Map.of("usage", usage));
        // 顺畅样本积累：轮数少、有真实工具调用的成功会话是配方候选
        maybeCollectRecipeDraft(request, toolTrace, rounds, toolCalls, false);
    }

    /**
     * 会话历史：注入 prompt 的文本块（可为空）+ 缓存隔离指纹（空历史 = 空串）
     * + 逐条留痕原文（slim 后），供澄清回路提取被澄清的原始问题。
     */
    private record History(String promptBlock, String fingerprint, List<String> rawEntries) {
        boolean isEmpty() { return fingerprint.isEmpty(); }

        History(String promptBlock, String fingerprint) {
            this(promptBlock, fingerprint, List.of());
        }
    }

    /**
     * 读回 (namespace, sessionId) 工作记忆最近 {@code historyEntries} 条（按写入时间升序），
     * 拼成注入 system prompt 的历史块；指纹 = 条目内容 sha256 前 16 位，用作缓存 prompt 后缀。
     * 读取失败只记日志返回空历史（历史是增强，不是回答的前置——与 remember() 对称）。
     */
    private History loadHistory(AgentRunRequest request) {
        if (historyEntries == 0 || request.sessionId() == null || request.sessionId().isBlank()) {
            return new History("", "");
        }
        try {
            WorkingMemoryDocument doc =
                    memoryService.getWorkingMemory(request.namespace(), request.sessionId());
            if (doc == null || doc.entries() == null || doc.entries().isEmpty()) {
                return new History("", "");
            }
            List<WorkingMemoryEntry> recent = doc.entries().stream()
                    .sorted(Comparator.comparingLong(WorkingMemoryEntry::createdAt))
                    .toList();
            int from = Math.max(0, recent.size() - historyEntries);
            recent = recent.subList(from, recent.size());
            StringBuilder sb = new StringBuilder("\n\n会话历史（本会话较早轮次的问答留痕，按时间先后；"
                    + "当前问题可能是对这些内容的回应，如确认执行你此前的建议——请结合历史理解当前问题）：");
            StringBuilder fp = new StringBuilder();
            List<String> raw = new ArrayList<>();
            for (WorkingMemoryEntry e : recent) {
                // 历史条目瘦身：助手答案只保留摘要段或前 200 字符——历史的作用是
                // 「对上指代」（澄清答复/继续执行），不是提供全文；6 条 × 全文最多 7.2k
                // 字符的常驻注入会挤压推理空间
                String c = slimHistoryEntry(e.content());
                if (c.length() > HISTORY_ENTRY_MAX_CHARS) {
                    c = c.substring(0, HISTORY_ENTRY_MAX_CHARS) + "…（截断）";
                }
                sb.append("\n---\n").append(c);
                fp.append(c).append('\n');
                raw.add(c);
            }
            return new History(sb.toString(), sha256Short(fp.toString()), List.copyOf(raw));
        } catch (Exception e) {
            log.warn("Agent 会话历史读取失败（按无历史继续）ns={} session={} err={}",
                    request.namespace(), request.sessionId(), safeMsg(e));
            return new History("", "");
        }
    }

    /** 缓存 prompt：无历史 = 原句；有历史 = 原句 + 历史指纹后缀（同句不同语境 → 不同 key）。 */
    private String cachePrompt(String message, History history) {
        return history.isEmpty() ? message : message + "\n[hctx:" + history.fingerprint() + "]";
    }

    /**
     * 澄清回路规范化回写。
     *
     * <p><b>为什么需要</b>：澄清流存在三层缓存断层——原问题触发澄清不入缓存、
     * 终答 key 是「澄清答复：…」句（新问法 promptHash 不同）、cachePrompt 带历史指纹
     * （同问法不同轮次也必 miss）。用户换问法重问永远打不到缓存。本方法在澄清答复轮
     * 终答后，额外回写一条「被澄清的原始问题为 key、无历史指纹」的规范化条目，
     * 跨问法/跨会话均可语义命中。
     *
     * <p><b>自包含门</b>：答案含承接/指代词（因此、按你选择…）说明它依赖澄清语境，
     * 单独回放是半截答案——直接放弃回写（宁可少存，不可错放）。
     * 规则 12 的标准默认答案会在正文说明所选标准，过门条目的答案因此可独立阅读。
     *
     * <p><b>依赖声明</b>：沿用澄清答复轮的 touched 实体（答案正是基于当前轮次工具结果），
     * 版本守卫语义不变。全程 best-effort，失败只记日志不影响回答。
     */
    private void maybeStoreCanonicalEntry(AgentRunRequest request, History history,
                                          String finalAnswer, Set<String> dependencies) {
        if (!request.message().startsWith("澄清答复：")) {
            return;
        }
        String original = originalClarifiedQuestion(history);
        if (original == null || original.startsWith("澄清答复：")) {
            return;
        }
        if (!isSelfContained(finalAnswer)) {
            log.debug("澄清回路终答含指代表述，放弃规范化回写 ns={}", request.namespace());
            return;
        }
        try {
            llmCacheService.store(request.namespace(), original, finalAnswer,
                    model, null, List.copyOf(dependencies));
            log.debug("澄清回路规范化条目已回写 ns={} key={}", request.namespace(), original);
        } catch (Exception e) {
            log.warn("规范化条目回写失败（不影响回答）ns={} err={}",
                    request.namespace(), e.getMessage());
        }
    }

    /** 从留痕找最近的「用户: X\n助手: [待澄清] …」条目，返回被澄清的原始问题 X。 */
    private String originalClarifiedQuestion(History history) {
        for (int i = history.rawEntries().size() - 1; i >= 0; i--) {
            String e = history.rawEntries().get(i);
            if (e.startsWith("用户: ") && e.contains("\n助手: [待澄清]")) {
                int end = e.indexOf("\n助手: ");
                String q = e.substring("用户: ".length(), end).trim();
                return q.isBlank() ? null : q;
            }
        }
        return null;
    }

    /**
     * 自包含门（保守启发式）：答案开头 60 字符内出现承接/指代词即视为依赖澄清语境。
     * 只看开头：指代通常出现在句首衔接处；正文后段的「以上共 N 笔」类总结表述不误伤。
     * 漏放（该存没存）只会造成一次缓存 miss，错放（半截答案被回放）会直接答错用户——
     * 阈值向「拒绝」倾斜。
     */
    private static final String[] ANAPHORA_MARKERS = {
            "因此", "所以", "那么", "按你", "按照你", "上述", "刚才", "换用", "改用",
            "之前说", "如前", "改为", "改为按", "你选择", "你确认",
    };

    private boolean isSelfContained(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String head = answer.substring(0, Math.min(60, answer.length()));
        for (String marker : ANAPHORA_MARKERS) {
            if (head.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 配方草稿落池（自进化循环）。
     *
     * <p>采集两类原始素材：①<b>顺畅成功会话</b>（轮数 ≤ 阈值）的工具序列——
     * 审核后可直接固化为 recipe；②<b>失败会话</b>（烧满轮数）——暴露原语/配方缺口。
     * 只存「问题 + 工具序列 + 轮数」原始素材，<b>不让 LLM 自动生成配方</b>
     * （LLM 生成的配方会编造工具名）；人工审核走 REST 审核视图。
     * 全程 best-effort：草稿是旁路，绝不影响回答链路。
     */
    private void maybeCollectRecipeDraft(AgentRunRequest request, List<String> toolTrace,
                                         int rounds, int toolCalls, boolean failed) {
        if (!recipeDraftsEnabled || request.message() == null || request.message().isBlank()) {
            return;
        }
        if (!failed) {
            if (rounds > recipeDraftSmoothMaxRounds || toolCalls <= 0) {
                return;
            }
        }
        RecipeDraftStore store = recipeDraftStoreProvider == null
                ? null : recipeDraftStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            store.append(request.namespace(), new RecipeDraftStore.RecipeDraft(
                    request.message(), String.join("\n", toolTrace),
                    rounds, toolCalls, failed, model, System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("配方草稿采集失败（忽略）ns={} err={}", request.namespace(), e.getMessage(), e);
        }
    }

    /** 工具参数摘要：截断到 200 字符，够审核人还原调用形态即可。 */
    private static String summarizeArgs(String argumentsJson) {
        if (argumentsJson == null) {
            return "";
        }
        String s = argumentsJson.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * 历史条目瘦身：
     * <ul>
     *   <li><b>已摘要条目</b>（含 {@link #FULL_ANSWER_MARKER}）：直接取标记前的
     *       「用户 + 助手摘要」段——摘要由 LLM 按结论/数字/标准约束生成，
     *       不再机械砍前 200 字符（追问「把表格列全」这类指代由
     *       {@code get_working_memory} 工具按条目回捞全文兜底）；</li>
     *   <li><b>未摘要条目</b>（旧格式/短答案/摘要失败的全文条目）：沿用旧规则，
     *       用户原文全保留，助手答案留前 200 字符。</li>
     * </ul>
     */
    private static String slimHistoryEntry(String content) {
        int marker = content.indexOf(FULL_ANSWER_MARKER);
        if (marker > 0) {
            return content.substring(0, marker);
        }
        int idx = content.indexOf("\n助手: ");
        if (idx < 0) {
            return content;
        }
        String user = content.substring(0, idx);
        String answer = content.substring(idx + "\n助手: ".length());
        String slim = answer.length() > 200 ? answer.substring(0, 200) + "…" : answer;
        return user + "\n助手: " + slim;
    }

    /** 历史指纹：SHA-256 前 16 位十六进制（{@code [hctx:...]} 缓存隔离段）。 */
    private String sha256Short(String text) {
        return Digests.sha256Hex(text).substring(0, 16);
    }

    /** 执行一轮：流式转发增量，收集工具调用并执行回填。MCP 会话非空时工具经 tools/call 协议执行。 */
    private RoundOutcome runRound(AgentChatClient client, AgentEventSink sink,
                                  List<AgentChatClient.ChatMessage> messages,
                                  AgentToolDispatcher.ToolContext ctx,
                                  Set<String> dependencies,
                                  Integer maxTokens,
                                  List<AgentChatClient.ToolSpec> catalog,
                                  McpToolPort.McpSession mcpSession,
                                  RepeatGuard guard, boolean allowTools,
                                  List<String> toolTrace) {
        boolean[] contentEmitted = {false};
        List<AgentChatClient.ToolCall>[] finished = new List[1];
        AgentChatClient.FinishKind[] kind = new AgentChatClient.FinishKind[1];
        String[] content = new String[1];
        StringBuilder reasoningBuf = new StringBuilder();

        client.chat(new AgentChatClient.ChatRequest(messages, catalog, maxTokens),
                new AgentChatClient.StreamListener() {
                    @Override
                    public void onReasoning(String delta) {
                        reasoningBuf.append(delta);
                        sink.emit("reasoning", Map.of("text", delta));
                    }

                    @Override
                    public void onContent(String delta) {
                        contentEmitted[0] = true;
                        sink.emit("answer_delta", Map.of("text", delta));
                    }

                    @Override
                    public void onFinished(AgentChatClient.FinishKind finishKind,
                                           String finishContent, List<AgentChatClient.ToolCall> calls) {
                        kind[0] = finishKind;
                        content[0] = finishContent;
                        finished[0] = calls;
                    }
                });

        if (kind[0] == AgentChatClient.FinishKind.CONTENT) {
            return new RoundOutcome(AgentChatClient.FinishKind.CONTENT, content[0], 0);
        }

        // 中间轮的零星叙述会污染答案区，通知前端清空重画
        if (contentEmitted[0]) {
            sink.emit("answer_reset", Map.of());
        }
        List<AgentChatClient.ChatMessage> appended = new ArrayList<>();
        appended.add(new AgentChatClient.ChatMessage("assistant", content[0], finished[0], null));
        // reasoning 复读检测：单轮思考内尾部片段重复出现 → 注入打断提示
        // （reasoning 不进上下文，检测只能救下一轮的预算）
        if (reasoningLooped(reasoningBuf.toString())) {
            appended.add(AgentChatClient.ChatMessage.of("user",
                    "（系统提示：检测到你的思考过程出现大量重复，疑似陷入循环。"
                            + "请立即发起必要的工具调用或直接给出最终结论，不要再复述。）"));
        }
        // 最后一轮不提供工具（总结机会）：模型若仍幻觉出工具调用，不执行直接结束
        if (!allowTools) {
            messages.addAll(appended);
            return new RoundOutcome(AgentChatClient.FinishKind.TOOL_CALLS, null, finished[0].size());
        }
        for (AgentChatClient.ToolCall call : finished[0]) {
            sink.emit("tool_call", Map.of("tool", call.name(), "args", call.argumentsJson()));
            // 澄清拦截：ask_clarification 不是数据工具，不走 dispatcher/MCP 执行——
            // 解析出问题与选项后发 clarify 事件并终止当前轮（MCP 模式同样在本地拦截，
            // 因为该工具不在 MCP 服务端工具面里，是 Agent 演示链路专属）。
            if (CLARIFY_TOOL.equals(call.name())) {
                Clarify clarify = parseClarify(call.argumentsJson());
                sink.emit("clarify", Map.of(
                        "question", clarify.question(),
                        "options", clarify.options()));
                messages.addAll(appended);
                return new RoundOutcome(AgentChatClient.FinishKind.TOOL_CALLS, null,
                        finished[0].size(), clarify);
            }
            long start = System.currentTimeMillis();
            String resultJson;
            Set<String> touched;
            // 同参重复护栏：完全相同的调用或仅翻页的循环 → 不再执行，
            // 直接回写「路径不可行」错误让模型换策略（防穷举死循环）
            if (guard.isRepeat(call.name(), call.argumentsJson())) {
                resultJson = errorJson("该调用与此前重复（同参数或仅翻页），该路径已证明不可行。"
                        + "请改变策略：换过滤字段/收窄范围/改用 aggregate_entity 聚合，"
                        + "或如实说明无法回答");
                touched = Set.of();
            } else if (mcpSession != null && !LOCAL_DISPATCH_TOOLS.contains(call.name())) {
                // MCP 模式：协议路径执行（与外部 Agent 一致）；调用失败归一为 error JSON 供模型自修正。
                // 依赖收集：工具面协议不含实体元数据，此处不声明（该模式下版本守卫按无依赖处理）。
                try {
                    resultJson = mcpSession.callTool(call.name(), call.argumentsJson());
                } catch (Exception e) {
                    log.debug("MCP 工具调用失败 tool={} err={}", call.name(), e.getMessage(), e);
                    resultJson = errorJson("MCP 工具调用失败: " + e.getMessage());
                }
                touched = Set.of();
            } else {
                // 直连模式全量本地分发；MCP 模式下注入的演示链路专属工具
                // （ask_clarification / aggregate_entity）也在本地执行
                AgentToolDispatcher.DispatchResult result =
                        dispatcher.dispatch(call.name(), call.argumentsJson(), ctx);
                resultJson = result.resultJson();
                touched = result.entitiesTouched();
            }
            long latency = System.currentTimeMillis() - start;
            dependencies.addAll(touched);
            // 信号采集：工具序列（name + 参数摘要），配方自进化的原始素材
            toolTrace.add(call.name() + " " + summarizeArgs(call.argumentsJson()));
            sink.emit("tool_result", Map.of(
                    "tool", call.name(),
                    "latencyMs", latency,
                    "result", resultJson));
            appended.add(AgentChatClient.ChatMessage.toolResult(call.id(), resultJson));
        }
        messages.addAll(appended);
        return new RoundOutcome(AgentChatClient.FinishKind.TOOL_CALLS, null, finished[0].size());
    }

    /** ask_clarification 的工具名常量（目录注册与分发拦截共用）。 */
    private static final String CLARIFY_TOOL = "ask_clarification";

    /** aggregate_entity 的工具名常量。 */
    private static final String AGGREGATE_TOOL = "aggregate_entity";

    /** MCP 会话不提供、必须本地分发的工具（演示链路专属注入）。 */
    private static final Set<String> LOCAL_DISPATCH_TOOLS = Set.of(CLARIFY_TOOL, AGGREGATE_TOOL);

    /**
     * reasoning 复读检测：取末尾 64 字符片段，在全文中重复出现 ≥4 次
     * 即判定循环——同一句话重复上百次后烧光输出预算是典型失败形态。
     * 只在长思考（>1KB）时启用，避免误伤正常推理；窗口搜索限制在尾部 32KB，
     * 成本可忽略。
     */
    private static boolean reasoningLooped(String reasoning) {
        if (reasoning == null || reasoning.length() < 1024) {
            return false;
        }
        int n = Math.min(64, reasoning.length());
        String tail = reasoning.substring(reasoning.length() - n);
        int from = Math.max(0, reasoning.length() - 32 * 1024);
        int count = 0;
        int idx = reasoning.indexOf(tail, from);
        while (idx >= 0 && count < 4) {
            count++;
            idx = reasoning.indexOf(tail, idx + 1);
        }
        return count >= 4;
    }

    /**
     * 同参重复护栏（跨轮状态）：完全相同参数的调用出现第 2 次，或
     * 「同工具+同过滤条件仅 page/page_size 递增」的翻页循环出现第 4 次
     * （给合法的 ≤3 页翻页留余量）→ 判定为重复。
     */
    private static final class RepeatGuard {

        private final ObjectMapper objectMapper;
        private final Map<String, Integer> exact = new java.util.HashMap<>();
        private final Map<String, Integer> shaped = new java.util.HashMap<>();

        RepeatGuard(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        boolean isRepeat(String tool, String argsJson) {
            String a = argsJson == null ? "" : argsJson;
            if (exact.merge(tool + ":" + a, 1, Integer::sum) >= 2) {
                return true;
            }
            return shaped.merge(tool + ":" + shapeKey(a), 1, Integer::sum) >= 4;
        }

        /** 去掉分页参数并排序键名，得到「查询形状」指纹：仅翻页的调用形状相同。 */
        private String shapeKey(String argsJson) {
            try {
                Map<?, ?> raw = objectMapper.readValue(argsJson, Map.class);
                Map<String, Object> shapedArgs = new java.util.TreeMap<>();
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (!"page".equals(k) && !"page_size".equals(k) && !"pageSize".equals(k)) {
                        shapedArgs.put(k, e.getValue());
                    }
                }
                return objectMapper.writeValueAsString(shapedArgs);
            } catch (Exception e) {
                return argsJson;
            }
        }
    }

    /**
     * 问答对写入工作记忆（失败只记日志：留痕是增强，不是回答的前置）。
     *
     * <p><b>两段式写入（异步摘要）</b>：
     * <ol>
     *   <li><b>同步存全文</b>——保真优先：摘要无论算多久/是否失败，历史窗口里
     *       都立即有完整可用的条目（快速追问不丢上下文）；</li>
     *   <li><b>异步补摘要</b>——长答案（&gt; {@link #SUMMARY_MIN_CHARS}）由后台
     *       线程调 LLM（输入=答案原文，输出=摘要），完成后按 entryId 回写为
     *       「摘要 + [完整答案] 全文」映射。摘要失败保持全文不动——
     *       注入侧对无标记条目按旧规则截断，天然兜底。</li>
     * </ol>
     * 摘要在 done 事件之后进行，不占回答链路延迟；LLM 缺席时整段跳过。
     */
    private void remember(AgentRunRequest request, String answer) {
        try {
            String full = "用户: " + request.message() + "\n助手: " + answer;
            String entryId = memoryService.saveWorkingMemory(
                    request.namespace(), request.sessionId(), full);
            if (answer.length() <= SUMMARY_MIN_CHARS) {
                return; // 短答案全文即摘要，不付 LLM 成本
            }
            LlmClient llm = llmProvider.getIfAvailable();
            if (llm == null || entryId == null) {
                return;
            }
            String namespace = request.namespace();
            String sessionId = request.sessionId();
            String question = request.message();
            historySummaryExecutor.submit(() -> {
                try {
                    String summary = llm.complete(SUMMARY_SYSTEM_PROMPT, answer).trim();
                    if (summary.isEmpty()) {
                        return; // 模型输出空：保持全文条目，注入侧截断兜底
                    }
                    memoryService.updateWorkingMemoryEntry(namespace, sessionId, entryId,
                            "用户: " + question + "\n助手: " + summary + FULL_ANSWER_MARKER + answer);
                    log.debug("留痕摘要已回写 ns={} session={} entry={} 摘要={}字 原文={}字",
                            namespace, sessionId, entryId, summary.length(), answer.length());
                } catch (Exception e) {
                    // fail-open：摘要失败 = 条目保持全文，注入侧按旧规则截断
                    log.debug("留痕摘要失败（保持全文）ns={} err={}", namespace, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Agent 会话留痕失败（不影响回答）ns={} session={} err={}",
                    request.namespace(), request.sessionId(), e.getMessage());
        }
    }

    /**
     * 当前 namespace 的实体 Schema 紧凑摘要，注入 system prompt——
     * 模型开局即知全部字段/主键/租户字段/关系，省掉一轮 get_schema。
     * get_schema 工具保留作兜底（摘要不足以回答时仍可调用）。
     *
     * <p><b>两级注入</b>：实体数超过 {@link #schemaDigestFullMax} 时
     * 降为「一级表级摘要」——只注入表名 + 用途描述（EntitySchema.description）+ 主键/
     * 租户字段/外键关联，字段级详情由 get_schema(entity) 按需现取。收益：ecomm 135 表
     * 场景常驻上下文从 ≈14k 字符降到 ≈3-4k；代价：确定目标实体后多一跳 get_schema。
     * 实体数少的 namespace 保留全量摘要（小库一步到位，省一跳更划算）。
     */
    private String schemaDigest(String namespace) {
        List<EntitySchema> entities = schemaManager.list().stream()
                .filter(s -> s.namespace().equals(namespace))
                .toList();
        if (entities.isEmpty()) {
            return "当前 namespace " + namespace + " 暂无已注册实体 Schema。";
        }
        StringBuilder sb = new StringBuilder("实体 Schema 摘要（namespace ").append(namespace);
        if (entities.size() > schemaDigestFullMax) {
            sb.append("，两级摘要模式：以下仅列表级用途与关联，构造查询前必须先调 ")
                    .append("get_schema(entity) 获取字段详情）:");
            for (EntitySchema s : entities) {
                sb.append("\n- ").append(s.entity());
                if (s.description() != null) {
                    sb.append("：").append(s.description());
                }
                sb.append("（PK ").append(String.join(",", s.primaryKeys()));
                if (s.tenantField() != null) {
                    sb.append("；多租户字段 ").append(s.tenantField()).append("，查询必带");
                }
                appendCompactRelations(sb, s);
                sb.append("）");
            }
            return sb.toString();
        }
        sb.append("）：");
        for (EntitySchema s : entities) {
            sb.append("\n- ").append(s.entity()).append("（PK ")
                    .append(String.join(",", s.primaryKeys()));
            if (s.tenantField() != null) {
                sb.append("；多租户字段 ").append(s.tenantField()).append("，查询必带");
            }
            sb.append("）: ").append(s.fields().stream()
                    .map(f -> f.name() + ":" + f.type()
                            + (f.relatedEntity() == null ? "" : "→" + f.relatedEntity()))
                    .collect(Collectors.joining(" | ")));
        }
        return sb.toString();
    }

    /** 一级摘要的关系段：外键关联列表（fk→目标实体），无外键时不出现在摘要里。 */
    private void appendCompactRelations(StringBuilder sb, EntitySchema s) {
        List<FieldSchema> fks = s.foreignKeyFields();
        if (!fks.isEmpty()) {
            sb.append("；关联 ").append(fks.stream()
                    .map(f -> f.name() + "→" + f.relatedEntity())
                    .collect(Collectors.joining(", ")));
        }
    }

    /**
     * 数据时间基准时区：UTC 边界，不是 +08:00。
     *
     * <p><b>结论：基准恒为「DATETIME 字面量按 UTC 解释」，且无法通过配置改变。</b>
     * 依据两处：①Debezium 官方文档《Temporal values without time zones》写明
     * DATETIME「converted into epoch milliseconds … <b>by using UTC</b>」；②
     * {@code connectionTimeZone} 只作用于 TIMESTAMP 列（转 ZonedTimestamp），对 DATETIME
     * 无效——在 {@code deploy/debezium/application.properties} 配
     * {@code connectionTimeZone=Asia/Shanghai} 后重启重新快照，载荷里 DATETIME 毫秒值
     * 一字未变。故「改 Debezium 配置根治时区」在原理上不成立。
     *
     * <p>所以某日 D 的时间窗 = [D 00:00:00.000 的毫秒, D 23:59:59.999 的毫秒]，毫秒按
     * <b>UTC 边界</b>取。本常量即该基准，<b>不得</b>改成 Asia/Shanghai——改了会让
     * 「8/30 有多少笔订单」从 1217 变成 790（差值正是 8 小时错位）。
     */
    private static final ZoneId DATA_ZONE = ZoneOffset.UTC;

    /**
     * 组装「当前系统时间」锚点，注入 system prompt 末尾。
     *
     * <p><b>为什么必须注入</b>：LLM 是无 I/O 的纯函数，读不到系统时钟。「今天/昨天」
     * 属于指示词（indexical），所指完全依赖说话时刻，词本身不含信息。不注入时，
     * 模型只能从数据反推「今天」，各模型推法不同 → 同一问题答案分裂：同一问
     * 「今天有人下单吗」，两个模型会分别给出 1267 与 2484（后者把 8/30 与 8/31 累加）。
     *
     * <p><b>为什么放 system prompt 末尾</b>：静态前缀（SYSTEM_PROMPT + MCP 提示 +
     * Schema 摘要）必须字节稳定，否则随时间变化的锚点会打掉 provider 侧的前缀缓存
     * （KV cache）。放末尾 = 静态前缀完整可缓存，代价只落在本就动态的尾部（历史块、
     * 本锚点）。粒度到分钟而非秒，进一步降低缓存失效频率。
     *
     * <p><b>为什么要额外给「换算基准 + 边界数值」</b>：只给人类可读的「当前系统时间」，
     * 模型就得自己决定按哪个时区把日期换算成 epoch；它与数据侧基准一旦不一致，
     * 就会系统性地答错。典型坑有二：
     * <ul>
     *   <li>锚点写 Asia/Shanghai 而数据是 UTC 字面基准时，模型在「锚点说 CST」「数据像
     *       UTC」之间摇摆，同一模型会混用两套标准：问「今天有人下单吗」按 UTC 日界恰好
     *       对齐数据（对），问「2026年8月30日多少笔订单」按 CST 日界算，窗口落在字面
     *       8/29 16:00~8/30 15:59，只查到 790 笔，而正确值 1217 笔，误差 -35%。</li>
     *   <li>若把锚点日界改成 +08:00，会与数据基准整体错位 8 小时——数据基准由
     *       Debezium 对 DATETIME 的写死 UTC 换算决定，锚点日界必须与数据基准严格一致。</li>
     * </ul>
     * 所以标准必须显式写死、并把「今天/昨天」的边界毫秒直接给出，让模型从「推理换算」
     * 退化为「照抄数值」。<b>锚点标准与数据基准是强耦合的一对</b>，任何一侧单独改动
     * 都会造成 8 小时整体偏移；而数据基准由 Debezium 单方面锁死在 UTC，因此锚点也只能是 UTC。
     */
    private String nowAnchor() {
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today = now.toLocalDate();
        // 近 32 天逐日 UTC 边界由应用预计算。
        // 为什么必须预计算：LLM 自行做日期→epoch 毫秒换算极易差一天——把
        // 「8/30 的 1217 笔」按 8/29 的窗口 [1787961600000,1788047999999] 查询
        // 会得到 6 笔
        // （8/29 真值恰好 6 笔，窗口错但查询对，极具迷惑性）。表内日期一律查表。
        StringBuilder table = new StringBuilder();
        for (int i = 31; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long min = d.atStartOfDay(DATA_ZONE).toInstant().toEpochMilli();
            table.append("\n").append(d).append("=[").append(min).append(", ").append(min + 86_400_000L - 1).append("]");
        }
        return "\n\n当前系统时间：" + now.format(NOW_FORMAT) + "（时区 " + now.getZone() + "）。"
                + "\n数据时间字段换算基准（*_at / *_time，毫秒 LONG）：毫秒值 = DATETIME 字面量按 UTC"
                + "解释（Debezium 对 DATETIME 的换算写死为 UTC，无配置项可改）。"
                + "因此某日 D 的时间窗 = [D 00:00:00.000 的毫秒, D 23:59:59.999 的毫秒]，"
                + "按 UTC 边界取；严禁改用 +08:00 或其它时区换算日界"
                + "（错用时区会使日界偏移 8 小时，整日统计标准完全错位）。"
                + "\n日期→毫秒边界表（应用已按 UTC 预计算，日期在表内时必须直接引用，"
                + "严禁自行换算——自行换算极易偏移一天：窗口错了但查询语法是对的，"
                + "返回一个小数字极具迷惑性）："
                + table
                + "\n日期不在表内时才自行换算，且必须自检：相邻两天的 00:00 毫秒差恰为 86400000。";
    }

    /** MCP 模式附加提示：工具面是 MCP 目录，数据查询走 search→call 两跳发现。 */
    private static final String MCP_MODE_PROMPT = """

            本次会话接入方式为 MCP 协议（与外部 Agent 一致）。数据查询工具不直接暴露，按两跳使用：
            1. search_entity_tools 检索：query 直接用实体全名（见下方 Schema 摘要）或字段英文名，空格分隔；一次没命中就换实体全名再搜，不要用自然语言长句。
            2. call_entity_tool 执行：name=搜到的工具名，arguments=该工具 parameters 所列参数。只用 namespace 等于目标命名空间的工具；查列表优先选 query_{实体}（支持 filters/range_filters/text_filters 等值、范围、全文过滤）。
            记忆/Schema/运维类静态工具可直接调用，无需检索。""";

    @Override
    public PromptDigest promptDigest() {
        return new PromptDigest(sha256Prefix(SYSTEM_PROMPT + MCP_MODE_PROMPT),
                SYSTEM_PROMPT.length() + MCP_MODE_PROMPT.length());
    }

    /** SHA-256 前 16 位十六进制（system prompt 指纹，/agent/info 展示用）。 */
    private static String sha256Prefix(String text) {
        return Digests.sha256Hex(text).substring(0, 16);
    }

    /** 一次运行的工具面：目录 + 可选 MCP 会话（null = 直连模式）。 */
    private record ToolFace(List<AgentChatClient.ToolSpec> catalog, McpToolPort.McpSession session) {
    }

    /**
     * 打开当前运行的工具面。MCP 模式建立会话并拉取 tools/list；失败发 error 事件并返回 null。
     */
    private ToolFace openToolFace(AgentRunRequest request, AgentEventSink sink) {
        if (!request.mcpTools()) {
            return new ToolFace(AgentToolCatalog.all(), null);
        }
        McpToolPort port = mcpToolPortProvider.getIfAvailable();
        if (port == null) {
            sink.emit("error", Map.of("message", "MCP 客户端不可用（未启用 McpToolPort 实现）"));
            return null;
        }
        McpToolPort.McpSession session = null;
        try {
            session = port.open(request.apiKey());
            // 澄清与聚合是演示链路专属增强，不在 MCP 服务端工具面——注入到给
            // LLM 的目录，执行由 runRound 本地分发（LOCAL_DISPATCH_TOOLS，不经过 tools/call）
            List<AgentChatClient.ToolSpec> catalog = new ArrayList<>(toToolSpecs(session.listTools()));
            catalog.add(AgentToolCatalog.askClarification());
            catalog.add(AgentToolCatalog.aggregateEntity());
            return new ToolFace(catalog, session);
        } catch (Exception e) {
            // listTools 等网络往返失败时 session 已 open：必须关掉，否则连接泄漏
            //（此处的 session 不在 run() 的 try-with-resources 管辖内）
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // 关闭失败无能为力，原始异常更重要
                }
            }
            log.warn("MCP 会话建立失败 ns={} err={}", request.namespace(), safeMsg(e));
            sink.emit("error", Map.of("message", "MCP 会话建立失败: " + safeMsg(e)));
            return null;
        }
    }

    /** MCP tools/list → LLM function calling ToolSpec（JSON 结构同构，直接转换）。 */
    private List<AgentChatClient.ToolSpec> toToolSpecs(McpToolPort.ListOfTools tools) {
        return tools.tools().stream()
                .map(t -> new AgentChatClient.ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", true, "message", message));
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"工具执行失败\"}";
        }
    }

    private String safeMsg(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    /** 单轮结果：终态 + 正文 + 当前轮次工具调用数 + 澄清载荷（仅 ask_clarification 轮非空）。 */
    private record RoundOutcome(AgentChatClient.FinishKind kind, String content, int toolCalls,
                                Clarify clarify) {

        RoundOutcome(AgentChatClient.FinishKind kind, String content, int toolCalls) {
            this(kind, content, toolCalls, null);
        }
    }

    /** 一次澄清提问：来自 ask_clarification 工具参数，经 clarify 事件下发前端。 */
    private record Clarify(String question, List<String> options) {

        /** 留痕/展示文本：编号选项 + 固定的「其他」项说明（自由填写）。 */
        String display() {
            StringBuilder sb = new StringBuilder("[待澄清] ").append(question);
            for (int i = 0; i < options.size(); i++) {
                sb.append("\n").append(i + 1).append(") ").append(options.get(i));
            }
            sb.append("\n其他) 用户可自行补充描述");
            return sb.toString();
        }
    }

    /** 解析澄清工具参数；question 缺失或 options 非法时降级为「原文展示」，不阻断收尾。 */
    private Clarify parseClarify(String argumentsJson) {
        String question;
        List<String> options = new ArrayList<>();
        try {
            Map<?, ?> args = objectMapper.readValue(argumentsJson, Map.class);
            Object q = args.get("question");
            question = q instanceof String s && !s.isBlank() ? s : argumentsJson;
            if (args.get("options") instanceof List<?> list) {
                for (Object o : list) {
                    String s = String.valueOf(o);
                    if (!s.isBlank()) {
                        options.add(s);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("澄清参数解析失败（按原文降级）err={}", e.getMessage(), e);
            question = argumentsJson;
        }
        return new Clarify(question, options);
    }
}
