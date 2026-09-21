import { useCallback, useEffect, useState } from "react"
import {
  Badge,
  Button,
  Card,
  CardHead,
  cx,
  EmptyState,
  Field,
  Input,
  Modal,
  ReportPanel,
  Ring,
  Segmented,
  Select,
  Tabs,
  Textarea,
  toast,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import type { PageProps } from "."

type WmEntry = { id: string; content: string; createdAt: number; owner?: string }
type WmDoc = {
  context: string
  strategy: string
  owner: string
  usagePercent: number
  entries: WmEntry[]
}

type LongMemory = {
  id: string
  namespace: string
  type: string
  content: string
  createdAt: number
  topics: string[] | null
  entities: string[] | null
  owner: string
  memoryHash: string
}

const stratTone = { discrete: "readonly", summary: "accent", preferences: "hit", custom: "need" } as const

export default function Memory({ ns }: PageProps) {
  const [tab, setTab] = useState<"work" | "long">("work")
  return (
    <div className="flex flex-col gap-4">
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: "work", label: "工作记忆" },
          { value: "long", label: "长期记忆" },
        ]}
      />
      {tab === "work" ? <WorkingMemory ns={ns} /> : <LongMemory ns={ns} />}
    </div>
  )
}

function fmtTime(ts: number) {
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`
}

function WorkingMemory({ ns }: PageProps) {
  const [sessionId, setSessionId] = useState("sess-1")
  const doc = useApi<WmDoc>(
    () => api.get(`/api/v1/memory/working?namespace=${encodeURIComponent(ns)}&sessionId=${encodeURIComponent(sessionId)}`),
    [ns, sessionId],
    { pollMs: 20000 },
  )
  const [clearOpen, setClearOpen] = useState(false)
  const [extract, setExtract] = useState<null | { extracted: number }>(null)
  const [extracting, setExtracting] = useState(false)

  // 追加条目
  const [content, setContent] = useState("")
  const [strategy, setStrategy] = useState("")
  const [owner, setOwner] = useState("")
  const [saving, setSaving] = useState(false)

  // 会话内检索
  const [q, setQ] = useState("")
  const [qLimit, setQLimit] = useState(5)
  const [hits, setHits] = useState<WmEntry[] | null>(null)

  const save = async () => {
    if (!content.trim()) return
    setSaving(true)
    try {
      await api.post("/api/v1/memory/working", {
        namespace: ns,
        sessionId,
        content: content.trim(),
        strategy: strategy || undefined,
        owner: owner || undefined,
      })
      setContent("")
      toast("已追加条目")
      doc.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "写入失败", "error")
    } finally {
      setSaving(false)
    }
  }

  const search = async () => {
    if (!q.trim()) return
    try {
      const res = await api.get<WmEntry[]>(
        `/api/v1/memory/working/search?namespace=${encodeURIComponent(ns)}&sessionId=${encodeURIComponent(sessionId)}&query=${encodeURIComponent(q.trim())}&limit=${qLimit}`,
      )
      setHits(res)
    } catch (e) {
      setHits(null)
      toast(e instanceof Error ? e.message : "检索失败", "error")
    }
  }

  const doExtract = useCallback(async () => {
    setExtracting(true)
    try {
      const res = await api.post<{ extracted: number }>(
        `/api/v1/memory/extract?namespace=${encodeURIComponent(ns)}&sessionId=${encodeURIComponent(sessionId)}`,
      )
      setExtract(res)
    } catch (e) {
      toast(e instanceof Error ? e.message : "抽取失败", "error")
    } finally {
      setExtracting(false)
    }
  }, [ns, sessionId])

  useEffect(() => setExtract(null), [sessionId])

  const usage = doc.data ? Math.min(1, doc.data.usagePercent / 100) : 0

  return (
    <>
      {/* session bar */}
      <Card>
        <div className="flex flex-wrap items-center gap-4 p-3">
          <div className="flex items-center gap-2">
            <span className="text-[13px] text-fg-muted">会话 ID</span>
            <Input
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
              className="w-44 font-mono"
            />
          </div>
          {doc.data?.strategy ? <Badge tone="accent">strategy: {doc.data.strategy}</Badge> : null}
          {doc.data?.owner ? <Badge tone="neutral">owner: {doc.data.owner}</Badge> : null}
          {doc.data && (
            <div className="flex items-center gap-2">
              <Ring value={usage} />
              <span className="text-[13px] text-fg-muted">
                usage
                <br />
                <span className="font-mono text-fg">{doc.data.usagePercent.toFixed(0)}%</span>
              </span>
            </div>
          )}
          <Button variant="danger" size="sm" className="ml-auto" onClick={() => setClearOpen(true)}>
            清空会话
          </Button>
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        {/* context + timeline */}
        <div className="flex flex-col gap-4 xl:col-span-2">
          <Card>
            <CardHead title="context 滚动摘要" right={<Badge tone="neutral">可折叠</Badge>} />
            <div className="p-4 text-[14px] leading-relaxed text-fg-muted">
              {doc.data?.context || <span className="text-fg-subtle">暂无摘要（会话消息未达压缩阈值）</span>}
            </div>
          </Card>

          <Card>
            <CardHead title="entries 时间线" desc={doc.data ? `共 ${doc.data.entries.length} 条 · 按时间倒序` : undefined} />
            <div className="flex flex-col">
              {doc.data && doc.data.entries.length > 0 ? (
                [...doc.data.entries].reverse().map((e) => {
                  const hit = hits?.some((h) => h.id === e.id)
                  return (
                    <div key={e.id} className={cx("flex gap-3 border-b border-border px-4 py-2.5", hit && "bg-accent/5")}>
                      <span className="font-mono text-[12px] text-fg-subtle">{fmtTime(e.createdAt)}</span>
                      {e.owner && <Badge tone="neutral">{e.owner}</Badge>}
                      <span className="flex-1 text-[14px]">{e.content}</span>
                      {hit && <span className="text-[11px] text-accent">命中</span>}
                    </div>
                  )
                })
              ) : (
                <div className="p-4">
                  <EmptyState
                    icon="◈"
                    title={doc.data ? "会话暂无条目" : doc.loading ? "读取中…" : `读取失败：${doc.error ?? ""}`}
                    desc="在右侧「追加条目」写入第一条工作记忆，或通过 MCP save_working_memory 写入。"
                  />
                </div>
              )}
            </div>
          </Card>
        </div>

        {/* right ops panel */}
        <div className="flex flex-col gap-4">
          <Card>
            <CardHead title="追加条目" />
            <div className="flex flex-col gap-3 p-4">
              <Field label="内容">
                <Textarea rows={3} value={content} onChange={(e) => setContent(e.target.value)} placeholder="输入记忆内容…" />
              </Field>
              <div className="grid grid-cols-2 gap-2">
                <Field label="策略" hint="留空不改">
                  <Select value={strategy} onChange={(e) => setStrategy(e.target.value)}>
                    <option value="">（不改）</option>
                    <option value="summary">摘要</option>
                    <option value="discrete">离散</option>
                    <option value="preferences">偏好</option>
                    <option value="custom">自定义</option>
                  </Select>
                </Field>
                <Field label="所有者">
                  <Input value={owner} onChange={(e) => setOwner(e.target.value)} placeholder="留空=default" />
                </Field>
              </div>
              <Button variant="primary" onClick={save} disabled={saving || !content.trim()}>
                {saving ? "保存中…" : "保存"}
              </Button>
            </div>
          </Card>

          <Card>
            <CardHead title="会话内语义检索" desc="试玩 · 命中条目在时间线高亮" />
            <div className="flex flex-col gap-2 p-4">
              <Input
                value={q}
                onChange={(e) => setQ(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && search()}
                placeholder="query…"
              />
              <div className="flex items-center gap-2">
                <Input type="number" value={qLimit} onChange={(e) => setQLimit(Number(e.target.value) || 5)} className="w-20" />
                <span className="text-[13px] text-fg-muted">条数</span>
                <Button size="sm" className="ml-auto" onClick={search}>
                  检索
                </Button>
              </div>
              {hits && (
                <div className="text-[13px] text-fg-muted">
                  命中 {hits.length} 条{hits.length === 0 && "（工作记忆语义索引不可用或无匹配）"}
                </div>
              )}
            </div>
          </Card>

          <Card>
            <CardHead title="手动触发抽取" />
            <div className="flex flex-col gap-3 p-4">
              <Button onClick={doExtract} disabled={extracting}>
                {extracting ? "抽取中（LLM）…" : "触发抽取"}
              </Button>
              {extract && (
                <ReportPanel
                  title="抽取结果"
                  items={[
                    { label: "新增长期记忆", value: extract.extracted, tone: extract.extracted > 0 ? "hit" : "miss" },
                  ]}
                />
              )}
              <p className="text-[12px] text-fg-subtle">抽取走真实 LLM（每 6 条自动触发，此处手动）。owner 继承会话归属。</p>
            </div>
          </Card>
        </div>
      </div>

      <Modal
        open={clearOpen}
        onClose={() => setClearOpen(false)}
        title="清空会话工作记忆"
        danger
        footer={
          <>
            <Button variant="ghost" onClick={() => setClearOpen(false)}>
              取消
            </Button>
            <Button
              variant="danger"
              onClick={async () => {
                setClearOpen(false)
                try {
                  await api.del(`/api/v1/memory/working?namespace=${encodeURIComponent(ns)}&sessionId=${encodeURIComponent(sessionId)}`)
                  toast(`会话 ${sessionId} 已清空`, "error")
                  doc.refresh()
                } catch (e) {
                  toast(e instanceof Error ? e.message : "清空失败", "error")
                }
              }}
            >
              确认清空
            </Button>
          </>
        }
      >
        将删除会话 <span className="font-mono text-fg">{sessionId}</span> 的全部工作记忆条目与滚动摘要，
        <span className="text-error">此操作不可逆</span>。长期记忆不受影响。
      </Modal>
    </>
  )
}

function LongMemory({ ns }: PageProps) {
  const [mode, setMode] = useState<"semantic" | "hybrid" | "keyword">("hybrid")
  const [query, setQuery] = useState("")
  // 输入防抖：受控输入逐字变化直接进 deps 会每字符发一次请求（连发 N 个）
  const [queryDebounced, setQueryDebounced] = useState("")
  useEffect(() => {
    const timer = setTimeout(() => setQueryDebounced(query), 400)
    return () => clearTimeout(timer)
  }, [query])
  const [limit, setLimit] = useState(10)
  const [ownerId, setOwnerId] = useState("")
  const [writeOpen, setWriteOpen] = useState(false)

  const search = useCallback(
    () =>
      api.get<LongMemory[]>(
        `/api/v1/memory/long-term/search?namespace=${encodeURIComponent(ns)}&query=${encodeURIComponent(queryDebounced)}&limit=${limit}&mode=${mode}` +
          (ownerId ? `&ownerId=${encodeURIComponent(ownerId)}` : ""),
      ),
    [ns, queryDebounced, limit, mode, ownerId],
  )
  const results = useApi<LongMemory[]>(search, [ns, queryDebounced, limit, mode, ownerId])

  // 直写表单
  const [wType, setWType] = useState("preference")
  const [wContent, setWContent] = useState("")
  const [wOwner, setWOwner] = useState("")
  const [saving, setSaving] = useState(false)

  const del = async (m: LongMemory) => {
    try {
      await api.del(`/api/v1/memory/long-term?namespace=${encodeURIComponent(ns)}&memoryId=${encodeURIComponent(m.id)}`)
      toast("已删除记忆", "error")
      results.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "删除失败", "error")
    }
  }

  const save = async () => {
    if (!wContent.trim()) return
    setSaving(true)
    try {
      await api.post("/api/v1/memory/long-term", {
        namespace: ns,
        type: wType,
        content: wContent.trim(),
        owner: wOwner || undefined,
      })
      setWriteOpen(false)
      setWContent("")
      toast("已写入长期记忆")
      results.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "写入失败", "error")
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="flex items-center justify-end">
        <Button variant="primary" onClick={() => setWriteOpen(true)}>
          + 直写记忆
        </Button>
      </div>

      <Card>
        <CardHead title="记忆检索" desc="query 为空返回最近记忆（keyword 模式）" />
        <div className="flex flex-wrap items-start gap-3 p-4">
          <Field label="查询语句">
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && results.refresh()}
              placeholder="用户支付偏好…"
              className="w-64"
            />
          </Field>
          <Field label="模式">
            <Segmented
              value={mode}
              onChange={setMode}
              options={[
                { value: "semantic", label: "语义" },
                { value: "hybrid", label: "混合" },
                { value: "keyword", label: "关键词" },
              ]}
            />
          </Field>
          <Field label="条数">
            <Input type="number" value={limit} onChange={(e) => setLimit(Number(e.target.value) || 10)} className="w-20" />
          </Field>
          <Field label="所有者 ID" hint="留空 = 全部 owner 可见">
            <Input value={ownerId} onChange={(e) => setOwnerId(e.target.value)} placeholder="如 alice" className="w-36" />
          </Field>
          <Button variant="primary" className="ml-auto mt-5" onClick={() => results.refresh()}>
            检索
          </Button>
        </div>
      </Card>

      {results.data && results.data.length > 0 ? (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {results.data.map((m) => (
            <Card key={m.id} interactive>
              <div className="flex flex-col gap-2 p-3.5">
                <div className="flex items-center gap-2">
                  <Badge tone={stratTone[m.type as keyof typeof stratTone] ?? "neutral"}>{m.type}</Badge>
                  {(m.topics ?? []).map((t) => (
                    <Badge key={t} tone="neutral">
                      #{t}
                    </Badge>
                  ))}
                  <button className="ml-auto text-[13px] text-error hover:underline" onClick={() => del(m)}>
                    删除
                  </button>
                </div>
                <p className="text-[14px] leading-relaxed">{m.content}</p>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-fg-subtle">
                  {(m.entities ?? []).map((e) => (
                    <span key={e} className="font-mono text-readonly">
                      {e}
                    </span>
                  ))}
                  <span className="font-mono">hash {m.memoryHash?.slice(0, 8)}</span>
                  <span className="font-mono">owner {m.owner}</span>
                  <span className="ml-auto font-mono">{new Date(m.createdAt).toLocaleString()}</span>
                </div>
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <div className="p-4">
          <EmptyState
            icon="◈"
            title={results.loading ? "检索中…" : results.data ? "无匹配记忆" : `检索失败：${results.error ?? ""}`}
            desc="调整 query / ownerId / mode；直写一条记忆后立即出现在结果中。"
          />
        </div>
      )}

      <Modal
        open={writeOpen}
        onClose={() => setWriteOpen(false)}
        title="直写长期记忆"
        footer={
          <>
            <Button variant="ghost" onClick={() => setWriteOpen(false)}>
              取消
            </Button>
            <Button variant="primary" onClick={save} disabled={saving || !wContent.trim()}>
              {saving ? "写入中…" : "写入"}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Field label="内容">
            <Textarea rows={3} value={wContent} onChange={(e) => setWContent(e.target.value)} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="类型">
              <Select value={wType} onChange={(e) => setWType(e.target.value)}>
                <option value="preference">偏好</option>
                <option value="summary">摘要</option>
                <option value="discrete">离散</option>
                <option value="background">背景</option>
                <option value="constraint">约束</option>
              </Select>
            </Field>
            <Field label="所有者 ID">
              <Input value={wOwner} onChange={(e) => setWOwner(e.target.value)} placeholder="留空=default" />
            </Field>
          </div>
        </div>
      </Modal>
    </>
  )
}
