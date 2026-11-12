export interface ModelView {
  name: string
  family: string | null
  parameterSize: string | null
  quantization: string | null
  sizeBytes: number
  modifiedAt: string | null
  running: boolean
  vramBytes: number | null
  expiresAt: string | null
}

export interface ChatSummary {
  id: string
  title: string
  model: string
  pinned: boolean
  createdAt: string
  updatedAt: string
}

export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

export interface MessageView {
  id: string
  role: MessageRole
  content: string
  model: string | null
  promptTokens: number | null
  completionTokens: number | null
  latencyMs: number | null
  tokensPerSec: number | null
  error: string | null
  createdAt: string
}

export interface ChatDetail {
  id: string
  title: string
  model: string
  systemPrompt: string | null
  pinned: boolean
  createdAt: string
  updatedAt: string
  messages: MessageView[]
}

export type StreamEvent =
  | { type: 'token'; text: string }
  | { type: 'done'; message: MessageView }
  | { type: 'error'; error: string }
