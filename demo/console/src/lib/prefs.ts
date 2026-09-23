// namespace 列表管理：后端无 namespace 枚举端点，本地面板维护
// 缺省 ecomm（全库电商演示数据），用户可在设置页添加（存 localStorage，不伪造后端数据）

const NS_STORAGE = "iris4j.namespaces"
const BASELINE_NS = "ecomm"

// demo 命名空间无 Schema（全库即演示数据）：旧 localStorage 残留直接清除，
// 否则列表排序后 nsList[0] 选中一个无 Schema 的空命名空间（Agent 页表现为查不到实体）
const RETIRED_NS = new Set(["demo"])

export function getNamespaces(): string[] {
  try {
    const raw = localStorage.getItem(NS_STORAGE)
    const list = (raw ? (JSON.parse(raw) as string[]) : []).filter((n) => !RETIRED_NS.has(n))
    if (raw) {
      const filtered = JSON.stringify(list)
      if (filtered !== raw) localStorage.setItem(NS_STORAGE, filtered) // 顺手写回，清掉残留
    }
    return list.includes(BASELINE_NS) ? list : [BASELINE_NS, ...list]
  } catch {
    return [BASELINE_NS]
  }
}

export function addNamespace(ns: string): boolean {
  const trimmed = ns.trim()
  if (!trimmed) return false
  const list = getNamespaces()
  if (list.includes(trimmed)) return false
  try {
    localStorage.setItem(NS_STORAGE, JSON.stringify([...list, trimmed]))
  } catch {
    return false
  }
  return true
}

export function removeNamespace(ns: string) {
  if (ns === BASELINE_NS) return // 基线 namespace 不允许移除
  const list = getNamespaces().filter((n) => n !== ns)
  try {
    localStorage.setItem(NS_STORAGE, JSON.stringify(list))
  } catch {
    /* ignore */
  }
}
