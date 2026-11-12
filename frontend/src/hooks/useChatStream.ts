import { useCallback, useRef, useState } from 'react'
import { streamMessage } from '../api/client'
import type { MessageView } from '../api/types'

interface StreamHandlers {
  onDone: (message: MessageView) => void
}

export function useChatStream() {
  const [streaming, setStreaming] = useState(false)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const controller = useRef<AbortController | null>(null)

  const send = useCallback(async (chatId: string, content: string, handlers: StreamHandlers) => {
    setStreaming(true)
    setText('')
    setError(null)

    const abort = new AbortController()
    controller.current = abort

    try {
      await streamMessage(
        chatId,
        content,
        (event) => {
          if (event.type === 'token') {
            setText((current) => current + event.text)
          } else if (event.type === 'done') {
            handlers.onDone(event.message)
          } else {
            setError(event.error)
          }
        },
        abort.signal,
      )
    } catch (cause) {
      if (!abort.signal.aborted) {
        setError(cause instanceof Error ? cause.message : 'Streaming failed')
      }
    } finally {
      setStreaming(false)
      setText('')
      controller.current = null
    }
  }, [])

  const stop = useCallback(() => controller.current?.abort(), [])

  const dismissError = useCallback(() => setError(null), [])

  return { streaming, text, error, send, stop, dismissError }
}
