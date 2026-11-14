import type { MessageView } from '../api/types'
import { duration } from '../lib/format'
import { MarkdownText } from './MarkdownText'

interface MessageBubbleProps {
  message: MessageView
  canRegenerate?: boolean
  onRegenerate?: () => void
  /** Deletes this message and everything after it. */
  onTruncate?: () => void
}

export function MessageBubble({ message, canRegenerate = false, onRegenerate, onTruncate }: MessageBubbleProps) {
  const isUser = message.role === 'USER'
  const hasMetrics =
    !isUser &&
    (message.completionTokens != null || message.tokensPerSec != null || message.latencyMs != null)

  return (
    <div className={`group flex flex-col gap-1 ${isUser ? 'items-end' : 'items-start'}`}>
      <div
        className={`max-w-[46rem] rounded-lg px-3.5 py-2.5 ring-1 ${
          isUser ? 'bg-emerald-600/90 text-white ring-emerald-500/40' : 'bg-zinc-900 text-zinc-100 ring-zinc-800'
        }`}
      >
        {isUser ? (
          <p className="whitespace-pre-wrap break-words text-sm leading-relaxed">{message.content}</p>
        ) : message.content ? (
          <MarkdownText text={message.content} />
        ) : (
          <p className="text-sm text-zinc-500">{message.error || message.cancelled ? '' : '…'}</p>
        )}

        {message.error && <p className="mt-2 text-xs text-red-400">{message.error}</p>}
        {message.cancelled && <p className="mt-1.5 text-[11px] text-amber-400/80">stopped</p>}

        {hasMetrics && (
          <p className="mt-1.5 text-[11px] text-zinc-500">
            {[
              message.completionTokens != null ? `${message.completionTokens} tok` : null,
              message.tokensPerSec != null ? `${message.tokensPerSec} tok/s` : null,
              message.latencyMs != null ? duration(message.latencyMs) : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </p>
        )}
      </div>

      {(canRegenerate && onRegenerate) || onTruncate ? (
        <div className="flex items-center gap-2">
          {canRegenerate && onRegenerate && (
            <button
              type="button"
              onClick={onRegenerate}
              className="rounded px-1 text-[11px] text-zinc-600 transition hover:text-zinc-300"
            >
              ↻ Regenerate
            </button>
          )}
          {onTruncate && (
            <button
              type="button"
              onClick={onTruncate}
              title="Delete this message and everything after it"
              className="rounded px-1 text-[11px] text-zinc-600 opacity-0 transition group-hover:opacity-100 hover:text-red-400 focus:opacity-100"
            >
              ✂ Rewind to here
            </button>
          )}
        </div>
      ) : null}
    </div>
  )
}
