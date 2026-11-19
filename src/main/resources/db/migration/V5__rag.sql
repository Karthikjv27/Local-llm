-- Local retrieval over a folder: one row per indexed file, one per chunk.
--
-- The embedding is stored as a comma-separated float list in a text column rather than a pgvector
-- `vector`: the extension is not installed on every machine this runs on, and an index of a
-- personal folder is small enough that exact cosine ranking in the application is fast. The column
-- type keeps the door open for `vector` + an HNSW index later without a data migration.

create table rag_documents
(
    id            uuid primary key,
    path          text        not null unique,
    title         text        not null,
    content_hash  text        not null,
    size_bytes    bigint      not null,
    chunk_count   integer     not null default 0,
    indexed_at    timestamptz not null default now()
);

create table rag_chunks
(
    id          uuid primary key,
    document_id uuid    not null references rag_documents (id) on delete cascade,
    ordinal     integer not null,
    content     text    not null,
    embedding   text    not null,
    constraint uq_rag_chunk unique (document_id, ordinal)
);

create index idx_rag_chunks_document on rag_chunks (document_id);
