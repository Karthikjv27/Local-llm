import type { ModelView } from '../api/types'
import { gigabytes } from '../lib/format'

interface ModelPickerProps {
  models: ModelView[]
  value: string
  disabled: boolean
  onChange: (model: string) => void
}

export function ModelPicker({ models, value, disabled, onChange }: ModelPickerProps) {
  const loaded = models.filter((model) => model.running)
  const selected = models.find((model) => model.name === value)
  const missingValue = value !== '' && !selected

  return (
    <div className="flex min-w-0 items-center gap-2">
      <select
        value={value}
        disabled={disabled || models.length === 0}
        onChange={(event) => onChange(event.target.value)}
        aria-label="Model"
        className="max-w-[24rem] truncate rounded-md border border-zinc-800 bg-zinc-900 px-2 py-1.5 text-xs text-zinc-200 focus:border-zinc-700 focus:outline-none disabled:opacity-60"
      >
        {value === '' && <option value="">Select a model</option>}
        {missingValue && <option value={value}>{value} (not installed)</option>}
        {models.map((model) => (
          <option key={model.name} value={model.name}>
            {model.name}
            {model.running ? ' ● loaded' : ''} — {model.parameterSize ?? '?'} {model.quantization ?? ''} ·{' '}
            {gigabytes(model.sizeBytes)}
          </option>
        ))}
      </select>

      <span className="flex shrink-0 items-center gap-1.5 rounded-full border border-zinc-800 px-2 py-1 text-[11px] text-zinc-400">
        <span className={`h-1.5 w-1.5 rounded-full ${loaded.length ? 'bg-emerald-400' : 'bg-zinc-600'}`} />
        {loaded.length ? `${loaded.length} loaded` : 'none loaded'}
      </span>

      {selected?.running && selected.vramBytes != null && (
        <span className="shrink-0 text-[11px] text-zinc-500">VRAM {gigabytes(selected.vramBytes)}</span>
      )}
    </div>
  )
}
