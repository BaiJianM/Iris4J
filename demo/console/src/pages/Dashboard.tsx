import { useEffect, useState } from "react"
import {
  BarChart,
  Badge,
  Card,
  CardHead,
  Dot,
  EmptyState,
  LineChart,
  Segmented,
  StatCard,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import { metricName, sampleDelta, seriesLabels, seriesValues, fetchMeasurement, fetchQueryLatency } from "../lib/metrics"
import type { PageProps } from "."

type RedisStatus = {
  dbsize: number
  redisVersion: string
  uptimeSeconds: number
  usedMemoryHuman: string
  aofEnabled: boolean
  aofLastWriteStatus: string
  aofSizeHuman: string
  rdbLastSaveTime: string
  rdbLastBgsaveStatus: string
  rdbChangesSinceLastSave: number
}

type CdcSource = {
  index: number
  namespace: string
  stream: string
  entity: string
  /** XLEN：stream 保留的事件总量（Debezium 消费后不删除，历史累计、只增不减） */
  streamLen: number | null
  /** 主消费组未投递事件数：真实积压（消费跟上就归零） */
  lag: number | null
  pending: number | null
  dlqLen: number | null
}

type EntitySummary = { entity: string; namespace: string }

const WINDOW_POINTS: Record<"1h" | "6h" | "24h" | "7d", number> = {
  "1h": 120,
  "6h": 720,
  "24h": 1440,
  "7d": 1440,
}

export default function Dashboard({ ns }: PageProps) {
  const [win, setWin] = useState<"1h" | "6h" | "24h" | "7d">("24h")

  const redis = useApi<RedisStatus>(() => api.get("/api/v1/redis/status"), [], { pollMs: 15000 })
  const cdc = useApi<CdcSource[]>(() => api.get("/api/v1/cdc/sources"), [], { pollMs: 15000 })
  const entities = useApi<EntitySummary[]>(
    () => api.get(`/api/v1/schema/entities?namespace=${encodeURIComponent(ns)}`),
    [ns],
  )
  const [p95, setP95] = useState<{ name: string; value: number }[]>([])

  // 采样循环：30s 一次，命中/未命中落区间增量
  useEffect(() => {
    const sample = () => {
      sampleDelta("cache-hit", metricName("iris.cache.requests", ["result", "hit"]))
      sampleDelta("cache-miss", metricName("iris.cache.requests", ["result", "miss"]))
    }
    sample()
    const timer = setInterval(sample, 30000)
    return () => clearInterval(timer)
  }, [])

  // 重渲染以刷新采样曲线
  const [, setTick] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setTick((t) => t + 1), 30000)
    return () => clearInterval(timer)
  }, [])

  // P95 按实体拉取（走后端 /api/v1/metrics/query-latency——actuator 已无 PERCENTILE_*）
  useEffect(() => {
    fetchQueryLatency().then((rows) =>
      setP95(
        rows
          .filter((r) => r.p95 !== null)
          .map((r) => ({ name: r.entity, value: r.p95 as number })),
      ),
    )
  }, [entities.data])

  const hitTotal = seriesValues("cache-hit").reduce((a, b) => a + b, 0)
  const missTotal = seriesValues("cache-miss").reduce((a, b) => a + b, 0)
  const hitRate = hitTotal + missTotal > 0 ? (hitTotal / (hitTotal + missTotal)) * 100 : null

  const lagTotal = (cdc.data ?? []).reduce((a, s) => a + (s.lag ?? 0), 0)
  const pel = (cdc.data ?? []).reduce((a, s) => a + (s.pending ?? 0), 0)
  const cumulative = (cdc.data ?? []).reduce((a, s) => a + (s.streamLen ?? 0), 0)
  const dlq = (cdc.data ?? []).reduce((a, s) => a + (s.dlqLen ?? 0), 0)
  const cdcCountsKnown = (cdc.data ?? []).some((s) => s.lag !== null)

  const pt = WINDOW_POINTS[win]
  const hitSeries = seriesValues("cache-hit", pt)
  const missSeries = seriesValues("cache-miss", pt)
  const hasSamples = hitSeries.length > 1 || missSeries.length > 1

  return (
    <div className="flex flex-col gap-4">
      {/* Stat cards */}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <StatCard
          label="Redis 版本 / dbsize"
          value={redis.data?.redisVersion ?? "—"}
          sub={redis.data ? `dbsize ${redis.data.dbsize.toLocaleString()} keys` : "读取中"}
        />
        <StatCard
          label="内存占用"
          value={redis.data?.usedMemoryHuman ?? "—"}
          sub={redis.data ? `距上次 RDB 变更 ${redis.data.rdbChangesSinceLastSave} keys` : undefined}
        />
        <StatCard
          label="AOF / RDB 状态"
          value={redis.data?.aofEnabled ? "正常" : "—"}
          sub={
            redis.data
              ? `AOF ${redis.data.aofEnabled ? "on" : "off"} · RDB ${redis.data.rdbLastBgsaveStatus}`
              : undefined
          }
        />
        <StatCard
          label="缓存命中率"
          value={hitRate === null ? "—" : hitRate.toFixed(1)}
          unit={hitRate === null ? undefined : "%"}
          spark={hasSamples ? hitSeries : undefined}
          sparkTone="hit"
          sub={hitRate === null ? "暂无查询流量" : `命中 ${hitTotal} · 未命中 ${missTotal}`}
        />
        <StatCard
          label="CDC 积压"
          value={cdcCountsKnown ? lagTotal.toLocaleString() : "—"}
          sub={cdcCountsKnown ? `累计事件 ${cumulative.toLocaleString()} · PEL ${pel}` : "管道未运行"}
          sparkTone="warn"
        />
        <StatCard label="DLQ 计数" value={cdcCountsKnown ? dlq.toLocaleString() : "—"} alert={dlq > 0} sub={dlq > 0 ? "需要人工介入" : undefined} />
      </div>

      {/* Charts + health */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <div className="flex flex-col gap-4 xl:col-span-2">
          <Card>
            <CardHead
              title="缓存命中 / 未命中"
              desc="每 30s 采样一次区间增量 · 自部署起积累"
              right={
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
              }
            />
            <div className="p-4">
              {hasSamples ? (
                <LineChart
                  height={190}
                  labels={seriesLabels("cache-hit", 6)}
                  series={[
                    { name: "命中", tone: "hit", data: hitSeries },
                    { name: "未命中", tone: "miss", data: missSeries },
                  ]}
                />
              ) : (
                <EmptyState
                  icon="📈"
                  title="时序数据积累中"
                  desc="曲线由前端每 30 秒采样一次，部署后约 1 分钟即可看到首批数据点。"
                />
              )}
            </div>
          </Card>

          <Card>
            <CardHead title="查询延迟 P95" desc="按 entity 维度 · 单位 ms" />
            <div className="p-4">
              {p95.length > 0 ? (
                <BarChart
                  height={180}
                  groups={p95.map((r) => r.name)}
                  series={[{ name: "P95 (ms)", tone: "accent", data: p95.map((r) => Math.round(r.value ?? 0)) }]}
                />
              ) : (
                <EmptyState
                  icon="⏱"
                  title="暂无查询延迟数据"
                  desc="执行实体查询后，此处展示各实体 P95 延迟（iris.query 指标）。"
                />
              )}
            </div>
          </Card>
        </div>

        <div className="flex flex-col gap-4">
          <Card>
            <CardHead title="健康状态" />
            <div className="flex flex-col divide-y divide-border">
              {[
                { label: "应用 (Spring Boot)", status: redis.error ? "离线" : "在线", tone: redis.error ? ("error" as const) : ("hit" as const) },
                { label: "Redis 连接", status: redis.data ? "在线" : redis.loading ? "检测中" : "离线", tone: redis.data ? ("hit" as const) : redis.loading ? ("neutral" as const) : ("error" as const) },
                { label: "CDC 管道", status: cdcCountsKnown ? "运行中" : "未启用", tone: cdcCountsKnown ? ("hit" as const) : ("neutral" as const) },
                { label: "源库连接", status: cdcCountsKnown ? "经由 CDC" : "未启用", tone: cdcCountsKnown ? ("hit" as const) : ("neutral" as const) },
              ].map((r) => (
                <div key={r.label} className="flex items-center justify-between px-4 py-2.5">
                  <span className="flex items-center gap-2 text-[14px]">
                    <Dot tone={r.tone} /> {r.label}
                  </span>
                  <Badge tone={r.tone}>{r.status}</Badge>
                </div>
              ))}
            </div>
          </Card>

          <Card>
            <CardHead title="最近告警" desc="按严重程度排序" />
            <div className="p-4">
              <EmptyState
                icon="🔔"
                title="暂未接入告警事件流"
                desc="后端目前没有告警事件端点（日志/异常流），此卡片为占位展示。DLQ 积压与 CDC 异常请查看对应页面。"
              />
            </div>
          </Card>
        </div>
      </div>
    </div>
  )
}
