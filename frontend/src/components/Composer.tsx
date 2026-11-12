import { useState } from 'react'

interface ComposerProps {
  disabled: boolean
  streaming: boolean
  onSend: (content: string) => void
  onStop: () => void
}

export function Composer({ disabled, streaming, onSend, onStop }: ComposerProps) {
  const [value, setValue] = useState('')

  const submit = () => {
    const content = value.trim()
    if (!content || disabled || streaming) {
      return
    }
    setValue('')
    onSend(content)
  }

  return (
    <div className="border-t border-zinc-800 px-4 py-3">
      <div className="mx-auto flex max-w-4xl items-end gap-2">
        <textarea
          value={value}
          disabled={disabled}
          rows={2}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              submit()
            }
          }}
          placeholder={
            disabled ? 'Start a chat to send a message' : 'Message your local model…  (Enter to send, Shift+Enter for a new line)'
          }
          className="min-h-12 flex-1 resize-none rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-600 focus:border-zinc-700 focus:outline-none disabled:opacity-60"
        />

        {streaming ? (
          <button
            type="button"
            onClick={onStop}
            className="rounded-lg bg-zinc-800 px-3.5 py-2 text-xs font-medium text-zinc-200 transition hover:bg-zinc-700"
          >
            Stop
          </button>
        ) : (
          <button
            type="button"
            onClick={submit}
            disabled={disabled || !value.trim()}
            className="rounded-lg bg-emerald-600 px-3.5 py-2 text-xs font-semibold text-white transition hover:bg-emerald-500 disabled:opacity-40"
          >
            Send
          </button>
        )}
      </div>
    </div>
  )
}
