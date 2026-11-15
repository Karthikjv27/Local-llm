import { useEffect, useRef } from 'react'
import type { ChatDetail } from '../api/types'
import { MarkdownText } from './MarkdownText'
import { MessageBubble } from './MessageBubble'
import { ToolCallCard } from './ToolCallCard'

interface ChatViewProps {
  detail: ChatDetail | null
  streaming: boolean
  streamText: string
  onStart: () => void
  onRegenerate: () => void
  onTruncate: (messageId: string) => void
  onApproveTool: (id: string) => void
  onDenyTool: (id: string) => void
  onContinueTool: (id: string) => void
  toolBusy: boolean
}

export function ChatView({
  detail,
  streaming,
  streamText,
  onStart,
  onRegenerate,
  onTruncate,
  onApproveTool,
  onDenyTool,
  onContinueTool,
  toolBusy,
}: ChatViewProps) {
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

  const lastIndex = detail.messages.length - 1

  return (
    <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
      <div className="mx-auto flex max-w-4xl flex-col gap-3">
        {detail.messages.length === 0 && !streaming && (
          <p className="py-10 text-center text-xs text-zinc-600">
            This chat is empty — send the first message below.
          </p>
        )}

        {detail.messages.map((message, index) => (
          <MessageBubble
            key={message.id}
            message={message}
            canRegenerate={!streaming && message.role === 'ASSISTANT' && index === lastIndex}
            onRegenerate={onRegenerate}
            onTruncate={streaming ? undefined : () => onTruncate(message.id)}
          />
        ))}

        {streaming && (
          <div className="flex justify-start">
            <div className="max-w-[46rem] rounded-lg bg-zinc-900 px-3.5 py-2.5 ring-1 ring-zinc-800">
              {streamText ? <MarkdownText text={streamText} /> : <p className="text-sm text-zinc-500">thinking…</p>}
            </div>
          </div>
        )}

        {detail.toolCalls.length > 0 && (
          <div className="mt-1 flex flex-col gap-2">
            {detail.toolCalls.map((call) => (
              <ToolCallCard
                key={call.id}
                call={call}
                busy={toolBusy || streaming}
                onApprove={onApproveTool}
                onDeny={onDenyTool}
                onContinue={onContinueTool}
              />
            ))}
          </div>
        )}

        <div ref={bottom} />
      </div>
    </div>
  )
}
