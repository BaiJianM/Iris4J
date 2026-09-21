import ReactMarkdown from "react-markdown"
import remarkBreaks from "remark-breaks"
import remarkGfm from "remark-gfm"
import type { ReactNode } from "react"

/**
 * 轻量 Markdown 渲染（Agent 回答区）：模型输出常含 **加粗**、列表、代码、表格，
 * 此前按纯文本上屏导致标记符号裸露。基于 react-markdown + remark-gfm（GFM 表格/
 * 删除线/任务列表），样式对齐控制台暗色主题；内联代码等宽高亮，代码块带边框横向滚动。
 * XSS：react-markdown 默认不渲染原始 HTML，链接仅 http/https 协议可点击。
 */

const proseClass = "text-[14px] leading-relaxed [&>*:first-child]:mt-0 [&>*:last-child]:mb-0"

/** 段内元素共用字号/颜色，间距用 mt 控制紧凑排布。 */
function renderMarkdown(text: string): ReactNode {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm, remarkBreaks]}
      components={{
        h1: (p) => <h1 className="mb-2 mt-3 text-[16px] font-semibold text-fg" {...p} />,
        h2: (p) => <h2 className="mb-2 mt-3 text-[15px] font-semibold text-fg" {...p} />,
        h3: (p) => <h3 className="mb-1.5 mt-2.5 text-[14px] font-semibold text-fg" {...p} />,
        p: (p) => <p className="my-1.5" {...p} />,
        strong: (p) => <strong className="font-semibold text-fg" {...p} />,
        em: (p) => <em className="italic" {...p} />,
        del: (p) => <del className="text-fg-subtle" {...p} />,
        a: (p) => (
          <a className="text-accent underline decoration-dotted underline-offset-2" target="_blank" rel="noreferrer" {...p} />
        ),
        ul: (p) => <ul className="my-1.5 list-disc space-y-0.5 pl-5" {...p} />,
        ol: (p) => <ol className="my-1.5 list-decimal space-y-0.5 pl-5" {...p} />,
        li: (p) => <li className="marker:text-fg-subtle" {...p} />,
        blockquote: (p) => (
          <blockquote className="my-1.5 border-l-2 border-border pl-2.5 text-fg-muted" {...p} />
        ),
        hr: () => <hr className="my-2.5 border-border" />,
        code: ({ className, children, ...rest }) => {
          const isBlock = /language-/.test(className ?? "")
          if (isBlock) {
            return (
              <code className="block font-mono text-[12.5px] leading-relaxed text-fg" {...rest}>
                {children}
              </code>
            )
          }
          return (
            <code
              className="rounded border border-border bg-bg-elevated px-1 py-0.5 font-mono text-[12.5px] text-accent"
              {...rest}
            >
              {children}
            </code>
          )
        },
        pre: (p) => (
          <pre
            className="my-1.5 overflow-x-auto rounded-md border border-border bg-bg-elevated px-2.5 py-2"
            {...p}
          />
        ),
        table: (p) => (
          <div className="my-1.5 overflow-x-auto">
            <table className="w-full border-collapse text-[13px]" {...p} />
          </div>
        ),
        thead: (p) => <thead className="bg-bg-elevated" {...p} />,
        th: (p) => <th className="border border-border px-2 py-1 text-left font-medium text-fg" {...p} />,
        td: (p) => <td className="border border-border px-2 py-1 align-top" {...p} />,
      }}
    >
      {text}
    </ReactMarkdown>
  )
}

/** 用户消息仍按纯文本渲染（用户输入不该被当 Markdown 解释）。 */
export function MarkdownAnswer({ text }: { text: string }) {
  return <div className={proseClass}>{renderMarkdown(text)}</div>
}
