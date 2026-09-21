import type { ComponentType, ReactNode } from "react"
import Dashboard from "./Dashboard"
import Query from "./Query"
import Keys from "./Keys"
import Memory from "./Memory"
import LlmCache from "./LlmCache"
import Cache from "./Cache"
import Cdc from "./Cdc"
import Schema from "./Schema"
import AgentKeys from "./AgentKeys"
import Consistency from "./Consistency"
import Redis from "./Redis"
import Metrics from "./Metrics"
import Settings from "./Settings"
import AgentDemo, { AgentConfigButton } from "./AgentDemo"

export type PageProps = { ns: string }

export type PageKey =
  | "dashboard"
  | "agent"
  | "query"
  | "keys"
  | "memory"
  | "llmcache"
  | "cache"
  | "cdc"
  | "schema"
  | "agentkeys"
  | "consistency"
  | "redis"
  | "metrics"
  | "settings"

type PageDef = {
  title: string
  desc: string
  component: ComponentType<PageProps>
  actions?: () => ReactNode
}

export const PAGES: Record<PageKey, PageDef> = {
  dashboard: {
    title: "仪表盘",
    desc: "集群健康、缓存命中与 CDC 管道的实时总览",
    component: Dashboard,
  },
  agent: {
    title: "Agent 演示",
    desc: "内置 Agent 对话：AI 自主调用工具查询数据、检索记忆，流式展示每一步",
    component: AgentDemo,
    actions: () => <AgentConfigButton />,
  },
  query: {
    title: "实体数据查询",
    desc: "按索引字段过滤、检索并下钻实体投影数据",
    component: Query,
  },
  keys: {
    title: "键浏览",
    desc: "按模式扫描 Redis 键空间并只读查看键值",
    component: Keys,
  },
  memory: {
    title: "记忆管理",
    desc: "管理会话工作记忆与长期记忆的抽取、检索与写入",
    component: Memory,
  },
  llmcache: {
    title: "LLM 缓存",
    desc: "语义缓存的命中试玩、写入与按 namespace 清理",
    component: LlmCache,
  },
  cache: {
    title: "缓存管理",
    desc: "精确 KV 读写失效、语义索引重建与缓存指标",
    component: Cache,
  },
  cdc: {
    title: "CDC 数据集成",
    desc: "死信队列排查重放，管道积压与事件成功率监控",
    component: Cdc,
  },
  schema: {
    title: "Schema 管理",
    desc: "实体 Schema 热加载状态、动态 MCP 工具与索引",
    component: Schema,
  },
  agentkeys: {
    title: "Agent 密钥",
    desc: "管理静态与动态 Agent 鉴权密钥及标签权限",
    component: AgentKeys,
  },
  consistency: {
    title: "一致性",
    desc: "校验源库与 Redis 投影的漂移并按需修复",
    component: Consistency,
  },
  redis: {
    title: "Redis 状态",
    desc: "实例版本、内存、持久化与快照操作",
    component: Redis,
  },
  metrics: {
    title: "监控指标",
    desc: "查询延迟、缓存与 CDC 管道的多维时序图表",
    component: Metrics,
  },
  settings: {
    title: "设置",
    desc: "默认 namespace、鉴权、服务端信息与外观偏好",
    component: Settings,
  },
}
