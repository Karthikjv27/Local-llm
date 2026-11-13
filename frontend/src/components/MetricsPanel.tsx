import type { ReactNode } from 'react'
import type { MetricsResponse } from '../api/types'
import { gigabytes } from '../lib/format'
import type { MetricsHistory } from '../hooks/useMetrics'

interface MetricsPanelProps {
  metrics: MetricsResponse | null
  history: MetricsHistory
}

function tone(percent: number): string {
  if (percent >= 85) {
    return 'text-red-400'
  }
  if (percent >= 65) {
    return 'text-amber-400'
  }
  return 'text-emerald-400'
}

function fillTone(percent: number): string {
  if (percent >= 85) {
    return 'bg-red-500'
  }
  if (percent >= 65) {
    return 'bg-amber-500'
  }
  return 'bg-emerald-500'
}

function Sparkline({ values, toneClass }: { values: number[]; toneClass: string }) {
  const width = 100
  const height = 28

  if (values.length < 2) {
    return <div className="h-7" />
  }

  const step = width / (values.length - 1)
  const points = values
    .map((value, index) => {
      const clamped = Math.min(100, Math.max(0, value))
      return `${(index * step).toFixed(2)},${(height - (clamped / 100) * height).toFixed(2)}`
    })
    .join(' ')

  return (
    <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="h-7 w-full">
      <polyline
        points={points}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
        className={toneClass}
      />
    </svg>
  )
}

function Bar({ value, toneClass }: { value: number; toneClass: string }) {
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-zinc-800">
      <div
        className={`h-full rounded-full transition-all ${toneClass}`}
        style={{ width: `${Math.min(100, Math.max(0, value))}%` }}
      />
    </div>
  )
}

function Section({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-3">
      <header className="mb-2 flex items-center justify-between gap-2">
        <p className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500">{title}</p>
        {right}
      </header>
      {children}
    </section>
  )
}

export function MetricsPanel({ metrics, history }: MetricsPanelProps) {
  if (!metrics) {
    return <p className="text-[11px] text-zinc-600">Waiting for metrics…</p>
  }

  return (
    <div className="flex flex-col gap-2">
      <Section title="CPU" right={<span className="text-[10px] text-zinc-500">{metrics.cpu.logicalProcessors} threads</span>}>
        <div className="flex items-baseline gap-1">
          <span className={`text-xl font-semibold tabular-nums ${tone(metrics.cpu.loadPercent)}`}>
            {metrics.cpu.loadPercent.toFixed(1)}
          </span>
          <span className="text-[11px] text-zinc-500">%</span>
        </div>
        <Sparkline values={history.cpu} toneClass={tone(metrics.cpu.loadPercent)} />
        <p className="mt-0.5 truncate text-[10px] text-zinc-600" title={metrics.cpu.name}>
          {metrics.cpu.name}
        </p>
        <div className="mt-2 grid grid-cols-6 gap-1">
          {metrics.cpu.perCorePercent.map((value, index) => (
            <div
              key={index}
              title={`thread ${index}: ${value.toFixed(0)}%`}
              className="flex h-8 items-end overflow-hidden rounded-sm bg-zinc-800/70"
            >
              <div
                className={`w-full rounded-sm transition-all ${fillTone(value)}`}
                style={{ height: `${Math.max(6, Math.min(100, value))}%` }}
              />
            </div>
          ))}
        </div>
      </Section>

      <Section
        title="Memory"
        right={
          <span className="text-[10px] text-zinc-500">
            {gigabytes(metrics.memory.usedBytes)} / {gigabytes(metrics.memory.totalBytes)}
          </span>
        }
      >
        <Bar value={metrics.memory.usedPercent} toneClass={fillTone(metrics.memory.usedPercent)} />
        <p className="mt-1 text-[10px] text-zinc-500">
          {metrics.memory.usedPercent.toFixed(1)}% used · {gigabytes(metrics.memory.availableBytes)} free
        </p>
      </Section>

      <Section title="GPU" right={<span className="text-[10px] text-zinc-500">{metrics.gpu.source}</span>}>
        {!metrics.gpu.available ? (
          <p className="text-[11px] text-zinc-500">{metrics.gpu.detail}</p>
        ) : (
          <>
            <div className="flex items-baseline gap-1">
              <span
                className={`text-xl font-semibold tabular-nums ${
                  metrics.gpu.utilizationPercent == null ? 'text-zinc-500' : tone(metrics.gpu.utilizationPercent)
                }`}
              >
                {metrics.gpu.utilizationPercent == null ? '—' : metrics.gpu.utilizationPercent.toFixed(0)}
              </span>
              <span className="text-[11px] text-zinc-500">
                %{metrics.gpu.temperatureC != null ? ` · ${metrics.gpu.temperatureC}°C` : ''}
              </span>
            </div>
            <Sparkline values={history.gpu} toneClass={tone(metrics.gpu.utilizationPercent ?? 0)} />

            {metrics.gpu.memoryUsedBytes != null && metrics.gpu.memoryTotalBytes != null && (
              <div className="mt-1">
                <Bar
                  value={(metrics.gpu.memoryUsedBytes / metrics.gpu.memoryTotalBytes) * 100}
                  toneClass="bg-sky-500"
                />
                <p className="mt-1 text-[10px] text-zinc-500">
                  VRAM {gigabytes(metrics.gpu.memoryUsedBytes)} / {gigabytes(metrics.gpu.memoryTotalBytes)}
                </p>
              </div>
            )}

            <ul className="mt-2 space-y-0.5">
              {metrics.gpu.adapters.map((adapter) => (
                <li key={adapter.name} className="truncate text-[10px] text-zinc-600" title={adapter.name}>
                  {adapter.name}
                  {adapter.memoryTotalBytes ? ` · ${gigabytes(adapter.memoryTotalBytes)}` : ''}
                </li>
              ))}
            </ul>
            <p className="mt-2 text-[10px] leading-snug text-zinc-600">{metrics.gpu.detail}</p>
          </>
        )}
      </Section>

      <Section
        title="Ollama"
        right={
          <span className="flex items-center gap-1 text-[10px] text-zinc-500">
            <span className={`h-1.5 w-1.5 rounded-full ${metrics.ollama.reachable ? 'bg-emerald-400' : 'bg-red-500'}`} />
            {metrics.ollama.reachable ? metrics.ollama.version : 'offline'}
          </span>
        }
      >
        {metrics.ollama.loadedModels === 0 ? (
          <p className="text-[11px] text-zinc-600">No models loaded</p>
        ) : (
          <>
            <p className="text-[11px] text-zinc-400">
              {metrics.ollama.loadedModels} loaded · {gigabytes(metrics.ollama.vramBytes)} in VRAM
            </p>
            <ul className="mt-1 space-y-0.5">
              {metrics.ollama.models.map((name) => (
                <li key={name} className="truncate text-[10px] text-zinc-600">
                  {name}
                </li>
              ))}
            </ul>
          </>
        )}
      </Section>
    </div>
  )
}
