import { useState } from 'react'
import type { MetricsResponse, ModelView } from '../api/types'
import type { MetricsHistory } from '../hooks/useMetrics'
import { ActionsPanel } from './ActionsPanel'
import { MetricsPanel } from './MetricsPanel'

type Tab = 'metrics' | 'actions'

interface RightRailProps {
  metrics: MetricsResponse | null
  history: MetricsHistory
  models: ModelView[]
  chatId: string | null
  onClose: () => void
}

export function RightRail({ metrics, history, models, chatId, onClose }: RightRailProps) {
  const [tab, setTab] = useState<Tab>('metrics')

  return (
    <aside className="flex w-72 shrink-0 flex-col border-l border-zinc-800">
      <div className="flex items-center gap-1 border-b border-zinc-800 px-3 py-2">
        {(['metrics', 'actions'] as const).map((value) => (
          <button
            key={value}
            type="button"
            onClick={() => setTab(value)}
            className={`rounded px-2 py-1 text-[11px] font-medium capitalize transition ${
              tab === value ? 'bg-zinc-800 text-zinc-100' : 'text-zinc-500 hover:text-zinc-300'
            }`}
          >
            {value}
          </button>
        ))}
        <button
          type="button"
          onClick={onClose}
          aria-label="Hide panel"
          className="ml-auto rounded px-1 text-[11px] text-zinc-600 hover:text-zinc-300"
        >
          ✕
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-3 py-3">
        {tab === 'metrics' ? (
          <MetricsPanel metrics={metrics} history={history} />
        ) : (
          <ActionsPanel models={models} chatId={chatId} />
        )}
      </div>
    </aside>
  )
}
