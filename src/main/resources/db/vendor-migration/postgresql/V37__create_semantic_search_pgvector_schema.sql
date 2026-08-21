create extension if not exists vector;

create table semantic_search_index_versions (
    id uuid primary key,
    release_id varchar(128) not null,
    field_version varchar(128) not null,
    manifest_sha256 char(64) not null,
    search_text_checksum char(64) not null,
    provider varchar(128) not null,
    model varchar(128) not null,
    embedding_mode varchar(16) not null,
    dimension integer not null check (dimension = 1024),
    l2_normalized boolean not null,
    status varchar(16) not null check (status in ('BUILDING', 'READY', 'FAILED', 'RETIRED')),
    active boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp,
    check (not active or status = 'READY')
);

create unique index semantic_search_index_versions_one_active_ready
    on semantic_search_index_versions (active)
    where active;

create table semantic_game_embeddings (
    index_version_id uuid not null references semantic_search_index_versions (id),
    game_id bigint not null,
    embedding vector(1024) not null,
    primary key (index_version_id, game_id)
);
