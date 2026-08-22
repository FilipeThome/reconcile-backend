create table reconciliation_rules (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    name varchar(160) not null,
    description text not null,
    rule_type varchar(32) not null,
    active boolean not null default true,
    unique (tenant_id, external_id)
);

create table calculation_snapshots (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    order_id uuid not null references orders (id),
    engine_version varchar(32) not null,
    gross_amount numeric(18, 2) not null,
    expected_amount numeric(18, 2) not null,
    actual_amount numeric(18, 2) not null,
    difference_amount numeric(18, 2) not null,
    components jsonb not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, external_id)
);

create table matching_attempts (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    order_id uuid references orders (id),
    layer varchar(64) not null,
    deterministic boolean not null,
    confidence numeric(5, 4) not null,
    result varchar(40) not null,
    created_at timestamptz not null default now()
);

create table matching_evidence (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    attempt_id uuid not null references matching_attempts (id),
    field_name varchar(80) not null,
    expected_value varchar(180),
    actual_value varchar(180)
);

create table reconciliation_cases (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    opened_at timestamptz not null,
    order_external_id varchar(64) not null,
    payment_external_id varchar(64),
    settlement_external_id varchar(64),
    payout_external_id varchar(64),
    invoice_external_id varchar(64),
    cause varchar(64) not null,
    description text not null,
    amount numeric(18, 2) not null,
    recoverable_amount numeric(18, 2) not null,
    confirmed_loss_amount numeric(18, 2) not null,
    impact varchar(32) not null,
    status varchar(32) not null,
    priority varchar(16) not null,
    owner_name varchar(160) not null,
    confidence numeric(5, 4) not null,
    suggested_action text not null,
    version bigint not null default 0,
    unique (tenant_id, external_id)
);

create index idx_cases_tenant_status on reconciliation_cases (tenant_id, status);

create table case_comments (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    case_id uuid not null references reconciliation_cases (id),
    author varchar(160) not null,
    body text not null,
    created_at timestamptz not null default now()
);

create table case_timeline (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    case_id uuid not null references reconciliation_cases (id),
    occurred_at timestamptz not null,
    title varchar(180) not null,
    detail text not null,
    tone varchar(16) not null
);
