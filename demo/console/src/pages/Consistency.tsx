import { Fragment, useState } from "react"
import {
  Badge,
  Button,
  Card,
  CardHead,
  EmptyState,
  Field,
  JsonTree,
  Modal,
  ReportPanel,
  Segmented,
  Select,
  Table,
  Td,
  Th,
  toast,
  Tr,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import type { PageProps } from "."

type CheckRequest = { namespace: string; entity: string; mode: "count" | "full"; tableName?: string }

type ConsistencyReport = {
  namespace: string
  entity: string
  mode: string
  sourceCount: number
  projectionCount: number
  missing: Record<string, unknown>[]
  orphan: Record<string, unknown>[]
  mismatched: Record<string, unknown>[]
}

type RepairResult = { namespace: string; entity: string; repairedMissing: number; repairedMismatched: number; removedOrphan: number }

export default function Consistency({ ns }: PageProps) {
  const [mode, setMode] = useState<"count" | "full">("count")
  const [entity, setEntity] = useState("")
  const [report, setReport] = useState<ConsistencyReport | null>(null)
  const [repairOpen, setRepairOpen] = useState(false)
  const [repaired, setRepaired] = useState<RepairResult | null>(null)
  const [checking, setChecking] = useState(false)
  const [repairing, setRepairing] = useState(false)
  const [expanded, setExpanded] = useState<string | null>(null)

  const entities = useApi<{ entity: string }[]>(
    () => api.get(`/api/v1/schema/entities?namespace=${encodeURIComponent(ns)}`),
    [ns],
  )
  const list = entities.data ?? []
  const target = entity || list[0]?.entity || ""

  const driftTotal = report
    ? report.missing.length + report.orphan.length + report.mismatched.length
    : 0

  const runCheck = async () => {
    if (!target) return
    setChecking(true)
    try {
      const req: CheckRequest = { namespace: ns, entity: target, mode }
      const res = await api.post<ConsistencyReport>("/api/v1/admin/consistency/check", req)
      setReport(res)
      setRepaired(null)
      setExpanded(null)
    } catch (e) {
      setReport(null)
      const msg = e instanceof Error ? e.message : String(e)
      toast(msg.includes("500") ? "校验失败（源库连接未配置或源库不可达）" : msg, "error")
    } finally {
      setChecking(false)
    }
  }

  const runRepair = async () => {
    if (!target) return
    setRepairing(true)
    try {
      const res = await api.post<RepairResult>("/api/v1/admin/consistency/repair", {
        namespace: ns,
        entity: target,
        mode,
      })
      setRepaired(res)
      setRepairOpen(false)
      toast(`已修复 ${res.repairedMissing + res.repairedMismatched} · 清理孤儿 ${res.removedOrphan}`, "warn")
      // 修复后自动复查
      runCheck()
    } catch (e) {
      toast(e instanceof Error ? e.message : "修复失败", "error")
    } finally {
      setRepairing(false)
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHead title="漂移校验" desc="比对源库与 Redis 投影（源库为准）· POST /api/v1/admin/consistency/check" />
        <div className="flex flex-wrap items-end gap-3 p-4">
          <Field label="命名空间">
            <Select value={ns} disabled>
              <option>{ns}</option>
            </Select>
          </Field>
          <Field label="实体">
            <Select value={target} onChange={(e) => setEntity(e.target.value)}>
              {list.map((e) => (
                <option key={e.entity} value={e.entity}>
                  {e.entity}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="模式">
            <Segmented
              value={mode}
              onChange={setMode}
              options={[
                { value: "count", label: "count 计数" },
                { value: "full", label: "full 全量" },
              ]}
            />
          </Field>
          <Button variant="primary" className="ml-auto" onClick={runCheck} disabled={!target || checking}>
            {checking ? "校验中…" : "执行校验"}
          </Button>
        </div>
      </Card>

      {report && (
        <>
          <Card>
            <CardHead
              title="校验报告"
              desc={`mode=${report.mode} · 源库 ${report.sourceCount.toLocaleString()} / 投影 ${report.projectionCount.toLocaleString()}`}
              right={
                driftTotal > 0 ? (
                  <Badge tone="warn">发现 {driftTotal} 条漂移</Badge>
                ) : (
                  <Badge tone="hit">无漂移</Badge>
                )
              }
            />
            {driftTotal === 0 ? (
              <div className="p-4">
                <EmptyState icon="≍" title="源库与投影一致" desc="未发现缺失 / 孤儿 / 值不一致。" />
              </div>
            ) : (
              <Table>
                <thead>
                  <tr>
                    <Th>差异类型</Th>
                    <Th align="right">条数</Th>
                    <Th>明细</Th>
                  </tr>
                </thead>
                <tbody>
                  {(
                    [
                      { key: "missing", label: "missing（源库有 · 投影缺）", rows: report.missing },
                      { key: "orphan", label: "orphan（投影有 · 源库无）", rows: report.orphan },
                      { key: "mismatched", label: "mismatched（值不一致）", rows: report.mismatched },
                    ] as const
                  ).map((g) => (
                    <Fragment key={g.key}>
                      <Tr
                        onClick={g.rows.length ? () => setExpanded(expanded === g.key ? null : g.key) : undefined}
                        active={expanded === g.key}
                      >
                        <Td mono>{g.label}</Td>
                        <Td mono align="right" className={g.rows.length > 0 ? "text-warn" : "text-hit"}>
                          {g.rows.length}
                        </Td>
                        <Td className={g.rows.length ? "text-accent" : "text-fg-subtle"}>
                          {g.rows.length ? "查看明细 ▾" : "—"}
                        </Td>
                      </Tr>
                      {expanded === g.key && g.rows.length > 0 && (
                        <tr>
                          <td colSpan={3} className="border-b border-border bg-bg p-3">
                            <div className="max-h-64 overflow-y-auto rounded border border-border bg-card p-2">
                              <JsonTree data={g.rows.slice(0, 20)} />
                            </div>
                            {g.rows.length > 20 && (
                              <div className="mt-1 text-[12px] text-fg-subtle">仅展示前 20 条，共 {g.rows.length} 条</div>
                            )}
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </Table>
            )}
          </Card>

          <Card danger>
            <CardHead title="修复投影" desc="以源库为准重写 Redis · POST /api/v1/admin/consistency/repair" />
            <div className="flex flex-col gap-3 p-4">
              <div className="rounded border border-error/50 bg-error/10 px-3 py-2 text-[14px] text-error">
                ⚠ 修复将<b>以源库为准重写 Redis 投影</b>，覆盖当前 {driftTotal} 条漂移记录，操作不可逆。
              </div>
              <Button variant="danger" className="w-fit" onClick={() => setRepairOpen(true)}>
                执行修复
              </Button>
              {repaired && (
                <ReportPanel
                  title="修复结果"
                  items={[
                    { label: "补齐缺失", value: repaired.repairedMissing, tone: "hit" },
                    { label: "修正不一致", value: repaired.repairedMismatched, tone: "hit" },
                    { label: "清理孤儿", value: repaired.removedOrphan, tone: repaired.removedOrphan > 0 ? "warn" : "hit" },
                  ]}
                />
              )}
            </div>
          </Card>
        </>
      )}

      <Modal
        open={repairOpen}
        onClose={() => setRepairOpen(false)}
        title="确认重写投影"
        danger
        footer={
          <>
            <Button variant="ghost" onClick={() => setRepairOpen(false)}>
              取消
            </Button>
            <Button variant="danger" onClick={runRepair} disabled={repairing}>
              {repairing ? "修复中…" : "我确认重写"}
            </Button>
          </>
        }
      >
        将以源库当前值覆盖 Redis 中 <span className="font-mono text-fg">{driftTotal}</span> 条投影记录。若源库在校验后发生变更，可能引入新的不一致。
      </Modal>
    </div>
  )
}
