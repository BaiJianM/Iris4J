import { useEffect, useState } from "react"
import { BarChart, Card, CardHead, EmptyState, Gauge, LineChart, Segmented } from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import { metricName, sampleDelta, seriesLabels, seriesValues } from "../lib/metrics"
import type { PageProps } from "."

type CdcSource = { index: number; streamLen: number | null; lag: number | null; pending: number | null; dlqLen: number | null }

const WINDOW_POINTS: Record<"1h" | "6h" | "24h" | "7d", number> = {
  "1h": 120,
  "6h": 720,
  "24h": 1440,
  "7d": 1440,
}

export default function Metrics({ ns }: PageProps) {
  const [win, setWin] = useState<"1h" | "6h" | "24h" | "7d">("24h")
  const cdc = useApi<CdcSource[]>(() => api.get("/api/v1/cdc/sources"), [], { pollMs: 15000 })
  const [, setTick] = useState(0)

  // 采样循环：延迟分位 + 缓存 + CDC + DLQ
  useEffect(() => {
    const sample = () => {
      sampleDelta("m-cache-hit", metricName("iris.cache.requests", ["result", "hit"]))
      sampleDelta("m-cache-miss", metricName("iris.cache.requests", ["result", "miss"]))
      sampleDelta("m-cache-evict", "iris.cache.evictions")
      sampleDelta("m-dlq", "iris.cdc.dlq")
      sampleDelta("m-q-count", "iris.query", "COUNT")
    }
    sample()
    const timer = setInterval(sample, 30000)
    return () => clearInterval(timer)
  }, [])
  useEffect(() => {
    const timer = setInterval(() => setTick((t) => t + 1), 30000)
    return () => clearInterval(timer)
  }, [])

  const pt = WINDOW_POINTS[win]
  const hitData = seriesValues("m-cache-hit", pt)
  const missData = seriesValues("m-cache-miss", pt)
  const evictData = seriesValues("m-cache-evict", pt)
  const dlqData = seriesValues("m-dlq", pt)
  const qCount = seriesValues("m-q-count", pt)

  // 延迟分位：走后端 /api/v1/metrics/query-latency（actuator 已无 PERCENTILE_*），
  // 按 entity 分组返回；"总体"取各 entity 分位的最大值（上界近似，无法精确合成）
  const [p50, setP50] = useState<number[]>([])
  const [p95, setP95] = useState<number[]>([])
  useEffect(() => {
    const sample = async () => {
      const { fetchQueryLatency } = await import("../lib/metrics")
      const rows = await fetchQueryLatency()
      const maxOf = (k: "p50" | "p95") =>
        rows.reduce((acc, r) => Math.max(acc, (r[k] as number | null) ?? 0), 0)
      setP50((s) => [...s, rows.length ? maxOf("p50") : 0].slice(-1440))
      setP95((s) => [...s, rows.length ? maxOf("p95") : 0].slice(-1440))
    }
    sample()
    const timer = setInterval(sample, 30000)
    return () => clearInterval(timer)
  }, [])

  const lagTotal = (cdc.data ?? []).reduce((a, s) => a + (s.lag ?? 0), 0)
  const pel = (cdc.data ?? []).reduce((a, s) => a + (s.pending ?? 0), 0)
  const known = (cdc.data ?? []).some((s) => s.lag !== null)

  const hitRateSeries = hitData.map((h, i) => {
    const total = h + (missData[i] ?? 0)
    return total > 0 ? Math.round((h / total) * 100) : 0
  })

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="text-[13px] text-fg-muted">
          数据源：<span className="text-fg">前端采样</span>（外接 Prometheus 未配置；/actuator/prometheus 可随时接入）
        </span>
        <Segmented
          value={win}
          onChange={setWin}
          options={[
            { value: "1h", label: "近 1 小时" },
            { value: "6h", label: "近 6 小时" },
            { value: "24h", label: "近 24 小时" },
            { value: "7d", label: "近 7 天" },
          ]}
        />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card>
          <CardHead title="查询延迟 P50 / P95" desc="iris.query Timer · 每 30s 采样即时分位 · ms" />
          <div className="p-4">
            {p95.length > 1 ? (
              <LineChart
                height={180}
                labels={seriesLabels("m-q-count", 3)}
                series={[
                  { name: "P50", tone: "accent", data: p50 },
                  { name: "P95", tone: "warn", data: p95 },
                ]}
              />
            ) : (
              <EmptyState icon="⏱" title="时序数据积累中" desc="执行实体查询后约 1 分钟可见首批数据点。" />
            )}
          </div>
        </Card>
        <Card>
          <CardHead title="缓存 hit / miss" desc="每 30s 区间增量 · 堆叠柱状" />
          <div className="p-4">
            {hitData.length > 1 ? (
              <BarChart
                stacked
                height={180}
                groups={seriesLabels("m-cache-hit", 6)}
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
          <CardHead title="缓存命中率" desc="按采样点现算 · %" />
          <div className="p-4">
            {hitRateSeries.length > 1 && hitRateSeries.some((v) => v > 0) ? (
              <LineChart height={180} labels={seriesLabels("m-cache-hit", 5)} series={[{ name: "hit %", tone: "hit", data: hitRateSeries }]} />
            ) : (
              <EmptyState icon="📈" title="暂无查询流量" desc="有缓存查询后此处出现命中率曲线。" />
            )}
          </div>
        </Card>
        <Card>
          <CardHead title="缓存淘汰" desc="iris.cache.evictions · 区间增量" />
          <div className="p-4">
            {evictData.length > 1 && evictData.some((v) => v > 0) ? (
              <LineChart height={180} labels={seriesLabels("m-cache-evict", 5)} series={[{ name: "evicted", tone: "warn", data: evictData }]} />
            ) : (
              <EmptyState icon="📉" title="无淘汰事件" desc="容量上限未触发时无淘汰。" />
            )}
          </div>
        </Card>
        <Card>
          <CardHead title="DLQ 转入" desc="iris.cdc.dlq · 区间增量" />
          <div className="p-4">
            {dlqData.length > 1 ? (
              <LineChart height={180} labels={seriesLabels("m-dlq", 5)} series={[{ name: "DLQ", tone: "error", data: dlqData }]} />
            ) : (
              <EmptyState icon="📉" title="时序数据积累中" desc="发生毒消息转 DLQ 后出现曲线。" />
            )}
          </div>
        </Card>
        <Card>
          <CardHead title="积压 / PEL 实时" desc="GET /api/v1/cdc/sources（lag=真实积压 / XPENDING）" />
          <div className="grid grid-cols-2 gap-3 p-4">
            {known ? (
              <>
                <div className="rounded-md border border-border bg-card p-2">
                  <Gauge value={lagTotal} max={Math.max(500, lagTotal)} label="积压 (lag)" tone="warn" />
                </div>
                <div className="rounded-md border border-border bg-card p-2">
                  <Gauge value={pel} max={Math.max(100, pel)} label="PEL" tone="accent" />
                </div>
              </>
            ) : (
              <div className="col-span-2">
                <EmptyState icon="⇄" title="CDC 管道未运行" desc="未配置 source 或计数不可用。" />
              </div>
            )}
          </div>
        </Card>
      </div>
    </div>
  )
}
