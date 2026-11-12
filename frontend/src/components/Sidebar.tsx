import type { ChatSummary } from '../api/types'
import { relativeTime } from '../lib/format'

interface SidebarProps {
  chats: ChatSummary[]
  activeId: string | null
  query: string
  onQueryChange: (query: string) => void
  onSelect: (id: string) => void
  onNew: () => void
  onDelete: (id: string) => void
}

export function Sidebar({ chats, activeId, query, onQueryChange, onSelect, onNew, onDelete }: SidebarProps) {
  return (
    <aside className="flex w-72 shrink-0 flex-col border-r border-zinc-800 bg-zinc-900/30">
      <div className="flex items-start justify-between px-4 py-3">
        <div>
          <p className="text-sm font-semibold tracking-tight">Locie</p>
          <p className="text-[11px] text-zinc-500">local model chat</p>
        </div>
        <button
          type="button"
          onClick={onNew}
          className="rounded-md bg-emerald-600 px-2.5 py-1.5 text-xs font-medium text-white transition hover:bg-emerald-500"
        >
          New chat
        </button>
      </div>

      <div className="px-3 pb-2">
        <input
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder="Search chats"
          className="w-full rounded-md border border-zinc-800 bg-zinc-950 px-2.5 py-1.5 text-xs text-zinc-200 placeholder:text-zinc-600 focus:border-zinc-700 focus:outline-none"
        />
      </div>

      <nav className="min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {chats.length === 0 && (
          <p className="px-2 py-8 text-center text-xs text-zinc-600">
            {query ? 'No chats match that search' : 'No saved chats yet'}
          </p>
        )}

        {chats.map((chat) => {
          const active = chat.id === activeId
          return (
            <div
              key={chat.id}
              className={`group mb-1 flex items-start gap-1.5 rounded-md px-2.5 py-2 transition ${
                active ? 'bg-zinc-800' : 'hover:bg-zinc-800/60'
              }`}
            >
              <button type="button" onClick={() => onSelect(chat.id)} className="min-w-0 flex-1 text-left">
                <p className="truncate text-xs font-medium text-zinc-200">{chat.title}</p>
                <p className="mt-0.5 truncate text-[11px] text-zinc-500">
                  {chat.model} · {relativeTime(chat.updatedAt)}
                </p>
              </button>
              <button
                type="button"
                onClick={() => onDelete(chat.id)}
                title="Delete chat"
                aria-label={`Delete ${chat.title}`}
                className="shrink-0 rounded px-1 text-[11px] text-zinc-600 opacity-0 transition group-hover:opacity-100 hover:text-red-400 focus:opacity-100"
              >
                ✕
              </button>
            </div>
          )
        })}
      </nav>
    </aside>
  )
}
