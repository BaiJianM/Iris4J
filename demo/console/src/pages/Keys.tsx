import { Fragment, useEffect, useRef, useState } from "react"
import { Badge, Banner, Button, Card, EmptyState, Input, JsonTree, Table, TableSummary, Td, Th, Tr, toast } from "../lib/ui"
import { api } from "../lib/api"
import type { PageProps } from "."

type KeyPage = { pattern: string; keys: string[]; count: number; truncated: boolean }

type KeyValueView = {
  key: string
  type: string | null
  pttlMs: number | null
  json: string | null
  hash: Record<string, string> | null
  string: string | null
  error: string | null
}

/** 键名段推断展示类型（iris:{ns}:memory:… → memory） */
function keyKind(key: string) {
  if (key.includes(":memory:")) return "memory"
  if (key.includes(":llmcache:")) return "llmcache"
  if (key.includes(":cache:")) return "cache"
  if (key.includes(":vec:")) return "vec"
  if (key.includes(":entity:")) return "entity"
  if (key.includes(":security:")) return "security"
  return "other"
}
const typeTone = { memory: "need", llmcache: "accent", cache: "hit", vec: "warn", entity: "accent", security: "error", other: "neutral" } as const

export default function Keys({ ns }: PageProps) {
  const [pattern, setPattern] = useState(`iris:${ns}:*`)
  const [limit, setLimit] = useState(200)
  const [pageData, setPageData] = useState<KeyPage | null>(null)
  const [scanning, setScanning] = useState(false)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [view, setView] = useState<KeyValueView | null>(null)
  const [viewLoading, setViewLoading] = useState(false)
  // 展开行归属守卫：快速点击不同行时旧响应后到会显示错误键的数据
  const viewReqRef = useRef<string | null>(null)
  // 顶栏切换 namespace 后 pattern 跟随（初始值固定为挂载时的 ns）
  useEffect(() => {
    setPattern(`iris:${ns}:*`)
  }, [ns])

  const scan = async () => {
    setScanning(true)
    setExpanded(null)
    setView(null)
    try {
      const res = await api.get<KeyPage>(
        `/api/v1/admin/redis/keys?pattern=${encodeURIComponent(pattern)}&limit=${limit}`,
      )
      setPageData(res)
    } catch (e) {
      setPageData(null)
      toast(e instanceof Error ? `扫描失败：${e.message}` : "扫描失败", "error")
    } finally {
      setScanning(false)
    }
  }

  const toggleRow = async (key: string) => {
    if (expanded === key) {
      setExpanded(null)
      return
    }
    setExpanded(key)
    setView(null)
    setViewLoading(true)
    viewReqRef.current = key
    try {
      const res = await api.get<KeyValueView>(`/api/v1/admin/redis/keys/${encodeURIComponent(key)}`)
      if (viewReqRef.current !== key) return // 已切换到其他行，丢弃旧响应
      setView(res)
    } catch (e) {
      if (viewReqRef.current === key) {
        toast(e instanceof Error ? `读取失败：${e.message}` : "读取失败", "error")
      }
    } finally {
      if (viewReqRef.current === key) setViewLoading(false)
    }
  }

  const viewJson = (() => {
    if (!view) return null
    if (view.json !== null) {
      try {
        return JSON.parse(view.json)
      } catch {
        return view.json
      }
    }
    if (view.hash !== null) return view.hash
    if (view.string !== null) return view.string
    return { note: "该类型暂不展开负载", type: view.type }
  })()

  return (
    <div className="flex flex-col gap-4">
      <Banner tone="need">
        <span>🔒</span>
        <span>operator 专属 · 只读扫描。键值编辑被刻意排除——绕过治理链的写入会制造 CDC 投影漂移。</span>
      </Banner>

      <Card>
        <div className="flex items-center gap-2 border-b border-border p-3">
          <Input
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && scan()}
            className="max-w-md font-mono"
          />
          <Button variant="primary" onClick={scan} disabled={scanning}>
            {scanning ? "扫描中…" : "SCAN"}
          </Button>
          <label className="ml-auto flex items-center gap-1.5 text-[13px] text-fg-muted">
            上限
            <select
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
              className="h-7 rounded border border-border bg-bg px-1.5 text-[13px]"
            >
              {[50, 100, 200, 500].map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </label>
        </div>

        {!pageData ? (
          <div className="p-4">
            <EmptyState
              icon="⌗"
              title="输入键模式后执行扫描"
              desc="例如 iris:demo:* 匹配该 namespace 全部键；默认上限 200 条，SCAN 有界执行不会全库遍历。"
            />
          </div>
        ) : (
          <>
            <TableSummary
              count={pageData.count}
              right={pageData.truncated ? <span className="text-need">已达上限，仅显示前 {pageData.count} 条</span> : undefined}
            />
            <Table>
              <thead>
                <tr>
                  <Th>键名</Th>
                  <Th>前缀类型</Th>
                </tr>
              </thead>
              <tbody>
                {pageData.keys.map((key) => (
                  <Fragment key={key}>
                    <Tr onClick={() => toggleRow(key)} active={expanded === key}>
                      <Td mono>{key}</Td>
                      <Td>
                        <Badge tone={typeTone[keyKind(key) as keyof typeof typeTone]}>{keyKind(key)}</Badge>
                      </Td>
                    </Tr>
                    {expanded === key && (
                      <tr>
                        <td colSpan={2} className="border-b border-border bg-bg p-3">
                          <div className="mb-1.5 text-[12px] text-fg-muted">
                            只读值{view?.type ? ` · ${view.type}` : ""}{view?.pttlMs != null ? ` · TTL ${(view.pttlMs / 1000).toFixed(0)}s` : " · 无 TTL"}
                          </div>
                          {viewLoading ? (
                            <div className="skeleton h-16" />
                          ) : view?.error ? (
                            <div className="text-[13px] text-error">{view.error}</div>
                          ) : viewJson !== null ? (
                            <JsonTree data={viewJson} />
                          ) : (
                            <div className="text-[13px] text-fg-subtle">读取中…</div>
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </Table>
          </>
        )}
      </Card>
    </div>
  )
}
