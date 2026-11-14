-- Stop now keeps the partial reply, and chat search looks inside messages.

alter table messages
    add column cancelled boolean not null default false;

-- '%term%' searches cannot use a btree; a trigram index makes them usable instead of a seq scan.
-- pg_trgm is a trusted extension, so the database owner can create it without superuser rights.
create extension if not exists pg_trgm;
create index idx_messages_content_trgm on messages using gin (content gin_trgm_ops);
