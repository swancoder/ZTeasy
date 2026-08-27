// Mirrors gateway-service's com.zte.gateway.dashboard.* records (Stage 29, ADR-029).
export interface GateDecisions {
  allowed: number
  held: number
  denied: number
}

export interface SummaryPanel {
  agentsGoverned: number
  agentsSeen: number
  actionsInWindow: number
  decisions: GateDecisions
  awaitingApproval: number
  acapProfilesCurrent: number
  acapProfilesTotal: number
  acapProfilesOverdue: number
  spendMicros: number
  tokensTotal: number
  llmCalls: number
}

export interface DailySpend {
  date: string
  costMicros: number
}

export interface AgentSpend {
  agentId: string
  inputTokens: number
  outputTokens: number
  costMicros: number
  calls: number
}

export interface SpendTotals {
  costMicros: number
  inputTokens: number
  outputTokens: number
  calls: number
}

export interface SpendPanel {
  daily: DailySpend[]
  byAgent: AgentSpend[]
  totals: SpendTotals
  /** false = nothing has ever been metered — render "not reported", never a confident zero. */
  instrumented: boolean
}

export interface AgentActivity {
  agentId: string
  allowCount: number
  denyCount: number
  holdCount: number
  lastActivity: string | null
}

export interface RegistryEntry {
  id: string
  name: string
  targetType: string
  status: string
  hasSchema?: boolean
}

export interface OperationsPanel {
  agents: AgentActivity[]
  registry: RegistryEntry[]
}

export interface AcapProfileRisk {
  agentId: string
  displayName: string
  euAiActClass: string | null
  internalTier: number | null
  reauthDue: string | null
  overdue: boolean
}

export interface OutOfPolicyRow {
  id: string
  timestamp: string
  agentId: string | null
  toolName: string | null
  message: string | null
}

export interface RiskPanel {
  profiles: AcapProfileRisk[]
  outOfPolicy: OutOfPolicyRow[]
}

export interface ResourceFields {
  resource: string
  fields: string[]
}

export interface AgentDataScope {
  agentId: string
  territory: string | null
  writeAllowed: boolean
  reads: ResourceFields[]
}

export interface DataProtectionPanel {
  scopes: AgentDataScope[]
}

/** Audiences the dashboard serves — each is a real realm role (ADR-029). */
export type Audience = 'CEO' | 'CFO' | 'CTO' | 'BOARD' | 'DPO'
