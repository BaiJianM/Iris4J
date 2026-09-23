import { useCallback, useEffect, useState } from "react"
import { Badge, Button, cx, Dot, Select, ToastHost, toast } from "./lib/ui"
import { getApiKey, maskKey, probeRole, api } from "./lib/api"
import { getNamespaces } from "./lib/prefs"
import { PAGES, type PageKey } from "./pages"

type NavItem = { key: PageKey; label: string; icon: string }
type NavGroup = { group: string; items: NavItem[] }

const NAV: NavGroup[] = [
  { group: "总览", items: [{ key: "dashboard", label: "仪表盘", icon: "▤" }] },
  { group: "演示", items: [{ key: "agent", label: "Agent 对话", icon: "✦" }] },
  {
    group: "数据",
    items: [
      { key: "query", label: "实体数据查询", icon: "⚲" },
      { key: "keys", label: "键浏览", icon: "⌗" },
    ],
  },
  { group: "记忆", items: [{ key: "memory", label: "记忆管理", icon: "◈" }] },
  {
    group: "缓存",
    items: [
      { key: "llmcache", label: "LLM 缓存", icon: "⚡" },
      { key: "cache", label: "缓存管理", icon: "▦" },
    ],
  },
  { group: "集成", items: [{ key: "cdc", label: "CDC 数据集成", icon: "⇄" }] },
  {
    group: "治理",
    items: [
      { key: "schema", label: "Schema 管理", icon: "❏" },
      { key: "agentkeys", label: "Agent Keys", icon: "🔑" },
      { key: "consistency", label: "一致性", icon: "≍" },
    ],
  },
  {
    group: "运维",
    items: [
      { key: "redis", label: "Redis 状态", icon: "◉" },
      { key: "metrics", label: "监控指标", icon: "📈" },
      { key: "settings", label: "设置", icon: "⚙" },
    ],
  },
]

export default function App() {
  const [light, setLight] = useState(() => {
    try {
      return localStorage.getItem("iris4j.theme") === "light"
    } catch {
      return false // localStorage 被禁用：回落暗色（默认主题）
    }
  })
  const [collapsed, setCollapsed] = useState(false)
  const [page, setPage] = useState<PageKey>("dashboard")
  const [nsList, setNsList] = useState<string[]>(() => getNamespaces())
  const [ns, setNs] = useState(nsList[0] ?? "ecomm")
  const [healthy, setHealthy] = useState<boolean | null>(null)
  const [role, setRole] = useState<"operator" | "agent" | null>(null)
  const [keyMask, setKeyMask] = useState(() => maskKey(getApiKey()))

  // 后端健康轮询（10s）
  useEffect(() => {
    let alive = true
    const check = async () => {
      try {
        const h = await api.get<{ status: string }>("/actuator/health")
        if (alive) setHealthy(h?.status === "UP")
      } catch {
        if (alive) setHealthy(false)
      }
    }
    check()
    const timer = setInterval(check, 10000)
    return () => {
      alive = false
      clearInterval(timer)
    }
  }, [])

  // 角色 / key 掩码探测：apiKey 变化或 key 变动后重探
  const refreshIdentity = useCallback(() => {
    setKeyMask(maskKey(getApiKey()))
    probeRole()
      .then((r) => setRole(r))
      .catch(() => setRole(null))
  }, [])

  useEffect(() => {
    refreshIdentity()
  }, [refreshIdentity])

  // 设置页保存后广播，顶栏同步
  useEffect(() => {
    const handler = () => {
      setNsList(getNamespaces())
      refreshIdentity()
      toast("设置已保存，顶栏状态已刷新")
    }
    window.addEventListener("iris4j.settings-saved", handler)
    return () => window.removeEventListener("iris4j.settings-saved", handler)
  }, [refreshIdentity])

  // 设置页切主题
  useEffect(() => {
    const handler = (e: Event) => setLight((e as CustomEvent<boolean>).detail)
    window.addEventListener("iris4j.theme-change", handler)
    return () => window.removeEventListener("iris4j.theme-change", handler)
  }, [])

  const Current = PAGES[page].component
  const meta = PAGES[page]
  // Agent 演示页：固定视口布局——页面不滚，内部左右两栏各自滚动
  const fixedLayout = page === "agent"

  return (
    <div className={cx(light && "theme-light", "flex h-full w-full flex-col bg-bg text-fg")}>
      {/* Top bar */}
      <header className="flex h-14 shrink-0 items-center gap-4 border-b border-border bg-bg-elevated px-4">
        <div className="flex items-center gap-2">
          <button
            onClick={() => setCollapsed((c) => !c)}
            className="grid size-7 place-items-center rounded text-fg-muted hover:bg-card-hover hover:text-fg"
            aria-label="折叠导航"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
              <path d="M2.5 4h11M2.5 8h11M2.5 12h11" />
            </svg>
          </button>
          <div className="flex items-center gap-2">
            <span className="grid size-6 place-items-center rounded bg-accent text-[14px] font-bold text-white">i</span>
            <span className="text-[16px] font-semibold tracking-tight">iris4j 控制台</span>
          </div>
        </div>

        <div className="ml-auto flex items-center gap-4">
          <div className="flex items-center gap-2 rounded-md border border-border-strong bg-card px-2 py-1">
            <span className="text-[13px] text-fg-muted">namespace</span>
            <Select value={ns} onChange={(e) => setNs(e.target.value)} className="h-6 border-0 bg-transparent font-mono text-[14px] font-medium">
              {nsList.map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </Select>
          </div>
          <span className="flex items-center gap-1.5 text-[13px] text-fg-muted">
            <Dot tone={healthy === null ? "neutral" : healthy ? "hit" : "error"} />
            {healthy === null ? "检测中" : healthy ? "已连接" : "未连接"}
          </span>
          <span className="flex items-center gap-1.5 font-mono text-[13px] text-fg-muted" title="REST/MCP 鉴权头 X-API-Key（设置页配置）；与 LLM 模型 key 无关，后端未开启鉴权时匿名访问">
            {keyMask === "未配置" ? "API key 未配置（匿名访问）" : `API key ${keyMask}`}
            {role && <Badge tone={role === "operator" ? "readonly" : "accent"}>{role}</Badge>}
          </span>
          {/* 顶栏切换与设置页同语义：持久化 + 广播（原实现只改 state，刷新即丢） */}
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              const next = !light
              setLight(next)
              try {
                localStorage.setItem("iris4j.theme", next ? "light" : "dark")
              } catch {
                /* ignore */
              }
              window.dispatchEvent(new CustomEvent("iris4j.theme-change", { detail: next }))
            }}
          >
            {light ? "🌙 暗色" : "☀ 亮色"}
          </Button>
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* Sidebar */}
        <nav
          className={cx(
            "flex shrink-0 flex-col gap-4 overflow-y-auto border-r border-border bg-bg-elevated py-3 transition-all",
            collapsed ? "w-16 px-1.5" : "w-[220px] px-2.5",
          )}
        >
          {NAV.map((g) => (
            <div key={g.group}>
              {!collapsed && (
                <div className="px-2 pb-1 text-[11px] font-semibold uppercase tracking-wider text-fg-subtle">
                  {g.group}
                </div>
              )}
              <div className="flex flex-col gap-0.5">
                {g.items.map((it) => {
                  const active = page === it.key
                  return (
                    <button
                      key={it.key}
                      onClick={() => setPage(it.key)}
                      title={collapsed ? it.label : undefined}
                      className={cx(
                        "group flex items-center gap-2.5 rounded px-2 py-1.5 text-[14px] transition-colors",
                        collapsed && "justify-center",
                        active ? "bg-accent/12 font-medium text-accent" : "text-fg-muted hover:bg-card-hover hover:text-fg",
                      )}
                    >
                      <span className={cx("w-5 text-center text-[18px] leading-none", active && "text-accent")}>{it.icon}</span>
                      {!collapsed && <span className="flex-1 text-left">{it.label}</span>}
                    </button>
                  )
                })}
              </div>
            </div>
          ))}
        </nav>

        {/* Main */}
        <main className={cx("min-w-0 flex-1", fixedLayout ? "flex flex-col overflow-hidden" : "overflow-y-auto")}>
          <div
            className={cx(
              "flex items-start justify-between gap-4 border-b border-border px-5 py-3.5",
              fixedLayout && "shrink-0",
            )}
          >
            <div>
              <h1 className="text-[17px] font-semibold">{meta.title}</h1>
              <p className="mt-0.5 text-[14px] text-fg-muted">{meta.desc}</p>
            </div>
            <div className="flex shrink-0 items-center gap-2">{meta.actions?.()}</div>
          </div>
          <div className={cx("fade-in", fixedLayout ? "min-h-0 flex-1 p-5" : "p-5")}>
            <Current ns={ns} />
          </div>
        </main>
      </div>

      <ToastHost />
    </div>
  )
}
