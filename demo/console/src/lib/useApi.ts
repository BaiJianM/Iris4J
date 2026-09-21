import { useCallback, useEffect, useRef, useState } from "react"

type ApiState<T> = {
  data: T | null
  error: string | null
  loading: boolean
}

/**
 * 数据获取 hook：声明式拉取 + 可选轮询。
 * - deps 变化自动重拉（组件卸载/竞态安全）
 * - pollMs 开启定时间询（后台刷新不闪 loading）
 * - refresh() 手动刷新
 */
export function useApi<T>(fn: () => Promise<T>, deps: unknown[], opts?: { pollMs?: number }) {
  const [state, setState] = useState<ApiState<T>>({ data: null, error: null, loading: true })
  const fnRef = useRef(fn)
  fnRef.current = fn
  const firstLoad = useRef(true)
  // 请求序号守卫：deps 快速变化时旧请求后到会覆盖新数据（竞态）
  const seqRef = useRef(0)

  const load = useCallback(async (silent = false) => {
    const seq = ++seqRef.current
    if (!silent) setState((s) => ({ ...s, loading: true }))
    try {
      const data = await fnRef.current()
      if (seq !== seqRef.current) return // 旧响应后到，丢弃
      setState({ data, error: null, loading: false })
    } catch (e) {
      if (seq !== seqRef.current) return
      setState((s) => ({ data: s.data, error: e instanceof Error ? e.message : String(e), loading: false }))
    }
  }, [])

  // biome-ignore lint/correctness/useExhaustiveDependencies: deps 由调用方声明
  useEffect(() => {
    firstLoad.current = true
    load(false)
    firstLoad.current = false
    if (!opts?.pollMs) return
    const timer = setInterval(() => load(true), opts.pollMs)
    return () => clearInterval(timer)
  }, deps)

  return { ...state, refresh: () => load(true) }
}
