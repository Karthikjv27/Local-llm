# Locie

A local-first chat client for the models you already have in [Ollama](https://ollama.com). Saved
conversations in PostgreSQL, streaming replies, live system metrics, and a small console for the
handful of Ollama commands you actually need.

## What it does

- **Conversations are saved** — every chat and message lives in PostgreSQL, with per-reply token
  counts and throughput.
- **Replies stream** over SSE, token by token, with a stop button.
- **Model picker** lists installed models, their size and which ones are resident in VRAM.
- **System panel** shows CPU load per thread, memory, GPU utilisation and VRAM, and Ollama residency.
- **Command console** runs a fixed allowlist of Ollama/system commands and records each run.

## Stack

| Layer | Choice |
|---|---|
| Backend | Spring Boot 4, Java 25, Spring MVC, JDBC/JPA, Flyway |
| Database | PostgreSQL |
| Frontend | React 19, Vite, TypeScript, Tailwind CSS 4 |
| Model runtime | Ollama's HTTP API (`/api/chat`, `/api/tags`, `/api/ps`, `/api/show`) |

## Prerequisites

- Java 25+, the Maven wrapper is included (`./mvnw`)
- Node 20+ and npm
- PostgreSQL running locally
- Ollama running locally, with at least one model pulled (`ollama pull qwen2.5-coder:3b`)

## Database setup

Create a database and an owning role:

```sql
CREATE ROLE locie LOGIN PASSWORD 'choose-a-password';
CREATE DATABASE local_llm_chat OWNER locie;
```

Flyway creates the tables on first start (`src/main/resources/db/migration`). No manual schema work
is needed.

## Configuration

Connection details belong in `src/main/resources/application-local.properties`, which is
**gitignored**:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/local_llm_chat
spring.datasource.username=locie
spring.datasource.password=choose-a-password
```

Anything else is overridable with environment variables: `DB_URL`, `DB_USER`, `DB_PASSWORD`,
`OLLAMA_BASE_URL` (default `http://localhost:11434`).

## Running

Backend (default port 8080):

```bash
./mvnw spring-boot:run
```

If 8080 is taken, pick another port: `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Vite serves on <http://localhost:5173> and proxies `/api` to the backend. If the backend is not on
8080, point the proxy at it:

```bash
VITE_API_TARGET=http://localhost:8081 npm run dev
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | Database and Ollama reachability |
| GET | `/api/models` | Installed models with residency state |
| GET | `/api/models/running` | Models currently loaded |
| GET | `/api/models/{name}` | Full model metadata |
| GET/POST | `/api/chats` | List or create conversations |
| GET/PATCH/DELETE | `/api/chats/{id}` | Read, rename or delete a conversation |
| POST | `/api/chats/{id}/messages` | Send a prompt, streamed as SSE |
| POST | `/api/chats/{id}/regenerate` | Replace the last reply |
| GET | `/api/metrics` | CPU, memory, GPU and Ollama residency |
| GET | `/api/commands` | Allowlisted commands |
| POST | `/api/commands/run` | Run one allowlisted command |
| GET | `/api/commands/history` | Recent command runs |

## Design notes

- **Streaming reads Ollama's native `/api/chat`.** Spring AI was evaluated and dropped: its
  streaming aggregator discards Ollama's native usage payload, so `eval_count`/`eval_duration` — the
  exact throughput counters — never reach the caller. The reply rate shown per message is computed
  from those counters, not from wall-clock timing.
- **GPU monitoring is pluggable.** `GpuProbe` has an NVIDIA implementation (`nvidia-smi`) and a
  vendor-neutral Windows implementation (GPU performance counters, which covers AMD and Intel).
  A probe that cannot read anything reports `available: false` with a reason rather than zeros.
- **The command console has no shell.** `CommandCatalog` fixes each executable and subcommand;
  commands taking one argument validate it against a pattern and reject leading flags. Commands run
  via `ProcessBuilder` with an argument list, a timeout and an output cap.
- **Replies are persisted after the stream finishes**, so a message in the database always matches
  what was on screen. A failed stream is stored with its error attached.

## Tests

```bash
./mvnw test        # backend context test
cd frontend && npm run build   # typecheck + production build
```

## Layout

```
src/main/java/com/localllm/locie/
  chat/      conversations, messages, SSE streaming, regenerate
  model/     Ollama clients (catalog and native chat stream), model views
  system/    metrics, health, command runner, GPU probes
  command/   command allowlist and run history
frontend/src/
  api/       typed REST client and SSE reader
  components/ sidebar, model picker, chat view, metrics, actions
  hooks/     streaming and metrics polling
```
