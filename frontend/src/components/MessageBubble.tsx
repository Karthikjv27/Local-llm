import type { MessageView } from '../api/types'
import { duration } from '../lib/format'

export function MessageBubble({ message }: { message: MessageView }) {
  const isUser = message.role === 'USER'
  const hasMetrics =
    !isUser &&
    (message.completionTokens != null || message.tokensPerSec != null || message.latencyMs != null)

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div
        className={`max-w-[46rem] rounded-lg px-3.5 py-2.5 text-sm leading-relaxed ring-1 ${
          isUser
            ? 'bg-emerald-600/90 text-white ring-emerald-500/40'
            : 'bg-zinc-900 text-zinc-100 ring-zinc-800'
        }`}
      >
        <p className="whitespace-pre-wrap break-words">{message.content || (message.error ? '' : '…')}</p>

        {message.error && <p className="mt-2 text-xs text-red-400">{message.error}</p>}

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
    </div>
  )
}
