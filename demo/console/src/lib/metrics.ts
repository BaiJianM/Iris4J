// 指标接入：/actuator/metrics + 前端滚动采样（localStorage）
// 后端只暴露累计值；时序曲线由前端定时采样积累（部署起算，窗口 12h）

export type MetricSample = { t: number; v: number }

const SERIES_PREFIX = "redis-iris-java.series."
const MAX_POINTS = 1440 // 12h @ 30s

type Measurement = { statistic: string; value: number }

/**
 * 读取单个指标测量值。
 * - stat 缺省 "VALUE"（Gauge/Counter）
 * - Timer 用 "COUNT"/"TOTAL_TIME"（注意：Micrometer 1.17 起 actuator JSON
 *   不再输出 PERCENTILE_*，延迟分位走 fetchQueryLatency）
 */
export async function fetchMeasurement(name: string, stat = "VALUE"): Promise<number | null> {
  try {
    const res = await fetch(`/actuator/metrics/${name}`)
    if (!res.ok) return null
    const json = await res.json()
    const m = (json?.measurements as Measurement[] | undefined)?.find((x) => x.statistic === stat)
    return typeof m?.value === "number" ? m.value : null
  } catch {
    return null
  }
}

/**
 * 带 tag 的指标名助手：tags 为 key/value 交替数组。
 * actuator 要求每个 tag 形如 key:value、多个 tag 用多个 tag= 参数传递
 * （?tag=result:hit&tag=entity:customer）；逗号分隔会被 Spring 按 List
 * 绑定拆成多个裸 key 而 400。
 */
export function metricName(name: string, tags?: string[]): string {
  if (!tags || tags.length === 0) return name
  const pairs: string[] = []
  for (let i = 0; i + 1 < tags.length; i += 2) pairs.push(`${tags[i]}:${tags[i + 1]}`)
  return `${name}?${pairs.map((p) => `tag=${encodeURIComponent(p)}`).join("&")}`
}

export type QueryLatencyRow = {
  entity: string
  count: number
  p50: number | null
  p95: number | null
  p99: number | null
}

/**
 * 查询延迟分位（毫秒，按 entity 分组）。
 *
 * 注意：不能用 actuator /metrics 的 PERCENTILE_* —— Micrometer 1.17 起
 * percentile 不再出现在 actuator JSON measurements，后端提供了
 * 结构化端点 /api/v1/metrics/query-latency（IrisMetrics.takeSnapshot 通道）。
 */
export async function fetchQueryLatency(): Promise<QueryLatencyRow[]> {
  try {
    const apiKey = localStorage.getItem("redis-iris-java.apiKey") ?? ""
    const res = await fetch("/api/v1/metrics/query-latency", {
      headers: apiKey ? { "X-API-Key": apiKey } : {},
    })
    if (!res.ok) return []
    const rows = (await res.json()) as Array<Record<string, unknown>>
    return rows.map((r) => ({
      entity: String(r.entity ?? ""),
      count: Number(r.count ?? 0),
      p50: typeof r.p50 === "number" ? r.p50 : null,
      p95: typeof r.p95 === "number" ? r.p95 : null,
      p99: typeof r.p99 === "number" ? r.p99 : null,
    }))
  } catch {
    return []
  }
}

// 解析缓存：getSeries 在多个页面每次渲染都会被调用（30s tick 强制重渲染），
// 反复 localStorage 同步读 + 全量 JSON.parse 1440 点是纯浪费——原文不变直接复用
const seriesCache = new Map<string, { raw: string; parsed: MetricSample[] }>()

export function getSeries(key: string): MetricSample[] {
  try {
    const raw = localStorage.getItem(SERIES_PREFIX + key)
    if (!raw) return []
    const hit = seriesCache.get(key)
    if (hit && hit.raw === raw) return hit.parsed
    const parsed = JSON.parse(raw) as MetricSample[]
    if (seriesCache.size >= 100) seriesCache.clear()
    seriesCache.set(key, { raw, parsed })
    return parsed
  } catch {
    return []
  }
}

function saveSeries(key: string, series: MetricSample[]) {
  try {
    localStorage.setItem(SERIES_PREFIX + key, JSON.stringify(series))
  } catch {
    // 存储满：清掉该序列最老的一半再试一次
    try {
      localStorage.setItem(SERIES_PREFIX + key, JSON.stringify(series.slice(-MAX_POINTS / 2)))
    } catch {
      /* 彻底放弃，不影响主流程 */
    }
  }
}

function pushSample(key: string, v: number) {
  const series = getSeries(key)
  series.push({ t: Date.now(), v })
  while (series.length > MAX_POINTS) series.shift()
  saveSeries(key, series)
}

/** 采样一个指标（累计值原样落窗口） */
export async function sampleMetric(key: string, metric: string, stat = "VALUE"): Promise<number | null> {
  const v = await fetchMeasurement(metric, stat)
  if (v !== null) pushSample(key, v)
  return v
}

/**
 * 采样计数器并落"区间增量"：曲线表达的是每采样间隔内发生了多少次，
 * 而非单调上升的累计值（累计值曲线没有分析价值）。
 */
export async function sampleDelta(key: string, metric: string, stat = "COUNT"): Promise<number | null> {
  const v = await fetchMeasurement(metric, stat)
  if (v === null) return null
  const lastKey = SERIES_PREFIX + key + ".last"
  let delta: number
  try {
    const last = localStorage.getItem(lastKey)
    delta = last === null ? 0 : Math.max(0, v - Number(last))
  } catch {
    delta = 0
  }
  try {
    localStorage.setItem(lastKey, String(v))
  } catch {
    /* ignore */
  }
  pushSample(key, delta)
  return delta
}

/** 取序列的数值数组（图直接吃），可选截取最近 n 个点 */
export function seriesValues(key: string, n?: number): number[] {
  let s = getSeries(key)
  if (n && s.length > n) s = s.slice(-n)
  return s.map((p) => p.v)
}

/** 取序列的时间标签（用于 x 轴） */
export function seriesLabels(key: string, buckets = 6): string[] {
  const s = getSeries(key)
  if (s.length === 0) return []
  const out: string[] = []
  for (let i = 0; i < buckets; i++) {
    const idx = Math.min(s.length - 1, Math.floor((i / (buckets - 1)) * (s.length - 1)))
    const d = new Date(s[idx].t)
    out.push(`${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`)
  }
  return out
}
