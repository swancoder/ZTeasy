/** Display helpers for the dashboard (Stage 29, ADR-029). */

/** Micro-euros → "€735" / "€52.40". Money is integer micro-units end to end. */
export function euros(micros: number, digits?: number): string {
  const value = micros / 1_000_000
  const fractionDigits = digits ?? (value >= 100 ? 0 : 2)
  return `€${value.toLocaleString(undefined, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })}`
}

/** 8240 → "8,240"; 1_250_000 → "1.25M" for token counts that outgrow a tile. */
export function compact(n: number): string {
  if (Math.abs(n) >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`
  return n.toLocaleString()
}

/** An agent id as reported by Keycloak's service accounts, trimmed for display. */
export function agentLabel(agentId: string): string {
  return agentId.startsWith('service-account-') ? agentId.slice('service-account-'.length) : agentId
}
