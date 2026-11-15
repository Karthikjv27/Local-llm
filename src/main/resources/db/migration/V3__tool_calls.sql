-- Every command the model proposes, whether it ran, and what it returned.
-- A row is written when the model asks for a command, so a denied proposal is recorded too.

create table tool_calls
(
    id              uuid primary key,
    chat_id         uuid        not null references chats (id) on delete cascade,
    message_id      uuid references messages (id) on delete set null,
    command_id      text        not null,
    args            text,
    status          text        not null check (status in ('PENDING', 'APPROVED', 'DENIED', 'FAILED')),
    command_run_id  uuid references command_runs (id) on delete set null,
    exit_code       integer,
    duration_ms     integer,
    output          text,
    error           text,
    created_at      timestamptz not null default now(),
    decided_at      timestamptz
);

create index idx_tool_calls_chat_created on tool_calls (chat_id, created_at);
