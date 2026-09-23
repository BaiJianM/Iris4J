import { useEffect, useState } from "react"
import { Badge, Button, Card, CardHead, Field, Input, JsonTree, Segmented, toast, Toggle } from "../lib/ui"
import { api, getApiKey, maskKey, probeRole, setApiKey } from "../lib/api"
import { addNamespace, getNamespaces, removeNamespace } from "../lib/prefs"
import type { PageProps } from "."

const THEME_KEY = "iris4j.theme"

export default function Settings(_: PageProps) {
  const [test, setTest] = useState<null | { ok: boolean; role: string; msg: string }>(null)
  const [theme, setTheme] = useState<"dark" | "light">(() => {
    try {
      return (localStorage.getItem(THEME_KEY) as "dark" | "light") ?? "dark"
    } catch {
      return "dark" // localStorage 被禁用：回落默认主题
    }
  })
  const [keyInput, setKeyInput] = useState("")

  // namespace 管理
  const [nsList, setNsList] = useState<string[]>(() => getNamespaces())
  const [newNs, setNewNs] = useState("")

  // 服务端信息（真实读取）
  const [info, setInfo] = useState<Record<string, unknown> | null>(null)

  useEffect(() => {
    api
      .get<Record<string, unknown>>("/actuator/info")
      .then(setInfo)
      .catch(() => setInfo({ note: "/actuator/info 未返回内容" }))
  }, [])

  useEffect(() => {
    document.documentElement.classList.toggle("theme-light-root", theme === "light")
  }, [theme])

  const saveTheme = (t: "dark" | "light") => {
    setTheme(t)
    try {
      localStorage.setItem(THEME_KEY, t)
    } catch {
      /* ignore */
    }
    window.dispatchEvent(new CustomEvent("iris4j.theme-change", { detail: t === "light" }))
  }

  const testConn = async () => {
    try {
      const role = await probeRole()
      setTest({ ok: true, role, msg: role === "operator" ? "管理端点可访问" : "管理端点不可访问（operator 专属）" })
    } catch (e) {
      setTest({ ok: false, role: "-", msg: e instanceof Error ? e.message : "连接失败" })
    }
  }

  const saveKey = () => {
    setApiKey(keyInput.trim())
    setKeyInput("")
    toast(`API Key 已保存（${maskKey(getApiKey())}）`)
    testConn()
  }

  const addNs = () => {
    if (addNamespace(newNs)) {
      toast(`已添加 namespace ${newNs.trim()}`)
      setNsList(getNamespaces())
      setNewNs("")
    } else {
      toast("添加失败（为空或已存在）", "warn")
    }
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <Card>
        <CardHead title="默认 namespace" desc="后端无 namespace 枚举端点，本地维护列表（ecomm 为基线，不可移除）" />
        <div className="flex flex-col gap-3 p-4">
          <div className="flex flex-wrap gap-2">
            {nsList.map((n) => (
              <span key={n} className="flex items-center gap-1.5 rounded border border-border bg-bg-elevated px-2 py-1 font-mono text-[13px]">
                {n}
                {n !== "ecomm" && (
                  <button
                    className="text-error hover:underline"
                    onClick={() => {
                      removeNamespace(n)
                      setNsList(getNamespaces())
                      toast(`已移除 ${n}`, "warn")
                    }}
                  >
                    ✕
                  </button>
                )}
              </span>
            ))}
          </div>
          <div className="flex gap-2">
            {/* Input 基类自带 w-full，与 w-48 直接叠加会冲突（无 tailwind-merge），
                用定宽容器约束，避免铺满后把"添加"按钮挤成竖排 */}
            <div className="w-48">
              <Input value={newNs} onChange={(e) => setNewNs(e.target.value)} placeholder="新 namespace…" className="font-mono" />
            </div>
            <Button size="sm" onClick={addNs}>
              添加
            </Button>
          </div>
        </div>
      </Card>

      <Card>
        <CardHead title="API Key 配置" desc="X-API-Key 请求头 · 本地 localStorage 存储，不落服务端" />
        <div className="flex flex-col gap-3 p-4">
          <Field label="API Key" hint={`当前：${maskKey(getApiKey())} · 清空输入并保存 = 移除 key`}>
            <Input value={keyInput} onChange={(e) => setKeyInput(e.target.value)} placeholder="粘贴 key（掩码回显，保存后不再明文显示）" className="font-mono" />
          </Field>
          <div className="flex items-center gap-3">
            <Button onClick={saveKey}>保存</Button>
            <Button onClick={testConn}>测试连接</Button>
            {test && (
              <span className={`flex items-center gap-1.5 text-[14px] ${test.ok ? "text-hit" : "text-error"}`}>
                {test.ok ? "✓" : "✕"} 身份：<Badge tone={test.role === "operator" ? "readonly" : "accent"}>{test.role}</Badge> {test.msg}
              </span>
            )}
          </div>
        </div>
      </Card>

      <Card>
        <CardHead title="服务端信息" desc="/actuator/info + /actuator/health（真实响应）" />
        <div className="p-4">
          <div className="rounded border border-border bg-bg p-3">
            <JsonTree data={info ?? { loading: true }} />
          </div>
          <p className="mt-2 text-[12px] text-fg-subtle">健康详情仅在后端配置 show-details 授权时可见；未启用鉴权时端点对本地开放。</p>
        </div>
      </Card>

      <Card>
        <CardHead title="外观" desc="主题偏好本地保存；自动刷新固定 30s（时序采样周期）" />
        <div className="flex flex-col gap-4 p-4">
          <Field label="主题">
            <Segmented
              value={theme}
              onChange={saveTheme}
              options={[
                { value: "dark", label: "暗色" },
                { value: "light", label: "亮色" },
              ]}
            />
          </Field>
          <div className="flex items-center justify-between">
            <span className="text-[14px]">自动刷新</span>
            <div className="flex items-center gap-2">
              <span className="font-mono text-[14px]">30</span>
              <span className="text-[13px] text-fg-muted">秒</span>
              <Toggle checked onChange={() => toast("采样/轮询周期固定为 30s，与指标采样一致", "warn")} />
            </div>
          </div>
          <Button variant="primary" className="w-fit" onClick={() => window.dispatchEvent(new CustomEvent("iris4j.settings-saved"))}>
            保存设置
          </Button>
        </div>
      </Card>
    </div>
  )
}
