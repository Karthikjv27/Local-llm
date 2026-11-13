import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { MetricsResponse } from '../api/types'

const POLL_MS = 2000
const HISTORY_POINTS = 40

export interface MetricsHistory {
  cpu: number[]
  gpu: number[]
}

/** Polls /api/metrics while the tab is visible and keeps a short series for the sparklines. */
export function useMetrics() {
  const [metrics, setMetrics] = useState<MetricsResponse | null>(null)
  const [history, setHistory] = useState<MetricsHistory>({ cpu: [], gpu: [] })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    const tick = async (force = false) => {
      // paused while the tab is in the background, but the first sample always runs so the
      // panel is not stuck on "waiting" when the page loads unfocused
      if (!force && document.visibilityState === 'hidden') {
        return
      }
      try {
        const next = await api.metrics()
        if (cancelled) {
          return
        }
        setMetrics(next)
        setError(null)
        setHistory((current) => ({
          cpu: [...current.cpu, next.cpu.loadPercent].slice(-HISTORY_POINTS),
          gpu: [...current.gpu, next.gpu.utilizationPercent ?? 0].slice(-HISTORY_POINTS),
        }))
      } catch (cause) {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : 'Metrics unavailable')
        }
      }
    }

    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        void tick(true)
      }
    }

    void tick(true)
    const timer = setInterval(() => void tick(), POLL_MS)
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      cancelled = true
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  return { metrics, history, error }
}
