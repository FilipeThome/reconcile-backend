create table tenants (
    id uuid primary key,
    external_id varchar(64) not null unique,
    name varchar(160) not null,
    settlement_deadline_days integer not null default 14,
    amount_tolerance numeric(18, 2) not null default 0.05,
    date_tolerance_days integer not null default 2,
    raw_event_retention_days integer not null default 180,
    invoice_required boolean not null default true,
    invoice_deadline_hours integer not null default 48,
    created_at timestamptz not null default now()
);

create table users (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    name varchar(160) not null,
    email varchar(180) not null,
    password_hash varchar(255) not null,
    role varchar(32) not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, email)
);

create index idx_users_tenant on users (tenant_id);

create table seller_accounts (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    marketplace varchar(64) not null,
    seller_external_id varchar(80) not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, marketplace, seller_external_id)
);
