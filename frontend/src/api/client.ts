import type {
  ChatDetail,
  ChatStats,
  CommandStreamEvent,
  ChatSummary,
  CommandDefinitionView,
  CommandRunView,
  MetricsResponse,
  ModelView,
  StreamEvent,
  ToolCallView,
} from './types'

const jsonHeaders = { 'Content-Type': 'application/json' }

async function readJson<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `Request failed (${response.status})`)
  }
  return response.json() as Promise<T>
}

export const api = {
  models: () => fetch('/api/models').then(readJson<ModelView[]>),

  chats: (query = '', archived = false) => {
    const params = new URLSearchParams()
    if (query) params.set('q', query)
    if (archived) params.set('archived', 'true')
    const suffix = params.toString()
    return fetch(suffix ? `/api/chats?${suffix}` : '/api/chats').then(readJson<ChatSummary[]>)
  },

  chat: (id: string) => fetch(`/api/chats/${id}`).then(readJson<ChatDetail>),

  stats: (id: string) => fetch(`/api/chats/${id}/stats`).then(readJson<ChatStats>),

  exportChatUrl: (id: string) => `/api/chats/${encodeURIComponent(id)}/export`,

  metrics: () => fetch('/api/metrics').then(readJson<MetricsResponse>),

  commands: () => fetch('/api/commands').then(readJson<CommandDefinitionView[]>),

  commandHistory: () => fetch('/api/commands/history').then(readJson<CommandRunView[]>),

  runCommand: (commandId: string, args: string, chatId: string | null) =>
    fetch('/api/commands/run', {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ commandId, args, chatId }),
    }).then(readJson<CommandRunView>),

  createChat: (model: string) =>
    fetch('/api/chats', { method: 'POST', headers: jsonHeaders, body: JSON.stringify({ model }) }).then(
      readJson<ChatDetail>,
    ),

  updateChat: (id: string, patch: { model?: string; title?: string; pinned?: boolean; archived?: boolean; systemPrompt?: string }) =>
    fetch(`/api/chats/${id}`, {
      method: 'PATCH',
      headers: jsonHeaders,
      body: JSON.stringify(patch),
    }).then(readJson<ChatDetail>),

  deleteChat: async (id: string) => {
    const response = await fetch(`/api/chats/${id}`, { method: 'DELETE' })
    if (!response.ok) {
      throw new Error(`Could not delete chat (${response.status})`)
    }
  },

  /** Server-side stop, so the reply ends even if this browser stops listening. */
  stopChat: (id: string) =>
    fetch(`/api/chats/${id}/stop`, { method: 'POST' }).then(readJson<{ stopped: boolean }>),

  forkChat: (id: string, fromSeq?: number) =>
    fetch(`/api/chats/${id}/fork${fromSeq === undefined ? '' : `?fromSeq=${fromSeq}`}`, {
      method: 'POST',
    }).then(readJson<ChatDetail>),

  /** Removes this message and everything after it. */
  truncateChat: (id: string, messageId: string) =>
    fetch(`/api/chats/${id}/messages/${encodeURIComponent(messageId)}`, { method: 'DELETE' }).then(
      readJson<ChatDetail>,
    ),

  /** Runs a proposed command. The server re-checks the allowlist before executing. */
  approveToolCall: (chatId: string, callId: string) =>
    fetch(`/api/chats/${chatId}/tool-calls/${callId}/approve`, { method: 'POST' }).then(
      readJson<ToolCallView>,
    ),

  denyToolCall: (chatId: string, callId: string) =>
    fetch(`/api/chats/${chatId}/tool-calls/${callId}/deny`, { method: 'POST' }).then(
      readJson<ToolCallView>,
    ),
}

/**
 * Reads an SSE response body and hands every frame to `onEvent`. `EventSource` cannot POST, so the
 * body is read from fetch and split on blank lines.
 */
async function consume<T>(response: Response, onEvent: (event: T) => void): Promise<void> {
  if (!response.ok || !response.body) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `Request failed (${response.status})`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)

      const payload = frame
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('\n')

      if (payload) {
        try {
          onEvent(JSON.parse(payload) as T)
        } catch {
          // ignore an unparsable frame rather than killing the stream
        }
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}

export async function streamMessage(
  chatId: string,
  content: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api/chats/${chatId}/messages`, {
    method: 'POST',
    headers: { ...jsonHeaders, Accept: 'text/event-stream' },
    body: JSON.stringify({ content }),
    signal,
  })
  await consume(response, onEvent)
}

export async function streamCommand(
  commandId: string,
  args: string,
  chatId: string | null,
  onEvent: (event: CommandStreamEvent) => void,
): Promise<void> {
  const response = await fetch('/api/commands/run/stream', {
    method: 'POST',
    headers: { ...jsonHeaders, Accept: 'text/event-stream' },
    body: JSON.stringify({ commandId, args, chatId }),
  })
  await consume(response, onEvent)
}

export async function regenerateReply(
  chatId: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api/chats/${chatId}/regenerate`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    signal,
  })
  await consume(response, onEvent)
}

/** Streams the model's answer to a command it proposed, once the user has decided. */
export async function continueAfterTool(
  chatId: string,
  callId: string,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(`/api/chats/${chatId}/tool-calls/${callId}/continue`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    signal,
  })
  await consume(response, onEvent)
}
