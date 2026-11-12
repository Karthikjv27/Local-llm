-- Locie initial schema: conversations, messages and command console runs.

create table chats
(
    id            uuid primary key,
    title         text        not null,
    model         text        not null,
    system_prompt text,
    pinned        boolean     not null default false,
    archived      boolean     not null default false,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create table messages
(
    id                uuid primary key,
    chat_id           uuid        not null references chats (id) on delete cascade,
    seq               integer     not null,
    role              text        not null check (role in ('USER', 'ASSISTANT', 'SYSTEM')),
    content           text        not null default '',
    model             text,
    prompt_tokens     integer,
    completion_tokens integer,
    latency_ms        integer,
    tokens_per_sec    numeric(8, 2),
    error             text,
    created_at        timestamptz not null default now(),
    constraint uq_messages_chat_seq unique (chat_id, seq)
);

create table command_runs
(
    id          uuid primary key,
    chat_id     uuid references chats (id) on delete set null,
    command     text        not null,
    args        text,
    status      text        not null check (status in ('RUNNING', 'OK', 'FAILED', 'TIMEOUT', 'REJECTED')),
    exit_code   integer,
    stdout      text,
    stderr      text,
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    duration_ms integer
);

create index idx_chats_updated_at on chats (updated_at desc);
create index idx_command_runs_started_at on command_runs (started_at desc);
