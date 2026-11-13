import { useCallback, useEffect, useRef, useState } from 'react'
import { api, streamCommand } from '../api/client'
import type { CommandDefinitionView, CommandRunView, ModelView } from '../api/types'
import { duration } from '../lib/format'

const MAX_LIVE_LINES = 200

function statusTone(status: string): string {
  if (status === 'OK') {
    return 'text-emerald-400'
  }
  if (status === 'RUNNING') {
    return 'text-amber-400'
  }
  return 'text-red-400'
}

/**
 * `ollama pull` redraws a progress bar in place, and every redraw arrives as its own line. Keep one
 * entry per bar (keyed by its digest prefix) so the panel grows instead of flickering.
 */
function mergeLine(lines: string[], incoming: string): string[] {
  if (lines.length > 0 && lines[lines.length - 1] === incoming) {
    return lines
  }

  const bar = incoming.match(/^(.{0,80}?:)\s/)
  const isProgress = bar !== null && /%|\d\s*[KMGT]?B\s*\//.test(incoming)
  let next: string[]

  if (isProgress) {
    const key = bar[1]
    const index = lines.findIndex((line) => line.startsWith(key))
    next =
      index >= 0 ? lines.map((line, at) => (at === index ? incoming : line)) : [...lines, incoming]
  } else {
    next = [...lines, incoming]
  }

  return next.length > MAX_LIVE_LINES ? next.slice(next.length - MAX_LIVE_LINES) : next
}

interface ActionsPanelProps {
  models: ModelView[]
  chatId: string | null
}

export function ActionsPanel({ models, chatId }: ActionsPanelProps) {
  const [definitions, setDefinitions] = useState<CommandDefinitionView[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [args, setArgs] = useState('')
  const [result, setResult] = useState<CommandRunView | null>(null)
  const [live, setLive] = useState<string[]>([])
  const [history, setHistory] = useState<CommandRunView[]>([])
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const liveBox = useRef<HTMLPreElement>(null)

  const load = useCallback(async () => {
    try {
      const [available, recent] = await Promise.all([api.commands(), api.commandHistory()])
      setDefinitions(available)
      setHistory(recent)
      setSelectedId((current) => current || available[0]?.id || '')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load commands')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    if (liveBox.current) {
      liveBox.current.scrollTop = liveBox.current.scrollHeight
    }
  }, [live])

  const selected = definitions.find((definition) => definition.id === selectedId) ?? null

  const execute = async () => {
    if (!selected || running) {
      return
    }
    const value = args.trim()
    if (selected.requiresArguments && !value) {
      setError(`${selected.label} needs an argument`)
      return
    }
    if (selected.destructive && !window.confirm(`${selected.label} cannot be undone. Run it for "${value}"?`)) {
      return
    }

    setRunning(true)
    setError(null)
    setLive([])

    let saved: CommandRunView | null = null
    let failure: string | null = null

    try {
      await streamCommand(
        selected.id,
        selected.requiresArguments ? value : '',
        chatId,
        (event) => {
          if (event.type === 'line') {
            setLive((current) => mergeLine(current, event.line))
          } else if (event.type === 'done') {
            saved = event.run
          } else if (event.type === 'error') {
            failure = event.error
          }
        },
      )

      if (saved) {
        setResult(saved)
      }
      if (failure) {
        setError(failure)
      }
      setHistory(await api.commandHistory())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Command failed')
    } finally {
      setRunning(false)
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <section className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-3">
        <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Run a command</p>

        <select
          value={selectedId}
          onChange={(event) => {
            setSelectedId(event.target.value)
            setArgs('')
          }}
          className="w-full rounded border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px] text-zinc-200 focus:border-zinc-700 focus:outline-none"
        >
          {definitions.map((definition) => (
            <option key={definition.id} value={definition.id}>
              {definition.label}
            </option>
          ))}
        </select>

        {selected && <p className="mt-1 text-[10px] leading-snug text-zinc-600">{selected.description}</p>}

        {selected?.requiresArguments && (
          <>
            <input
              value={args}
              onChange={(event) => setArgs(event.target.value)}
              list="locie-model-names"
              placeholder="model name"
              className="mt-2 w-full rounded border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px] text-zinc-200 placeholder:text-zinc-600 focus:border-zinc-700 focus:outline-none"
            />
            <datalist id="locie-model-names">
              {models.map((model) => (
                <option key={model.name} value={model.name} />
              ))}
            </datalist>
          </>
        )}

        <button
          type="button"
          onClick={() => void execute()}
          disabled={!selected || running}
          className={`mt-2 w-full rounded px-2 py-1.5 text-[11px] font-medium text-white transition disabled:opacity-40 ${
            selected?.destructive ? 'bg-red-600 hover:bg-red-500' : 'bg-emerald-600 hover:bg-emerald-500'
          }`}
        >
          {running ? 'Running…' : selected?.destructive ? `Run ${selected.label.toLowerCase()}` : 'Run'}
        </button>

        {error && <p className="mt-2 text-[10px] text-red-400">{error}</p>}
      </section>

      {running && (
        <section className="rounded-lg border border-amber-900/60 bg-zinc-900/40 p-3">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[10px] font-medium text-amber-400">
              <span className="mr-1 inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400 align-middle" />
              streaming
            </span>
            <span className="text-[10px] text-zinc-600">{selected?.timeoutSeconds}s limit</span>
          </div>
          <pre
            ref={liveBox}
            className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words rounded border border-zinc-800 bg-zinc-950 p-2 text-[10px] leading-relaxed text-zinc-300"
          >
            {live.length > 0 ? live.join('\n') : 'waiting for output…'}
          </pre>
        </section>
      )}

      {!running && result && (
        <section className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-3">
          <div className="flex items-center justify-between gap-2">
            <span className={`text-[10px] font-medium ${statusTone(result.status)}`}>
              {result.status}
              {result.exitCode != null ? ` · exit ${result.exitCode}` : ''}
            </span>
            <span className="text-[10px] text-zinc-600">
              {result.durationMs != null ? duration(result.durationMs) : ''}
            </span>
          </div>
          <p className="mt-1 truncate text-[10px] text-zinc-500" title={result.command}>
            {result.command}
          </p>
          <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words rounded border border-zinc-800 bg-zinc-950 p-2 text-[10px] leading-relaxed text-zinc-300">
            {result.stdout?.trim() ? result.stdout : '(no output)'}
            {result.stderr ? `\n--- stderr ---\n${result.stderr}` : ''}
          </pre>
        </section>
      )}

      <section className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-3">
        <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Recent runs</p>
        {history.length === 0 ? (
          <p className="text-[10px] text-zinc-600">Nothing run yet</p>
        ) : (
          <ul className="space-y-0.5">
            {history.slice(0, 8).map((run) => (
              <li key={run.id}>
                <button
                  type="button"
                  onClick={() => setResult(run)}
                  className="w-full truncate rounded px-1.5 py-1 text-left text-[10px] text-zinc-500 transition hover:bg-zinc-800/60 hover:text-zinc-300"
                  title={run.command}
                >
                  <span className={statusTone(run.status)}>●</span> {run.command}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
