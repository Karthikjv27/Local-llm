import { useCallback, useEffect, useState } from 'react'
import { api } from './api/client'
import type { ChatDetail, ChatStats, ChatSummary, ModelView } from './api/types'
import { ChatView } from './components/ChatView'
import { Composer } from './components/Composer'
import { ContextMeter } from './components/ContextMeter'
import { ModelPicker } from './components/ModelPicker'
import { RightRail } from './components/RightRail'
import { Sidebar } from './components/Sidebar'
import { useChatStream } from './hooks/useChatStream'
import { useMetrics } from './hooks/useMetrics'

const PREFERRED_MODEL = 'qwen2.5-coder:3b'
const MODELS_REFRESH_MS = 10_000

export default function App() {
  const [chats, setChats] = useState<ChatSummary[]>([])
  const [models, setModels] = useState<ModelView[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [detail, setDetail] = useState<ChatDetail | null>(null)
  const [stats, setStats] = useState<ChatStats | null>(null)
  const [query, setQuery] = useState('')
  const [showArchived, setShowArchived] = useState(false)
  const [banner, setBanner] = useState<string | null>(null)
  const [showMetrics, setShowMetrics] = useState(true)

  const { streaming, text, error, send, regenerate, continueAfter, stop, dismissError } = useChatStream()
  const { metrics, history } = useMetrics()
  const [toolBusy, setToolBusy] = useState(false)

  const loadModels = useCallback(async () => {
    try {
      setModels(await api.models())
    } catch {
      // the chats request surfaces the connectivity banner
    }
  }, [])

  const loadChats = useCallback(async (search: string, archived: boolean) => {
    try {
      setChats(await api.chats(search, archived))
    } catch (cause) {
      setBanner(cause instanceof Error ? cause.message : 'Could not load chats')
    }
  }, [])

  const refreshStats = useCallback(async (chatId: string) => {
    try {
      setStats(await api.stats(chatId))
    } catch {
      setStats(null)
    }
  }, [])

  useEffect(() => {
    void loadChats(query, showArchived)
  }, [query, showArchived, loadChats])

  useEffect(() => {
    void loadModels()
    const timer = setInterval(() => void loadModels(), MODELS_REFRESH_MS)
    return () => clearInterval(timer)
  }, [loadModels])

  const openChat = useCallback(
    async (id: string) => {
      setActiveId(id)
      try {
        setDetail(await api.chat(id))
        void refreshStats(id)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not open chat')
      }
    },
    [refreshStats],
  )

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
      setStats(null)
      await loadChats(query, showArchived)
    } catch (cause) {
      setBanner(cause instanceof Error ? cause.message : 'Could not create chat')
    }
  }, [models, query, showArchived, loadChats])

  const removeChat = useCallback(
    async (id: string) => {
      try {
        await api.deleteChat(id)
        if (activeId === id) {
          setDetail(null)
          setActiveId(null)
          setStats(null)
        }
        await loadChats(query, showArchived)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not delete chat')
      }
    },
    [activeId, query, showArchived, loadChats],
  )

  const archiveChat = useCallback(
    async (id: string, archived: boolean) => {
      try {
        await api.updateChat(id, { archived })
        if (archived && activeId === id) {
          setDetail(null)
          setActiveId(null)
          setStats(null)
        }
        await loadChats(query, showArchived)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not archive chat')
      }
    },
    [activeId, query, showArchived, loadChats],
  )

  const forkChat = useCallback(async () => {
    if (!detail || streaming) {
      return
    }
    try {
      const copy = await api.forkChat(detail.id)
      setDetail(copy)
      setActiveId(copy.id)
      void refreshStats(copy.id)
      await loadChats(query, showArchived)
    } catch (cause) {
      setBanner(cause instanceof Error ? cause.message : 'Could not fork chat')
    }
  }, [detail, streaming, refreshStats, query, showArchived, loadChats])

  const changeModel = useCallback(
    async (model: string) => {
      if (!detail || model === detail.model) {
        return
      }
      try {
        setDetail(await api.updateChat(detail.id, { model }))
        await loadChats(query, showArchived)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not switch model')
      }
    },
    [detail, query, showArchived, loadChats],
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
                  cancelled: false,
                  sources: null,
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
          void loadChats(query, showArchived)
          void loadModels()
          void refreshStats(chatId)
          // the first reply renames the chat server-side; pick up the new title
          void api
            .chat(chatId)
            .then((fresh) => setDetail((current) => (current && current.id === chatId ? fresh : current)))
            .catch(() => undefined)
        },
      })
    },
    [detail, streaming, send, query, showArchived, loadChats, loadModels, refreshStats],
  )

  const regenerateLast = useCallback(async () => {
    if (!detail || streaming) {
      return
    }
    const chatId = detail.id

    // drop the reply locally so the new one streams into its place
    setDetail((current) => {
      if (!current || current.id !== chatId) {
        return current
      }
      const messages = [...current.messages]
      while (messages.length > 0 && messages[messages.length - 1].role === 'ASSISTANT') {
        messages.pop()
      }
      return { ...current, messages }
    })

    await regenerate(chatId, {
      onDone: (message) => {
        setDetail((current) =>
          current && current.id === chatId
            ? { ...current, messages: [...current.messages, message] }
            : current,
        )
        void loadChats(query, showArchived)
        void loadModels()
        void refreshStats(chatId)
      },
    })
  }, [detail, streaming, regenerate, query, showArchived, loadChats, loadModels, refreshStats])

  /** Removes one message and everything after it, so the conversation can be rewound. */
  const decideTool = useCallback(
    async (callId: string, approve: boolean) => {
      if (!detail) {
        return
      }
      const chatId = detail.id
      setToolBusy(true)
      try {
        const updated = approve
          ? await api.approveToolCall(chatId, callId)
          : await api.denyToolCall(chatId, callId)
        setDetail((current) =>
          current && current.id === chatId
            ? { ...current, toolCalls: current.toolCalls.map((call) => (call.id === callId ? updated : call)) }
            : current,
        )
        void loadChats(query, showArchived)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not resolve the command')
      } finally {
        setToolBusy(false)
      }
    },
    [detail, query, showArchived, loadChats],
  )

  const continueTool = useCallback(
    async (callId: string) => {
      if (!detail || detail.toolCalls.find((call) => call.id === callId)?.continued) {
        return
      }
      const chatId = detail.id
      setToolBusy(true)
      try {
        await continueAfter(chatId, callId, {
          onDone: (message) => {
            setDetail((current) =>
              current && current.id === chatId
                ? { ...current, messages: [...current.messages, message] }
                : current,
            )
            void refreshStats(chatId)
            void loadChats(query, showArchived)
          },
        })
      } finally {
        setToolBusy(false)
        // Re-read so the card shows the server's single-use marker even if the stream failed.
        void api
          .chat(chatId)
          .then((fresh) => setDetail((current) => (current && current.id === chatId ? fresh : current)))
          .catch(() => undefined)
      }
    },
    [detail, continueAfter, refreshStats, query, showArchived, loadChats],
  )

  const truncateFrom = useCallback(
    async (messageId: string) => {
      if (!detail || streaming) {
        return
      }
      if (!window.confirm('Delete this message and every message after it?')) {
        return
      }
      const chatId = detail.id
      try {
        const fresh = await api.truncateChat(chatId, messageId)
        setDetail((current) => (current && current.id === chatId ? fresh : current))
        void refreshStats(chatId)
        void loadChats(query, showArchived)
      } catch (cause) {
        setBanner(cause instanceof Error ? cause.message : 'Could not rewind chat')
      }
    },
    [detail, streaming, refreshStats, query, showArchived, loadChats],
  )

  /**
   * Stop asks the server to end the generation, then aborts the fetch. The server persists the
   * partial reply either way, so the chat is re-read a moment later to show what was kept.
   */
  const stopStreaming = useCallback(() => {
    const chatId = detail?.id
    if (!chatId) {
      stop()
      return
    }
    void api.stopChat(chatId).catch(() => undefined)
    stop()
    window.setTimeout(() => {
      void api
        .chat(chatId)
        .then((fresh) => setDetail((current) => (current && current.id === chatId ? fresh : current)))
        .catch(() => undefined)
      void refreshStats(chatId)
    }, 400)
  }, [detail, stop, refreshStats])

  const problem = banner ?? error

  return (
    <div className="flex h-full min-h-0 text-zinc-100">
      <Sidebar
        chats={chats}
        activeId={activeId}
        query={query}
        archivedView={showArchived}
        onQueryChange={setQuery}
        onSelect={openChat}
        onNew={startChat}
        onDelete={removeChat}
        onArchive={archiveChat}
        onToggleArchived={() => setShowArchived((current) => !current)}
      />

      <main className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-zinc-800 px-4 py-2.5">
          <ModelPicker
            models={models}
            value={detail?.model ?? ''}
            disabled={!detail || streaming}
            onChange={changeModel}
          />
          <span className="min-w-0 flex-1 truncate text-xs text-zinc-500">{detail?.title ?? 'No chat selected'}</span>
          <div className="ml-auto flex shrink-0 items-center gap-3">
            {stats && stats.messages > 0 && (
              <span className="hidden items-center text-[11px] text-zinc-500 xl:flex">
                {stats.messages} msgs · {stats.completionTokens.toLocaleString()} tok
                {stats.averageTokensPerSec != null ? ` · ${stats.averageTokensPerSec} tok/s avg` : ''}
              </span>
            )}
            {detail && (
              <button
                type="button"
                onClick={forkChat}
                disabled={streaming}
                title="Copy this conversation into a new chat"
                className="rounded border border-zinc-800 px-2 py-1 text-[11px] text-zinc-400 transition hover:text-zinc-200 disabled:opacity-40"
              >
                Fork
              </button>
            )}
            {detail && <ContextMeter context={detail.context} />}
            {detail && (
              <a
                href={api.exportChatUrl(detail.id)}
                download
                className="rounded border border-zinc-800 px-2 py-1 text-[11px] text-zinc-400 transition hover:text-zinc-200"
              >
                Export
              </a>
            )}
            {streaming && <span className="text-[11px] text-emerald-400">generating…</span>}
            {!showMetrics && (
              <button
                type="button"
                onClick={() => setShowMetrics(true)}
                className="rounded border border-zinc-800 px-2 py-1 text-[11px] text-zinc-400 transition hover:text-zinc-200"
              >
                Metrics
              </button>
            )}
          </div>
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

        <ChatView
          detail={detail}
          streaming={streaming}
          streamText={text}
          onStart={startChat}
          onRegenerate={regenerateLast}
          onTruncate={truncateFrom}
          onApproveTool={(id) => void decideTool(id, true)}
          onDenyTool={(id) => void decideTool(id, false)}
          onContinueTool={(id) => void continueTool(id)}
          toolBusy={toolBusy}
        />

        <Composer disabled={!detail} streaming={streaming} onSend={sendMessage} onStop={stopStreaming} />
      </main>

      {showMetrics && (
        <RightRail
          metrics={metrics}
          history={history}
          models={models}
          chatId={detail?.id ?? null}
          onClose={() => setShowMetrics(false)}
        />
      )}
    </div>
  )
}
