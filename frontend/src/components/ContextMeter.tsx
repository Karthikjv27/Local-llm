import type { ContextView } from '../api/types'

function compact(tokens: number): string {
  if (tokens >= 1000) {
    return `${(tokens / 1000).toFixed(1).replace(/\.0$/, '')}k`
  }
  return String(tokens)
}

/** Shows how much of the model's context window the next request will use. */
export function ContextMeter({ context }: { context: ContextView | undefined }) {
  if (!context || context.limitTokens <= 0) {
    return null
  }

  const percent = Math.min(100, Math.round((context.usedTokens / context.limitTokens) * 100))
  const tone =
    context.trimmed || percent >= 95 ? 'bg-red-500' : percent >= 80 ? 'bg-amber-500' : 'bg-emerald-500'
  const title = context.trimmed
    ? `Context window: ${context.usedTokens} of ${context.limitTokens} estimated tokens. ` +
      `${context.includedMessages} of ${context.consideredMessages} messages are sent — older turns are left out.`
    : `Context window: ${context.usedTokens} of ${context.limitTokens} estimated tokens ` +
      `(${context.includedMessages} messages).`

  return (
    <div title={title} className="flex items-center gap-1.5">
      <div className="h-1 w-16 overflow-hidden rounded-full bg-zinc-800">
        <div className={`h-full rounded-full ${tone}`} style={{ width: `${percent}%` }} />
      </div>
      <span className={`text-[11px] tabular-nums ${context.trimmed ? 'text-amber-400' : 'text-zinc-500'}`}>
        {compact(context.usedTokens)}/{compact(context.limitTokens)}
      </span>
    </div>
  )
}
