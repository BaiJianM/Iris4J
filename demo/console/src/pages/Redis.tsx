import { useState } from "react"
import { Badge, Button, Card, CardHead, EmptyState, Modal, StatCard, toast } from "../lib/ui"
import { api } from "../lib/api"
import { useApi } from "../lib/useApi"
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

type RedisStats = {
  connected_clients: number | null
  blocked_clients: number | null
  total_commands_processed: number | null
  instantaneous_ops_per_sec: number | null
  keyspace_hits: number | null
  keyspace_misses: number | null
  evicted_keys: number | null
  expired_keys: number | null
  rejected_connections: number | null
  keyspace_hit_rate: number | null
}

export default function Redis(_: PageProps) {
  const [bgOpen, setBgOpen] = useState(false)
  const status = useApi<RedisStatus>(() => api.get("/api/v1/redis/status"), [], { pollMs: 15000 })
  const stats = useApi<RedisStats>(() => api.get("/api/v1/admin/redis/stats"), [], { pollMs: 15000 })

  const fmtUptime = (s?: number) => {
    if (!s) return "—"
    const d = Math.floor(s / 86400)
    const h = Math.floor((s % 86400) / 3600)
    return d > 0 ? `${d} 天 ${h} 小时` : `${h} 小时`
  }
  const rdbAge = (() => {
    if (!status.data?.rdbLastSaveTime) return "—"
    const t = Date.parse(status.data.rdbLastSaveTime.replace(" ", "T") + "Z")
    return isNaN(t) ? status.data.rdbLastSaveTime : `${Math.max(0, Math.round((Date.now() - t) / 60000))} 分钟前`
  })()

  const doBgsave = async () => {
    setBgOpen(false)
    try {
      await api.post("/api/v1/redis/bgsave")
      toast("BGSAVE 已在后台启动", "warn")
      status.refresh()
    } catch (e) {
      toast(e instanceof Error ? e.message : "触发失败", "error")
    }
  }

  const s = stats.data

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <Button variant="warn" onClick={() => setBgOpen(true)}>
          执行 BGSAVE
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <StatCard label="版本" value={status.data?.redisVersion ?? "—"} sub={`运行 ${fmtUptime(status.data?.uptimeSeconds)}`} />
        <StatCard label="dbsize" value={status.data ? status.data.dbsize.toLocaleString() : "—"} sub="keys" />
        <StatCard label="内存" value={status.data?.usedMemoryHuman ?? "—"} />
        <StatCard label="AOF" value={status.data?.aofEnabled ? "on" : "off"} sub={status.data?.aofLastWriteStatus ?? undefined} />
        <StatCard label="RDB" value={status.data?.rdbLastBgsaveStatus ?? "—"} sub={rdbAge} />
        <StatCard label="changesSinceLastSave" value={status.data ? status.data.rdbChangesSinceLastSave.toLocaleString() : "—"} />
      </div>

      <Card>
        <CardHead
          title="连接与吞吐统计"
          desc="INFO clients + INFO stats · GET /api/v1/admin/redis/stats（operator）"
          right={<Badge tone={s ? "hit" : "need"}>{s ? "已接入" : "需 operator key"}</Badge>}
        />
        <div className="p-4">
          {s ? (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <StatCard label="connected_clients" value={fmt(s.connected_clients)} />
              <StatCard label="blocked_clients" value={fmt(s.blocked_clients)} />
              <StatCard label="ops/sec" value={fmt(s.instantaneous_ops_per_sec)} />
              <StatCard label="total_commands" value={fmt(s.total_commands_processed)} />
              <StatCard label="keyspace_hits" value={fmt(s.keyspace_hits)} />
              <StatCard label="keyspace_misses" value={fmt(s.keyspace_misses)} />
              <StatCard
                label="hit_rate"
                value={s.keyspace_hit_rate == null ? "—" : (s.keyspace_hit_rate * 100).toFixed(1)}
                unit={s.keyspace_hit_rate == null ? undefined : "%"}
              />
              <StatCard label="evicted / expired" value={`${fmt(s.evicted_keys)} / ${fmt(s.expired_keys)}`} />
            </div>
          ) : (
            <EmptyState
              icon="◉"
              title={stats.loading ? "读取中…" : "统计不可用"}
              desc="此端点需要 operator 身份（legacy key 或含通配 tag）。请在设置页配置 API Key 后重试。"
              tone="need"
            />
          )}
          {s && s.rejected_connections != null && s.rejected_connections > 0 && (
            <div className="mt-3 rounded border border-warn/50 bg-warn/10 px-3 py-2 text-[14px] text-warn">
              ⚠ 存在 {s.rejected_connections} 个被拒绝的连接——检查 maxclients 与连接池配置。
            </div>
          )}
        </div>
      </Card>

      <Modal
        open={bgOpen}
        onClose={() => setBgOpen(false)}
        title="执行 BGSAVE"
        footer={
          <>
            <Button variant="ghost" onClick={() => setBgOpen(false)}>
              取消
            </Button>
            <Button variant="warn" onClick={doBgsave}>
              确认执行
            </Button>
          </>
        }
      >
        将触发一次后台 RDB 快照（BGSAVE）。快照在后台异步执行，可能短暂增加内存与 IO 负载。
      </Modal>
    </div>
  )
}

function fmt(v: number | null | undefined): string {
  return v == null ? "—" : v.toLocaleString()
}
