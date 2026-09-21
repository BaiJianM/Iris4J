import { useCallback, useState } from "react"
import {
  Badge,
  Banner,
  Button,
  Card,
  CardHead,
  Field,
  Input,
  Modal,
  Table,
  Td,
  Th,
  toast,
  Tr,
} from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
import type { PageProps } from "."

/** tag 徽标列表（模块顶层组件：定义在组件体内会每次渲染产生新组件类型，表格行整棵 remount） */
function TagList({ tags }: { tags: string[] }) {
  return (
    <div className="flex gap-1">
      {tags.map((t) => (
        <Badge key={t} tone={t === "*" ? "error" : t === "write" ? "warn" : "readonly"}>
          {t === "*" ? "* 全权限" : t}
        </Badge>
      ))}
      {!tags.length && <span className="text-[13px] text-fg-subtle">无（fail-closed 不可见任何行）</span>}
    </div>
  )
}

type StaticKey = { agentId: string; tags: string[] }
type DynamicKey = { fingerprint: string; agentId: string; tags: string[]; updatedAt: string }
type KeysResp = { staticCount: number; static: StaticKey[]; dynamicCount: number; dynamic: DynamicKey[] }

export default function AgentKeys(_: PageProps) {
  const keys = useApi<KeysResp>(() => api.get("/api/v1/admin/agent-keys"), [])
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<DynamicKey | null>(null)
  const [agentId, setAgentId] = useState("")
  const [keyValue, setKeyValue] = useState("")
  const [tags, setTags] = useState("read")
  const [reveal, setReveal] = useState<{ plain: string; fingerprint: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState<DynamicKey | null>(null)

  const wildcard = tags.split(/[,\s]+/).includes("*")

  const refresh = useCallback(() => keys.refresh(), [keys])

  const openForm = (k?: DynamicKey) => {
    setEditing(k ?? null)
    setAgentId(k?.agentId ?? "")
    setKeyValue("")
    setTags(k?.tags.join(",") ?? "read")
    setFormOpen(true)
  }

  const save = async () => {
    if (!agentId.trim() || !keyValue.trim()) {
      toast("agentId 与 key 均不能为空（明文 key 不落存储，仅存指纹）", "warn")
      return
    }
    setSaving(true)
    try {
      const res = await api.put<{ fingerprint: string; shortFingerprint: string; overrideStatic: boolean }>(
        "/api/v1/admin/agent-keys",
        {
          agentId: agentId.trim(),
          key: keyValue.trim(),
          tags: tags.split(/[,\s]+/).filter(Boolean),
        },
      )
      setFormOpen(false)
      setReveal({ plain: keyValue.trim(), fingerprint: res.shortFingerprint })
      refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "保存失败", "error")
    } finally {
      setSaving(false)
    }
  }

  const doDelete = async (k: DynamicKey) => {
    setDeleteOpen(null)
    try {
      await api.del(`/api/v1/admin/agent-keys/${encodeURIComponent(k.fingerprint)}`)
      toast(`已删除 ${k.agentId} 的动态 key`, "error")
      refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "删除失败", "error")
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <Banner tone="readonly" right={<Button variant="primary" size="sm" onClick={() => openForm()}>+ 新建 / 更新 Key</Button>}>
        <span>优先级：动态 key &gt; 静态 key &gt; legacy · 写入后即时生效（免重启热载）</span>
      </Banner>

      {keys.error && (
        <Banner tone="error">
          <span>加载失败：{keys.error}（operator 专属端点，检查设置页的 API Key）</span>
        </Banner>
      )}

      {(["dynamic", "static"] as const).map((kind) => {
        const rows = kind === "dynamic" ? (keys.data?.dynamic ?? []) : (keys.data?.static ?? [])
        return (
          <Card key={kind}>
            <CardHead
              title={kind === "dynamic" ? "动态 Key（运行时写入）" : "静态 Key（配置文件）"}
              right={<Badge tone={kind === "dynamic" ? "accent" : "neutral"}>{rows.length} 个</Badge>}
            />
            <Table>
              <thead>
                <tr>
                  <Th>{kind === "dynamic" ? "agentId / 指纹" : "agentId"}</Th>
                  <Th>标签（tags）</Th>
                  <Th align="right">时间</Th>
                  <Th align="right">操作</Th>
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={4}>
                      <span className="text-[13px] text-fg-subtle">{keys.loading ? "读取中…" : "暂无条目"}</span>
                    </td>
                  </tr>
                )}
                {kind === "dynamic" &&
                  (keys.data?.dynamic ?? []).map((k) => (
                    <Tr key={k.fingerprint}>
                      <Td>
                        <div className="font-mono text-[14px]">{k.agentId}</div>
                        <div className="font-mono text-[12px] text-fg-subtle">sha256:{k.fingerprint.slice(0, 8)}…</div>
                      </Td>
                      <Td>
                        <TagList tags={k.tags} />
                        {k.tags.includes("*") && <div className="text-[11px] text-error">operator 全可见</div>}
                      </Td>
                      <Td mono align="right" className="text-fg-muted">
                        {k.updatedAt?.replace("T", " ").slice(0, 19)}
                      </Td>
                      <Td align="right">
                        <div className="flex justify-end gap-2">
                          <button className="text-[13px] text-accent hover:underline" onClick={() => openForm(k)}>
                            覆盖 tags
                          </button>
                          <button className="text-[13px] text-error hover:underline" onClick={() => setDeleteOpen(k)}>
                            删除
                          </button>
                        </div>
                      </Td>
                    </Tr>
                  ))}
                {kind === "static" &&
                  (keys.data?.static ?? []).map((k) => (
                    <Tr key={k.agentId}>
                      <Td mono>{k.agentId}</Td>
                      <Td>
                        <TagList tags={k.tags} />
                      </Td>
                      <Td align="right" className="text-fg-muted">
                        配置文件
                      </Td>
                      <Td align="right">
                        <span className="cursor-not-allowed text-[13px] text-fg-subtle" title="静态 key 走配置文件，动态层可覆盖同指纹 key">
                          只读
                        </span>
                      </Td>
                    </Tr>
                  ))}
              </tbody>
            </Table>
          </Card>
        )
      })}

      {/* create/update form */}
      <Modal
        open={formOpen}
        onClose={() => setFormOpen(false)}
        title={editing ? `覆盖动态 Key tags（${editing.agentId}）` : "新建 / 更新 Agent Key"}
        footer={
          <>
            <Button variant="ghost" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button variant="primary" onClick={save} disabled={saving}>
              {saving ? "保存中…" : editing ? "覆盖 tags" : "保存并生成"}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Field label="Agent ID">
            <Input value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="support-agent" className="font-mono" />
          </Field>
          <Field label="密钥（明文仅在提交时使用，服务端只存 SHA-256 指纹）">
            <Input value={keyValue} onChange={(e) => setKeyValue(e.target.value)} placeholder="任意高熵字符串" className="font-mono" />
          </Field>
          <Field label="标签（逗号分隔）" hint="决定该 agent 可见的行级/字段级范围；集合值取授权交集">
            <Input value={tags} onChange={(e) => setTags(e.target.value)} className="font-mono" />
          </Field>
          {wildcard && (
            <div className="rounded border border-error bg-error/10 px-3 py-2 text-[14px] text-error">
              ⚠ 标签包含 <span className="font-mono">*</span>：该 Key 将拥有 <b>operator 全可见权限</b>，可读取全部 namespace 与 owner 的数据。严禁给真实 agent。
            </div>
          )}
        </div>
      </Modal>

      {/* delete confirm */}
      <Modal
        open={!!deleteOpen}
        onClose={() => setDeleteOpen(null)}
        title="删除动态 Key"
        danger
        footer={
          <>
            <Button variant="ghost" onClick={() => setDeleteOpen(null)}>
              取消
            </Button>
            <Button variant="danger" onClick={() => deleteOpen && doDelete(deleteOpen)}>
              确认删除
            </Button>
          </>
        }
      >
        将删除 <span className="font-mono text-fg">{deleteOpen?.agentId}</span> 的动态 key
        （指纹 <span className="font-mono">{deleteOpen?.fingerprint.slice(0, 8)}…</span>）。删除后该 key 立即失效，
        若该 agent 无静态 key 将回落 legacy 或被拒绝。
      </Modal>

      {/* one-time reveal */}
      <Modal open={!!reveal} onClose={() => setReveal(null)} title="Key 已登记" danger footer={<Button variant="secondary" onClick={() => setReveal(null)}>关闭</Button>}>
        <div className="flex flex-col gap-3">
          <div className="rounded-md border border-border bg-black/40 p-4">
            <div className="mb-2 text-[12px] text-fg-muted">明文密钥（仅此一次展示）</div>
            <div className="flex items-center gap-2">
              <code className="flex-1 break-all font-mono text-[16px] font-semibold text-hit">{reveal?.plain}</code>
              <Button
                size="sm"
                onClick={() => {
                  navigator.clipboard?.writeText(reveal?.plain ?? "")
                  toast("已复制到剪贴板")
                }}
              >
                复制
              </Button>
            </div>
            <div className="mt-2 font-mono text-[12px] text-fg-subtle">服务端指纹：sha256:{reveal?.fingerprint}…</div>
          </div>
          <div className="text-[14px] text-error">⚠ 本地页面不保存明文；请立即交给调用方妥善保存。</div>
        </div>
      </Modal>
    </div>
  )
}
