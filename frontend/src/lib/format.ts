export function gigabytes(bytes: number | null): string {
  if (!bytes) {
    return '—'
  }
  // decimal units, to match what `ollama list` reports for the same file
  const gb = bytes / 1e9
  return gb >= 10 ? `${Math.round(gb)} GB` : `${gb.toFixed(1)} GB`
}

export function relativeTime(iso: string): string {
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) {
    return ''
  }
  const seconds = Math.max(0, Math.round((Date.now() - then) / 1000))
  if (seconds < 60) {
    return 'just now'
  }
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) {
    return `${minutes}m ago`
  }
  const hours = Math.round(minutes / 60)
  if (hours < 24) {
    return `${hours}h ago`
  }
  return `${Math.round(hours / 24)}d ago`
}

export function duration(ms: number | null): string {
  if (ms == null) {
    return ''
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}
