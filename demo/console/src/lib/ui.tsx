import React, { useEffect, useState } from "react"

export function cx(...parts: Array<string | false | null | undefined>) {
  return parts.filter(Boolean).join(" ")
}

/* ------------------------------------------------------------------ */
/* Badges                                                              */
/* ------------------------------------------------------------------ */

type Tone =
  | "hit"
  | "miss"
  | "error"
  | "warn"
  | "need"
  | "readonly"
  | "accent"
  | "neutral"

const toneStyle: Record<Tone, string> = {
  hit: "text-hit border-hit/35 bg-hit/10",
  miss: "text-miss border-miss/35 bg-miss/10",
  error: "text-error border-error/35 bg-error/10",
  warn: "text-warn border-warn/35 bg-warn/10",
  need: "text-need border-need/35 bg-need/10",
  readonly: "text-readonly border-readonly/35 bg-readonly/10",
  accent: "text-accent border-accent/35 bg-accent/10",
  neutral: "text-fg-muted border-border-strong bg-bg-elevated",
}

export function Badge({
  tone = "neutral",
  children,
  className,
  mono,
}: {
  tone?: Tone
  children: React.ReactNode
  className?: string
  mono?: boolean
}) {
  return (
    <span
      className={cx(
        "inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[12px] font-medium leading-none whitespace-nowrap",
        mono && "font-mono",
        toneStyle[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}

export function Dot({ tone = "hit", className }: { tone?: Tone; className?: string }) {
  const colors: Record<Tone, string> = {
    hit: "bg-hit",
    miss: "bg-miss",
    error: "bg-error",
    warn: "bg-warn",
    need: "bg-need",
    readonly: "bg-readonly",
    accent: "bg-accent",
    neutral: "bg-fg-subtle",
  }
  return <span className={cx("inline-block size-2 rounded-full", colors[tone], className)} />
}

/** Capability tier badge */
export function CapBadge({ tier }: { tier: "done" | "need" | "hold" }) {
  if (tier === "done") return <Badge tone="hit">✅ 已实现</Badge>
  if (tier === "need") return <Badge tone="need">🔶 需补 API</Badge>
  return <Badge tone="neutral">⏸ 暂缓</Badge>
}

/* ------------------------------------------------------------------ */
/* Buttons + inputs                                                    */
/* ------------------------------------------------------------------ */

type BtnVariant = "primary" | "secondary" | "danger" | "warn" | "ghost"

export function Button({
  variant = "secondary",
  size = "md",
  children,
  className,
  ...rest
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: BtnVariant
  size?: "sm" | "md"
}) {
  const base =
    "inline-flex shrink-0 whitespace-nowrap items-center justify-center gap-1.5 rounded border font-medium transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring/60 disabled:opacity-45 disabled:cursor-not-allowed"
  const sizes = { sm: "h-7 px-2.5 text-[13px]", md: "h-8 px-3 text-[14px]" }
  const variants: Record<BtnVariant, string> = {
    primary: "bg-accent border-accent text-white hover:brightness-110",
    secondary: "bg-bg-elevated border-border-strong text-fg hover:bg-card-hover",
    danger: "bg-error border-error text-white hover:brightness-110",
    warn: "bg-warn/15 border-warn/50 text-warn hover:bg-warn/25",
    ghost: "border-transparent text-fg-muted hover:bg-card-hover hover:text-fg",
  }
  return (
    <button className={cx(base, sizes[size], variants[variant], className)} {...rest}>
      {children}
    </button>
  )
}

export function Input({ className, ...rest }: React.InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cx(
        "h-8 w-full rounded border border-border bg-bg px-2.5 text-[14px] text-fg placeholder:text-fg-subtle focus:border-accent focus:outline-none focus:ring-1 focus:ring-ring/40",
        className,
      )}
      {...rest}
    />
  )
}

export function Textarea({ className, ...rest }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      className={cx(
        "w-full rounded border border-border bg-bg px-2.5 py-1.5 text-[14px] text-fg placeholder:text-fg-subtle focus:border-accent focus:outline-none focus:ring-1 focus:ring-ring/40",
        className,
      )}
      {...rest}
    />
  )
}

export function Select({
  className,
  children,
  ...rest
}: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cx(
        "h-8 rounded border border-border bg-bg px-2 text-[14px] text-fg focus:border-accent focus:outline-none focus:ring-1 focus:ring-ring/40",
        className,
      )}
      {...rest}
    >
      {children}
    </select>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[13px] font-medium text-fg-muted">{label}</span>
      {children}
      {hint && <span className="text-[12px] text-fg-subtle">{hint}</span>}
    </label>
  )
}

/* ------------------------------------------------------------------ */
/* Layout surfaces                                                     */
/* ------------------------------------------------------------------ */

export function Card({
  className,
  children,
  danger,
  interactive,
}: {
  className?: string
  children: React.ReactNode
  danger?: boolean
  interactive?: boolean
}) {
  return (
    <div
      className={cx(
        "rounded-md border bg-card",
        danger ? "border-error/60" : "border-border",
        interactive && "transition-colors hover:bg-card-hover",
        className,
      )}
    >
      {children}
    </div>
  )
}

export function CardHead({
  title,
  desc,
  right,
  className,
}: {
  title: React.ReactNode
  desc?: React.ReactNode
  right?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cx("flex items-start justify-between gap-3 border-b border-border px-4 py-2.5", className)}>
      <div className="min-w-0">
        <div className="text-[14px] font-semibold text-fg">{title}</div>
        {desc && <div className="mt-0.5 text-[13px] text-fg-muted">{desc}</div>}
      </div>
      {right && <div className="flex shrink-0 items-center gap-2">{right}</div>}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Stat card + sparkline                                               */
/* ------------------------------------------------------------------ */

export function Sparkline({
  data,
  tone = "hit",
  width = 96,
  height = 28,
}: {
  data: number[]
  tone?: Tone
  width?: number
  height?: number
}) {
  const stroke: Record<Tone, string> = {
    hit: "var(--hit)",
    miss: "var(--miss)",
    error: "var(--error)",
    warn: "var(--warn)",
    need: "var(--need)",
    readonly: "var(--readonly)",
    accent: "var(--accent)",
    neutral: "var(--fg-subtle)",
  }
  const min = Math.min(...data)
  const max = Math.max(...data)
  const span = max - min || 1
  const pts = data.map((d, i) => {
    const x = (i / (data.length - 1)) * width
    const y = height - ((d - min) / span) * (height - 4) - 2
    return [x, y]
  })
  const line = pts.map((p, i) => `${i === 0 ? "M" : "L"}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ")
  const area = `${line} L${width},${height} L0,${height} Z`
  const id = React.useId()
  return (
    <svg width={width} height={height} className="overflow-visible">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={stroke[tone]} stopOpacity="0.25" />
          <stop offset="100%" stopColor={stroke[tone]} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={stroke[tone]} strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  )
}

export function StatCard({
  label,
  value,
  unit,
  sub,
  trend,
  spark,
  sparkTone = "hit",
  alert,
  disabled,
  disabledLabel = "未启用",
}: {
  label: string
  value?: React.ReactNode
  unit?: string
  sub?: React.ReactNode
  trend?: { dir: "up" | "down"; value: string; good?: boolean }
  spark?: number[]
  sparkTone?: Tone
  alert?: boolean
  disabled?: boolean
  disabledLabel?: string
}) {
  return (
    <div
      className={cx(
        "flex flex-col gap-1.5 rounded-md border bg-card px-3.5 py-3",
        alert ? "border-error/70" : "border-border",
      )}
    >
      <div className="flex items-center justify-between">
        <span className="text-[13px] text-fg-muted">{label}</span>
        {alert && <Dot tone="error" />}
      </div>
      {disabled ? (
        <div className="flex h-9 items-center">
          <Badge tone="neutral">{disabledLabel}</Badge>
        </div>
      ) : (
        <div className="flex items-end justify-between gap-2">
          <div className="flex items-baseline gap-1">
            <span className={cx("font-mono text-2xl font-semibold leading-none tnum", alert ? "text-error" : "text-fg")}>
              {value}
            </span>
            {unit && <span className="text-[13px] text-fg-subtle">{unit}</span>}
          </div>
          {spark && <Sparkline data={spark} tone={sparkTone} />}
          {trend && (
            <span
              className={cx(
                "font-mono text-[13px] tnum",
                trend.good === false ? "text-error" : trend.good ? "text-hit" : "text-fg-muted",
              )}
            >
              {trend.dir === "up" ? "▲" : "▼"} {trend.value}
            </span>
          )}
        </div>
      )}
      {sub && <div className="text-[12px] text-fg-subtle">{sub}</div>}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Charts (lightweight SVG)                                            */
/* ------------------------------------------------------------------ */

const toneVar: Record<string, string> = {
  hit: "var(--hit)",
  miss: "var(--miss)",
  error: "var(--error)",
  warn: "var(--warn)",
  need: "var(--need)",
  readonly: "var(--readonly)",
  accent: "var(--accent)",
}

export function LineChart({
  series,
  height = 180,
  labels,
}: {
  series: { name: string; tone: Tone; data: number[] }[]
  height?: number
  labels?: string[]
}) {
  const [hidden, setHidden] = useState<Record<string, boolean>>({})
  const w = 640
  const padL = 34
  const padB = 20
  const visible = series.filter((s) => !hidden[s.name])
  const all = visible.flatMap((s) => s.data)
  const max = Math.max(1, ...all)
  const n = series[0]?.data.length ?? 1
  const px = (i: number) => padL + (i / (n - 1)) * (w - padL - 6)
  const py = (v: number) => 8 + (1 - v / max) * (height - padB - 8)
  const grid = [0, 0.25, 0.5, 0.75, 1]
  return (
    <div>
      <svg viewBox={`0 0 ${w} ${height}`} className="w-full" preserveAspectRatio="none" style={{ height }}>
        {grid.map((g) => (
          <g key={g}>
            <line x1={padL} x2={w - 6} y1={py(g * max)} y2={py(g * max)} stroke="var(--border)" strokeWidth="1" />
            <text x={4} y={py(g * max) + 3} fill="var(--fg-subtle)" fontSize="9" className="font-mono">
              {Math.round(g * max)}
            </text>
          </g>
        ))}
        {visible.map((s) => {
          const line = s.data.map((d, i) => `${i === 0 ? "M" : "L"}${px(i).toFixed(1)},${py(d).toFixed(1)}`).join(" ")
          return (
            <g key={s.name}>
              <path
                d={`${line} L${px(n - 1)},${height - padB} L${padL},${height - padB} Z`}
                fill={toneVar[s.tone]}
                opacity="0.08"
              />
              <path d={line} fill="none" stroke={toneVar[s.tone]} strokeWidth="1.5" strokeLinejoin="round" />
            </g>
          )
        })}
        {labels &&
          labels.map((l, i) => (
            <text
              key={i}
              x={px((i / (labels.length - 1)) * (n - 1))}
              y={height - 6}
              fill="var(--fg-subtle)"
              fontSize="9"
              textAnchor="middle"
              className="font-mono"
            >
              {l}
            </text>
          ))}
      </svg>
      <ChartLegend series={series} hidden={hidden} onToggle={(n) => setHidden((h) => ({ ...h, [n]: !h[n] }))} />
    </div>
  )
}

export function BarChart({
  groups,
  series,
  height = 180,
  stacked,
}: {
  groups: string[]
  series: { name: string; tone: Tone; data: number[] }[]
  height?: number
  stacked?: boolean
}) {
  const [hidden, setHidden] = useState<Record<string, boolean>>({})
  const w = 640
  const padL = 34
  const padB = 22
  const visible = series.filter((s) => !hidden[s.name])
  const totals = groups.map((_, gi) =>
    stacked
      ? visible.reduce((a, s) => a + s.data[gi], 0)
      : Math.max(...visible.map((s) => s.data[gi]), 0),
  )
  const max = Math.max(1, ...totals)
  const py = (v: number) => 8 + (1 - v / max) * (height - padB - 8)
  const gw = (w - padL - 6) / groups.length
  return (
    <div>
      <svg viewBox={`0 0 ${w} ${height}`} className="w-full" style={{ height }}>
        {[0, 0.5, 1].map((g) => (
          <g key={g}>
            <line x1={padL} x2={w - 6} y1={py(g * max)} y2={py(g * max)} stroke="var(--border)" />
            <text x={4} y={py(g * max) + 3} fill="var(--fg-subtle)" fontSize="9" className="font-mono">
              {Math.round(g * max)}
            </text>
          </g>
        ))}
        {groups.map((grp, gi) => {
          const bw = stacked ? gw * 0.5 : (gw * 0.7) / Math.max(1, visible.length)
          let stackY = height - padB
          return (
            <g key={grp}>
              {visible.map((s, si) => {
                const h = (s.data[gi] / max) * (height - padB - 8)
                const x = stacked
                  ? padL + gi * gw + gw * 0.25
                  : padL + gi * gw + gw * 0.15 + si * bw
                const y = stacked ? stackY - h : height - padB - h
                if (stacked) stackY -= h
                return <rect key={s.name} x={x} y={y} width={bw} height={Math.max(0, h)} fill={toneVar[s.tone]} rx="1" />
              })}
              <text
                x={padL + gi * gw + gw / 2}
                y={height - 7}
                fill="var(--fg-subtle)"
                fontSize="9"
                textAnchor="middle"
              >
                {grp}
              </text>
            </g>
          )
        })}
      </svg>
      <ChartLegend series={series} hidden={hidden} onToggle={(n) => setHidden((h) => ({ ...h, [n]: !h[n] }))} />
    </div>
  )
}

function ChartLegend({
  series,
  hidden,
  onToggle,
}: {
  series: { name: string; tone: Tone }[]
  hidden: Record<string, boolean>
  onToggle: (n: string) => void
}) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-3 px-1">
      {series.map((s) => (
        <button
          key={s.name}
          onClick={() => onToggle(s.name)}
          className={cx("flex items-center gap-1.5 text-[12px] transition-opacity", hidden[s.name] ? "opacity-35" : "")}
        >
          <span className="size-2 rounded-sm" style={{ background: toneVar[s.tone] }} />
          <span className="text-fg-muted">{s.name}</span>
        </button>
      ))}
    </div>
  )
}

export function Gauge({
  value,
  max,
  label,
  tone = "accent",
  unit,
}: {
  value: number
  max: number
  label: string
  tone?: Tone
  unit?: string
}) {
  const pct = Math.min(1, value / max)
  const r = 46
  const c = Math.PI * r
  return (
    <div className="flex flex-col items-center">
      <svg viewBox="0 0 120 70" className="w-full max-w-[160px]">
        <path d="M8,64 A52,52 0 0 1 112,64" fill="none" stroke="var(--border)" strokeWidth="8" strokeLinecap="round" />
        <path
          d="M8,64 A52,52 0 0 1 112,64"
          fill="none"
          stroke={toneVar[tone]}
          strokeWidth="8"
          strokeLinecap="round"
          strokeDasharray={`${pct * c} ${c}`}
          transform="scale(0.865) translate(9.5 9.5)"
        />
        <text x="60" y="56" textAnchor="middle" fontSize="20" className="font-mono" fill="var(--fg)" fontWeight="600">
          {value}
        </text>
      </svg>
      <div className="text-[13px] text-fg-muted">
        {label}
        {unit && <span className="text-fg-subtle"> {unit}</span>}
      </div>
    </div>
  )
}

export function ProgressBar({ value, tone = "accent", threshold }: { value: number; tone?: Tone; threshold?: number }) {
  return (
    <div className="relative h-2 w-full overflow-hidden rounded-full bg-bg-elevated">
      <div className="h-full rounded-full transition-all" style={{ width: `${value * 100}%`, background: toneVar[tone] }} />
      {threshold !== undefined && (
        <div className="absolute top-0 h-full w-px bg-fg" style={{ left: `${threshold * 100}%` }} />
      )}
    </div>
  )
}

export function Ring({ value, size = 44, tone = "warn" }: { value: number; size?: number; tone?: Tone }) {
  const r = size / 2 - 4
  const c = 2 * Math.PI * r
  return (
    <svg width={size} height={size} className="-rotate-90">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--border)" strokeWidth="4" />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={r}
        fill="none"
        stroke={toneVar[tone]}
        strokeWidth="4"
        strokeLinecap="round"
        strokeDasharray={`${value * c} ${c}`}
      />
      <text
        x={size / 2}
        y={size / 2}
        className="font-mono"
        style={{ transform: "rotate(90deg)", transformOrigin: "center" }}
        textAnchor="middle"
        dominantBaseline="central"
        fontSize="10"
        fill="var(--fg)"
      >
        {Math.round(value * 100)}%
      </text>
    </svg>
  )
}

/* ------------------------------------------------------------------ */
/* Table                                                               */
/* ------------------------------------------------------------------ */

export function Table({ children }: { children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-[14px]">{children}</table>
    </div>
  )
}

export function Th({
  children,
  sortable,
  align = "left",
  className,
}: {
  children?: React.ReactNode
  sortable?: boolean
  align?: "left" | "right" | "center"
  className?: string
}) {
  return (
    <th
      className={cx(
        "border-b border-border bg-bg-elevated px-3 py-2 text-[13px] font-medium text-fg-muted whitespace-nowrap",
        align === "right" && "text-right",
        align === "center" && "text-center",
        align === "left" && "text-left",
        sortable && "cursor-pointer select-none hover:text-fg",
        className,
      )}
    >
      <span className="inline-flex items-center gap-1">
        {children}
        {sortable && <span className="text-fg-subtle">↕</span>}
      </span>
    </th>
  )
}

export function Td({
  children,
  align = "left",
  mono,
  className,
}: {
  children?: React.ReactNode
  align?: "left" | "right" | "center"
  mono?: boolean
  className?: string
}) {
  return (
    <td
      className={cx(
        "border-b border-border/60 px-3 py-2 align-middle text-fg",
        align === "right" && "text-right",
        align === "center" && "text-center",
        mono && "font-mono tnum text-[13.5px]",
        className,
      )}
    >
      {children}
    </td>
  )
}

export function Tr({
  children,
  onClick,
  active,
}: {
  children: React.ReactNode
  onClick?: () => void
  active?: boolean
}) {
  return (
    <tr
      onClick={onClick}
      className={cx(
        "even:bg-bg-elevated/40",
        onClick && "cursor-pointer hover:bg-card-hover",
        active && "bg-accent/10 hover:bg-accent/10",
      )}
    >
      {children}
    </tr>
  )
}

export function TableSummary({ count, ms, right }: { count: number; ms?: number; right?: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between border-b border-border bg-bg-elevated/60 px-3 py-1.5 text-[12px] text-fg-muted">
      <span className="font-mono tnum">
        共 {count.toLocaleString()} 条{ms !== undefined && <span className="text-fg-subtle"> · 耗时 {ms} ms</span>}
      </span>
      {right}
    </div>
  )
}

export function Pagination({
  page,
  pages,
  pageSize,
  onPage,
  onPageSize,
}: {
  page: number
  pages: number
  pageSize: number
  onPage: (p: number) => void
  onPageSize?: (s: number) => void
}) {
  return (
    <div className="flex items-center justify-end gap-3 px-3 py-2 text-[13px] text-fg-muted">
      {onPageSize && (
        <label className="flex items-center gap-1.5">
          每页
          <Select value={pageSize} onChange={(e) => onPageSize(Number(e.target.value))} className="h-6 py-0 text-[13px]">
            {[10, 20, 50, 100].map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
          条
        </label>
      )}
      <span className="font-mono tnum">
        第 {page}/{pages} 页
      </span>
      <div className="flex gap-1">
        <Button size="sm" variant="ghost" disabled={page <= 1} onClick={() => onPage(page - 1)}>
          ‹
        </Button>
        <Button size="sm" variant="ghost" disabled={page >= pages} onClick={() => onPage(page + 1)}>
          ›
        </Button>
      </div>
    </div>
  )
}

export function SkeletonRows({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <tbody>
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }).map((_, c) => (
            <td key={c} className="border-b border-border/60 px-3 py-2.5">
              <div className="skeleton h-3.5" style={{ width: `${40 + ((r + c) % 4) * 15}%` }} />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  )
}

/* ------------------------------------------------------------------ */
/* Segmented / Tabs / Toggle                                           */
/* ------------------------------------------------------------------ */

export function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="inline-flex rounded border border-border bg-bg-elevated p-0.5">
      {options.map((o) => (
        <button
          key={o.value}
          onClick={() => onChange(o.value)}
          className={cx(
            "rounded px-2.5 py-1 text-[13px] font-medium transition-colors",
            value === o.value ? "bg-card text-fg shadow-sm" : "text-fg-muted hover:text-fg",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
}: {
  tabs: { value: T; label: string; badge?: React.ReactNode }[]
  value: T
  onChange: (v: T) => void
}) {
  return (
    <div className="flex items-center gap-1 border-b border-border">
      {tabs.map((t) => (
        <button
          key={t.value}
          onClick={() => onChange(t.value)}
          className={cx(
            "flex items-center gap-1.5 border-b-2 px-3 py-2 text-[14px] font-medium transition-colors -mb-px",
            value === t.value
              ? "border-accent text-fg"
              : "border-transparent text-fg-muted hover:text-fg",
          )}
        >
          {t.label}
          {t.badge}
        </button>
      ))}
    </div>
  )
}

export function Toggle({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <button
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className={cx(
        "relative h-5 w-9 shrink-0 rounded-full border transition-colors",
        checked ? "border-hit bg-hit/80" : "border-border-strong bg-bg-elevated",
      )}
    >
      {/* 拇指必须显式 left-0：absolute 无 left 时取 static 位置，而 button 内容默认
          水平居中——起点跑到轨道中间，加上位移就溢出右缘，会导致样式 bug */}
      <span
        className={cx(
          "absolute top-0.5 left-0.5 size-3.5 rounded-full bg-white shadow-sm transition-transform",
          checked ? "translate-x-4" : "translate-x-0",
        )}
      />
    </button>
  )
}

/* ------------------------------------------------------------------ */
/* JSON tree                                                           */
/* ------------------------------------------------------------------ */

export function JsonTree({ data, name, depth = 0 }: { data: unknown; name?: string; depth?: number }) {
  const [open, setOpen] = useState(depth < 2)
  const [copied, setCopied] = useState(false)
  const isObj = data !== null && typeof data === "object"
  const indent = { paddingLeft: depth * 14 }

  const copy = (e: React.MouseEvent) => {
    e.stopPropagation()
    navigator.clipboard?.writeText(JSON.stringify(data, null, 2))
    setCopied(true)
    setTimeout(() => setCopied(false), 1200)
  }

  if (!isObj) {
    let cls = "text-fg"
    if (typeof data === "string") cls = "text-hit"
    else if (typeof data === "number") cls = "text-warn"
    else if (typeof data === "boolean") cls = "text-need"
    else if (data === null) cls = "text-fg-subtle"
    return (
      <div className="group flex items-center gap-2 font-mono text-[13px] leading-5" style={indent}>
        {name !== undefined && <span className="text-accent">{name}:</span>}
        <span className={cls}>{typeof data === "string" ? `"${data}"` : String(data)}</span>
      </div>
    )
  }

  const entries = Array.isArray(data) ? data.map((v, i) => [i, v] as const) : Object.entries(data as object)
  const bracket = Array.isArray(data) ? ["[", "]"] : ["{", "}"]

  return (
    <div className="font-mono text-[13px] leading-5">
      <div
        className="group flex cursor-pointer items-center gap-1 hover:bg-card-hover"
        style={indent}
        onClick={() => setOpen(!open)}
      >
        <span className="w-3 text-fg-subtle">{open ? "▾" : "▸"}</span>
        {name !== undefined && <span className="text-accent">{name}:</span>}
        <span className="text-fg-subtle">
          {bracket[0]}
          {!open && ` …${entries.length} `}
          {!open && bracket[1]}
        </span>
        <button
          onClick={copy}
          className="ml-auto mr-1 rounded px-1 text-[11px] text-fg-subtle opacity-0 transition-opacity hover:text-accent group-hover:opacity-100"
        >
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      {open && (
        <div>
          {entries.map(([k, v]) => (
            <JsonTree key={String(k)} name={String(k)} data={v} depth={depth + 1} />
          ))}
          <div className="text-fg-subtle" style={indent}>
            <span className="w-3 inline-block" />
            {bracket[1]}
          </div>
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Drawer / Modal / Empty / Banner / Toast                             */
/* ------------------------------------------------------------------ */

export function Drawer({
  open,
  onClose,
  title,
  children,
  width = "42%",
}: {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  children: React.ReactNode
  width?: string
}) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-40">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div
        className="drawer-in absolute right-0 top-0 flex h-full min-w-[420px] flex-col border-l border-border bg-card shadow-2xl"
        style={{ width }}
      >
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <div className="text-[14px] font-semibold">{title}</div>
          <Button variant="ghost" size="sm" onClick={onClose}>
            ✕
          </Button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
      </div>
    </div>
  )
}

export function Modal({
  open,
  onClose,
  title,
  danger,
  children,
  footer,
  className,
}: {
  open: boolean
  onClose: () => void
  title: React.ReactNode
  danger?: boolean
  children: React.ReactNode
  footer?: React.ReactNode
  /** 面板附加类（如 max-h-* 限制高度，超出部分正文内部滚动）。 */
  className?: string
}) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div
        className={cx(
          "fade-in relative flex max-h-full w-full max-w-lg flex-col rounded-lg border border-border bg-card shadow-2xl",
          className,
        )}
      >
        <div className="shrink-0">
          <div className="flex items-center gap-2 border-b border-border px-4 py-3">
            {danger && <span className="text-error">⚠</span>}
            <div className={cx("text-[15px] font-semibold", danger && "text-error")}>{title}</div>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4 text-[14px] text-fg-muted">
          {children}
        </div>
        {footer && (
          <div className="flex shrink-0 justify-end gap-2 border-t border-border px-4 py-3">
            {footer}
          </div>
        )}
      </div>
    </div>
  )
}

export function EmptyState({
  icon = "◍",
  title,
  desc,
  action,
  tone = "neutral",
}: {
  icon?: React.ReactNode
  title: string
  desc?: string
  action?: React.ReactNode
  tone?: Tone
}) {
  return (
    <div
      className={cx(
        "flex flex-col items-center justify-center gap-2 rounded-md border border-dashed px-6 py-10 text-center",
        tone === "need" ? "border-need/40 bg-need/5" : "border-border bg-bg-elevated/40",
      )}
    >
      <div className={cx("text-2xl", tone === "need" ? "text-need" : "text-fg-subtle")}>{icon}</div>
      <div className="text-[14px] font-medium text-fg">{title}</div>
      {desc && <div className="max-w-md text-[13px] text-fg-muted">{desc}</div>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  )
}

export function Banner({
  tone = "need",
  children,
  right,
}: {
  tone?: Tone
  children: React.ReactNode
  right?: React.ReactNode
}) {
  return (
    <div
      className={cx(
        "flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-[14px]",
        toneStyle[tone],
      )}
    >
      <div className="flex items-center gap-2">{children}</div>
      {right}
    </div>
  )
}

const toneText: Record<Tone, string> = {
  hit: "text-hit",
  miss: "text-miss",
  error: "text-error",
  warn: "text-warn",
  need: "text-need",
  readonly: "text-readonly",
  accent: "text-accent",
  neutral: "text-fg",
}

export function ReportPanel({
  title,
  tone = "hit",
  items,
}: {
  title: string
  tone?: Tone
  items: { label: string; value: React.ReactNode; tone?: Tone }[]
}) {
  return (
    <div className={cx("rounded-md border p-3", toneStyle[tone])}>
      <div className="mb-2 text-[14px] font-semibold">{title}</div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {items.map((it) => (
          <div key={it.label} className="rounded border border-border bg-card px-2.5 py-2">
            <div className="text-[12px] text-fg-muted">{it.label}</div>
            <div className={cx("font-mono text-lg font-semibold tnum", it.tone ? toneText[it.tone] : "text-fg")}>
              {it.value}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/* Toast host */
type Toast = { id: number; msg: string; tone: Tone }
let toastPush: ((t: Omit<Toast, "id">) => void) | null = null
export function toast(msg: string, tone: Tone = "hit") {
  toastPush?.({ msg, tone })
}
export function ToastHost() {
  const [items, setItems] = useState<Toast[]>([])
  useEffect(() => {
    toastPush = (t) => {
      const id = Date.now() + Math.random()
      setItems((s) => [...s, { ...t, id }])
      setTimeout(() => setItems((s) => s.filter((x) => x.id !== id)), 3200)
    }
    return () => {
      toastPush = null
    }
  }, [])
  return (
    <div className="fixed right-4 top-16 z-[60] flex w-72 flex-col gap-2">
      {items.map((t) => (
        <div
          key={t.id}
          className={cx("fade-in flex items-center gap-2 rounded-md border px-3 py-2 text-[14px] shadow-lg bg-card", toneStyle[t.tone])}
        >
          <Dot tone={t.tone} />
          <span className="text-fg">{t.msg}</span>
        </div>
      ))}
    </div>
  )
}

export function LockCell({ children }: { children?: React.ReactNode }) {
  return (
    <span
      className="inline-flex items-center gap-1 text-fg-subtle"
      title="按访问策略裁剪"
    >
      <span>🔒</span>
      <span className="text-fg-subtle/70">{children ?? "••••"}</span>
    </span>
  )
}
