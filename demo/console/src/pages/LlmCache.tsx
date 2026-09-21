import { useEffect, useState } from "react"
import {
  Badge,
  Banner,
  Button,
  Card,
  CardHead,
  Field,
  Input,
  JsonTree,
  Modal,
  ProgressBar,
  StatCard,
  Textarea,
  toast,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import type { PageProps } from "."

type LookupResp = {
  hit: boolean
  exact: boolean
  similarity: number
  entry?: { prompt: string; response: string; model: string; createdAt: number }
  reason?: string
}

type Stats = {
  enabled: boolean
  lookups: number
  hitExact: number
  hitSemantic: number
  hitTotal: number
  miss: number
  hitRate: number | null
  evictions: number
  entries: number
  threshold: number
  ttlSeconds: number
  maxEntries: number
}

export default function LlmCache({ ns }: PageProps) {
  const stats = useApi<Stats>(() => api.get(`/api/v1/llm-cache/stats?namespace=${encodeURIComponent(ns)}`), [ns], { pollMs: 20000 })
  const [clearOpen, setClearOpen] = useState(false)

  // lookup 试玩
  const [lpPrompt, setLpPrompt] = useState("总结这份订单的退款政策")
  const [lpModel, setLpModel] = useState("")
  const [lpThreshold, setLpThreshold] = useState("0.90")
  const [result, setResult] = useState<LookupResp | null>(null)
  const [lookuping, setLookuping] = useState(false)

  const doLookup = async () => {
    if (!lpPrompt.trim()) return
    setLookuping(true)
    try {
      const res = await api.post<LookupResp>("/api/v1/llm-cache/lookup", {
        namespace: ns,
        prompt: lpPrompt.trim(),
        model: lpModel || undefined,
        thresholdOverride: lpThreshold ? Number(lpThreshold) : undefined,
      })
      setResult(res)
    } catch (e) {
      setResult(null)
      toast(e instanceof Error ? e.message : "查找失败", "error")
    } finally {
      setLookuping(false)
    }
  }

  // store 写入
  const [stPrompt, setStPrompt] = useState("")
  const [stResponse, setStResponse] = useState("")
  const [stModel, setStModel] = useState("")
  const [stTtl, setStTtl] = useState("3600")
  const [storing, setStoring] = useState(false)

  const doStore = async () => {
    if (!stPrompt.trim() || !stResponse.trim()) return
    setStoring(true)
    try {
      await api.put("/api/v1/llm-cache", {
        namespace: ns,
        prompt: stPrompt.trim(),
        response: stResponse.trim(),
        model: stModel || undefined,
        ttlSeconds: stTtl ? Number(stTtl) : undefined,
      })
      toast("已写入缓存")
      setStPrompt("")
      setStResponse("")
      stats.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "写入失败", "error")
    } finally {
      setStoring(false)
    }
  }

  useEffect(() => setResult(null), [ns])

  return (
    <div className="flex flex-col gap-4">
      <Banner tone="accent">
        <span>ℹ</span>
        <span>
          不同 <span className="font-mono">model</span> 的缓存绝不互命中——试玩换 model 必 miss 是预期行为。
        </span>
      </Banner>

      {/* Lookup playground */}
      <Card>
        <CardHead title="Lookup 试玩" desc="模拟一次语义缓存查找（真实请求 /api/v1/llm-cache/lookup）" />
        <div className="grid gap-4 p-4 lg:grid-cols-2">
          <div className="flex flex-col gap-3">
            <Field label="提示词">
              <Textarea rows={4} value={lpPrompt} onChange={(e) => setLpPrompt(e.target.value)} />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="模型" hint="留空 = none">
                <Input value={lpModel} onChange={(e) => setLpModel(e.target.value)} className="font-mono" />
              </Field>
              <Field label="相似度阈值覆盖">
                <Input value={lpThreshold} onChange={(e) => setLpThreshold(e.target.value)} className="font-mono" />
              </Field>
            </div>
            <Button variant="primary" className="w-fit" onClick={doLookup} disabled={lookuping}>
              {lookuping ? "查找中…" : "执行 Lookup"}
            </Button>
          </div>

          <div className="rounded-md border border-border bg-bg p-3">
            {!result ? (
              <div className="grid h-full place-items-center text-[13px] text-fg-subtle">执行后在此显示结果</div>
            ) : (
              <div className="flex flex-col gap-3">
                <div className="flex gap-2">
                  <Badge tone={result.hit ? "hit" : "miss"}>{result.hit ? "HIT 命中" : "MISS 未命中"}</Badge>
                  {result.hit && (
                    <Badge tone={result.exact ? "accent" : "neutral"}>{result.exact ? "exact 精确" : "semantic 语义"}</Badge>
                  )}
                </div>
                {result.hit ? (
                  <>
                    <div>
                      <div className="mb-1 flex justify-between text-[12px] text-fg-muted">
                        <span>相似度</span>
                        <span className="font-mono text-fg">
                          {result.similarity.toFixed(2)} · 阈值 {stats.data?.threshold ?? 0.9}
                        </span>
                      </div>
                      <ProgressBar value={result.similarity} tone="hit" threshold={stats.data?.threshold ?? 0.9} />
                    </div>
                    <div className="rounded border border-border bg-card p-2">
                      <div className="mb-1 text-[12px] text-fg-muted">命中 response</div>
                      <JsonTree
                        data={{
                          prompt: result.entry?.prompt,
                          response: result.entry?.response,
                          model: result.entry?.model,
                        }}
                      />
                    </div>
                  </>
                ) : (
                  <div className="text-[13px] text-fg-muted">
                    reason: <span className="font-mono text-fg">{result.reason}</span>
                    {result.reason === "no-match" && "（无相似度超阈值的缓存条目）"}
                    {result.reason === "no-embedder" && "（Embedder 未启用，仅精确命中可用）"}
                    {result.reason === "disabled" && "（LLM 缓存未启用）"}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </Card>

      {/* Store + stats + clear */}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHead title="Store 写入" desc="手动写入缓存条目" />
          <div className="flex flex-col gap-3 p-4">
            <Field label="提示词">
              <Textarea rows={2} value={stPrompt} onChange={(e) => setStPrompt(e.target.value)} />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="模型">
                <Input value={stModel} onChange={(e) => setStModel(e.target.value)} placeholder="留空 = none" className="font-mono" />
              </Field>
              <Field label="TTL (秒)">
                <Input value={stTtl} onChange={(e) => setStTtl(e.target.value)} className="font-mono" />
              </Field>
            </div>
            <Field label="响应">
              <Textarea rows={2} value={stResponse} onChange={(e) => setStResponse(e.target.value)} />
            </Field>
            <Button className="w-fit" onClick={doStore} disabled={storing || !stPrompt.trim() || !stResponse.trim()}>
              {storing ? "写入中…" : "写入"}
            </Button>
          </div>
        </Card>

        <div className="flex flex-col gap-4">
          <Card>
            <CardHead title="命中统计" desc="进程生命周期累计值" />
            <div className="grid grid-cols-3 gap-3 p-4">
              {stats.data?.enabled ? (
                <>
                  <StatCard
                    label="命中率"
                    value={stats.data.hitRate === null ? "—" : (stats.data.hitRate * 100).toFixed(1)}
                    unit={stats.data.hitRate === null ? undefined : "%"}
                    sub={stats.data.lookups === 0 ? "暂无查找流量" : `查找 ${stats.data.lookups} 次`}
                  />
                  <StatCard label="条目数" value={stats.data.entries.toLocaleString()} sub={`容量上限 ${stats.data.maxEntries}`} />
                  <StatCard
                    label="命中明细"
                    value={`${stats.data.hitExact}/${stats.data.hitSemantic}`}
                    sub={`精确/语义 · 未命中 ${stats.data.miss}`}
                  />
                </>
              ) : (
                <>
                  <StatCard label="命中率" disabled />
                  <StatCard label="条目数" disabled />
                  <StatCard label="命中明细" disabled />
                </>
              )}
            </div>
          </Card>

          <Card danger>
            <CardHead title="按 namespace 清空" desc="危险操作" />
            <div className="flex flex-col gap-2 p-4">
              <div className="rounded border border-border bg-bg p-2 font-mono text-[13px] text-fg-muted">
                将删除 iris:{ns}:llmcache:*（当前 {stats.data?.entries ?? "—"} 条）
              </div>
              <Button variant="danger" className="w-fit" onClick={() => setClearOpen(true)}>
                清空该 namespace 缓存
              </Button>
            </div>
          </Card>
        </div>
      </div>

      <Modal
        open={clearOpen}
        onClose={() => setClearOpen(false)}
        title="清空 LLM 缓存"
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
                  const res = await api.del<{ removed: number }>(`/api/v1/llm-cache?namespace=${encodeURIComponent(ns)}`)
                  toast(`已清空 ${res.removed} 条缓存`, "error")
                  stats.refresh()
                } catch (e) {
                  toast(e instanceof Error ? e.message : "清空失败", "error")
                }
              }}
            >
              我确认删除
            </Button>
          </>
        }
      >
        将删除匹配 <span className="font-mono text-fg">iris:{ns}:llmcache:*</span> 的全部键（当前 {stats.data?.entries ?? "—"} 条），
        <span className="text-error">不可逆</span>。清空后所有查找将回落到模型调用直到缓存重新预热。
      </Modal>
    </div>
  )
}
