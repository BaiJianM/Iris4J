import { Fragment, useCallback, useEffect, useRef, useState } from "react"
import {
  Badge,
  BarChart,
  Button,
  Card,
  CardHead,
  EmptyState,
  Gauge,
  JsonTree,
  Modal,
  ReportPanel,
  Select,
  Table,
  TableSummary,
  Td,
  Th,
  Tr,
  toast,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import { metricName, sampleDelta, seriesLabels, seriesValues } from "../lib/metrics"
import type { PageProps } from "."

type DlqEntry = {
  namespace: string
  entity: string
  dlqStream: string
  dlqId: string
  originStream: string
  originGroup: string
  originMsgId: string
  msgKey: string
  payload: string | null
  deliveryCount: number
  failedAt: string
  error: string
}

type SourceView = {
  index: number
  namespace: string
  stream: string
  entity: string
  group: string
  consumer: string
  /** XLEN：事件总量（历史累计，只增不减） */
  streamLen: number | null
  /** 主消费组未投递事件数：真实积压 */
  lag: number | null
  pending: number | null
  dlqLen: number | null
}

type PendingView = { id: string; consumer: string; idleMs: number; deliveryCount: number }

export default function Cdc({ ns }: PageProps) {
  const [expanded, setExpanded] = useState<string | null>(null)
  const [replayOpen, setReplayOpen] = useState(false)
  const [report, setReport] = useState<null | { replayed: number; left: number }>(null)
  const [replaying, setReplaying] = useState(false)
  const [entityFilter, setEntityFilter] = useState("")
  const [limit, setLimit] = useState(50)
  const [pelSource, setPelSource] = useState<number | null>(null)
  const pelSourceRef = useRef<number | null>(null)
  const [pel, setPel] = useState<PendingView[] | null>(null)

  const dlq = useApi<DlqEntry[]>(
    () =>
      api.get<DlqEntry[]>(
        `/api/v1/cdc/dlq?namespace=${encodeURIComponent(ns)}&limit=${limit}` +
          (entityFilter ? `&entity=${encodeURIComponent(entityFilter)}` : ""),
      ),
    [ns, entityFilter, limit],
  )
  const sources = useApi<SourceView[]>(() => api.get("/api/v1/cdc/sources"), [], { pollMs: 15000 })

  // 事件 ok/fail 采样
  const [entities, setEntities] = useState<{ entity: string }[]>([])
  useEffect(() => {
    api
      .get<{ entity: string }[]>(`/api/v1/schema/entities?namespace=${encodeURIComponent(ns)}`)
      .then((l) => setEntities(l))
      .catch(() => setEntities([]))
  }, [ns])

  useEffect(() => {
    const sample = () => {
      for (const e of entities) {
        sampleDelta(`cdc-ok-${e.entity}`, metricName("iris.cdc.events", ["entity", e.entity, "result", "ok"]))
        sampleDelta(`cdc-fail-${e.entity}`, metricName("iris.cdc.events", ["entity", e.entity, "result", "fail"]))
      }
    }
    sample()
    const timer = setInterval(sample, 30000)
    return () => clearInterval(timer)
  }, [entities])
  const [, setTick] = useState(0)
  useEffect(() => {
    const timer = setInterval(() => setTick((t) => t + 1), 30000)
    return () => clearInterval(timer)
  }, [])

  const sourceList = sources.data ?? []
  const lagTotal = sourceList.reduce((a, s) => a + (s.lag ?? 0), 0)
  const pelTotal = sourceList.reduce((a, s) => a + (s.pending ?? 0), 0)
  const known = sourceList.some((s) => s.lag !== null)

  const showPel = useCallback(async (index: number) => {
    const target = pelSourceRef.current === index ? null : index
    pelSourceRef.current = target
    setPelSource(target)
    setPel(null)
    try {
      const res = await api.get<PendingView[]>(`/api/v1/cdc/sources/${index}/pel?limit=50`)
      if (pelSourceRef.current !== index) return // 已切换到其他 source，丢弃旧响应
      setPel(res)
    } catch (e) {
      if (pelSourceRef.current === index) {
        toast(e instanceof Error ? e.message : "PEL 读取失败", "error")
      }
    }
  }, [])

  const doReplay = useCallback(async () => {
    setReplaying(true)
    try {
      const res = await api.post<{ replayed: number }>(
        `/api/v1/cdc/dlq/replay?namespace=${encodeURIComponent(ns)}` +
          (entityFilter ? `&entity=${encodeURIComponent(entityFilter)}` : ""),
      )
      const left = (dlq.data?.length ?? 0) - res.replayed
      setReport({ replayed: res.replayed, left: Math.max(0, left) })
      toast(`重放完成：成功 ${res.replayed}`, res.replayed > 0 ? "hit" : "warn")
      dlq.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "重放失败", "error")
    } finally {
      setReplaying(false)
      setReplayOpen(false)
    }
  }, [ns, entityFilter, dlq])

  const entries = dlq.data ?? []

  return (
    <div className="flex flex-col gap-4">
      {/* DLQ */}
      <Card>
        <CardHead
          title="死信队列 (DLQ)"
          desc="处理失败、已达最大投递次数的 CDC 事件"
          right={
            <Button variant="danger" size="sm" onClick={() => setReplayOpen(true)} disabled={entries.length === 0}>
              重放当前过滤
            </Button>
          }
        />
        <div className="flex items-center gap-2 border-b border-border px-4 py-2.5">
          <Select className="w-40" value={entityFilter} onChange={(e) => setEntityFilter(e.target.value)}>
            <option value="">全部 entity</option>
            {entities.map((e) => (
              <option key={e.entity} value={e.entity}>
                {e.entity}
              </option>
            ))}
          </Select>
          <span className="ml-auto text-[13px] text-fg-muted">条数</span>
          <Select className="w-20" value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
            {[50, 100, 200].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </Select>
        </div>
        <TableSummary count={entries.length} />
        <Table>
          <thead>
            <tr>
              <Th>实体</Th>
              <Th>主键</Th>
              <Th>投递次数</Th>
              <Th>错误摘要</Th>
              <Th align="right">时间</Th>
            </tr>
          </thead>
          <tbody>
            {entries.map((r) => (
              <Fragment key={r.dlqId}>
                <Tr onClick={() => setExpanded(expanded === r.dlqId ? null : r.dlqId)} active={expanded === r.dlqId}>
                  <Td>
                    <Badge tone="neutral" mono>
                      {r.entity}
                    </Badge>
                  </Td>
                  <Td mono>{r.msgKey}</Td>
                  <Td mono>{r.deliveryCount}</Td>
                  <Td className="text-error">{r.error}</Td>
                  <Td mono align="right" className="text-fg-muted">
                    {r.failedAt}
                  </Td>
                </Tr>
                {expanded === r.dlqId && (
                  <tr>
                    <td colSpan={5} className="border-b border-border bg-bg p-3">
                      <div className="mb-1.5 text-[12px] text-fg-muted">
                        事件载荷 · dlqId <span className="font-mono">{r.dlqId}</span> · origin{" "}
                        <span className="font-mono">{r.originStream}</span>
                      </div>
                      <JsonTree
                        data={{
                          payload: r.payload ? safeJson(r.payload) : null,
                          error: r.error,
                          deliveryCount: r.deliveryCount,
                          failedAt: r.failedAt,
                        }}
                      />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </Table>
        {entries.length === 0 && !dlq.loading && (
          <div className="p-4">
            <EmptyState icon="✓" title="死信队列为空" desc="没有需要人工介入的失败事件。" />
          </div>
        )}
        {report && (
          <div className="p-4">
            <ReportPanel
              title="重放结果"
              tone={report.left > 0 ? "warn" : "hit"}
              items={[
                { label: "成功重放", value: report.replayed, tone: "hit" },
                { label: "残留 DLQ", value: report.left, tone: report.left > 0 ? "warn" : "hit" },
              ]}
            />
          </div>
        )}
      </Card>

      {/* Backlog panel */}
      <Card>
        <CardHead title="管道积压" desc="按配置的 source 逐行展示（XLEN / XPENDING / DLQ）" />
        <div className="p-4">
          {sourceList.length === 0 ? (
            <EmptyState icon="⇄" title="未配置 CDC source" desc="application.yml 的 iris.cdc.sources 为空，管道未运行。" />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Stream</Th>
                  <Th>实体</Th>
                  <Th>消费组</Th>
                  <Th align="right">积压 (lag)</Th>
                  <Th align="right">待确认（PEL）</Th>
                  <Th align="right">死信（DLQ）</Th>
                  <Th align="right">PEL 明细</Th>
                </tr>
              </thead>
              <tbody>
                {sourceList.map((s) => (
                  <Fragment key={s.stream}>
                    <Tr>
                      <Td mono className="max-w-[280px] truncate">
                        {s.stream}
                      </Td>
                      <Td>
                        <Badge tone="neutral" mono>
                          {s.entity}
                        </Badge>
                      </Td>
                      <Td mono className="text-fg-muted">
                        {s.group}
                      </Td>
                      <Td mono align="right" className="whitespace-nowrap">
                        {s.lag ?? "未知"}
                        <span className="ml-1 text-[12px] text-fg-muted">
                          / {s.streamLen != null ? s.streamLen.toLocaleString() : "?"} 总量
                        </span>
                      </Td>
                      <Td mono align="right">
                        {s.pending ?? "未知"}
                      </Td>
                      <Td mono align="right" className={(s.dlqLen ?? 0) > 0 ? "text-error" : ""}>
                        {s.dlqLen ?? "未知"}
                      </Td>
                      <Td align="right">
                        <Button size="sm" variant="ghost" onClick={() => showPel(s.index)}>
                          {pelSource === s.index ? "收起" : "查看"}
                        </Button>
                      </Td>
                    </Tr>
                    {pelSource === s.index && (
                      <tr>
                        <td colSpan={7} className="border-b border-border bg-bg p-3">
                          <div className="mb-1.5 text-[12px] text-fg-muted">已投递未确认消息（PEL）</div>
                          {pel && pel.length > 0 ? (
                            <Table>
                              <thead>
                                <tr>
                                  <Th>消息 ID</Th>
                                  <Th>消费者</Th>
                                  <Th align="right">空闲时长</Th>
                                  <Th align="right">投递次数</Th>
                                </tr>
                              </thead>
                              <tbody>
                                {pel.map((p) => (
                                  <Tr key={p.id}>
                                    <Td mono>{p.id}</Td>
                                    <Td mono>{p.consumer}</Td>
                                    <Td mono align="right">
                                      {(p.idleMs / 1000).toFixed(1)}s
                                    </Td>
                                    <Td mono align="right">
                                      {p.deliveryCount}
                                    </Td>
                                  </Tr>
                                ))}
                              </tbody>
                            </Table>
                          ) : (
                            <div className="text-[13px] text-fg-subtle">
                              {pel ? "PEL 为空——全部消息已确认" : "读取中…"}
                            </div>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </Table>
          )}
        </div>
        {known && (
          <div className="grid grid-cols-2 gap-3 border-t border-border p-4">
            <div className="rounded-md border border-border bg-card p-2">
              <Gauge value={lagTotal} max={Math.max(500, lagTotal)} label="积压 (lag)" tone="warn" />
            </div>
            <div className="rounded-md border border-border bg-card p-2">
              <Gauge value={pelTotal} max={Math.max(100, pelTotal)} label="PEL" tone="accent" />
            </div>
          </div>
        )}
      </Card>

      {/* Event success */}
      <Card>
        <CardHead title="事件成功率" desc="ok / fail 每 30s 采样区间增量（按实体聚合）" />
        <div className="p-4">
          {entities.length > 0 && seriesValues(`cdc-ok-${entities[0].entity}`).length > 1 ? (
            <BarChart
              height={190}
              groups={seriesLabels(`cdc-ok-${entities[0].entity}`, 6)}
              series={[
                {
                  name: "ok",
                  tone: "hit",
                  data: entities[0] ? sumEntities(entities, "cdc-ok") : [],
                },
                { name: "fail", tone: "error", data: sumEntities(entities, "cdc-fail") },
              ]}
            />
          ) : (
            <EmptyState icon="📈" title="时序数据积累中" desc="CDC 事件流入后约 1 分钟可见首批数据点。" />
          )}
        </div>
      </Card>

      <Modal
        open={replayOpen}
        onClose={() => setReplayOpen(false)}
        title="重放死信事件"
        danger
        footer={
          <>
            <Button variant="ghost" onClick={() => setReplayOpen(false)}>
              取消
            </Button>
            <Button variant="danger" onClick={doReplay} disabled={replaying}>
              {replaying ? "重放中…" : "确认重放"}
            </Button>
          </>
        }
      >
        将把当前过滤下的 <span className="font-mono text-fg">{entries.length}</span> 条死信事件重新投递到 CDC 管道。重放可能触发重复写入，
        <span className="text-error">请确认下游具备幂等能力</span>。
      </Modal>
    </div>
  )
}

function safeJson(s: string): unknown {
  try {
    return JSON.parse(s)
  } catch {
    return s
  }
}

/** 按实体序列逐点求和（各实体采样点数一致） */
function sumEntities(entities: { entity: string }[], prefix: string): number[] {
  const seriesList = entities.map((e) => seriesValues(`${prefix}-${e.entity}`))
  const maxLen = Math.max(0, ...seriesList.map((s) => s.length))
  const out: number[] = []
  for (let i = 0; i < maxLen; i++) {
    out.push(seriesList.reduce((a, s) => a + (s[s.length - maxLen + i] ?? 0), 0))
  }
  return out
}
