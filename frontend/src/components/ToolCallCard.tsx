import type { ToolCallView } from '../api/types'

interface ToolCallCardProps {
  call: ToolCallView
  busy: boolean
  onApprove: (id: string) => void
  onDeny: (id: string) => void
  onContinue: (id: string) => void
}

const STATUS_STYLE: Record<ToolCallView['status'], string> = {
  PENDING: 'border-amber-700/50 bg-amber-950/30 text-amber-300',
  APPROVED: 'border-emerald-800/50 bg-emerald-950/30 text-emerald-300',
  DENIED: 'border-zinc-700 bg-zinc-900 text-zinc-400',
  FAILED: 'border-red-900/50 bg-red-950/30 text-red-300',
}

export function ToolCallCard({ call, busy, onApprove, onDeny, onContinue }: ToolCallCardProps) {
  const pending = call.status === 'PENDING'

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-950/60 px-3 py-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[11px] font-semibold uppercase tracking-wide text-zinc-500">wants to run</span>
        <code className="rounded bg-zinc-900 px-1.5 py-0.5 text-xs text-zinc-200">
          {call.commandId}
          {call.args ? ` ${call.args}` : ''}
        </code>
        {call.destructive && (
          <span className="rounded border border-red-800/60 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-red-400">
            destructive
          </span>
        )}
        <span className={`ml-auto rounded border px-1.5 py-0.5 text-[10px] font-semibold ${STATUS_STYLE[call.status]}`}>
          {call.status.toLowerCase()}
        </span>
      </div>

      <p className="mt-1 text-[11px] text-zinc-500">
        {call.label}
        {call.durationMs != null ? ` · ${call.durationMs} ms` : ''}
        {call.exitCode != null ? ` · exit ${call.exitCode}` : ''}
      </p>

      {pending && (
        <div className="mt-2 flex items-center gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={() => onApprove(call.id)}
            className="rounded-md bg-emerald-600 px-2.5 py-1 text-[11px] font-medium text-white transition hover:bg-emerald-500 disabled:opacity-40"
          >
            Approve &amp; run
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => onDeny(call.id)}
            className="rounded-md border border-zinc-700 px-2.5 py-1 text-[11px] text-zinc-300 transition hover:bg-zinc-800 disabled:opacity-40"
          >
            Deny
          </button>
          <span className="text-[10px] text-zinc-600">nothing runs without this</span>
        </div>
      )}

      {call.output && (
        <pre className="mt-2 max-h-40 overflow-auto rounded bg-zinc-950 px-2 py-1.5 text-[11px] leading-relaxed text-zinc-400">
          {call.output}
        </pre>
      )}

      {call.error && <p className="mt-2 text-[11px] text-red-400">{call.error}</p>}

      {!pending && (
        <div className="mt-2">
          <button
            type="button"
            disabled={busy}
            onClick={() => onContinue(call.id)}
            className="rounded border border-zinc-700 px-2 py-1 text-[11px] text-zinc-300 transition hover:bg-zinc-800 disabled:opacity-40"
          >
            Continue with this result
          </button>
        </div>
      )}
    </div>
  )
}
