// Mirrors gateway-service's com.zte.gateway.mcp.approval.PendingApproval
// (Stage 1, ADR-019) — same shape zt-admin-ui/src/types.ts declares; kept as a
// copy rather than a shared package because the two SPAs are deliberately
// independent npm projects (ADR-026).
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
  decidedAt: string | null
  decidedBy: string | null
  traceId: string | null
  clientIp: string | null
  userAgent: string | null
  displayIdentity: string | null
}
