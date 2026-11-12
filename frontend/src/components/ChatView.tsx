import { useEffect, useRef } from 'react'
import type { ChatDetail } from '../api/types'
import { MessageBubble } from './MessageBubble'

interface ChatViewProps {
  detail: ChatDetail | null
  streaming: boolean
  streamText: string
  onStart: () => void
}

export function ChatView({ detail, streaming, streamText, onStart }: ChatViewProps) {
  const bottom = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'end' })
  }, [detail?.messages.length, streamText])

  if (!detail) {
    return (
      <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
        <p className="text-sm text-zinc-400">No chat selected</p>
        <p className="max-w-sm text-xs text-zinc-600">
          Start a new chat to talk to one of your local Ollama models. Every conversation is saved.
        </p>
        <button
          type="button"
          onClick={onStart}
          className="rounded-md bg-emerald-600 px-3 py-1.5 text-xs font-medium text-white transition hover:bg-emerald-500"
        >
          New chat
        </button>
      </div>
    )
  }

  return (
    <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
      <div className="mx-auto flex max-w-4xl flex-col gap-3">
        {detail.messages.length === 0 && !streaming && (
          <p className="py-10 text-center text-xs text-zinc-600">
            This chat is empty — send the first message below.
          </p>
        )}

        {detail.messages.map((message) => (
          <MessageBubble key={message.id} message={message} />
        ))}

        {streaming && (
          <div className="flex justify-start">
            <div className="max-w-[46rem] rounded-lg bg-zinc-900 px-3.5 py-2.5 text-sm leading-relaxed text-zinc-100 ring-1 ring-zinc-800">
              {streamText ? (
                <p className="whitespace-pre-wrap break-words">{streamText}</p>
              ) : (
                <p className="text-zinc-500">thinking…</p>
              )}
            </div>
          </div>
        )}

        <div ref={bottom} />
      </div>
    </div>
  )
}
