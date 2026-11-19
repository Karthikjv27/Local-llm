# Locie

A local-first chat client for the models already installed in [Ollama](https://ollama.com). Conversations are
stored in PostgreSQL, replies stream token by token, the UI shows the machine's real CPU/GPU/VRAM load, and a
small console runs the handful of Ollama commands you actually need — with progress streamed back.

Everything runs on your machine. No cloud API keys, no model downloads beyond what you choose to pull.

```
┌──────────────────────────┐        ┌───────────────────────────┐        ┌──────────────┐
│  React 19 + Vite (:5173) │  /api  │  Spring Boot 4 (:8080)    │  HTTP  │    Ollama    │
│  sidebar · chat · rail   │ ─────► │  MVC + Reactor, JPA,      │ ─────► │  (:11434)    │
│  SSE reader (fetch)      │  SSE   │  Flyway, OSHI, probes     │ NDJSON │              │
└──────────────────────────┘        └─────────────┬─────────────┘        └──────────────┘
                                                  │ JDBC
                                                  ▼
                                          ┌───────────────┐
                                          │  PostgreSQL   │
                                          │  chats        │
                                          │  messages     │
                                          │  command_runs │
                                          └───────────────┘
```

---

## Contents

1. [What it does](#1-what-it-does)
2. [Architecture and request paths](#2-architecture-and-request-paths)
3. [Tech stack — and why each piece](#3-tech-stack--and-why-each-piece)
4. [End-to-end flows](#4-end-to-end-flows)
5. [Data model](#5-data-model)
6. [HTTP API](#6-http-api)
7. [Security model](#7-security-model)
8. [Design decisions and trade-offs](#8-design-decisions-and-trade-offs)
9. [Testing](#9-testing)
10. [Running Locie](#10-running-locie)
11. [Measured behaviour](#11-measured-behaviour)
12. [Known limitations and roadmap](#12-known-limitations-and-roadmap)
13. [Repository layout](#13-repository-layout)
14. [Interview notes](#14-interview-notes)

---

## 1. What it does

| Capability | Detail |
|---|---|
| **Persistent conversations** | Every chat and message is stored in PostgreSQL, including per-reply token counts, throughput and latency |
| **Streaming replies** | Server-Sent Events over a POST; the reply appears token by token with a stop button |
| **Regenerate** | Replaces the last reply in place, refusing to run when the chat doesn't end with a reply |
| **Fork** | Copies a conversation (whole or up to a chosen message) into a new chat so it can diverge |
| **Rewind** | Deletes a message and everything after it, so a prompt can be reworked from that point |
| **Archive** | Moves chats out of the main list into an archived view instead of deleting them |
| **Chat stats** | Per-conversation totals and averages — messages, tokens, tok/s, latency, failures and stops |
| **Model picker** | Installed models with parameter size, quantization, disk size, and which are resident in VRAM |
| **Live metrics** | CPU load overall and per core, memory, GPU utilisation, VRAM, Ollama residency and version |
| **Command console** | Seven allowlisted Ollama/system commands, with output streamed live and every run recorded |
| **Markdown** | Assistant replies render headings, lists, tables, fenced code and links (GFM) |
| **Context budget** | The prompt is trimmed to a token budget newest-first, with a meter showing what the next request will send |
| **Search** | Matches chat titles *and* message content (trigram-indexed in Postgres) |
| **Export** | Any conversation downloads as a Markdown transcript with its per-reply stats |
| **Tool calling** | The model can propose one allowlisted command per turn; nothing runs until the user approves it, a proposal can be decided exactly once even under a double click, each outcome feeds exactly one continuation, and every proposal — approved, denied or rejected — is recorded |
| **Local RAG** | Index a folder of notes/code: files are chunked, embedded with Ollama, stored in PostgreSQL, and the closest passages to each question are added to the prompt with their source paths — unchanged files are skipped on re-index |
| **Stop** | Interrupts generation and keeps what was produced, marked as stopped rather than lost; the UI aborts the stream and also calls a server-side stop so generation ends even if the browser stops listening |

---

## 2. Architecture and request paths

The backend is a **Spring MVC** application that returns **Reactor types** (`Flux<ServerSentEvent<...>>`). MVC
handles the request; when a handler returns a `Flux`, Spring writes each element as an SSE frame and the servlet
thread is released. This gives non-blocking streaming without switching the whole app to WebFlux.

Three distinct request paths exist:

```
Chat reply      browser ──POST /api/chats/{id}/messages──► ChatService ──Flux──► OllamaChatClient
                  ◄────────── SSE: token*, done ──────────          (NDJSON frames)

Command run     browser ──POST /api/commands/run/stream──► CommandService ──ProcessBuilder──► ollama
                  ◄─────── SSE: line*, done ────────────── (virtual thread + Sinks)

Metrics poll    browser ──GET /api/metrics (every 2 s)──► SystemMetricsService
                  ◄────────── JSON snapshot ─────────────   OSHI + GPU probe + Ollama /api/ps
```

Package layout is **package-by-feature**, so a feature's controller, service, entities and DTOs sit together:

```
com.localllm.locie
├── chat/      conversations, messages, SSE streaming, regenerate
├── model/     Ollama clients (admin + native chat stream), catalog views
├── system/    metrics, health, command runner, GPU probes
├── command/   command allowlist, run history, streamed execution
├── rag/       folder indexing, embeddings, cosine retrieval, prompt context
└── common/    typed exceptions, RFC 9457 error handling
```

---

## 3. Tech stack — and why each piece

### Backend

| Choice | Why this and not the alternative |
|---|---|
| **Java 25** | Records for DTOs, pattern matching, and **virtual threads** — every blocking read (process output, GPU probes) runs on one, so a slow `ollama pull` costs no platform thread |
| **Spring Boot 4.1.2** | Dependency injection, config binding, and an embedded server with almost no glue code. Boot 4 is also the version that pairs with Spring Framework 7 / Java 25 |
| **Spring MVC + Reactor** (`spring-boot-starter-webmvc` + `webflux`) | MVC + a returned `Flux` streams SSE without converting the app to a reactive stack. `webflux` is present for `WebClient` and the reactive types, not to run a Netty server |
| **Spring Data JPA / Hibernate** | Entity mapping and derived queries (`findByChatIdOrderBySeqAsc`) cover everything the app needs; no hand-written SQL for CRUD. `ddl-auto=validate` makes Hibernate a schema *checker* — Flyway owns the schema |
| **Flyway** (`flyway-core`, `flyway-database-postgresql`) | Versioned, reviewable migrations. The alternative (`ddl-auto=update`) silently drifts from production |
| **PostgreSQL** | `uuid` keys, `timestamptz`, `numeric` for rates, check constraints, cascade deletes. Chosen over an embedded DB because the data model is relational and the app is already a server |
| **OSHI** (`oshi-core` 6.12.0) | Cross-platform CPU tick counters and memory from the OS itself. Beats parsing `wmic` output, and gives per-core ticks, which is what makes the per-thread bars possible |
| **Lombok** | `@Getter/@Setter` on entities only; DTOs are plain records so nothing is hidden from the reader |
| **Spring Boot Actuator** | Liveness/readiness plumbing for free, exposed minimally (`health`, `info`) |
| **nvidia-smi / Windows CIM** | GPU data has no Java API. NVIDIA is queried through `nvidia-smi`; AMD/Intel through the Windows GPU performance counters (see [GPU probes](#gpu-probes)) |

**Deliberately removed:** `spring-ai-starter-model-ollama`. Spring AI's `ChatClient` was evaluated and dropped —
its streaming aggregator rebuilds usage as `DefaultUsage(int,int,int)` and discards Ollama's native
`eval_count` / `eval_duration`, which are exactly the numbers this app reports. Reading Ollama's NDJSON
directly preserves them. Removing the starter also removed the auto-configured `WebClient.Builder` /
`RestClient.Builder` beans, so both clients now build their own.

### Frontend

| Choice | Why |
|---|---|
| **React 19 + TypeScript** | Component model fits a three-pane UI; types are shared between the API client and components |
| **Vite 8** | Dev server with a `/api` proxy (so the browser only ever talks to one origin in dev) and a fast production build |
| **Tailwind CSS 4** (via `@tailwindcss/vite`) | Utility classes keep the whole design in the markup; no CSS files to keep in sync with components |
| **react-markdown + remark-gfm** | Renders assistant replies as real HTML (lists, tables, fenced code) rather than escaped text. GFM because models emit tables and task lists |
| **oxlint** | Fast lint pass with no plugin/config ecosystem to maintain; runs as part of review |
| **Hand-written SSE reader** | `EventSource` cannot POST a body, so the stream is read from `fetch` with `TextDecoder` and split on blank lines |

No state library, no data-fetching library: server state lives in `App.tsx` with `useState`, and the two hooks
(`useChatStream`, `useMetrics`) own the only side effects.

---

## 4. End-to-end flows

### 4.1 Startup

1. `application.properties` sets `spring.profiles.default=local`; `application-local.properties` (gitignored)
   supplies the database URL, user and password. Anything can be overridden by `DB_URL`, `DB_USER`,
   `DB_PASSWORD`, `OLLAMA_BASE_URL`.
2. **Flyway** applies `db/migration/V1__init.sql` before JPA starts.
3. **Hibernate validates** the entities against the migrated schema — a mismatch fails the boot instead of
   failing later in production.
4. Beans wire up: `OllamaAdminClient` (RestClient, 5 s timeouts), `OllamaChatClient` (WebClient),
   `SystemMetricsService` (OSHI handles taken once), `GpuService` (ordered list of `GpuProbe`s),
   `CommandCatalog` (the allowlist, built in a constructor).

### 4.2 Sending a message (`POST /api/chats/{id}/messages`)

```
ChatController.send
  └─ ChatService.streamReply(chatId, content)
       1. requireChat           → 404 ChatNotFoundException if missing
       2. new Message(USER)     → seq = max(seq)+1, saved immediately
       3. chats.touch(now)      → keeps the sidebar ordering correct
       4. if title == "New chat" → title = first 60 chars of the prompt
       5. promptMessages()      → system prompt + last 40 messages as {role, content}
       6. stream(chat, model, prompt) → Flux
ChatController returns Flux<ServerSentEvent<ChatStreamEvent>>
```

Inside the stream:

```java
chatStream.stream(model, prompt)        // NDJSON frames from Ollama
    .doOnNext(c -> { if (c.done()) finalChunk.set(c); })
    .mapNotNull(c -> sse("token", token(c.content())))   // one SSE event per token
    .publishOn(Schedulers.boundedElastic())              // blocking JDBC below
    .concatWith(Flux.defer(() -> {                       // only after the last token
        if (!saved.compareAndSet(false, true)) return Flux.empty();
        return Flux.just(sse("done", persistAnswer(...)));
    }))
    .onErrorResume(ex -> { persistFailure(...); return Flux.just(sse("error", ...)); });
```

Key properties of that design:

- **Persist after, never during.** A message in the database always matches what the user saw. A mid-stream
  failure is stored with its partial text and the error attached.
- **`AtomicBoolean saved`** guarantees `done` and `error` can't both write a row.
- **`publishOn(boundedElastic)`** moves the JDBC write off the event-loop thread.
- **`Flux.defer`** keeps the whole thing cold: the HTTP call to Ollama only happens when a subscriber appears.

The SSE payloads are:

```jsonc
{ "type": "token", "text": "Hello" }
{ "type": "done",  "message": { "id": "…", "role": "ASSISTANT", "completionTokens": 5,
                                "tokensPerSec": 39.96, "latencyMs": 9321 } }
{ "type": "error", "error": "Ollama is unreachable" }
```

**Throughput is not a wall-clock guess.** `tokensPerSec = eval_count / (eval_duration nanoseconds / 1e9)`,
rounded to two decimals, and left `null` when Ollama sends no counters. Wall-clock latency is stored separately
in `latency_ms`, because it includes model load and prompt evaluation.

### 4.3 Regenerate (`POST /api/chats/{id}/regenerate`)

1. Load history; **refuse unless the last message is an ASSISTANT reply** (`400 The last message is not a reply`).
2. Delete trailing assistant rows (a failed regenerate can therefore never delete a user message).
3. Touch the chat and stream a new reply from the remaining prompt.

The frontend mirrors this: it pops the assistant message locally before the stream starts, so the new reply
animates into the same place.

### 4.4 Model catalog and metrics

`ModelCatalogService.list()` merges `GET /api/tags` (installed) with `GET /api/ps` (resident) on model name and
computes `gpuPercent = size_vram / size` — the share of the model actually on the GPU. `SystemMetricsService`
samples:

- **CPU** — two OSHI tick snapshots; load is `1 − idleDelta / totalDelta`, computed system-wide and per core.
- **Memory** — `total − available` from OSHI.
- **GPU** — `GpuService.current()`.
- **Ollama** — running models, summed VRAM and daemon version; a `503` from Ollama degrades this section rather
  than failing the endpoint.

The frontend polls every **2 s** (`useMetrics`), keeps a 40-point series for sparklines, and pauses while the
tab is hidden — except for the first sample, so a background tab still shows real values when it comes forward.

### 4.5 Command console (`POST /api/commands/run/stream`)

```
CommandService.stream(request)
  1. prepare(request):
       catalog.require(id)              → 400 Unknown command
       validate args                    → 400 for missing/flag/metacharacter args
       insert command_runs row (RUNNING)
  2. virtual thread: CommandRunner.run(argv, timeout, 256 KB, line -> sink.tryEmitNext(line))
  3. on completion: update the row (status, exit code, duration), emit "done"
  4. emit "error" if the runner itself throws; always complete the sink
```

`CommandRunner` uses `ProcessBuilder` with an argument **list** — never a shell string — so metacharacters have
no meaning. Each command has its own timeout (30 s list, 60 s rm/stop, 300 s pull) and output is capped at
256 KB. stdout and stderr are read on separate virtual threads and both could be stored and streamed.

Output goes through a small state machine before it leaves the process:

- lines break on `\n`, `\r` **and** on a cursor-reset escape (`ESC[nG`), which is how Ollama redraws a progress
  bar instead of using a carriage return;
- CSI and OSC sequences are consumed (never emitted), so `ESC[?25l` cannot leak into the browser;
- braille spinner frames (`U+2800`–`U+28FF`) are stripped.

The frontend collapses repeated progress-bar redraws into one line per digest, so a 600 MB download shows a
progress line rather than 1,800 of them.

#### GPU probes

`GpuService` walks ordered `GpuProbe` beans and returns the first snapshot, caching for **2 s** and serialising
refreshes so overlapping polls can't spawn parallel PowerShell processes.

| Order | Probe | Notes |
|---|---|---|
| 10 | `NvidiaSmiGpuProbe` | `nvidia-smi --query-gpu=…`; remembers for 60 s that the tool is absent |
| 20 | `WindowsGpuProbe` | `Win32_VideoController` for adapter names/VRAM, GPU performance counters for utilisation and dedicated usage; adapters cached 120 s, 6 s timeout for a cold PowerShell start |
| — | fallback | `GpuSnapshot.unavailable(reason)` — the UI shows *why* rather than zeros |

The Windows counters are per engine and per adapter LUID, so the probe reports the busiest engine as
utilisation and the sum of dedicated usage as VRAM in use.

### 4.6 Frontend data flow

```
App.tsx  ── state: chats, models, activeId, detail, query, banner
   │
   ├─ Sidebar        search (server-side, title match) · select · new · delete
   ├─ ChatView       message list + streaming bubble + regenerate button
   ├─ Composer       textarea, Enter to send, Stop while streaming
   └─ RightRail      Metrics tab (CPU/GPU/memory/Ollama) · Actions tab (command console)
```

Sending optimistically appends the user's message locally, streams the reply, then re-fetches the chat because
the server may have renamed the title from the first prompt.

---

## 5. Data model

```sql
chats(id uuid pk, title text, model text, system_prompt text,
      pinned bool, archived bool, created_at timestamptz, updated_at timestamptz)

messages(id uuid pk, chat_id uuid → chats on delete cascade,
         seq int, role text check in (USER, ASSISTANT, SYSTEM),
         content text, model text,
         prompt_tokens int, completion_tokens int,
         latency_ms int, tokens_per_sec numeric(8,2), error text, cancelled bool,
         created_at timestamptz,
         unique (chat_id, seq))

command_runs(id uuid pk, chat_id uuid → chats on delete set null,
             command text, args text,
             status text check in (RUNNING, OK, FAILED, TIMEOUT, REJECTED),
             exit_code int, stdout text, stderr text,
             started_at timestamptz, finished_at timestamptz, duration_ms int)

rag_documents(id uuid pk, path text unique, title text, content_hash text,
              size_bytes bigint, chunk_count int, indexed_at timestamptz)

rag_chunks(id uuid pk, document_id uuid → rag_documents on delete cascade,
           ordinal int, content text, embedding text,
           unique (document_id, ordinal))

tool_calls(id uuid pk, chat_id uuid → chats on delete cascade,
           message_id uuid → messages on delete set null,
           command_id text, args text,
           status text check in (PENDING, APPROVED, DENIED, FAILED),
           command_run_id uuid → command_runs on delete set null,
           exit_code int, duration_ms int, output text, error text,
           continued bool, created_at timestamptz, decided_at timestamptz)
```

Why it looks like this:

- **`seq` instead of ordering by timestamp.** Messages inside one turn can land in the same millisecond; `seq` is
  the conversation order, and `unique(chat_id, seq)` makes it enforceable. Regenerate reuses the sequence number
  it deleted.
- **Cascade on messages, set-null on command runs.** Deleting a chat must remove its messages, but command
  history is an audit trail that should survive.
- **`tokens_per_sec numeric(8,2)`** rather than a float, so stored rates compare exactly.
- **Enum-as-text with a check constraint** — readable in `psql`, and Hibernate maps it with `@Enumerated(STRING)`.
- **Indexes**: `chats(updated_at desc)` for the sidebar, `command_runs(started_at desc)` for history; the unique
  constraint doubles as the index for `messages(chat_id, seq)`. Content search adds a GIN trigram index on
  `messages.content`, because `%term%` matching cannot use a btree.
- **`cancelled`** distinguishes "the user pressed Stop" from "generation failed" — both keep the partial text,
  but only one is an error.
- **Tool proposals are their own table.** A pending approval has to survive a refresh, and a denied proposal is
  still evidence of what the model asked for. The command it produces is a normal `command_runs` row, so the
  audit trail stays in one place and deleting a chat does not erase it.
- **A decision is won, not checked.** `approve`/`deny` move the row with `update … where status = 'PENDING'` and act
  only when that update touched one row, so two concurrent approvals cannot both run the command. The foreign key
  nulls `message_id` when a message is deleted, so rewind and regenerate remove the proposals of the turns they
  drop — a card can never outlive the conversation turn that proposed it.

---

## 6. HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | DB + Ollama reachability, `UP` / `DEGRADED` with a reason |
| GET | `/api/models` | Installed models + residency, VRAM share |
| GET | `/api/models/running` | Loaded models with `size_vram`, `gpuPercent`, `expiresAt` |
| GET | `/api/models/{*name}` | Full metadata from `/api/show` (capabilities, parameters, system) |
| GET | `/api/metrics` | CPU (system + per core), memory, GPU, Ollama |
| GET | `/api/chats?q=&archived=` | Summaries, pinned first then most recent; `q` matches titles or message content; `archived=true` lists archived chats |
| POST | `/api/chats` | Create (`model` required) |
| GET | `/api/chats/{id}` | Chat with full message history |
| PATCH | `/api/chats/{id}` | Rename, switch model, set system prompt, pin, archive |
| DELETE | `/api/chats/{id}` | Delete (cascades messages) |
| GET | `/api/chats/{id}/export` | Markdown transcript, sent as a download attachment |
| POST | `/api/chats/{id}/messages` | Send a prompt — **SSE** |
| POST | `/api/chats/{id}/regenerate` | Replace the last reply — **SSE** |
| POST | `/api/chats/{id}/stop` | Stop the running reply now — returns `{"stopped": true\|false}` |
| POST | `/api/chats/{id}/fork?fromSeq=` | Copy the conversation (optionally up to a sequence number) into a new chat |
| DELETE | `/api/chats/{id}/messages/{messageId}` | Rewind: remove this message and everything after it |
| GET | `/api/chats/{id}/stats` | Message counts, token totals, average tok/s and latency, failures and stops |
| POST | `/api/chats/{id}/tool-calls/{callId}/approve` | Runs a proposed command — re-validated against the allowlist; a second approve of the same proposal is `400` |
| POST | `/api/chats/{id}/tool-calls/{callId}/deny` | Declines it; nothing is executed |
| POST | `/api/chats/{id}/tool-calls/{callId}/continue` | Model answers using the result once — **SSE**; a second use is `400` |
| GET | `/api/commands` | The allowlist, with timeouts and destructive flags |
| POST | `/api/commands/run` | Run a command to completion, return the saved run |
| POST | `/api/commands/run/stream` | Run a command — **SSE**: `line`*, `done` |
| GET | `/api/commands/history` | 50 most recent runs |
| GET | `/api/rag/documents` | Indexed files with chunk counts |
| POST | `/api/rag/index` | Index a folder (`{"path": "D:\\notes"}`); unchanged files are skipped |
| DELETE | `/api/rag/documents/{id}` | Remove one file and its chunks from the index |
| GET | `/api/rag/search?q=&limit=` | The passages a question would retrieve, with scores |

Errors are RFC 9457 `application/problem+json` (`status`, `title`, `detail`, `instance`), produced by
`ApiExceptionHandler` from typed exceptions:

| Exception | Status |
|---|---|
| `IllegalArgumentException` (bad argument, bad request body) | 400 |
| `ChatNotFoundException` | 404 |
| `OllamaModelNotFoundException` | 404 |
| `MessageNotFoundException` | 404 |
| `OllamaUnavailableException` | 503 |

Example error:

```json
{ "status": 400, "title": "Invalid request", "detail": "Invalid argument: --all",
  "instance": "/api/commands/run/stream" }
```

---

## 7. Security model

The interesting surface is the command console, because it runs programs.

1. **No shell, ever.** Arguments are passed as a list to `ProcessBuilder`. There is no string concatenation into
   a command line, so `;`, `|`, `$(...)` and quotes are inert.
2. **Allowlist only.** `CommandCatalog` fixes the executable *and* the subcommand: `ollama list`, `ollama ps`,
   `ollama show`, `ollama pull`, `ollama stop`, `ollama rm`, `tasklist`. Nothing else can be requested.
3. **One validated argument.** Commands that take a model name accept a single argument matched against
   `^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$`, and a leading `-` is rejected so a flag can't be smuggled in.
4. **Bounded execution.** Per-command timeout, 256 KB output cap, no stdin.
5. **Destructive actions are marked** (`ollama rm`) and the UI requires an explicit confirmation.
6. **Everything is recorded** in `command_runs`, including exit code, duration and output.
7. **Model-proposed commands need approval.** The model sees one `run_command` tool whose `command_id` enum is
   generated from the catalog, so it never learns about anything outside the allowlist. A proposal is stored as
   `tool_calls` (`PENDING`) and executes only when the user approves it in the UI; approval re-validates the id and
   the argument against the catalog, so a tampered row cannot widen the allowlist. Denials are recorded too.
8. **Secrets stay out of git.** `application-local.properties` is ignored; `.gitignore` also covers `target/`,
   `node_modules/`, `dist/`, `.env` and JVM crash logs.

Known gaps (documented, not accidental): the server binds all interfaces and there is no authentication, so it is
a **local tool** by design today. Loopback binding plus a token filter is the obvious next step if it ever leaves
one machine.

---

## 8. Design decisions and trade-offs

**Why not Spring AI?** It was integrated and then removed. Its streaming aggregator collapses
`eval_duration`/`eval_count` into a `Usage` object without them, so the exact tok/s figure this project is built
around was unavailable. The native `/api/chat` call is a small client and preserves every field. Keeping the starter
"just in case" also kept two auto-configured HTTP client builders around, which broke the context when removed —
that's how the dependency was proven unnecessary.

**Why SSE and not WebSockets?** The traffic is one-directional server→client for the life of one request. SSE
survives proxies, is plain HTTP, and needs no handshake, heartbeat or replay logic. The cost is that
`EventSource` can't POST, so the frontend parses the stream from `fetch`.

**Why bytes, not strings, when framing Ollama's NDJSON?** A network buffer can end mid UTF-8 sequence. The client
accumulates raw bytes and only decodes complete lines, so a multi-byte character split across two packets can't
turn into `�`. The same reasoning reappears in `CommandRunner`, which reads through an `InputStreamReader` and
carries partial lines across reads.

**Why persist after streaming rather than token-by-token?** Writing every token would multiply database round
trips by ~40 per reply and make "what is stored" ambiguous after a failure. Persisting once, in `concatWith`,
gives an atomic outcome: full text and counters, or partial text plus an error.

**Why `seq` and not `created_at`?** Two messages from one turn can share a timestamp; ordering must be stable and
editable, which regeneration needs.

**Why are GPU probes pluggable?** There is no portable Java API for GPU load. On Windows the vendor-neutral
counters work for AMD and Intel, `nvidia-smi` is richer for NVIDIA, and a machine with neither must still get a
usable app. The `GpuProbe` interface with `@Order` lets a new vendor be added without touching the metrics
service, and `GpuSnapshot.unavailable(reason)` keeps failures honest instead of showing zero.

**Why virtual threads?** Process output, PowerShell probes and JDBC are all blocking. Virtual threads let those
run without sizing a pool or starving the request threads — which matters when a `pull` streams for minutes.

**Why is the console limited to seven commands?** Because a "run any command" console is a remote shell with a
web UI. The allowlist is the security boundary; adding a command is a deliberate code change with a review.

**Why a `LlmProvider` port when there is only one implementation?** The boundaries that change are not
persistence or HTTP — they are the model runtime, the hardware probes and the command surface. `LlmProvider`
puts the seam where a second provider (a hosted API, another local runtime) will land, so that change costs one
new adapter. ArchUnit enforces it: `ChatService` may not import an `Ollama*` class, and nothing outside `model`
may touch the transport clients.

**Why is retrieval a prompt prefix rather than a second model call?** The question is embedded
once, the chunks are ranked by cosine, and the winners become one extra system message. That keeps
one generation request per turn, makes the retrieved sources visible in the code path, and means a
missing embedding model degrades to a normal chat instead of an error. The cost is that the chat
model cannot ask for a second, better-targeted search — that needs a tool loop, not a prefix.

**Why is the embedding stored as text and ranked in Java?** The `vector` extension is not
installed on every machine this runs on, and a `CREATE EXTENSION` that fails would stop the app from
booting. A personal index is small (a folder of notes), so an exact scan is fast and the ranking is
readable and testable. The column stays a text list of floats, so moving to `vector` + an HNSW
index later is a migration, not a redesign.

**Trade-offs accepted:** no pagination yet (fine for personal history, not for thousands of chats); the frontend
has no state library (fine at this size, would need one if the UI grew another axis); no authentication (local
tool); GPU utilisation on Windows is the busiest engine, not an average.

---

## 9. Testing

```bash
./mvnw test                 # backend
cd frontend && npm run build   # typecheck + production build
cd frontend && npm run lint    # oxlint
```

| Test | What it locks down |
|---|---|
| `LocieApplicationTests` | The whole context loads: datasource, Flyway, JPA mappings, every bean |
| `CommandRunnerTest` (5 tests) | Line framing: cursor-reset ends a line, terminal escapes are swallowed, spinner frames stripped, a multi-byte character split at a 1-byte read boundary survives, a 3,000-char line isn't split by the 2,048-char buffer |
| `ContextBudgetTest` (5 tests) | Everything fits; oldest dropped first; a window never starts with a reply; the system prompt consumes budget; the four-chars-per-token estimate |
| `ChatStatsTest` (4 tests) | Empty history, rate/latency averages, failed vs stopped counts, and first/last message timestamps |
| `ActiveStreamsTest` (4 tests) | A registered stream can be stopped, an idle stop is a no-op, closing unregisters, and an old close cannot remove a newer registration |
| `ToolCallServiceTest` (13 tests) | A proposal stays pending until decided; an off-allowlist id or unknown tool name is recorded as failed; approval runs the command; deny never runs it; a decision is final; a raced second approve runs nothing; a proposal whose turn was rewound never runs; invalid arguments fail without a run; discard removes proposals and old orphans; an undecided proposal cannot continue, and a decided one only once |
| `RagChunkerTest` (6 tests) | Blank text produces nothing, short text stays whole, windows never exceed the size, a paragraph boundary is preferred, consecutive chunks overlap, and an overlap larger than the window still terminates |
| `RagServiceTest` (8 tests) | Indexing writes the document and its chunks while skipping ignored folders and binary files, an unchanged hash skips the embedder, search ranks by cosine and caps chunks per document, low scores are dropped, the context block carries the source path, a failed embedding leaves the chat without retrieval, a missing folder is rejected, and deleting removes the chunks |
| `ChatServiceTest` (3 tests) | Rewind and regenerate delete the proposals of the turns they remove (before the messages, so nothing is orphaned), and regenerating with nothing to replace changes nothing |
| `CatalogToolRunnerTest` (6 tests) | The tool schema's enum is exactly the allowlist, destructive and argument-taking commands are described, and running rejects an unknown id or a flag-shaped argument |
| `OllamaChatClientTest` (4 tests) | Tool calls parse whether Ollama sends arguments as an object or a JSON string; plain and counter-only frames still parse |
| `ArchitectureTest` (6 rules) | Controllers never touch repositories, feature modules stay cycle-free, `model` doesn't reach upward, domain helpers stay framework-free, and `ChatService` depends on `LlmProvider` rather than the Ollama classes |

CI runs the same gates on every push (`.github/workflows/ci.yml`): `mvn verify` for the backend against a
Postgres service container, then `npm ci`, `npm run lint` and `npm run build` in `frontend/`.

The splitter tests exist because that logic shipped broken twice — first leaking `ESC[?2026h`, then treating `[`
as the CSI terminator. Each test was verified by reintroducing the bug (`cursorReset`, missing length guard,
spinner strip disabled, ISO-8859-1 decoding) and confirming the right test fails.

**Not covered yet:** controllers, `ChatService`, the SSE reader on the frontend, and the command allowlist —
the highest-value next tests.

---

## 10. Running Locie

### Prerequisites

- Java 25+, Node 20+, PostgreSQL, Ollama with at least one model (`ollama pull qwen2.5-coder:3b`)
- For retrieval: an embedding model (`ollama pull nomic-embed-text`, ~274 MB); without it indexing
  returns a 404 telling you to pull it, and chat keeps working without retrieval

### Database

```sql
CREATE ROLE locie LOGIN PASSWORD 'choose-a-password';
CREATE DATABASE local_llm_chat OWNER locie;
```

Flyway creates the tables on first start.

### Configuration

`src/main/resources/application-local.properties` (gitignored):

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/local_llm_chat
spring.datasource.username=locie
spring.datasource.password=choose-a-password
```

Override anywhere with `DB_URL`, `DB_USER`, `DB_PASSWORD`, `OLLAMA_BASE_URL`, `CHAT_CONTEXT_TOKENS` (default
4096) or `CHAT_HISTORY_LIMIT` (default 40).

### Start

```bash
# backend (default 8080)
./mvnw spring-boot:run

# backend on another port
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081

# frontend
cd frontend
npm install
npm run dev                     # http://localhost:5173, proxies /api → :8080

# if the backend is elsewhere
VITE_API_TARGET=http://localhost:8081 npm run dev
```

On a memory-tight machine, cap the build JVM: `MAVEN_OPTS="-Xmx640m" ./mvnw test`.

---

## 11. Measured behaviour

Numbers taken from this machine (Ryzen 5 5600H, 8 GB RAM, Radeon RX 6500M with 4.3 GB VRAM):

| Observation | Value |
|---|---|
| `qwen2.5-coder:3b` reply rate | 39.96 / 45.11 / 49.35 / 50.31 tok/s across runs |
| `llama3:latest` (8B) reply rate | 0.66 – 6.03 tok/s — 4.7 GB of weights in 4.3 GB VRAM, so ~16% runs on CPU |
| Cold model load | `load_duration` 9,089 ms, excluded from `eval_duration` |
| VRAM with an 8B model loaded | 2.5 GB resident, `ollama ps` reports 16%/84% CPU/GPU |
| GPU probe overhead | Windows counters ~2 s cache; nvidia-smi 2 s timeout, skipped 60 s when absent |
| Streamed pull | 1,887 SSE line events over 212 s for a 636 MB model |

The tok/s gap between models above is the honest reason the metrics rail exists: on a small card the difference
between "fits in VRAM" and "partly on CPU" is two orders of magnitude.

---

## 12. Known limitations and roadmap

**Limitations**

- Context is budgeted by an **estimate** (≈4 characters per token), not the model's real tokenizer, so a dense
  code-heavy chat can still overflow slightly. `CHAT_CONTEXT_TOKENS` must be raised if the model was started
  with a larger `num_ctx`.
- Stop still depends on the stream reaching a cancellation point: the server-side request flips a flag that
  cancels the provider stream between chunks, but a model that never yields another chunk cannot be interrupted
  mid-token. The partial reply is kept either way once cancellation is observed.
- No authentication, binds all interfaces.
- No pagination for chats, messages or command history; command output is stored up to 256 KB per run.
- Search is substring matching, so it does not stem or rank results.
- RAG ranks chunks independently: there is no reranker, no query rewriting, and no citation UI — the
  sources are named inside the prompt the model receives, and retrieval runs on every message once
  something is indexed (there is no per-chat toggle yet).
- Tool calling depends on the model's chat template. `qwen2.5-coder:3b` (the only tools-capable model installed here)
  narrates the call as text instead of emitting `tool_calls`, so the loop was verified against a stub that returns
  Ollama's real wire shape. A model with working tool templates (`llama3.1`, `qwen2.5` 7B+) is needed for the model
  itself to drive it.
- No frontend tests.

**Roadmap, in the order I'd do it**

1. CI (`mvn verify`, `npm ci && lint && build`) and a `docker-compose` that runs Postgres + the app and serves
   the built SPA from Spring.
2. `@ConfigurationProperties` with validation, so a bad `locie.*` value fails at boot instead of first use.
3. Real tokenizer-aware context accounting, so the budget matches the model instead of a character estimate.
4. Model comparison view using the tok/s already recorded per message.
5. Image input — `llava` is already installed.
6. Per-chat retrieval toggle plus citations rendered under each reply, and pgvector + HNSW once the
   extension is present.

---

## 13. Repository layout

```
.
├── pom.xml                          Spring Boot 4, Java 25
├── src/main/java/com/localllm/locie/
│   ├── LocieApplication.java
│   ├── chat/                        Chat, Message, repositories, ChatService, SSE controller
│   ├── model/                       LlmProvider port · OllamaLlmProvider adapter ·
│   │                                OllamaAdminClient (RestClient) + OllamaChatClient (NDJSON)
│   ├── system/                      SystemMetricsService, HealthController, MetricsController,
│   │   └── gpu/                     CommandRunner, GpuProbe, GpuService, NvidiaSmiGpuProbe, WindowsGpuProbe
│   ├── command/                     CommandCatalog, CommandService, CommandRunner glue, stream events
│   ├── rag/                         RagService, RagChunker, OllamaEmbedder, /api/rag endpoints
│   └── common/                      typed exceptions, ApiExceptionHandler
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/V1__init.sql
├── src/test/java/…/system/CommandRunnerTest.java
└── frontend/
    ├── src/App.tsx                  layout + all server state
    ├── src/api/                     typed client, SSE reader, shared types
    ├── src/components/              Sidebar, ChatView, MessageBubble, Composer, ModelPicker,
    │                                RightRail, MetricsPanel, ActionsPanel, MarkdownText
    ├── src/hooks/                   useChatStream, useMetrics
    └── vite.config.ts               Tailwind plugin, /api proxy (VITE_API_TARGET)
```

---

## 14. Interview notes

### Questions this project invites

**"Why not just use Spring AI or LangChain?"**
I integrated Spring AI first and removed it: its aggregator dropped Ollama's `eval_count`/`eval_duration`, which
are the exact numbers the UI reports, so I read the NDJSON stream myself. Fewer layers, exact metrics, and I can
explain every byte.

**"How do you know the tok/s figure is real?"**
It's `eval_count / eval_duration` straight from the daemon's final frame — not wall-clock timing, which would
include model load and prompt evaluation. I store latency separately so both are visible.

**"Why SSE instead of WebSockets?"**
One-way server→client traffic for one request. SSE is plain HTTP, no handshake or reconnect protocol. The cost is
that `EventSource` can't POST, so the client parses frames from `fetch`.

**"How do you stream from MVC?"**
The handler returns `Flux<ServerSentEvent<…>>`; Spring writes each element as a frame and releases the servlet
thread. Blocking work (JDBC) is moved with `publishOn(boundedElastic)`.

**"What stops the command console being a remote shell?"**
No shell at all — `ProcessBuilder` takes an argument list. The catalog fixes executable and subcommand, one
argument is regex-validated with a leading-dash rejection, every command has a timeout and an output cap, and
every run is persisted.

**"What was the hardest bug?"**
Terminal output framing. Ollama redraws progress with a cursor-position escape rather than a carriage return, so
naive splitting produced one enormous line; then the CSI parser treated `[` as the terminator and leaked the rest
of the sequence. Both are now covered by tests that fail when the fix is reverted.

**"What would you do before letting someone else use it?"**
Bind to loopback with a token, add CI and a compose file, catch-all error mapping, pagination, and a
token-budgeted context window.

### Resume bullets

- Built a local-first LLM chat client (Spring Boot 4 / Java 25 / React 19 / PostgreSQL) that streams Ollama
  replies over SSE with exact `eval_count`/`eval_duration` throughput, persisting every conversation.
- Implemented a pluggable GPU/CPU telemetry layer (OSHI + `nvidia-smi` + Windows performance counters) with
  cached, single-flight probes, so the UI shows real VRAM residency and per-core load at 2 s resolution.
- Designed an allowlisted command console with no shell, per-command timeouts and output caps, streaming live
  process output (including `ollama pull` progress) to the browser via SSE.
- Wrote byte-level NDJSON and process-output framing that tolerates UTF-8 characters split across buffers and
  terminal control sequences, backed by mutation-tested unit tests.
