import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import type { RagDocumentView } from '../api/types'

/**
 * Indexes a local folder and lists what is indexed. Retrieval itself needs no controls: every
 * message searches the index and the closest passages are added to the prompt.
 */
export function DocsPanel() {
  const [folder, setFolder] = useState('')
  const [documents, setDocuments] = useState<RagDocumentView[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [summary, setSummary] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setDocuments(await api.ragDocuments())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load the index')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const index = async () => {
    const path = folder.trim()
    if (!path || busy) {
      return
    }
    setBusy(true)
    setError(null)
    setSummary(null)
    try {
      const result = await api.indexFolder(path)
      setDocuments(result.documents)
      setSummary(
        `${result.added} added · ${result.updated} updated · ${result.skipped} unchanged · ` +
          `${result.chunks} chunks · ${(result.durationMs / 1000).toFixed(1)}s`,
      )
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Indexing failed')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (document: RagDocumentView) => {
    if (!window.confirm(`Remove "${document.title}" from the index?`)) {
      return
    }
    setError(null)
    try {
      await api.deleteDocument(document.id)
      setDocuments(await api.ragDocuments())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not remove the document')
    }
  }

  const chunks = documents.reduce((total, document) => total + document.chunks, 0)

  return (
    <div className="flex flex-col gap-2">
      <section className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-3">
        <p className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Index a folder</p>
        <input
          value={folder}
          onChange={(event) => setFolder(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              void index()
            }
          }}
          placeholder="D:\notes"
          spellCheck={false}
          className="w-full rounded border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px] text-zinc-200 placeholder:text-zinc-600 focus:border-zinc-700 focus:outline-none"
        />
        <button
          type="button"
          onClick={() => void index()}
          disabled={busy || !folder.trim()}
          className="mt-2 w-full rounded bg-emerald-600 px-2 py-1.5 text-[11px] font-medium text-white transition hover:bg-emerald-500 disabled:opacity-40"
        >
          {busy ? 'Embedding…' : 'Index folder'}
        </button>
        <p className="mt-2 text-[10px] leading-snug text-zinc-600">
          Text and code files are chunked, embedded with the configured model, and stored in Postgres.
          Unchanged files are skipped, so re-index after editing.
        </p>
        {summary && <p className="mt-2 text-[10px] text-emerald-400">{summary}</p>}
        {error && <p className="mt-2 text-[10px] leading-snug text-red-400">{error}</p>}
      </section>

      <section className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-3">
        <div className="flex items-center justify-between">
          <p className="text-[10px] font-semibold uppercase tracking-wider text-zinc-500">Indexed</p>
          <span className="text-[10px] text-zinc-600">
            {documents.length} {documents.length === 1 ? 'file' : 'files'} · {chunks} chunks
          </span>
        </div>
        {documents.length === 0 ? (
          <p className="mt-2 text-[10px] text-zinc-600">Nothing indexed yet — retrieval is off until a folder is added.</p>
        ) : (
          <ul className="mt-2 space-y-0.5">
            {documents.map((document) => (
              <li key={document.id} className="group flex items-center gap-1">
                <span className="min-w-0 flex-1" title={document.path}>
                  <span className="block truncate text-[10px] text-zinc-300">{document.title}</span>
                  <span className="block truncate text-[9px] text-zinc-600">{document.path}</span>
                </span>
                <span className="shrink-0 text-[9px] text-zinc-600">{document.chunks}c</span>
                <button
                  type="button"
                  onClick={() => void remove(document)}
                  aria-label={`Remove ${document.title}`}
                  className="shrink-0 rounded px-1 text-[10px] text-zinc-700 transition hover:text-red-400"
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
