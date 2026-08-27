import { useEffect, useState } from 'react'

/**
 * Fetches one dashboard panel (Stage 29, ADR-029).
 *
 * A `403` is modelled as `forbidden`, not as an error: the gateway's
 * `u2s-dashboard-*` rules legitimately refuse panels this role isn't granted,
 * and the UI should say so plainly rather than showing a scary failure.
 */
export function usePanel<T>(path: string, accessToken: string) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setForbidden(false)
    setError(null)
    fetch(path, { headers: { Authorization: `Bearer ${accessToken}` } })
      .then(async (res) => {
        if (cancelled) return
        if (res.status === 403) {
          setForbidden(true)
          return
        }
        if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`)
        setData((await res.json()) as T)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [path, accessToken])

  return { data, loading, forbidden, error }
}
