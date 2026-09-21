import { useEffect, useState } from "react"
import {
  BarChart,
  Button,
  Card,
  CardHead,
  EmptyState,
  Field,
  Input,
  JsonTree,
  LineChart,
  ReportPanel,
  Select,
  toast,
} from "../lib/ui"
import { api } from "../lib/api"
import { metricName, sampleDelta, seriesLabels, seriesValues } from "../lib/metrics"
import type { PageProps } from "."

type ReindexReport = { scanned: number; skipped: number; migrated: number; failed: number }

export default function Cache({ ns }: PageProps) {
  const [readKey, setReadKey] = useState("")
  const [readResult, setReadResult] = useState<{ value: unknown } | { miss: true } | null>(null)
  const [writeKey, setWriteKey] = useState("")
  const [writeValue, setWriteValue] = useState("")
  const [writeTtl, setWriteTtl] = useState("600")
  const [delKey, setDelKey] = useState("")
  const [report, setReport] = useState<ReindexReport | null>(null)
  const [reindexing, setReindexing] = useState(false)

  // entity 选项来自 schema
  const [entities, setEntities] = useState<{ entity: string }[]>([])
  const [entity, setEntity] = useState("")
  useEffect(() => {
    api
      .get<{ entity: string }[]>(`/api/v1/schema/entities?namespace=${encodeURIComponent(ns)}`)
      .then((list) => {
        setEntities(list)
        setEntity((prev) => prev || list[0]?.entity || "")
      })
      .catch(() => setEntities([]))
  }, [ns])

  // 采样命中/未命中/淘汰（复用 Dashboard 的采样序列）
  useEffect(() => {
    const sample = () => {
      sampleDelta("cache-hit", metricName("iris.cache.requests", ["result", "hit"]))
      sampleDelta("cache-miss", metricName("iris.cache.requests", ["result", "miss"]))
      sampleDelta("cache-evict", "iris.cache.evictions")
    }
    sample()
    const timer = setInterval(sample, 30000)
    return () => clearInterval(timer)
  }, [])
  const [, setTick] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setTick((t) => t + 1), 30000)
    return () => clearInterval(timer)
  }, [])

  const doGet = async () => {
    if (!readKey.trim()) return
    try {
      const value = await api.get<unknown>(
        `/api/v1/cache?namespace=${encodeURIComponent(ns)}&key=${encodeURIComponent(readKey.trim())}`,
      )
      setReadResult({ value })
    } catch (e) {
      if (e instanceof Error && e.message.includes("404")) setReadResult({ miss: true })
      else {
        setReadResult(null)
        toast(e instanceof Error ? e.message : "读取失败", "error")
      }
    }
  }

  const doSet = async () => {
    if (!writeKey.trim() || !writeValue.trim()) return
    let parsed: unknown = writeValue
    try {
      parsed = JSON.parse(writeValue)
    } catch {
      /* 按字符串写入 */
    }
    try {
      await api.put("/api/v1/cache", {
        namespace: ns,
        key: writeKey.trim(),
        value: parsed,
        ttlSeconds: writeTtl ? Number(writeTtl) : undefined,
      })
      toast("已写入")
    } catch (e) {
      toast(e instanceof Error ? e.message : "写入失败", "error")
    }
  }

  const doDel = async () => {
    if (!delKey.trim()) return
    try {
      await api.del(`/api/v1/cache?namespace=${encodeURIComponent(ns)}&key=${encodeURIComponent(delKey.trim())}`)
      toast("已失效该键", "warn")
    } catch (e) {
      toast(e instanceof Error ? e.message : "失效失败", "error")
    }
  }

  const doReindex = async () => {
    if (!entity) return
    setReindexing(true)
    try {
      const res = await api.post<ReindexReport>(
        `/api/v1/cache/semantic/reindex?namespace=${encodeURIComponent(ns)}&entity=${encodeURIComponent(entity)}`,
      )
      setReport(res)
    } catch (e) {
      toast(e instanceof Error ? e.message : "重建失败", "error")
    } finally {
      setReindexing(false)
    }
  }

  const hitData = seriesValues("cache-hit")
  const missData = seriesValues("cache-miss")
  const evictData = seriesValues("cache-evict")

  return (
    <div className="flex flex-col gap-4">
      {/* Exact KV */}
      <Card>
        <CardHead title="精确 KV 操作" desc="读取 / 写入 / 失效单个缓存键（namespace 由顶栏决定）" />
        <div className="grid gap-4 p-4 md:grid-cols-3">
          <div className="flex flex-col gap-2">
            <div className="text-[13px] font-semibold text-fg-muted">读取</div>
            <Input value={readKey} onChange={(e) => setReadKey(e.target.value)} placeholder="cache key…" className="font-mono" />
            <Button size="sm" onClick={doGet}>
              GET
            </Button>
            {readResult && "miss" in readResult && (
              <EmptyState icon="404" title="未命中" desc="该键不存在或已过期。" />
            )}
            {readResult && "value" in readResult && (
              <div className="rounded border border-border bg-bg p-2">
                <JsonTree data={readResult.value} />
              </div>
            )}
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-[13px] font-semibold text-fg-muted">写入</div>
            <Input value={writeKey} onChange={(e) => setWriteKey(e.target.value)} placeholder="key" className="font-mono" />
            <Input
              value={writeValue}
              onChange={(e) => setWriteValue(e.target.value)}
              placeholder="value (JSON)"
              className="font-mono"
            />
            <Input value={writeTtl} onChange={(e) => setWriteTtl(e.target.value)} placeholder="TTL 秒" className="font-mono" />
            <Button size="sm" onClick={doSet}>
              SET
            </Button>
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-[13px] font-semibold text-fg-muted">失效</div>
            <Input value={delKey} onChange={(e) => setDelKey(e.target.value)} placeholder="key" className="font-mono" />
            <Button size="sm" variant="warn" onClick={doDel}>
              DEL
            </Button>
          </div>
        </div>
      </Card>

      {/* Semantic rebuild */}
      <Card>
        <CardHead title="语义索引重建" desc="换 Embedder 后：旧指纹命名空间条目迁移到当前指纹（幂等）" />
        <div className="flex flex-col gap-3 p-4">
          <div className="flex flex-wrap items-end gap-3">
            <Field label="命名空间">
              <Select value={ns} disabled>
                <option>{ns}</option>
              </Select>
            </Field>
            <Field label="实体">
              <Select value={entity} onChange={(e) => setEntity(e.target.value)}>
                {entities.map((e) => (
                  <option key={e.entity} value={e.entity}>
                    {e.entity}
                  </option>
                ))}
              </Select>
            </Field>
            <Button variant="primary" onClick={doReindex} disabled={!entity || reindexing}>
              {reindexing ? "重建中…" : "执行重建"}
            </Button>
          </div>
          {report && (
            <ReportPanel
              title="迁移报告"
              tone={report.failed > 0 ? "warn" : "hit"}
              items={[
                { label: "已扫描", value: report.scanned.toLocaleString() },
                { label: "已跳过", value: report.skipped, tone: "miss" },
                { label: "已迁移", value: report.migrated.toLocaleString(), tone: "hit" },
                { label: "失败", value: report.failed, tone: report.failed > 0 ? "warn" : "hit" },
              ]}
            />
          )}
        </div>
      </Card>

      {/* Metrics */}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHead title="命中 / 未命中" desc="每 30s 采样区间增量 · 自部署起积累" />
          <div className="p-4">
            {hitData.length > 1 ? (
              <BarChart
                stacked
                height={180}
                groups={seriesLabels("cache-hit", 6)}
                series={[
                  { name: "hit", tone: "hit", data: hitData },
                  { name: "miss", tone: "miss", data: missData },
                ]}
              />
            ) : (
              <EmptyState icon="📈" title="时序数据积累中" desc="部署后约 1 分钟可见首批数据点。" />
            )}
          </div>
        </Card>
        <Card>
          <CardHead title="淘汰 (eviction)" desc="每 30s 采样区间增量" />
          <div className="p-4">
            {evictData.length > 1 ? (
              <LineChart height={180} labels={seriesLabels("cache-evict", 5)} series={[{ name: "evicted", tone: "warn", data: evictData }]} />
            ) : (
              <EmptyState icon="📈" title="时序数据积累中" desc="发生容量淘汰后此处出现曲线。" />
            )}
          </div>
        </Card>
      </div>
    </div>
  )
}
