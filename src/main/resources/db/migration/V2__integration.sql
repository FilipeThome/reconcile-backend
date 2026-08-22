create table marketplace_connections (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    name varchar(120) not null,
    kind varchar(32) not null,
    status varchar(32) not null,
    detail varchar(400),
    last_sync_at timestamptz,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    unique (tenant_id, external_id)
);

create index idx_connections_tenant on marketplace_connections (tenant_id);

create table raw_events (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    connection_id uuid references marketplace_connections (id),
    source varchar(64) not null,
    event_type varchar(80) not null,
    idempotency_key varchar(180) not null,
    payload_hash varchar(128) not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, idempotency_key)
);

create table integration_syncs (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    connection_id uuid not null references marketplace_connections (id),
    kind varchar(32) not null,
    status varchar(32) not null,
    started_at timestamptz not null default now(),
    finished_at timestamptz
);

create table integration_errors (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    connection_id uuid references marketplace_connections (id),
    message varchar(500) not null,
    created_at timestamptz not null default now()
);
