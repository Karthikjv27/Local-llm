import { useCallback, useRef, useState } from 'react'
import { regenerateReply, streamMessage } from '../api/client'
import type { MessageView } from '../api/types'

interface StreamHandlers {
  onDone: (message: MessageView) => void
  /** Called after an abort, so the caller can re-read the reply the server kept. */
  onStopped?: () => void
}

export function useChatStream() {
  const [streaming, setStreaming] = useState(false)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)

  const run = useCallback(
    async (work: (emit: (event: unknown) => void, signal: AbortSignal) => Promise<void>, handlers: StreamHandlers) => {
      setStreaming(true)
      setText('')
      setError(null)

      const abort = new AbortController()
      controller.current = abort

      const emit = (event: unknown) => {
        if (event && typeof event === 'object' && 'type' in event) {
          const typed = event as { type: string; text?: string; message?: MessageView; error?: string }
          if (typed.type === 'token' && typed.text !== undefined) {
            setText((current) => current + typed.text)
          } else if (typed.type === 'done' && typed.message) {
            handlers.onDone(typed.message)
          } else if (typed.type === 'error' && typed.error) {
            setError(typed.error)
          }
        }
      }

      try {
        await work(emit, abort.signal)
      } catch (cause) {
        if (!abort.signal.aborted) {
          setError(cause instanceof Error ? cause.message : 'Streaming failed')
        }
      } finally {
        setStreaming(false)
        setText('')
        controller.current = null
        if (abort.signal.aborted) {
          handlers.onStopped?.()
        }
      }
    },
    [],
  )

  const send = useCallback(
    (chatId: string, content: string, handlers: StreamHandlers) =>
      run((emit, signal) => streamMessage(chatId, content, emit, signal), handlers),
    [run],
  )

  const regenerate = useCallback(
    (chatId: string, handlers: StreamHandlers) =>
      run((emit, signal) => regenerateReply(chatId, emit, signal), handlers),
    [run],
  )

  const stop = useCallback(() => controller.current?.abort(), [])

  const dismissError = useCallback(() => setError(null), [])

  return { streaming, text, error, send, regenerate, stop, dismissError }
}
