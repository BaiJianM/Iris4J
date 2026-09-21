import { useEffect, useRef, useState } from "react"
import type { ReactNode } from "react"
import { Badge, Button, Card, CardHead, cx, Input, JsonTree, Modal, toast } from "../lib/ui"
import { MarkdownAnswer } from "../lib/Markdown"
import { agentChatStream, api } from "../lib/api"
import type { PageProps } from "."

/**
 * Agent 演示页：左栏问答 + 状态栏（执行中/已完成）。右侧流程未全部
 * 结束前左侧只显示动画；done 拿到完整结果后以打字机效果流式上屏。
 * 右栏调用轨迹按"段"组织（思考段/工具执行段），进行中的段自动展开并跟随
 * 滚动到最新内容，段结束后自动收起；进行中标题带渐变扫过动画。
 * 事件协议与后端 AgentController 一致。
 */

type TraceStep =
  | { kind: "round"; id: number; round: number }
  | { kind: "reasoning"; id: number; round: number; text: string; done: boolean }
  | {
      kind: "tool"
      id: number
      round: number
      tool: string
      args: string
      result?: string
      latencyMs?: number
      done: boolean
    }
  | { kind: "cache"; id: number; result: string; exact?: boolean; similarity?: number }
  | { kind: "error"; id: number; message: string }

type Usage = {
  rounds: number
  toolCalls: number
  cacheResult: string
  dependencies?: string[]
  /** 会话以澄清提问收尾（clarify 事件已渲染选项卡片），等待用户点选/填写后续跑。 */
  clarified?: boolean
}

/** 澄清卡片载荷：来自后端 clarify 事件（ask_clarification 工具解析结果）。 */
type Clarify = { question: string; options: string[] }

/** 消息级缓存命中信息：来自后端 cache 事件（done 后挂到 assistant 消息上展示）。 */
type CacheInfo = { result: string; exact?: boolean; similarity?: number }

type ChatMessage = {
  role: "user" | "assistant"
  content: string
  interrupted?: boolean
  usage?: Usage
  cache?: CacheInfo
}

/** 预设演示场景：覆盖四服务能力（查询导航/Schema/记忆/缓存/围栏/运维）。 */
const SCENARIOS: { label: string; prompt: string; hint: string }[] = [
  { label: "关联查询", prompt: "查最近 3 笔支付记录，并告诉我每笔支付对应的订单和支付渠道", hint: "query_entity + 关系导航" },
  { label: "Schema 感知", prompt: "现在能查询哪些实体和字段？哪些字段之间有外键关系？", hint: "get_schema" },
  { label: "记住偏好", prompt: "请记住：我看数据偏好按渠道汇总展示", hint: "长期记忆写入" },
  { label: "回忆偏好", prompt: "我之前说过我看数据的偏好是什么？", hint: "长期记忆检索" },
  { label: "运维问答", prompt: "Redis 现在内存占用多少？CDC 管道有积压吗？", hint: "redis_status + cdc_sources" },
  { label: "数据洞察", prompt: "统计一下各支付渠道分别有多少笔支付，哪个渠道最多？", hint: "索引过滤 + 汇总" },
]

const CACHE_LABEL: Record<string, { tone: "hit" | "miss" | "warn" | "neutral"; text: string }> = {
  hit: { tone: "hit", text: "缓存 HIT" },
  "hit-exact": { tone: "hit", text: "缓存 HIT（精确）" },
  "hit-semantic": { tone: "hit", text: "缓存 HIT（语义）" },
  miss: { tone: "miss", text: "缓存 MISS" },
  "no-match": { tone: "miss", text: "缓存 MISS" },
  "no-embedder": { tone: "neutral", text: "缓存不可用（无 Embedder）" },
  disabled: { tone: "neutral", text: "缓存未启用" },
  bypass: { tone: "neutral", text: "实时模式（跳过缓存）" },
  stale: { tone: "warn", text: "围栏拦截 fence-miss" },
}

/** 将指定类型的所有未完成段标记为结束（新段开始/整体结束时收尾）。 */
function finishKind(steps: TraceStep[], kind: "reasoning" | "tool"): TraceStep[] {
  return steps.map((s) => (s.kind === kind && !s.done ? { ...s, done: true } : s))
}

const finishReasoning = (t: TraceStep[]) => finishKind(t, "reasoning")
const finishAll = (t: TraceStep[]) => finishKind(finishKind(t, "reasoning"), "tool")

/** 最大推理轮数：浏览器本地持久化，随每次 chat 请求带给后端（服务端 clamp 到 [1,20]）。 */
const MAX_TURNS_KEY = "iris.agent.max-turns"
const MAX_TURNS_DEFAULT = 6
export function loadMaxTurns(): number {
  let raw: string | null = null
  try {
    raw = window.localStorage.getItem(MAX_TURNS_KEY)
  } catch {
    return MAX_TURNS_DEFAULT // localStorage 被禁用（隐私模式/权限策略）时回落默认值
  }
  const n = raw == null ? NaN : Number(raw)
  return Number.isInteger(n) ? Math.max(1, Math.min(n, 20)) : MAX_TURNS_DEFAULT
}

/** 单轮输出预算 max_tokens：本地持久化随请求下发（服务端 clamp [4096,32768]，推理模型红线 ≥4096）。
 *  默认 8192：推理模型 reasoning+正文共享预算，4096 复杂问题不够。 */
const MAX_TOKENS_KEY = "iris.agent.max-tokens"
const MAX_TOKENS_DEFAULT = 8192
export function loadMaxTokens(): number {
  let raw: string | null = null
  try {
    raw = window.localStorage.getItem(MAX_TOKENS_KEY)
  } catch {
    return MAX_TOKENS_DEFAULT // localStorage 被禁用时回落默认值
  }
  const n = raw == null ? NaN : Number(raw)
  return Number.isInteger(n) ? Math.max(4096, Math.min(n, 32768)) : MAX_TOKENS_DEFAULT
}

/** 工具接入模式："1" = 以真实 MCP 客户端接入 /mcp（按需发现两跳）；缺省 = 内置目录直连。 */
const MCP_MODE_KEY = "iris.agent.mcp-tools"

/**
 * 运行配置入口：挂在页标题「Agent 演示」行右侧（PAGES.actions），
 * 与对话区解耦——保存只写 localStorage，AgentDemo 每次发请求时读取最新值。
 */
export function AgentConfigButton() {
  const [open, setOpen] = useState(false)
  const [turnsDraft, setTurnsDraft] = useState<string>(String(loadMaxTurns()))
  const [tokensDraft, setTokensDraft] = useState<string>(String(loadMaxTokens()))
  const save = () => {
    const turns = Number(turnsDraft)
    const turnsVal = Number.isInteger(turns) ? Math.max(1, Math.min(turns, 20)) : MAX_TURNS_DEFAULT
    const tokens = Number(tokensDraft)
    const tokensVal = Number.isInteger(tokens) ? Math.max(4096, Math.min(tokens, 32768)) : MAX_TOKENS_DEFAULT
    setTurnsDraft(String(turnsVal))
    setTokensDraft(String(tokensVal))
    window.localStorage.setItem(MAX_TURNS_KEY, String(turnsVal))
    window.localStorage.setItem(MAX_TOKENS_KEY, String(tokensVal))
    setOpen(false)
    toast(`已生效：最大推理轮数 ${turnsVal} · 输出预算 ${tokensVal}`)
  }
  return (
    <>
      <Button
        variant="primary"
        onClick={() => {
          setTurnsDraft(String(loadMaxTurns()))
          setTokensDraft(String(loadMaxTokens()))
          setOpen(true)
        }}
      >
        ⚙ 配置
      </Button>
      <Modal
        open={open}
        onClose={() => setOpen(false)}
        title="Agent 运行配置"
        footer={
          <>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button variant="primary" onClick={save}>
              保存
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1">
            <span className="text-[13px] text-fg-muted">最大推理轮数（1–20）</span>
            <Input
              type="number"
              min={1}
              max={20}
              value={turnsDraft}
              onChange={(e) => setTurnsDraft(e.target.value)}
              className="w-32"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-[13px] text-fg-muted">单轮输出预算 max_tokens（4096–32768）</span>
            <Input
              type="number"
              min={4096}
              max={32768}
              step={1024}
              value={tokensDraft}
              onChange={(e) => setTokensDraft(e.target.value)}
              className="w-32"
            />
          </label>
          <p className="text-[13px] text-fg-subtle">
            Agent 每轮可发起工具调用；超过该轮数仍在请求工具则终止循环并提示。输出预算含推理与正文两部分，被
            max_tokens 截断（finish_reason=length）时调大即可。推理模型下限 4096。
          </p>
        </div>
      </Modal>
    </>
  )
}

/**
 * /api/v1/agent/info 响应：服务端当前生效的 LLM/Agent 配置。
 * 用于演示页头部展示「现在用的是哪个模型、什么温度」——真读服务端配置，
 * 不从前端硬编码，改完 application.yml 重启即可在这里看出是否生效。
 */
type LlmInfo = {
  enabled: boolean
  model: string
  temperature: number
  maxTokens: number
  provider: string
  maxIterations: number
  agentTimeoutSeconds: number
}

export default function AgentDemo({ ns }: PageProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState("")
  const [sessionId, setSessionId] = useState("agent-demo")
  const [running, setRunning] = useState(false)
  const [trace, setTrace] = useState<TraceStep[]>([])
  const [currentRound, setCurrentRound] = useState(0)
  const [tools, setTools] = useState<{ name: string; description: string }[] | null>(null)
  const [toolsOpen, setToolsOpen] = useState(false)
  const [pickedTool, setPickedTool] = useState<string | null>(null)
  const pickedToolInfo = tools?.find((t) => t.name === pickedTool) ?? null
  /** 服务端生效的模型/温度等（头部展示）；取不到时不渲染，不猜默认值。 */
  const [llmInfo, setLlmInfo] = useState<LlmInfo | null>(null)
  /** 工具接入模式：direct=内置目录直连；mcp=以真实 MCP 客户端接入 /mcp（按需发现两跳）。 */
  const [mcpMode, setMcpMode] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem(MCP_MODE_KEY) === "1"
    } catch {
      return false
    }
  })
  const toggleMcpMode = (next: boolean) => {
    setMcpMode(next)
    try {
      window.localStorage.setItem(MCP_MODE_KEY, next ? "1" : "0")
    } catch {
      /* ignore */
    }
  }
  const [ranOnce, setRanOnce] = useState(false)
  /** 进行中的澄清提问（非空 = 展示选项卡片等待答复，答复后续跑）。 */
  const [clarify, setClarify] = useState<Clarify | null>(null)
  const [clarifyOther, setClarifyOther] = useState("")
  const chatEndRef = useRef<HTMLDivElement>(null)
  const traceScrollRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  // 答案缓冲：右侧流程未全部结束前左侧不上屏（保持动画原样），中间轮叙述
  // 被 answer_reset 清掉；done 拿到完整结果后以打字机效果流式上屏
  const answerBufRef = useRef("")
  // 当前运行的缓存事件（result/exact/similarity），done 时挂到 assistant 消息上展示徽章
  const cacheInfoRef = useRef<CacheInfo | null>(null)
  const typeTimerRef = useRef<number | null>(null)
  const stopTypewriter = () => {
    if (typeTimerRef.current != null) {
      window.clearInterval(typeTimerRef.current)
      typeTimerRef.current = null
    }
  }
  useEffect(() => stopTypewriter, [])
  // 组件卸载（切页）时中止进行中的 SSE 流：否则旧流继续占连接并向已卸载组件 setState
  useEffect(
    () => () => {
      abortRef.current?.abort()
    },
    [],
  )
  const idRef = useRef(0)
  const nextId = () => ++idRef.current

  // answer_delta 节流上屏：每个 delta 直接触发 setMessages 会让 react-markdown 对
  // 累积全文反复重解析（O(n²) 热点，长回答明显卡顿）——先入缓冲，60ms 批量 flush 一次
  const flushTimerRef = useRef<number | null>(null)
  const flushAnswerBuf = () => {
    if (flushTimerRef.current != null) {
      window.clearTimeout(flushTimerRef.current)
      flushTimerRef.current = null
    }
    const delta = answerBufRef.current
    if (!delta) return
    answerBufRef.current = ""
    setMessages((m) => {
      const copy = [...m]
      const lastMsg = copy[copy.length - 1]
      if (lastMsg?.role === "assistant") {
        copy[copy.length - 1] = { ...lastMsg, content: lastMsg.content + delta }
      }
      return copy
    })
  }
  const scheduleFlush = () => {
    if (flushTimerRef.current == null) {
      flushTimerRef.current = window.setTimeout(flushAnswerBuf, 60)
    }
  }

  // 右侧流程中是否还有未完成的段（决定左侧状态栏文案）
  const anyRunning = trace.some((s) => (s.kind === "reasoning" || s.kind === "tool") && !s.done)

  // 服务端生效的模型/温度：只取一次（配置变动需重启后端，页面无需轮询）
  useEffect(() => {
    api.get<LlmInfo>("/api/v1/agent/info")
      .then(setLlmInfo)
      .catch(() => setLlmInfo(null))
  }, [])

  useEffect(() => {
    const path = mcpMode ? "/api/v1/agent/tools?mcp=true" : "/api/v1/agent/tools"
    api.get<{ tools: { name: string; description: string }[] }>(path)
      .then((r) => setTools(r.tools))
      .catch(() => setTools(null))
    setPickedTool(null)
  }, [mcpMode])

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }, [messages])
  // 有段在进行中时，跟随最新输出滚动到底部；全部结束后不再强制滚动
  useEffect(() => {
    const el = traceScrollRef.current
    if (el && anyRunning) el.scrollTop = el.scrollHeight
  }, [trace, anyRunning])

  const send = async (text: string) => {
    const message = text.trim()
    if (!message || running) return
    setInput("")
    setRunning(true)
    setRanOnce(true)
    stopTypewriter()
    if (flushTimerRef.current != null) {
      window.clearTimeout(flushTimerRef.current)
      flushTimerRef.current = null
    }
    setTrace([])
    setCurrentRound(0)
    setClarify(null)
    setClarifyOther("")
    answerBufRef.current = ""
    cacheInfoRef.current = null
    setMessages((m) => [...m, { role: "user", content: message }, { role: "assistant", content: "" }])
    const controller = new AbortController()
    abortRef.current = controller
    let round = 0
    try {
      await agentChatStream(
        { sessionId, message, namespace: ns, maxTurns: loadMaxTurns(), maxTokens: loadMaxTokens(), mcpTools: mcpMode },
        (e) => {
          const d = e.data as Record<string, unknown>
          switch (e.type) {
            case "round": {
              round = Number(d.round)
              setCurrentRound(round)
              const id = nextId()
              // 新一轮开始：上一段思考必然已结束
              setTrace((t) => [...finishReasoning(t), { kind: "round", id, round }])
              break
            }
            case "reasoning": {
              const textDelta = String(d.text ?? "")
              setTrace((t) => {
                const last = t[t.length - 1]
                if (last && last.kind === "reasoning" && last.round === round && !last.done) {
                  const copy = [...t]
                  copy[copy.length - 1] = { ...last, text: last.text + textDelta }
                  return copy
                }
                return [...t, { kind: "reasoning", id: nextId(), round, text: textDelta, done: false }]
              })
              break
            }
            case "tool_call": {
              const id = nextId()
              setTrace((t) => [
                ...finishReasoning(t),
                { kind: "tool", id, round, tool: String(d.tool), args: String(d.args ?? "{}"), done: false },
              ])
              break
            }
            case "tool_result": {
              setTrace((t) => {
                const copy = [...t]
                for (let i = copy.length - 1; i >= 0; i--) {
                  const step = copy[i]
                  if (step.kind === "tool" && step.tool === String(d.tool) && step.result === undefined) {
                    copy[i] = {
                      ...step,
                      result: String(d.result ?? ""),
                      latencyMs: Number(d.latencyMs ?? 0),
                      done: true,
                    }
                    break
                  }
                }
                return copy
              })
              break
            }
            case "cache": {
              const info: CacheInfo = {
                result: String(d.result ?? "miss"),
                exact: Boolean(d.exact),
                similarity: typeof d.similarity === "number" ? d.similarity : undefined,
              }
              cacheInfoRef.current = info
              const id = nextId()
              setTrace((t) => [
                ...finishReasoning(t),
                {
                  kind: "cache",
                  id,
                  result: info.result,
                  exact: info.exact,
                  similarity: info.similarity,
                },
              ])
              break
            }
            case "answer_delta": {
              // 实时上屏（节流）：delta 先入缓冲、60ms 批量 flush，避免逐 delta
              // 全量重渲染。若后续被判定为中间轮污染，由 answer_reset 清除。
              const delta = String(d.text ?? "")
              answerBufRef.current += delta
              scheduleFlush()
              break;
            }
            case "answer_reset": {
              // 中间轮叙述被后端判定为污染答案区，同步清空左侧已上屏内容
              if (flushTimerRef.current != null) {
                window.clearTimeout(flushTimerRef.current)
                flushTimerRef.current = null
              }
              answerBufRef.current = ""
              setMessages((m) => {
                const copy = [...m]
                const lastMsg = copy[copy.length - 1]
                if (lastMsg?.role === "assistant") {
                  copy[copy.length - 1] = { ...lastMsg, content: "" }
                }
                return copy
              })
              break;
            }
            case "clarify": {
              // 澄清：右侧轨迹收口（悬挂的工具段标 done），澄清问题写进左侧气泡，
              // 选项卡片随 clarify 状态渲染在输入区上方；用户点选/填写后以
              // 「澄清答复：」前缀续跑（同 sessionId，上下文由工作记忆 + 规则 12 接续）。
              setTrace((t) => finishAll(t))
              const question = String(d.question ?? "")
              const options = Array.isArray(d.options) ? d.options.map(String) : []
              setClarify({ question, options })
              setMessages((m) => {
                const copy = [...m]
                const lastMsg = copy[copy.length - 1]
                if (lastMsg?.role === "assistant" && !lastMsg.content) {
                  copy[copy.length - 1] = { ...lastMsg, content: question }
                }
                return copy
              })
              break
            }
            case "done": {
              // 全部流程结束：先把未 flush 的答案尾巴上屏，再补 usage/缓存徽章
              flushAnswerBuf()
              const usage = (d.usage ?? {}) as unknown as Usage
              // 缓存命中信息挂到消息卡片（仅命中时展示徽章；miss 只在右侧轨迹体现）；
              // 后端 result 统一为 "hit"，按 exact/similarity 细化为精确/语义命中文案
              const rawCache = cacheInfoRef.current
              cacheInfoRef.current = null
              const cacheHit: CacheInfo | null =
                rawCache && rawCache.result.startsWith("hit")
                  ? {
                      ...rawCache,
                      result:
                        rawCache.result === "hit"
                          ? rawCache.exact
                            ? "hit-exact"
                            : rawCache.similarity != null && rawCache.similarity < 1
                              ? "hit-semantic"
                              : "hit"
                          : rawCache.result,
                    }
                  : null
              setMessages((m) => {
                const copy = [...m]
                const lastMsg = copy[copy.length - 1]
                if (lastMsg?.role === "assistant") {
                  copy[copy.length - 1] = {
                    ...lastMsg,
                    usage,
                    ...(cacheHit ? { cache: cacheHit } : {}),
                  }
                }
                return copy
              })
              break
            }
            case "error": {
              const msg = String(d.message ?? "未知错误")
              setTrace((t) => [...finishAll(t), { kind: "error", id: nextId(), message: msg }])
              // 先把未 flush 的部分答案上屏（不清空——已 flush 的内容在消息里）
              flushAnswerBuf()
              setMessages((m) => {
                const copy = [...m]
                const lastMsg = copy[copy.length - 1]
                if (lastMsg?.role === "assistant") {
                  copy[copy.length - 1] = {
                    ...lastMsg,
                    content: lastMsg.content || msg,
                    interrupted: true,
                  }
                }
                return copy
              })
              toast(msg, "error")
              break;
            }
          }
        },
        controller.signal,
      )
    } catch (err) {
      const aborted = err instanceof DOMException && err.name === "AbortError"
      const detail = aborted ? "已停止" : err instanceof Error ? err.message : "连接中断"
      setTrace(finishAll)
      // 先把未 flush 的部分答案上屏（不清空——已 flush 的内容在消息里）
      flushAnswerBuf()
      setMessages((m) => {
        const copy = [...m]
        const lastMsg = copy[copy.length - 1]
        if (lastMsg?.role === "assistant") {
          copy[copy.length - 1] = {
            ...lastMsg,
            interrupted: true,
            content: lastMsg.content || detail,
          }
        }
        return copy
      })
      if (!aborted) toast(detail, "error")
    } finally {
      // 兜底：流结束时把所有悬挂中的段全部收尾（含工具未返回 result 的异常路径）
      flushAnswerBuf()
      setTrace(finishAll)
      setRunning(false)
      abortRef.current = null
    }
  }

  const resetSession = () => {
    if (running) abortRef.current?.abort()
    stopTypewriter()
    setMessages([])
    setTrace([])
    setCurrentRound(0)
    setRanOnce(false)
    setClarify(null)
    setClarifyOther("")
    setSessionId(`agent-demo-${Date.now().toString(36)}`)
    toast("已开启新会话（工作记忆已隔离）")
  }

  const stop = () => abortRef.current?.abort()

  /** 提交澄清答复——「澄清答复：」前缀供服务端规则 12 识别指代并按原流程续跑。 */
  const answerClarify = (choice: string) => {
    if (running || !choice.trim()) return
    send(`澄清答复：${choice.trim()}`)
  }

  const statusText = running || anyRunning

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 xl:grid xl:grid-cols-[1fr_400px]">
      {/* 左：对话区（标题直接展示服务端生效的模型信息，不再写「Agent 对话」） */}
      <Card className="flex min-h-0 flex-1 flex-col">
        <CardHead
          title={
            llmInfo ? (
              <span
                className="font-mono"
                title={
                  (llmInfo.provider ? `提供商 ${llmInfo.provider} · ` : "") +
                  `服务端默认：轮数上限 ${llmInfo.maxIterations} / 输出预算 ${llmInfo.maxTokens} / 单轮超时 ${llmInfo.agentTimeoutSeconds}s` +
                  "（本页 ⚙ 配置可覆盖轮数与预算）"
                }
              >
                {llmInfo.enabled ? llmInfo.model : "Agent 未启用"}
                {llmInfo.enabled && (
                  <span className="ml-2 font-sans text-[12px] font-normal text-fg-muted">
                    温度 {llmInfo.temperature}
                  </span>
                )}
              </span>
            ) : (
              <span className="text-fg-subtle">模型信息不可用</span>
            )
          }
          desc={
            <>
              命名空间 {ns} · 会话 {sessionId}
              {" · "}
              {mcpMode ? "MCP 按需发现" : "内置目录直连"}
              {tools != null && (
                <>
                  {" · 内置 "}
                  <span
                    className="cursor-pointer text-accent underline decoration-dotted underline-offset-2 hover:opacity-80"
                    onClick={() => setToolsOpen(true)}
                    title="点击查看工具清单"
                  >
                    {tools.length} 个工具
                  </span>
                  {mcpMode ? "（query_entity 静态直查；实体专属工具走 search→call 按需两跳）" : "（内置目录一跳直查，无发现环节）"}
                </>
              )}
            </>
          }
          right={
            <div className="flex shrink-0 items-center gap-1.5">
              <div className="flex overflow-hidden rounded-md border border-border" role="group" aria-label="工具接入模式">
                <button
                  type="button"
                  onClick={() => toggleMcpMode(false)}
                  disabled={running}
                  className={`px-2 py-1 text-[12px] transition-colors ${!mcpMode ? "bg-accent/10 font-medium text-accent" : "text-fg-muted hover:text-fg"}`}
                >
                  直连
                </button>
                <button
                  type="button"
                  onClick={() => toggleMcpMode(true)}
                  disabled={running}
                  className={`px-2 py-1 text-[12px] transition-colors ${mcpMode ? "bg-accent/10 font-medium text-accent" : "text-fg-muted hover:text-fg"}`}
                >
                  MCP 发现
                </button>
              </div>
              <Button variant="ghost" onClick={resetSession} disabled={running}>
                新会话
              </Button>
            </div>
          }
        />
        {/* 状态栏：只要右侧流程中还有未完成的段就显示"正在执行中"，全部结束后转为完成状态 */}
        <div className="flex h-8 shrink-0 items-center border-b border-border px-4 text-[14px]">
          {statusText ? (
            <span className="text-sweep font-medium" data-text="正在执行中……">
              正在执行中……
            </span>
          ) : ranOnce ? (
            <span className="flex items-center gap-1.5 text-[13px] text-fg-subtle">
              <span className="font-medium text-hit">✓</span> 已完成
            </span>
          ) : null}
        </div>
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto px-4 py-3">
            {messages.length === 0 && (
              <div className="mt-6 text-center text-[14px] text-fg-subtle">
                <p>向 Agent 提问，它会自主调用工具查询真实数据。</p>
                <p className="mt-1">试试下面的预设场景，或在右侧观察它的每一步调用。</p>
              </div>
            )}
            {messages.map((m, i) => (
              <div key={i} className={m.role === "user" ? "flex justify-end" : "flex justify-start"}>
                <div
                  className={
                    m.role === "user"
                      ? "max-w-[80%] rounded-md border border-accent/35 bg-accent/10 px-3 py-2 text-[14px] text-fg whitespace-pre-wrap"
                      : "max-w-[85%] rounded-md border border-border bg-bg-elevated px-3 py-2 text-[14px] text-fg"
                  }
                >
                  {m.role === "assistant" ? (
                    m.content ? (
                      <MarkdownAnswer text={m.content} />
                    ) : (
                      running && i === messages.length - 1 && <ThinkingHint round={currentRound} />
                    )
                  ) : (
                    m.content
                  )}
                  {m.interrupted && (
                    <span className="ml-1 inline-flex items-center">
                      <Badge tone="error">已中断</Badge>
                    </span>
                  )}
                  {m.usage && (
                    <div className="mt-1.5 flex flex-wrap items-center gap-1 border-t border-border pt-1.5">
                      {m.cache && (
                        <Badge tone={CACHE_LABEL[m.cache.result]?.tone ?? "hit"}>
                          {CACHE_LABEL[m.cache.result]?.text ?? "缓存 HIT"}
                        </Badge>
                      )}
                      {m.cache?.similarity != null && m.cache.similarity > 0 && m.cache.similarity < 1 && (
                        <span className="text-[13px] text-fg-subtle">相似度 {m.cache.similarity.toFixed(3)}</span>
                      )}
                      <Badge tone="neutral" mono>
                        LLM {m.usage.rounds} 轮
                      </Badge>
                      <Badge tone="neutral" mono>
                        工具 {m.usage.toolCalls} 次
                      </Badge>
                      {m.usage.dependencies && m.usage.dependencies.length > 0 && (
                        <Badge tone="readonly" mono>
                          依赖 {m.usage.dependencies.join(", ")}
                        </Badge>
                      )}
                      {m.usage.clarified && <Badge tone="accent">澄清待答复</Badge>}
                    </div>
                  )}
                </div>
              </div>
            ))}
            <div ref={chatEndRef} />
          </div>
          {clarify && !running && (
            <div className="shrink-0 border-t border-border bg-accent/5 px-4 py-2.5">
              <p className="mb-2 text-[13px] text-fg-muted">
                请选择一个口径继续（Agent 将按你的选择直接查询，不再重复确认）：
              </p>
              <div className="mb-2 flex flex-wrap gap-1.5">
                {clarify.options.map((o) => (
                  <button
                    key={o}
                    type="button"
                    onClick={() => answerClarify(o)}
                    className="rounded border border-accent/40 bg-bg-elevated px-2.5 py-1 text-[13px] text-fg transition-colors hover:bg-accent/15"
                  >
                    {o}
                  </button>
                ))}
              </div>
              <div className="flex gap-2">
                <Input
                  value={clarifyOther}
                  placeholder="其他：自行填写补充描述…"
                  onChange={(e) => setClarifyOther(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" && !e.nativeEvent.isComposing && clarifyOther.trim()) {
                      answerClarify(clarifyOther)
                    }
                  }}
                />
                <Button
                  variant="primary"
                  onClick={() => answerClarify(clarifyOther)}
                  disabled={!clarifyOther.trim()}
                  className="w-20"
                >
                  提交
                </Button>
              </div>
            </div>
          )}
          <div className="shrink-0 border-t border-border px-4 py-2.5">
            <div className="mb-2 flex flex-wrap gap-1.5">
              {SCENARIOS.map((s) => (
                <button
                  key={s.label}
                  title={s.hint}
                  disabled={running}
                  onClick={() => send(s.prompt)}
                  className="rounded border border-border bg-bg-elevated px-2 py-1 text-[13px] text-fg-muted transition-colors hover:border-accent/50 hover:text-fg disabled:opacity-50"
                >
                  {s.label}
                </button>
              ))}
            </div>
            <div className="flex gap-2">
              <Input
                value={input}
                placeholder={running ? "Agent 正在思考…" : "输入问题，回车发送"}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.nativeEvent.isComposing) send(input)
                }}
                disabled={running}
              />
              {running ? (
                <Button variant="warn" onClick={stop} className="w-20">
                  停止
                </Button>
              ) : (
                <Button variant="primary" onClick={() => send(input)} disabled={!input.trim()} className="w-20">
                  发送
                </Button>
              )}
            </div>
          </div>
        </div>
      </Card>

      {/* 右：调用轨迹（分段：进行中自动展开+跟随滚动，结束后自动收起） */}
      <Card className="flex min-h-0 flex-1 flex-col">
        <CardHead title="调用轨迹" desc="Agent 每一步的推理与工具执行实时记录" />
        <div ref={traceScrollRef} className="min-h-0 flex-1 space-y-2 overflow-y-auto px-4 py-3">
          {trace.length === 0 && !running && (
            <p className="mt-6 text-center text-[14px] text-fg-subtle">发送消息后，这里会实时展示 Agent 的思考与工具调用。</p>
          )}
          {trace.map((step) => {
            if (step.kind === "round") {
              return (
                <div key={step.id} className="flex items-center gap-2 pt-1">
                  <span className="h-px flex-1 bg-border" />
                  <Badge tone="accent" mono>
                    第 {step.round} 轮
                  </Badge>
                  <span className="h-px flex-1 bg-border" />
                </div>
              )
            }
            if (step.kind === "reasoning") {
              return (
                <Collapsible
                  key={step.id}
                  running={!step.done}
                  className="rounded border border-border bg-bg-elevated px-2.5 py-1.5"
                  header={
                    step.done ? (
                      <span className="text-[13px] text-fg-subtle">思考过程</span>
                    ) : (
                      <span className="text-sweep text-[13px]" data-text="思考中…">
                        思考中…
                      </span>
                    )
                  }
                >
                  <p className="mt-1 whitespace-pre-wrap text-[13px] leading-relaxed text-fg-muted">{step.text}</p>
                </Collapsible>
              )
            }
            if (step.kind === "cache") {
              const meta = CACHE_LABEL[step.result] ?? CACHE_LABEL.miss
              return (
                <div key={step.id} className="flex items-center gap-2">
                  <Badge tone={meta.tone}>{meta.text}</Badge>
                  {step.similarity != null && step.similarity > 0 && step.similarity < 1 && (
                    <span className="text-[13px] text-fg-subtle">相似度 {step.similarity.toFixed(3)}</span>
                  )}
                </div>
              )
            }
            if (step.kind === "error") {
              return (
                <div key={step.id} className="rounded border border-error/40 bg-error/10 px-2.5 py-1.5 text-[13px] text-error">
                  {step.message}
                </div>
              )
            }
            // tool
            return (
              <Collapsible
                key={step.id}
                running={!step.done}
                className="rounded border border-border bg-bg-elevated px-2.5 py-2"
                header={
                  <div className="flex w-full items-center justify-between gap-2">
                    <Badge tone="accent" mono>
                      {step.tool}
                    </Badge>
                    {!step.done ? (
                      <span className="text-sweep text-[13px]" data-text="执行中…">
                        执行中…
                      </span>
                    ) : step.latencyMs != null ? (
                      <span className="font-mono text-[13px] text-fg-subtle">{step.latencyMs} ms</span>
                    ) : null}
                  </div>
                }
              >
                <div className="mt-1.5 space-y-1.5 text-[13px]">
                  <div>
                    <p className="mb-1 text-fg-subtle">参数</p>
                    <JsonTree data={cachedParse(step.args)} name="args" />
                  </div>
                  {step.result != null && (
                    <div>
                      <p className="mb-1 text-fg-subtle">结果</p>
                      <div className="max-h-48 overflow-y-auto">
                        <JsonTree data={cachedParse(step.result)} name="result" />
                      </div>
                    </div>
                  )}
                </div>
              </Collapsible>
            )
          })}
          {running && <p className="text-center text-[13px] text-fg-subtle">…</p>}
        </div>
      </Card>

      {/* 工具清单弹窗：工具名方块阵列，点选后在下方显示功能描述 */}
      <Modal
        open={toolsOpen}
        onClose={() => setToolsOpen(false)}
        title="内置工具清单"
        className="max-h-[52vh] max-w-2xl"
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            {tools?.length ? (
              tools.map((t) => {
                const picked = pickedTool === t.name
                return (
                  <button
                    key={t.name}
                    type="button"
                    onClick={() => setPickedTool(picked ? null : t.name)}
                    className={cx(
                      "rounded-md border px-2.5 py-1.5 font-mono text-[12px] transition-colors",
                      picked
                        ? "border-accent bg-accent/10 text-accent"
                        : "border-border bg-bg-elevated/50 text-fg-muted hover:border-accent/50 hover:text-fg",
                    )}
                  >
                    {t.name}
                  </button>
                )
              })
            ) : (
              <p className="text-[13px] text-fg-subtle">工具目录加载中或不可用。</p>
            )}
          </div>
          {/* 固定高度描述框：常驻占位（按最长描述校准），选中切换/取消不引起弹窗高度抖动 */}
          <div className="h-[92px] shrink-0 overflow-y-auto rounded-lg border border-border bg-bg-elevated/40 px-3 py-2.5">
            {pickedToolInfo ? (
              <>
                <code className="text-[12px] font-semibold text-accent">
                  {pickedToolInfo.name}
                </code>
                <p className="mt-1 text-[12.5px] leading-relaxed text-fg-muted">
                  {pickedToolInfo.description}
                </p>
              </>
            ) : (
              <p className="text-[12.5px] text-fg-subtle">点击上方工具名查看功能描述。</p>
            )}
          </div>
          <p className="text-[12.5px] text-fg-subtle">
            以上即模型 function calling 看到的完整工具面；数据面通过 query_entity 的 entity
            参数通查全部实体，与 MCP 按需发现（search_entity_tools + call_entity_tool）是两条独立链路。
          </p>
        </div>
      </Modal>
    </div>
  )
}

/**
 * 可折叠段容器：创建时若处于进行中则默认展开；转为结束态时自动收起；
 * 用户随时可点击标题手动展开/收起。
 */
function Collapsible({
  running,
  header,
  children,
  className,
}: {
  running: boolean
  header: ReactNode
  children: ReactNode
  className?: string
}) {
  const [open, setOpen] = useState(running)
  useEffect(() => {
    if (!running) setOpen(false)
  }, [running])
  return (
    <div className={className}>
      <button type="button" onClick={() => setOpen((o) => !o)} className="flex w-full items-center gap-1 text-left">
        <span className={`shrink-0 text-fg-subtle transition-transform ${open ? "rotate-90" : ""}`}>›</span>
        {header}
      </button>
      {open && <div>{children}</div>}
    </div>
  )
}

function ThinkingHint({ round }: { round: number }) {
  const text = round > 0 ? `第 ${round} 轮推理中…` : "正在执行中……"
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-accent" />
      <span className="text-sweep" data-text={text}>
        {text}
      </span>
    </span>
  )
}

function safeParse(json: string): unknown {
  try {
    return JSON.parse(json)
  } catch {
    return json
  }
}

/**
 * 带缓存的 safeParse：流式期间任一事件都会重渲染整份 trace，
 * 对每个工具步骤的 args/result（可能很大）逐次 JSON.parse 是纯浪费。
 * key=原文（同文必同果），封顶 500 条防泄漏。
 */
const parseCache = new Map<string, unknown>()
function cachedParse(json: string): unknown {
  const hit = parseCache.get(json)
  if (hit !== undefined) return hit
  const v = safeParse(json)
  if (parseCache.size >= 500) parseCache.clear()
  parseCache.set(json, v)
  return v
}
