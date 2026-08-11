// Mirrors gateway-service's com.zte.gateway.policy.def.{PolicyRule,PolicyDocument} (ADR-011/ADR-012).

export interface PolicyRule {
  id: string
  effect: 'ALLOW' | 'DENY'
  source: string
  target: string
  pathPattern: string | null
  methods: string | null
  priority: number
}

export interface PolicyDocument {
  schemaVersion: number
  users2service: PolicyRule[]
  service2service: PolicyRule[]
  agentMcpToolCalls: PolicyRule[]
}

export interface ReloadResult {
  status: 'success' | 'failed'
  timestamp: string
  errors?: string[]
}

// Mirrors gateway-service's com.zte.gateway.audit.RequestLog (ADR-013).
// agentId/toolName are always null for today's REST traffic — reserved for
// a future MCP-audit unification.
export interface RequestLogEntry {
  id: string
  timestamp: string
  traceId: string
  clientIp: string | null
  userAgent: string | null
  processId: string | null
  agentId: string | null
  toolName: string | null
  path: string
  statusCode: number | null
  message: string | null
}

// Mirrors gateway-service's com.zte.gateway.identity.IdpIdentity (ADR-014,
// CLIENT type added by ADR-015).
export interface IdpIdentityEntry {
  id: string
  type: 'USER' | 'GROUP' | 'ROLE' | 'CLIENT'
  externalId: string
  name: string
  displayName: string | null
  lastSynced: string
}

// Mirrors gateway-service's com.zte.gateway.admin.AdminIdentityRelationsController.RelatedIdentity
// (docs/adr/identities-ui-actors-containers-and-relations-caching.md) — one
// Group/Role related to an Actor (User/Client), plus how.
export interface RelatedIdentity {
  id: string
  type: 'USER' | 'GROUP' | 'ROLE' | 'CLIENT'
  name: string
  displayName: string | null
  relationType: 'MEMBER_OF' | 'HAS_ROLE'
}

// Mirrors gateway-service's com.zte.gateway.inventory.InventoryView (ADR-016).
export interface InventoryEntry {
  id: string
  name: string
  targetType: 'REST' | 'MCP'
  baseUrl: string
  // Optional — health polling pings this instead of baseUrl when set (ADR-016 amendment).
  managementUrl: string | null
  status: 'ACTIVE' | 'WARNING' | 'DOWN' | 'PENDING'
  createdAt: string
  lastPingMs: number | null
  actuatorStatus: string | null
  lastSuccessfulCall: string | null
}
