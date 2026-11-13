import type {
  ChatDetail,
  ChatSummary,
  CommandDefinitionView,
  CommandRunView,
  MetricsResponse,
  ModelView,
  StreamEvent,
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

  chats: (query = '') =>
    fetch(query ? `/api/chats?q=${encodeURIComponent(query)}` : '/api/chats').then(readJson<ChatSummary[]>),

  chat: (id: string) => fetch(`/api/chats/${id}`).then(readJson<ChatDetail>),

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

  updateChat: (id: string, patch: { model?: string; title?: string; pinned?: boolean }) =>
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
}

/**
 * POSTs a prompt and feeds every SSE frame to `onEvent` as it arrives. `EventSource` cannot POST,
 * so the stream is read from the fetch body and split on blank lines.
 */
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
          onEvent(JSON.parse(payload) as StreamEvent)
        } catch {
          // ignore an unparsable frame rather than killing the stream
        }
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}
