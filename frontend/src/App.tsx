import { useCallback, useEffect, useState } from 'react'
import { api } from './api/client'
import type { ChatDetail, ChatSummary, ModelView } from './api/types'
import { ChatView } from './components/ChatView'
import { Composer } from './components/Composer'
import { ModelPicker } from './components/ModelPicker'
import { Sidebar } from './components/Sidebar'
import { useChatStream } from './hooks/useChatStream'

const PREFERRED_MODEL = 'qwen2.5-coder:3b'
const MODELS_REFRESH_MS = 10_000

export default function App() {
  const [chats, setChats] = useState<ChatSummary[]>([])
  const [models, setModels] = useState<ModelView[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [detail, setDetail] = useState<ChatDetail | null>(null)
  const [query, setQuery] = useState('')
  const [banner, setBanner] = useState<string | null>(null)

  const { streaming, text, error, send, stop, dismissError } = useChatStream()

  const loadModels = useCallback(async () => {
    try {
      setModels(await api.models())
    } catch {
      // the chats request surfaces the connectivity banner
    }
  }, [])

  const loadChats = useCallback(async (search: string) => {
    try {
      setChats(await api.chats(search))
    } catch (cause) {
      setBanner(cause instanceof Error ? cause.message : 'Could not load chats')
    }
  }, [])

  useEffect(() => {
    void loadChats(query)
  }, [query, loadChats])

  useEffect(() => {
    void loadModels()
    const timer = setInterval(() => void loadModels(), MODELS_REFRESH_MS)
    return () => clearInterval(timer)
  }, [loadModels])

  const openChat = useCallback(async (id: string) => {
    setActiveId(id)
    try {
      setDetail(await api.chat(id))
    } catch (cause) {
      setBanner(cause instanceof Error ? cause.message : 'Could not open chat')
    }
  }, [])

  const startChat = useCallback(async () => {
    const model = models.find((candidate) => candidate.name === PREFERRED_MODEL)?.name ?? models[0]?.name
    if (!model) {
      setBanner('No Ollama models found — pull one with the command console first.')
      return
    }
    try {
      const chat = await api.createChat(model)
      setDetail(chat)
      setActiveId(chat.id)
      await loadChats(query)
    } catch (cause) {
      setBanner(cause instanceof Error ? cause.message : 'Could not create chat')
    }
  }, [models, query, loadChats])

  const removeChat = useCallback(
    async (id: string) => {
      try {
        await api.deleteChat(id)
        if (activeId === id) {
          setDetail(null)
          setActiveId(null)
        }
        await loadChats(query)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not delete chat')
      }
    },
    [activeId, query, loadChats],
  )

  const changeModel = useCallback(
    async (model: string) => {
      if (!detail || model === detail.model) {
        return
      }
      try {
        setDetail(await api.updateChat(detail.id, { model }))
        await loadChats(query)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not switch model')
      }
    },
    [detail, query, loadChats],
  )

  const sendMessage = useCallback(
    async (content: string) => {
      if (!detail || streaming) {
        return
      }
      const chatId = detail.id

      setDetail((current) =>
        current && current.id === chatId
          ? {
              ...current,
              messages: [
                ...current.messages,
                {
                  id: `local-${Date.now()}`,
                  role: 'USER',
                  content,
                  model: null,
                  promptTokens: null,
                  completionTokens: null,
                  latencyMs: null,
                  tokensPerSec: null,
                  error: null,
                  createdAt: new Date().toISOString(),
                },
              ],
            }
          : current,
      )

      await send(chatId, content, {
        onDone: (message) => {
          setDetail((current) =>
            current && current.id === chatId
              ? { ...current, messages: [...current.messages, message] }
              : current,
          )
          void loadChats(query)
          void loadModels()
          // the first reply renames the chat server-side; pick up the new title
          void api
            .chat(chatId)
            .then((fresh) => setDetail((current) => (current && current.id === chatId ? fresh : current)))
            .catch(() => undefined)
        },
      })
    },
    [detail, streaming, send, query, loadChats],
  )

  const problem = banner ?? error

  return (
    <div className="flex h-full min-h-0 text-zinc-100">
      <Sidebar
        chats={chats}
        activeId={activeId}
        query={query}
        onQueryChange={setQuery}
        onSelect={openChat}
        onNew={startChat}
        onDelete={removeChat}
      />

      <main className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-zinc-800 px-4 py-2.5">
          <ModelPicker
            models={models}
            value={detail?.model ?? ''}
            disabled={!detail || streaming}
            onChange={changeModel}
          />
          <span className="truncate text-xs text-zinc-500">{detail?.title ?? 'No chat selected'}</span>
          {streaming && <span className="ml-auto shrink-0 text-[11px] text-emerald-400">generating…</span>}
        </header>

        {problem && (
          <div className="flex items-center justify-between gap-3 border-b border-red-900/50 bg-red-950/40 px-4 py-2 text-xs text-red-300">
            <span className="min-w-0 break-words">{problem}</span>
            <button
              type="button"
              onClick={() => {
                setBanner(null)
                dismissError()
              }}
              className="shrink-0 rounded px-1.5 py-0.5 text-[11px] text-red-300/80 hover:text-red-200"
            >
              dismiss
            </button>
          </div>
        )}

        <ChatView detail={detail} streaming={streaming} streamText={text} onStart={startChat} />

        <Composer disabled={!detail} streaming={streaming} onSend={sendMessage} onStop={stop} />
      </main>
    </div>
  )
}
