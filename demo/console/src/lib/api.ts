// 后端接入层：双进程
//   api  = 中间件 fat jar :8080（REST 数据/运维面 + MCP 服务面 + actuator）
//   demo = 演示 fat jar   :8090（/api/v1/agent/* 聊天循环）
// 前端一律发相对路径，由 vite dev 代理按前缀分流（见 vite.config.ts）；
// 鉴权：X-API-Key（设置页配置，存 localStorage）；未配置 = 匿名访问（后端未开启鉴权时可用）

const API_KEY_STORAGE = "iris-lite.apiKey"

export function getApiKey(): string {
  try {
    return localStorage.getItem(API_KEY_STORAGE) ?? ""
  } catch {
    return ""
  }
}

export function setApiKey(key: string) {
  try {
    if (key) localStorage.setItem(API_KEY_STORAGE, key)
    else localStorage.removeItem(API_KEY_STORAGE)
  } catch {
    /* localStorage 不可用时忽略 */
  }
}

/** 掩码展示：只露末 4 位（对齐 agent key 不落明文原则） */
export function maskKey(key: string): string {
  if (!key) return "未配置"
  return "••••" + key.slice(-4)
}

export class ApiError extends Error {
  status: number
  body: string
  constructor(status: number, body: string) {
    super(`HTTP ${status}`)
    this.status = status
    this.body = body
  }
}

async function request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = {}
  const key = getApiKey()
  if (key) headers["X-API-Key"] = key
  if (body !== undefined) headers["Content-Type"] = "application/json"
  const res = await fetch(path, {
    method,
    headers,
    signal,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let text = ""
    try {
      text = await res.text()
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, text)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : null) as T
}

export const api = {
  get: <T,>(path: string, signal?: AbortSignal) => request<T>("GET", path, undefined, signal),
  post: <T,>(path: string, body?: unknown) => request<T>("POST", path, body ?? {}),
  put: <T,>(path: string, body?: unknown) => request<T>("PUT", path, body ?? {}),
  del: <T,>(path: string, body?: unknown) => request<T>("DELETE", path, body ?? {}),
}

/** 判定当前 key 的角色：operator 端点可访问即 operator，否则 agent/匿名 */
export async function probeRole(): Promise<"operator" | "agent"> {
  try {
    await api.get("/api/v1/admin/agent-keys")
    return "operator"
  } catch (e) {
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) return "agent"
    throw e // 网络错误等——交由调用方处理
  }
}

/* ------------------------------------------------------------------ */
/* Agent SSE 流式                                                      */
/* ------------------------------------------------------------------ */

export type AgentEvent = {
  type:
    | "round"
    | "reasoning"
    | "tool_call"
    | "tool_result"
    | "cache"
    | "answer_delta"
    | "answer_reset"
    | "clarify"
    | "done"
    | "error"
  data: Record<string, unknown>
}

/**
 * Agent 流式问答：POST /api/v1/agent/chat 消费 text/event-stream。
 *
 * 不用 EventSource——它不支持 POST 与自定义鉴权头；fetch + getReader 手动
 * 解析 SSE 帧（event:/data: 行，空行分帧）。网络中断抛异常，调用方负责
 * 把已收到的部分内容标记为「连接中断」。
 */
export async function agentChatStream(
  body: { sessionId: string; message: string; namespace?: string; bypassCache?: boolean; maxTurns?: number; maxTokens?: number; mcpTools?: boolean },
  onEvent: (e: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers: Record<string, string> = { "Content-Type": "application/json" }
  const key = getApiKey()
  if (key) headers["X-API-Key"] = key
  const res = await fetch("/api/v1/agent/chat", {
    method: "POST",
    headers,
    signal,
    body: JSON.stringify(body),
  })
  if (!res.ok || !res.body) {
    let text = ""
    try {
      text = await res.text()
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, text)
  }
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ""
  const dispatchFrame = (frame: string) => {
    let event = "message"
    const dataLines: string[] = []
    for (const line of frame.split("\n")) {
      if (line.startsWith("event:")) event = line.slice(6).trim()
      else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""))
      // 注释行（: ping 心跳）忽略；data 只剥一个前导空格（SSE 规范），trim 会破坏正文首尾空白
    }
    if (!dataLines.length) return
    const raw = dataLines.join("\n")
    try {
      onEvent({ type: event as AgentEvent["type"], data: JSON.parse(raw) })
    } catch {
      onEvent({ type: event as AgentEvent["type"], data: { text: raw } })
    }
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    // 规范化 CR 换行：SSE 规范允许 \r\n / \r 分帧，只按 \n\n 找边界会漏帧
    buffer = buffer.replace(/\r\n/g, "\n").replace(/\r/g, "\n")
    let idx: number
    while ((idx = buffer.indexOf("\n\n")) >= 0) {
      dispatchFrame(buffer.slice(0, idx))
      buffer = buffer.slice(idx + 2)
    }
  }
  if (buffer.trim()) dispatchFrame(buffer) // 收尾残留帧
}
