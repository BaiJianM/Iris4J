import { useEffect, useState } from "react"
import { Badge, Button, Card, CardHead, EmptyState, Field, Modal, Select, Table, Td, Th, toast, Toggle, Tr } from "../lib/ui"
import { ApiError, api } from "../lib/api"
import { probeRole } from "../lib/api"
import { useApi } from "../lib/useApi"
import type { PageProps } from "."

type SchemaStatus = {
  hotReloadEnabled: boolean
  schemaDir: string
  schemaCount: number
  lastReloadAtMillis: number
  lastError: string | null
}

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

type EntityDetail = { entity: string; fields: FieldSchema[] }

type IndexInfo = { index: string; numDocs: number; fields: Record<string, string> }

type RelationUpdateResponse = {
  namespace: string
  entity: string
  field: string
  relatedEntity: string | null
  indexAutoAssigned: boolean
  assignedIndex: string | null
}

/** FK 强制索引：缺索引时按类型自动补（与后端 DefaultSchemaAdminService 规则一致） */
function autoIndexFor(type: string): string {
  return type === "STRING" || type === "BOOLEAN" ? "tag" : "numeric"
}

const TOOL_KINDS = [
  { suffix: "query", desc: "等值/范围/全文过滤查询" },
  { suffix: "get_by_pk", desc: "主键精确查询" },
  { suffix: "filter_by_field", desc: "单字段过滤" },
  { suffix: "find_by_field_range", desc: "数值范围" },
  { suffix: "search_by_text", desc: "全文检索（TEXT 字段）" },
]

function toolName(entity: string, kind: string) {
  if (kind === "get_by_pk") return `get_${entity}_by_${kind === "get_by_pk" ? "pk" : kind}`
  return `query_${entity}`
}

export default function Schema({ ns }: PageProps) {
  const [reloadOpen, setReloadOpen] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  // 关系编辑：当前编辑的字段 + 下拉目标值 + 保存态 + operator 身份
  const [editField, setEditField] = useState<FieldSchema | null>(null)
  const [editTarget, setEditTarget] = useState<string>("")
  const [saving, setSaving] = useState(false)
  const [isOperator, setIsOperator] = useState(false)

  useEffect(() => {
    probeRole()
      .then((r) => setIsOperator(r === "operator"))
      .catch(() => setIsOperator(false))
  }, [])

  const status = useApi<SchemaStatus>(() => api.get("/api/v1/schema/status"), [])
  const entities = useApi<EntitySummary[]>(
    () => api.get(`/api/v1/schema/entities?namespace=${encodeURIComponent(ns)}`),
    [ns],
  )
  const list = entities.data ?? []
  const entityName = selected ?? list[0]?.entity ?? null

  const detail = useApi<EntityDetail | null>(
    async () => (entityName ? api.get(`/api/v1/schema/${encodeURIComponent(ns)}/${encodeURIComponent(entityName)}`) : null),
    [ns, entityName],
  )
  const index = useApi<IndexInfo | null>(
    async () => (entityName ? api.get(`/api/v1/schema/${encodeURIComponent(ns)}/${encodeURIComponent(entityName)}/index`) : null),
    [ns, entityName],
  )

  const doReload = async () => {
    setReloadOpen(false)
    try {
      await api.post("/api/v1/schema/reload")
      toast("Schema 已重新加载")
      status.refresh()
      entities.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "重载失败", "error")
    }
  }

  const openRelationEdit = (f: FieldSchema) => {
    setEditField(f)
    setEditTarget(f.relatedEntity ?? "")
  }

  const doSaveRelation = async () => {
    if (!editField || !entityName) return
    setSaving(true)
    try {
      const res = await api.put<RelationUpdateResponse>(
        `/api/v1/schema/${encodeURIComponent(ns)}/${encodeURIComponent(entityName)}/fields/${encodeURIComponent(editField.name)}/related-entity`,
        { relatedEntity: editTarget || null },
      )
      toast(
        res.indexAutoAssigned
          ? `关系已更新，并自动添加 ${res.assignedIndex} 索引（FK 强制索引）`
          : res.relatedEntity
            ? `关系已更新：${editField.name} → ${res.relatedEntity}`
            : `映射已清除：${editField.name}`,
      )
      setEditField(null)
      detail.refresh()
      entities.refresh()
      status.refresh()
      index.refresh()
    } catch (e) {
      let msg = e instanceof Error ? e.message : "保存失败"
      if (e instanceof ApiError) {
        try {
          msg = JSON.parse(e.body)?.message ?? msg
        } catch {
          /* 非 JSON 响应体，保留原信息 */
        }
      }
      toast(msg, "error")
    } finally {
      setSaving(false)
    }
  }

  const lastReload = status.data?.lastReloadAtMillis
    ? new Date(status.data.lastReloadAtMillis).toLocaleString()
    : "—"

  return (
    <div className="flex flex-col gap-4">
      {/* status overview */}
      <Card>
        <CardHead
          title="Schema 状态"
          right={
            <Button variant="warn" size="sm" onClick={() => setReloadOpen(true)}>
              重新加载
            </Button>
          }
        />
        <div className="grid grid-cols-2 gap-px bg-border md:grid-cols-4">
          {[
            {
              label: "热加载",
              node: (
                <div className="flex items-center gap-2">
                  <Toggle checked={!!status.data?.hotReloadEnabled} onChange={() => {}} />
                  <span className="text-[14px]">{status.data?.hotReloadEnabled ? "启用" : "关闭"}</span>
                </div>
              ),
            },
            { label: "Schema 目录", node: <span className="font-mono text-[13px] text-fg">{status.data?.schemaDir ?? "—"}</span> },
            { label: "Schema 数量", node: <span className="font-mono text-lg font-semibold">{status.data?.schemaCount ?? "—"}</span> },
            {
              label: "最近重载",
              node: (
                <div>
                  <span className="text-[14px] text-fg-muted">{lastReload}</span>
                  {status.data?.lastError && <div className="text-[12px] text-error">最近错误：{status.data.lastError}</div>}
                </div>
              ),
            },
          ].map((s) => (
            <div key={s.label} className="bg-card px-4 py-3">
              <div className="mb-1 text-[12px] text-fg-muted">{s.label}</div>
              {s.node}
            </div>
          ))}
        </div>
      </Card>

      {/* entities */}
      <Card>
        <CardHead title="实体清单" desc={`${list.length} 个实体 · 点击行查看字段与索引`} />
        <Table>
          <thead>
            <tr>
              <Th>实体</Th>
              <Th>主键</Th>
              <Th align="right">字段数</Th>
              <Th align="right">索引字段</Th>
              <Th align="right">关系字段</Th>
              <Th>租户 / 标签字段</Th>
            </tr>
          </thead>
          <tbody>
            {list.map((e) => (
              <Tr key={e.entity} onClick={() => setSelected(e.entity)} active={entityName === e.entity}>
                <Td mono>{e.entity}</Td>
                <Td mono className="text-readonly">
                  {e.primaryKeys.join(", ")}
                </Td>
                <Td mono align="right">
                  {e.fieldCount}
                </Td>
                <Td mono align="right">
                  {e.indexedFields}
                </Td>
                <Td mono align="right">
                  {e.relatedFields > 0 ? e.relatedFields : "—"}
                </Td>
                <Td mono className="text-fg-muted">
                  {[e.tenantField, e.accessTagField].filter(Boolean).join(" / ") || "—"}
                </Td>
              </Tr>
            ))}
          </tbody>
        </Table>
        <div className="border-t border-border px-3 py-2 text-[12px] text-fg-subtle">
          每实体注册 5 类动态 MCP 工具；动态工具上限 8 实体，当前 {list.length} / 8
        </div>
      </Card>

      {/* entity detail + index */}
      {entityName && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card>
            <CardHead
              title={<span className="font-mono">{entityName} · 字段定义</span>}
              desc={isOperator ? "来自 YAML Schema（关系映射可编辑）" : "来自 YAML Schema（只读）"}
            />
            {detail.data ? (
              <Table>
                <thead>
                  <tr>
                    <Th>字段</Th>
                    <Th>类型</Th>
                    <Th>索引</Th>
                    <Th>关系</Th>
                    <Th>字段级 tags</Th>
                  </tr>
                </thead>
                <tbody>
                  {detail.data.fields.map((f) => (
                    <Tr key={f.name}>
                      <Td mono>{f.name}</Td>
                      <Td mono className="text-fg-muted">
                        {f.type}
                      </Td>
                      <Td>
                        <Badge tone={f.indexed || f.index ? "hit" : "neutral"} mono>
                          {f.index ?? (f.indexed ? "自动" : "无")}
                        </Badge>
                      </Td>
                      <Td>
                        {f.relatedEntity ? (
                          isOperator ? (
                            <button type="button" onClick={() => openRelationEdit(f)} title="点击编辑关系映射">
                              <Badge tone="accent" mono className="cursor-pointer hover:border-accent">
                                → {f.relatedEntity}
                              </Badge>
                            </button>
                          ) : (
                            <Badge tone="accent" mono>→ {f.relatedEntity}</Badge>
                          )
                        ) : isOperator ? (
                          <button
                            type="button"
                            onClick={() => openRelationEdit(f)}
                            className="cursor-pointer text-[13px] text-fg-subtle hover:text-accent"
                            title="声明为外键（Related Entity）"
                          >
                            + 关系
                          </button>
                        ) : (
                          <span className="text-fg-subtle">—</span>
                        )}
                      </Td>
                      <Td>
                        <div className="flex flex-wrap gap-1">
                          {(f.tags ?? []).map((t) => (
                            <Badge key={t} tone="readonly">
                              {t}
                            </Badge>
                          ))}
                          {!f.tags?.length && <span className="text-fg-subtle">—</span>}
                        </div>
                      </Td>
                    </Tr>
                  ))}
                </tbody>
              </Table>
            ) : (
              <div className="p-4">
                <EmptyState icon="❏" title={detail.loading ? "读取中…" : `读取失败：${detail.error ?? ""}`} />
              </div>
            )}
            <div className="border-t border-border px-3 py-2">
              <div className="mb-1 text-[12px] text-fg-muted">动态 MCP 工具（该实体）</div>
              <div className="flex flex-wrap gap-1">
                {TOOL_KINDS.map((k) => (
                  <span key={k.suffix} title={k.desc} className="rounded border border-border bg-bg-elevated px-1.5 py-0.5 font-mono text-[12px] text-fg-muted">
                    {k.suffix === "get_by_pk" ? `get_${entityName}_by_pk` : k.suffix === "query" ? `query_${entityName}` : `${k.suffix.split("_")[0]}_${entityName}_by_${k.suffix.split("_by_")[1]}`}
                  </span>
                ))}
              </div>
            </div>
          </Card>

          <Card>
            <CardHead title="FT 索引详情" desc="FT.INFO 实时读取（num_docs + 字段类型）" />
            <div className="p-4">
              {index.data ? (
                <>
                  <div className="mb-3 rounded border border-border bg-bg p-2.5">
                    <div className="text-[12px] text-fg-muted">索引名</div>
                    <div className="font-mono text-[13px]">{index.data.index}</div>
                    <div className="mt-1.5 text-[12px] text-fg-muted">文档数（num_docs）</div>
                    <div className="font-mono text-lg font-semibold tnum">{index.data.numDocs.toLocaleString()}</div>
                  </div>
                  <Table>
                    <thead>
                      <tr>
                        <Th>字段别名</Th>
                        <Th>索引类型</Th>
                      </tr>
                    </thead>
                    <tbody>
                      {Object.entries(index.data.fields).map(([name, type]) => (
                        <Tr key={name}>
                          <Td mono>{name}</Td>
                          <Td>
                            <Badge tone={type === "VECTOR" ? "accent" : type === "TEXT" ? "warn" : "hit"} mono>
                              {type}
                            </Badge>
                          </Td>
                        </Tr>
                      ))}
                    </tbody>
                  </Table>
                </>
              ) : (
                <EmptyState
                  icon="❏"
                  title={index.loading ? "读取中…" : index.error?.includes("404") ? "索引尚未创建" : `读取失败：${index.error ?? ""}`}
                  desc="该实体暂无查询流量时索引可能未创建（懒加载）；执行一次实体查询后再来看。"
                />
              )}
            </div>
          </Card>
        </div>
      )}

      <Modal
        open={reloadOpen}
        onClose={() => setReloadOpen(false)}
        title="重新加载 Schema"
        footer={
          <>
            <Button variant="ghost" onClick={() => setReloadOpen(false)}>
              取消
            </Button>
            <Button variant="warn" onClick={doReload}>
              确认重载
            </Button>
          </>
        }
      >
        将从 <span className="font-mono text-fg">{status.data?.schemaDir ?? "外部目录"}</span> 重新解析全部 Schema 定义。
        解析失败时将<span className="text-fg">保留旧 Schema</span>，不会中断服务。
      </Modal>

      {/* 关系映射编辑 */}
      <Modal
        open={!!editField}
        onClose={() => (saving ? null : setEditField(null))}
        title={editField ? `编辑关系映射 · ${editField.name}` : "编辑关系映射"}
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditField(null)} disabled={saving}>
              取消
            </Button>
            <Button variant="primary" onClick={doSaveRelation} disabled={saving}>
              {saving ? "保存中…" : "保存并热载"}
            </Button>
          </>
        }
      >
        {editField && (
          <div className="flex flex-col gap-3">
            <Field label="Related Entity" hint="选择该字段指向的目标实体（值为目标实体主键）；清除映射不影响已有索引声明">
              <Select value={editTarget} onChange={(e) => setEditTarget(e.target.value)} disabled={saving}>
                <option value="">（无 · 清除映射）</option>
                {list.map((e2) => (
                  <option key={e2.entity} value={e2.entity}>
                    {e2.entity}
                  </option>
                ))}
              </Select>
            </Field>
            {editField.index == null && !editField.indexed && (
              <div className="rounded border border-warn/40 bg-warn/10 px-3 py-2 text-[12px] text-fg-muted">
                该字段当前未参与索引。FK 强制索引——保存后将自动添加{" "}
                <Badge tone="warn" mono>
                  {autoIndexFor(editField.type)}
                </Badge>{" "}
                索引声明。
              </div>
            )}
            <div className="text-[12px] text-fg-subtle">
              保存后回写 YAML（<span className="font-mono">{status.data?.schemaDir ?? "外部目录"}</span>）并热载生效，
              MCP 工具描述将在数秒内同步更新并通知已连接的 Agent。
            </div>
          </div>
        )}
      </Modal>
    </div>
  )
}
