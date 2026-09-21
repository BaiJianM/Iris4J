import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  Badge,
  Button,
  Card,
  CardHead,
  cx,
  Drawer,
  EmptyState,
  Input,
  JsonTree,
  LockCell,
  Pagination,
  Segmented,
  Select,
  Table,
  TableSummary,
  Tabs,
  Td,
  Th,
  Tr,
  toast,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import type { PageProps } from "."

type EntitySummary = {
  namespace: string
  entity: string
  primaryKeys: string[]
  fieldCount: number
  indexedFields: number
  relatedFields: number
  tenantField: string
  accessTagField: string
}

type FieldSchema = {
  name: string
  type: string
  indexed: boolean
  tags: string[] | null
  index: string | null
  description: string | null
  relatedEntity: string | null
}

type EntityDetail = {
  namespace: string
  entity: string
  primaryKeys: string[]
  fields: FieldSchema[]
  tenantField: string | null
  accessTagField: string | null
}

type QueryPage = {
  items: Record<string, unknown>[]
  total: number | null
  page: number
  pageSize: number
}

type Cond = { id: number; field: string; op: "=" | "range" | "text"; value: string }

/** 字段是否可下推过滤（索引字段才能进 WHERE，性能红线） */
function isIndexable(f: FieldSchema) {
  return f.indexed || f.index != null
}

export default function Query({ ns }: PageProps) {
  const entities = useApi<EntitySummary[]>(
    () => api.get(`/api/v1/schema/entities?namespace=${encodeURIComponent(ns)}`),
    [ns],
  )
  const list = entities.data ?? []
  const [active, setActive] = useState<string | null>(null)
  const entityName = active ?? list[0]?.entity ?? null

  const detail = useApi<EntityDetail | null>(
    async () => (entityName ? api.get(`/api/v1/schema/${encodeURIComponent(ns)}/${encodeURIComponent(entityName)}`) : null),
    [ns, entityName],
  )

  const pk = detail.data?.primaryKeys?.[0] ?? "id"
  const fields = detail.data?.fields ?? []
  const indexable = fields.filter(isIndexable)
  const hasTextField = fields.some((f) => f.index === "text")

  const [conds, setConds] = useState<Cond[]>([])
  const [textField, setTextField] = useState("")
  const [fieldPrune, setFieldPrune] = useState("all")
  const [tenant, setTenant] = useState("")
  const [pageSize, setPageSize] = useState(20)
  const [page, setPage] = useState(1)
  const [result, setResult] = useState<QueryPage | null>(null)
  const [querying, setQuerying] = useState(false)
  const [tookMs, setTookMs] = useState<number | null>(null)
  const [drawerRow, setDrawerRow] = useState<Record<string, unknown> | null>(null)

  // 切实体/namespace 时重置条件与页码
  useEffect(() => {
    setConds([])
    setPage(1)
    setResult(null)
  }, [entityName, ns])

  const addCond = () =>
    setConds((c) => [
      ...c,
      { id: Date.now(), field: indexable[0]?.name ?? pk, op: "=", value: "" },
    ])

  const buildBody = useCallback(
    (p: number, tenantOverride?: string) => {
      const filters: Record<string, unknown> = {}
      const rangeFilters: Record<string, { min?: number; max?: number }> = {}
      const textFilters: Record<string, string> = {}
      for (const c of conds) {
        if (c.value === "") continue
        if (c.op === "=") filters[c.field] = c.value
        else if (c.op === "range") {
          const [min, max] = c.value.split(",").map((x) => x.trim())
          rangeFilters[c.field] = {
            min: min === "" || isNaN(Number(min)) ? undefined : Number(min),
            max: max === "" || isNaN(Number(max)) ? undefined : Number(max),
          }
        } else textFilters[c.field] = c.value
      }
      if (textField.trim() !== "") {
        const tf = fields.find((f) => f.index === "text")
        if (tf) textFilters[tf.name] = textField.trim()
      }
      const body: Record<string, unknown> = {
        namespace: ns,
        page: p,
        pageSize,
      }
      if (Object.keys(filters).length) body.filters = filters
      if (Object.keys(rangeFilters).length) body.rangeFilters = rangeFilters
      if (Object.keys(textFilters).length) body.textFilters = textFilters
      if (tenantOverride != null && tenantOverride.trim() !== "") body.tenant = tenantOverride.trim()
      else if (tenant.trim() !== "") body.tenant = tenant.trim()
      if (fieldPrune === "indexed") body.fields = fields.filter(isIndexable).map((f) => f.name)
      else if (fieldPrune === "pk") body.fields = detail.data?.primaryKeys
      return body
    },
    [conds, textField, fields, ns, pageSize, tenant, fieldPrune, detail.data],
  )

  // 查询序号守卫：快速切换实体/连点查询时旧响应后到会覆盖新结果
  const querySeqRef = useRef(0)
  const runQuery = useCallback(
    async (p: number) => {
      if (!entityName) return
      const tf = detail.data?.tenantField
      // tenant 输入留空时，若条件里显式带了 tenant_id = x，自动取该值作 tenant 参数
      // （后端语义：filters 显式租户与 tenant 参数一致时放行）
      const condTenant = tf
        ? conds.find((c) => c.field === tf && c.op === "=" && c.value.trim() !== "")?.value.trim()
        : undefined
      const effectiveTenant = tenant.trim() !== "" ? tenant.trim() : (condTenant ?? "")
      // 多租户实体后端强制要求显式 tenant（IRIS-1004），本地先拦截给出可读提示
      if (tf && effectiveTenant === "") {
        toast(`该实体为多租户实体（${tf}），请填写租户（如 t1）`, "error")
        return
      }
      const seq = ++querySeqRef.current
      setQuerying(true)
      const t0 = performance.now()
      try {
        const body = { ...buildBody(p, effectiveTenant) }
        const res = await api.post<QueryPage>(
          `/api/v1/entities/${encodeURIComponent(entityName)}/query`,
          body,
        )
        if (seq !== querySeqRef.current) return // 旧响应后到，丢弃
        setResult(res)
        setPage(p)
      } catch (e) {
        if (seq !== querySeqRef.current) return
        // 主键精确查询无结果时后端按语义返回 404「实体不存在」——
        // 控制台语境下等价于"无匹配"，显示空结果而非报错
        if (e instanceof Error && "status" in e && (e as { status: number }).status === 404) {
          setResult({ items: [], total: 0, page: p, pageSize })
          setPage(p)
        } else {
          setResult(null)
          toast(e instanceof Error ? `查询失败：${e.message}` : "查询失败", "error")
        }
      } finally {
        if (seq === querySeqRef.current) {
          setTookMs(Math.round(performance.now() - t0))
          setQuerying(false)
        }
      }
    },
    [entityName, buildBody, detail.data, tenant, conds, pageSize],
  )

  // 实体切换后自动首查
  useEffect(() => {
    if (entityName) runQuery(1)
  }, [entityName, ns]) // eslint-disable-line react-hooks/exhaustive-deps

  const columns = useMemo(() => {
    if (result?.items?.length) return Object.keys(result.items[0])
    return fields.map((f) => f.name)
  }, [result, fields])

  const total = result?.total ?? null
  const pages = total != null ? Math.max(1, Math.ceil(total / (result?.pageSize ?? pageSize))) : 1

  return (
    <div className="flex gap-4">
      {/* Entity list */}
      <div className="relative w-60 shrink-0">
        <Card className="absolute inset-0 flex flex-col overflow-hidden">
          <CardHead title="实体" desc={`${list.length} 个已注册`} />
          <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-1.5">
          {list.length === 0 && entities.error && (
            <div className="px-2.5 py-3 text-[13px] text-error">加载失败：{entities.error}</div>
          )}
          {list.map((e) => (
            <button
              key={e.entity}
              onClick={() => setActive(e.entity)}
              title={e.entity}
              className={cx(
                "flex items-center justify-between gap-2 rounded px-2.5 py-2 text-left transition-colors",
                entityName === e.entity ? "bg-accent/12" : "hover:bg-card-hover",
              )}
            >
              <div className="min-w-0 flex-1">
                <div className={cx("truncate font-mono text-[14px]", entityName === e.entity ? "text-accent" : "text-fg")}>{e.entity}</div>
                <div className="text-[12px] text-fg-subtle">{e.fieldCount} 字段 · 索引 {e.indexedFields}</div>
              </div>
              <Badge tone="readonly" mono className="shrink-0">
                🔑 {e.primaryKeys[0]}
              </Badge>
            </button>
          ))}
          </div>
        </Card>
      </div>

      {/* Right column */}
      <div className="flex min-w-0 flex-1 flex-col gap-4">
        {/* Filter builder */}
        <Card>
          <CardHead
            title={<span className="font-mono">query_{entityName ?? "…"}</span>}
            desc="构建等值 / 范围 / 全文过滤条件（仅索引字段可下推）"
          />
          <div className="flex flex-col gap-3 p-4">
            <div className="flex flex-col gap-2">
              {conds.map((c) => {
                const idx = indexable.some((f) => f.name === c.field)
                const fld = fields.find((f) => f.name === c.field)
                const numeric = fld?.type === "INT" || fld?.type === "LONG" || fld?.type === "DOUBLE" || fld?.type === "FLOAT"
                const isText = fld?.index === "text"
                return (
                  <div key={c.id} className="flex items-center gap-2">
                    <Select
                      value={c.field}
                      onChange={(e) =>
                        setConds((cs) =>
                          cs.map((x) => {
                            if (x.id !== c.id) return x
                            const nf = fields.find((f) => f.name === e.target.value)
                            const numeric = nf?.type === "INT" || nf?.type === "LONG" || nf?.type === "DOUBLE" || nf?.type === "FLOAT"
                            const op: Cond["op"] = nf?.index === "text" ? "text" : numeric ? "range" : "="
                            return { ...x, field: e.target.value, op }
                          }),
                        )
                      }
                      className="w-40"
                    >
                      {fields.map((fl) => (
                        <option key={fl.name} value={fl.name} disabled={!isIndexable(fl)}>
                          {fl.name}
                          {!isIndexable(fl) ? " （未建索引）" : ""}
                        </option>
                      ))}
                    </Select>
                    <Select
                      value={c.op}
                      onChange={(e) =>
                        setConds((cs) => cs.map((x) => (x.id === c.id ? { ...x, op: e.target.value as Cond["op"] } : x)))
                      }
                      className="w-16"
                      title="切换操作符"
                    >
                      <option value="=">=</option>
                      <option value="range" disabled={!numeric}>范围</option>
                      <option value="text" disabled={!isText}>全文</option>
                    </Select>
                    <Input
                      value={c.value}
                      placeholder={c.op === "range" ? "min,max" : c.op === "text" ? "关键词…" : "值"}
                      onChange={(e) =>
                        setConds((cs) => cs.map((x) => (x.id === c.id ? { ...x, value: e.target.value } : x)))
                      }
                      className="h-8 max-w-xs"
                    />
                    <Button variant="ghost" size="sm" onClick={() => setConds((cs) => cs.filter((x) => x.id !== c.id))}>
                      ✕
                    </Button>
                    {!idx && <Badge tone="error">未索引</Badge>}
                  </div>
                )
              })}
              <button onClick={addCond} className="w-fit text-[13px] text-accent hover:underline" disabled={indexable.length === 0}>
                + 添加条件
              </button>
            </div>

            <div className="flex flex-wrap items-end gap-3 border-t border-border pt-3">
              <label className="flex flex-col gap-1">
                <span className="text-[12px] text-fg-muted">全文检索{hasTextField ? "" : "（需 TEXT 字段）"}</span>
                <Input
                  value={textField}
                  onChange={(e) => setTextField(e.target.value)}
                  placeholder="search_by_text…"
                  className="w-56"
                  disabled={!hasTextField}
                />
              </label>
              <label className="flex flex-col gap-1">
                <span className="text-[12px] text-fg-muted">字段裁剪</span>
                <Select value={fieldPrune} onChange={(e) => setFieldPrune(e.target.value)} className="w-44">
                  <option value="all">全部字段</option>
                  <option value="indexed">仅索引字段</option>
                  <option value="pk">仅主键</option>
                </Select>
              </label>
              <label className="flex flex-col gap-1">
                <span className="text-[12px] text-fg-muted">
                  tenant{detail.data?.tenantField ? `（必填 · ${detail.data.tenantField}）` : "（该实体无租户字段）"}
                </span>
                <Input
                  value={tenant}
                  onChange={(e) => setTenant(e.target.value)}
                  placeholder={detail.data?.tenantField ? "如 t1" : "留空=默认"}
                  className="w-32"
                  disabled={!detail.data?.tenantField}
                />
              </label>
              <div className="ml-auto flex items-end gap-2">
                <label className="flex flex-col gap-1">
                  <span className="text-[12px] text-fg-muted">页码 / 每页条数</span>
                  <div className="flex gap-1">
                    <Select
                      value={pageSize}
                      onChange={(e) => setPageSize(Number(e.target.value))}
                      className="w-16"
                    >
                      {[10, 20, 50].map((s) => (
                        <option key={s} value={s}>
                          {s}
                        </option>
                      ))}
                    </Select>
                  </div>
                </label>
                <Button variant="primary" onClick={() => runQuery(1)} disabled={!entityName || querying}>
                  ⚲ {querying ? "查询中…" : "查询"}
                </Button>
              </div>
            </div>
          </div>
        </Card>

        {/* Result table */}
        <Card>
          <TableSummary
            count={total ?? result?.items?.length ?? 0}
            ms={tookMs ?? undefined}
            right={total == null ? <span>total 未知（分页下推关闭时）</span> : <span>page {result?.page} · {result?.pageSize} / 页</span>}
          />
          {result && result.items.length > 0 ? (
            <>
              <Table>
                <thead>
                  <tr>
                    {columns.map((c) => (
                      <Th key={c} className={cx(c === pk && "font-semibold")}>
                        {c}
                      </Th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.items.map((row, i) => {
                    const key = String(row[pk] ?? i)
                    return (
                      <Tr key={key} onClick={() => setDrawerRow(row)} active={!!drawerRow && String(drawerRow[pk] ?? "") === key}>
                        {columns.map((c) => {
                          const v = row[c]
                          // 被裁剪字段（值为 null/缺失且 schema 声明了 tags）显示锁形
                          const pruned = (v === null || v === undefined) && fields.find((f) => f.name === c)?.tags?.length
                          return (
                            <Td key={c} mono={typeof v === "number"}>
                              {pruned ? <LockCell /> : v === null || v === undefined ? <span className="text-fg-subtle">—</span> : String(v)}
                            </Td>
                          )
                        })}
                      </Tr>
                    )
                  })}
                </tbody>
              </Table>
              <Pagination page={page} pages={pages} pageSize={result?.pageSize ?? pageSize} onPage={(p) => runQuery(p)} />
            </>
          ) : (
            <div className="p-4">
              <EmptyState
                icon="⚲"
                title={querying ? "查询中…" : result ? "无匹配数据" : "构建条件后点击查询"}
                desc={result?.items?.length === 0 ? "条件过严或该实体暂无数据；agent 无访问权限时 fail-closed 也会返回空结果。" : undefined}
              />
            </div>
          )}
        </Card>
      </div>

      <RowDrawer
        ns={ns}
        entity={entityName}
        pk={pk}
        row={drawerRow}
        onClose={() => setDrawerRow(null)}
      />
    </div>
  )
}

function RowDrawer({
  ns,
  entity,
  pk,
  row,
  onClose,
}: {
  ns: string
  entity: string | null
  pk: string
  row: Record<string, unknown> | null
  onClose: () => void
}) {
  const [tab, setTab] = useState<"json" | "rel">("json")
  const [graph, setGraph] = useState(false)
  const id = row ? String(row[pk] ?? "") : ""
  const related = useApi<{ relations: Record<string, unknown>[] } | null>(
    async () =>
      row && entity
        ? api.get(
            `/api/v1/entities/${encodeURIComponent(entity)}/${encodeURIComponent(id)}/related?namespace=${encodeURIComponent(ns)}`,
          )
        : null,
    [row, entity, ns],
  )
  useEffect(() => {
    setTab("json")
    setGraph(false)
  }, [row])

  const relations = related.data?.relations ?? []
  const forward = relations.filter((r) => r.direction === "forward")
  const reverse = relations.filter((r) => r.direction === "reverse")

  return (
    <Drawer open={!!row} onClose={onClose} title={<span className="font-mono">{entity} · {id}</span>}>
      <div className="px-4 pt-3">
        <Tabs
          value={tab}
          onChange={setTab}
          tabs={[
            { value: "json", label: "JSON 行详情" },
            { value: "rel", label: "关系导航" },
          ]}
        />
      </div>
      {tab === "json" ? (
        <div className="p-4">
          <div className="rounded-md border border-border bg-bg p-3">
            <JsonTree data={row ?? {}} />
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-3 p-4">
          <div className="flex items-center justify-between">
            <span className="text-[13px] text-fg-muted">按 forward / reverse 分组</span>
            <Segmented
              value={graph ? "graph" : "list"}
              onChange={(v) => setGraph(v === "graph")}
              options={[
                { value: "list", label: "列表" },
                { value: "graph", label: "关系图" },
              ]}
            />
          </div>
          {related.error ? (
            <EmptyState icon="⇢" title="关系导航不可用" desc={related.error} />
          ) : relations.length === 0 ? (
            <EmptyState
              icon="⇢"
              title={related.loading && row ? "加载中…" : "该行无关系声明"}
              desc="Schema 中没有该实体的外键声明，也没有其他实体指向它。"
            />
          ) : graph ? (
            <RelationGraph center={id} relations={relations} />
          ) : (
            <>
              {[...forward, ...reverse].map((r, i) => (
                <RelationCard key={i} rel={r} />
              ))}
            </>
          )}
        </div>
      )}
    </Drawer>
  )
}

function RelationCard({ rel }: { rel: Record<string, unknown> }) {
  const dir = String(rel.direction ?? "")
  const entity = String(rel.relatedEntity ?? rel.entity ?? "?")
  const field = String(rel.field ?? "")
  const total = Number(rel.total ?? (Array.isArray(rel.items) ? rel.items.length : 0))
  const items = Array.isArray(rel.items) ? (rel.items as Record<string, unknown>[]) : []
  return (
    <Card>
      <CardHead
        title={<span className="font-mono text-[14px]">{entity}</span>}
        desc={
          <span className="font-mono text-[12px]">
            {dir} · {field} · total {total}
          </span>
        }
        right={<Badge tone={dir === "forward" ? "accent" : "readonly"}>{dir}</Badge>}
      />
      {items.length === 0 ? (
        <div className="px-4 py-3 text-[13px] text-fg-subtle">
          {dir === "forward" ? "外键值为空，无关联目标行" : "反向无关联行"}
        </div>
      ) : (
        <Table>
          <tbody>
            {items.slice(0, 5).map((it, i) => (
              <Tr key={i}>
                <Td mono>{String(Object.values(it)[0] ?? "—")}</Td>
                <Td className="text-fg-muted">{String(Object.values(it)[1] ?? "")}</Td>
                <Td align="right" className="text-accent">
                  下钻 →
                </Td>
              </Tr>
            ))}
          </tbody>
        </Table>
      )}
    </Card>
  )
}

function RelationGraph({ center, relations }: { center: string; relations: Record<string, unknown>[] }) {
  const nodes = relations.flatMap((r) => {
    const entity = String(r.relatedEntity ?? r.entity ?? "?")
    const items = Array.isArray(r.items) ? (r.items as Record<string, unknown>[]) : []
    return items.slice(0, 3).map((it) => ({
      label: String(Object.values(it)[0] ?? entity),
      tone: r.direction === "forward" ? "accent" : "readonly",
    }))
  })
  const R = 96
  return (
    <div className="grid place-items-center rounded-md border border-border bg-bg p-4">
      <svg viewBox="0 0 320 240" className="w-full max-w-md">
        {nodes.map((n, i) => {
          const a = (i / Math.max(1, nodes.length)) * Math.PI * 2 - Math.PI / 2
          const x = 160 + Math.cos(a) * R
          const y = 120 + Math.sin(a) * R
          return (
            <g key={n.label + i}>
              <line x1="160" y1="120" x2={x} y2={y} stroke="var(--border-strong)" />
              <circle cx={x} cy={y} r="26" fill="var(--card)" stroke={`var(--${n.tone})`} strokeWidth="1.5" className="cursor-pointer" />
              <text x={x} y={y + 3} textAnchor="middle" fontSize="8" className="font-mono" fill="var(--fg)">
                {n.label.split("_")[0]}
              </text>
            </g>
          )
        })}
        <circle cx="160" cy="120" r="34" fill="var(--accent)" opacity="0.15" stroke="var(--accent)" strokeWidth="1.5" />
        <text x="160" y="118" textAnchor="middle" fontSize="8" className="font-mono" fill="var(--accent)">
          {center.split("_")[0]}
        </text>
        <text x="160" y="128" textAnchor="middle" fontSize="7" className="font-mono" fill="var(--fg-muted)">
          {center}
        </text>
      </svg>
      <p className="text-[12px] text-fg-subtle">点击节点以当前行为中心继续下钻</p>
    </div>
  )
}
