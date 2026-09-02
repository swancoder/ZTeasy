// Mirrors gateway-service's com.zte.gateway.policy.def.{PolicyRule,PolicyDocument} (ADR-011/ADR-012).

export interface PolicyRule {
  id: string
  effect: 'ALLOW' | 'DENY'
  source: string
  target: string
  pathPattern: string | null
  methods: string | null
  priority: number
  // Which MCP backend this rule applies to, matched against mcp-backend.name —
  // agentMcpToolCalls/agentMcpToolHolds only; null matches any backend (ADR-023).
  mcpTarget: string | null
}

export interface PolicyDocument {
  schemaVersion: number
  users2service: PolicyRule[]
  service2service: PolicyRule[]
  agentMcpToolCalls: PolicyRule[]
  // Stage 1 (ADR-019) — tool calls held for human approval even when
  // agentMcpToolCalls would ALLOW them. Matched independently (PolicyMatcher.matchAny),
  // not a third `effect` value — see that method's Javadoc for why.
  agentMcpToolHolds: PolicyRule[]
}

export interface ReloadResult {
  status: 'success' | 'failed'
  timestamp: string
  errors?: string[]
}

// Mirrors gateway-service's com.zte.gateway.audit.RequestLog (ADR-013, extended ADR-017).
// agentId/toolName are populated for MCP tool calls, null for plain REST traffic
// (a row has one or the other, never both) — see RequestLog.forRest/forMcp.
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
  // ADR-017 additions:
  initiatorClient: string | null
  originalUserObo: string | null
  targetService: string | null
  httpMethod: string | null
  // 'HOLD' added Stage 1 (ADR-019) — a held tool call awaiting human review.
  decisionEffect: 'ALLOW' | 'DENY' | 'HOLD' | 'ERROR' | null
}

// Mirrors gateway-service's com.zte.gateway.mcp.approval.PendingApproval (Stage 1, ADR-019).
export interface PendingApproval {
  id: string
  sessionId: string
  agentId: string
  toolName: string
  rpcIdJson: string
  argumentsJson: string | null
  routeTo: string | null
  reason: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'
  requestedAt: string
  expiresAt: string | null
  // Computed per request by the gateway (ADR-034): entitlement depends on the
  // caller's token and the remaining time on the clock, so neither is stored.
  canDecide: boolean
  refusalReason: string | null
  secondsRemaining: number
  addressedTo: string | null
  // ADR-035: separate from canDecide on purpose — an unrouted call may be decided
  // by anyone, but it is still addressed to someone, so that it has an owner.
  addressedToYou: boolean
  notificationStatus: 'SENT' | 'FAILED' | 'SKIPPED' | null
  notifiedAt: string | null
  decidedAt: string | null
  decidedBy: string | null
  traceId: string | null
  clientIp: string | null
  userAgent: string | null
  displayIdentity: string | null
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
  // Optional, REST-only — AutoDiscoveryWorker probes this instead of {baseUrl}/v3/api-docs when set (ADR-016 amendment).
  docsUrl: string | null
  // Optional — health polling pings this instead of baseUrl when set (ADR-016 amendment).
  managementUrl: string | null
  status: 'ACTIVE' | 'WARNING' | 'DOWN' | 'PENDING'
  createdAt: string
  lastPingMs: number | null
  actuatorStatus: string | null
  lastSuccessfulCall: string | null
  // Whether discovered_schema is captured — NOT implied by status === 'ACTIVE' alone
  // (a 2xx with an empty/invalid body still reaches ACTIVE but captures nothing);
  // use this to gate "View Schema" (ADR-016 amendment).
  hasSchema: boolean
}

// Mirrors gateway-service's com.zte.gateway.governance.AgentActivitySummary (Stage 4, ADR-021).
export interface AgentActivitySummary {
  agentId: string
  allowCount: number
  denyCount: number
  holdCount: number
  lastActivity: string | null
}

// Mirrors gateway-service's com.zte.gateway.governance.GovernanceReport (Stage 4, ADR-021).
export interface GovernanceReport {
  generatedAt: string
  windowHours: number
  agentActivity: AgentActivitySummary[]
  outOfPolicyAttempts: RequestLogEntry[]
}

// Mirrors gateway-service's com.zte.gateway.mcp.acap.* (Stage 3 ADR-020, agent
// metadata/thresholds Stage 6 ADR-022) — display-only, no client-side enforcement.
export interface AcapReadGrant {
  resource: string
  fields: string[]
}

export interface AcapScope {
  read: AcapReadGrant[]
  writeAllowed: boolean
}

export interface AcapOwner {
  name: string
  email: string
}

export interface AcapAgentInfo {
  name: string | null
  client: string | null
  owner: AcapOwner | null
  deploymentDate: string | null
  reauthDue: string | null
}

export interface AcapRisk {
  euAiActClass: string | null
  internalTier: number | null
}

export interface AcapThreshold {
  metric: string
  toolName: string
  limit: number
  onExceed: string
}

export interface AcapProfile {
  agentId: string
  territory: string
  scope: AcapScope | null
  agent: AcapAgentInfo | null
  risk: AcapRisk | null
  thresholds: AcapThreshold[]
}

// Mirrors gateway-service's com.zte.gateway.admin.AdminAcapProfileController.AcapProfileView (Stage 6, ADR-022).
export interface AcapProfileView {
  profile: AcapProfile
  currentThresholdUsage: Record<string, number>
  // Stage 32 (ADR-032): lifecycle overlay. The effective due date is the
  // operator-managed one when present, else the profile file's own.
  lifecycleStatus: 'ACTIVE' | 'SUSPENDED' | 'RETIRED'
  effectiveReauthDue: string | null
  reauthOverdue: boolean
}

// Mirrors com.zte.gateway.mcp.acap.lifecycle.AcapReauthorization (Stage 32, ADR-032).
export interface AcapReauthorization {
  id: string
  agentId: string
  reauthorizedBy: string
  reauthorizedAt: string
  nextDue: string
  note: string | null
}

// ── Stage 31 (ADR-031): policy activation overlay + AI audit runs ──

// Mirrors gateway-service's com.zte.gateway.policy.activation.PolicyRuleOverride.
export interface PolicyRuleOverride {
  ruleId: string
  enabled: boolean
  updatedBy: string | null
  updatedAt: string
}

// Computed at read time against the live document — see FreshnessEvaluator.
export type FindingFreshness = 'CURRENT' | 'RULE_CHANGED' | 'ADDRESSED'

// FindingView with the stored AuditFinding @JsonUnwrapped into it.
export interface AuditFinding {
  id: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  title: string
  ruleIds: string[] | null
  recommendation: string
  suggestedAction: 'DISABLE_RULE' | 'MODIFY_RULE' | 'ADD_RULE' | 'NONE'
  suggestedYaml: string | null
  acknowledgedBy: string | null
  acknowledgedAt: string | null
  freshness: FindingFreshness
}

// Mirrors com.zte.gateway.policyaudit.PolicyAuditRunView.
export interface PolicyAuditRun {
  id: string
  timestamp: string
  requestedBy: string | null
  model: string | null
  status: 'COMPLETED' | 'PARSE_ERROR' | 'FAILED'
  rawReport: string | null
  findings: AuditFinding[]
}
