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
  archived: boolean
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
  cancelled: boolean
  createdAt: string
}

export interface ContextView {
  usedTokens: number
  limitTokens: number
  includedMessages: number
  consideredMessages: number
  trimmed: boolean
}

export interface ChatDetail {
  id: string
  title: string
  model: string
  systemPrompt: string | null
  pinned: boolean
  archived: boolean
  createdAt: string
  updatedAt: string
  context: ContextView
  messages: MessageView[]
}

export interface ChatStats {
  chatId: string
  messages: number
  userMessages: number
  assistantMessages: number
  promptTokens: number
  completionTokens: number
  averageTokensPerSec: number | null
  averageLatencyMs: number | null
  failedReplies: number
  stoppedReplies: number
  firstMessageAt: string | null
  lastMessageAt: string | null
}

export type StreamEvent =
  | { type: 'token'; text: string }
  | { type: 'done'; message: MessageView }
  | { type: 'error'; error: string }

export interface CommandDefinitionView {
  id: string
  label: string
  description: string
  requiresArguments: boolean
  destructive: boolean
  timeoutSeconds: number
}

export type CommandStatus = 'RUNNING' | 'OK' | 'FAILED' | 'TIMEOUT' | 'REJECTED'

export interface CommandRunView {
  id: string
  command: string
  args: string | null
  status: CommandStatus
  exitCode: number | null
  stdout: string | null
  stderr: string | null
  durationMs: number | null
  startedAt: string
  finishedAt: string | null
}

export type CommandStreamEvent =
  | { type: 'line'; line: string }
  | { type: 'done'; run: CommandRunView }
  | { type: 'error'; error: string }

export interface GpuAdapter {
  name: string
  memoryTotalBytes: number | null
}

export interface GpuSnapshot {
  source: string
  available: boolean
  adapters: GpuAdapter[]
  utilizationPercent: number | null
  memoryUsedBytes: number | null
  memoryTotalBytes: number | null
  temperatureC: number | null
  detail: string
}

export interface MetricsResponse {
  timestamp: string
  cpu: {
    name: string
    physicalCores: number
    logicalProcessors: number
    loadPercent: number
    perCorePercent: number[]
  }
  memory: {
    totalBytes: number
    usedBytes: number
    availableBytes: number
    usedPercent: number
  }
  gpu: GpuSnapshot
  ollama: {
    reachable: boolean
    version: string | null
    loadedModels: number
    vramBytes: number
    models: string[]
  }
}
