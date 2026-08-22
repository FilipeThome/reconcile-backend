create table closings (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    period varchar(7) not null,
    label varchar(40) not null,
    status varchar(16) not null,
    closed_at timestamptz,
    orders_count integer not null,
    gross_amount numeric(18, 2) not null,
    expected_amount numeric(18, 2) not null,
    received_amount numeric(18, 2) not null,
    fees_amount numeric(18, 2) not null,
    refunds_amount numeric(18, 2) not null,
    open_diff_amount numeric(18, 2) not null,
    pending_invoices integer not null,
    version bigint not null default 0,
    unique (tenant_id, period)
);

create table closing_deltas (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    closing_id uuid not null references closings (id),
    reason varchar(200) not null,
    amount numeric(18, 2) not null,
    created_at timestamptz not null default now()
);

create table exports (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    report_type varchar(40) not null,
    filename varchar(180) not null,
    created_by varchar(160) not null,
    created_at timestamptz not null default now()
);
