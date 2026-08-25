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
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  requestedAt: string
  decidedAt: string | null
  decidedBy: string | null
  traceId: string | null
  clientIp: string | null
  userAgent: string | null
  displayIdentity: string | null
}
