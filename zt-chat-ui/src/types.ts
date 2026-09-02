// Mirrors the two APIs this console reads: zt-chat's reply (ADR-039 phase B) and
// the gateway's per-person event feed. Kept as a copy rather than a shared package
// because the three SPAs are deliberately independent npm projects (ADR-026).

export interface ChatStep {
  kind: string
  name: string
  detail: string
}

export interface ChatReply {
  reply: string
  steps: ChatStep[]
}

export interface Turn {
  role: 'user' | 'assistant'
  content: string
  /** Populated for assistant turns: what the gateway was asked to do on the way. */
  steps?: ChatStep[]
  pending?: boolean
  error?: string
}

/** One decision the gateway made where this person was the subject. */
export interface MyEvent {
  timestamp: string
  decision: string | null
  tool: string | null
  target: string | null
  path: string | null
  statusCode: number | null
  reason: string | null
  traceId: string | null
}

export interface MySpend {
  agentId: string
  inputTokens: number
  outputTokens: number
  costMicros: number
  calls: number
}
