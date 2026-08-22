create table orders (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    ordered_at timestamptz not null,
    buyer_name varchar(160) not null,
    marketplace varchar(64) not null,
    items integer not null,
    gross_amount numeric(18, 2) not null,
    expected_net numeric(18, 2) not null,
    actual_net numeric(18, 2),
    status varchar(32) not null,
    reconciliation_status varchar(40) not null,
    version bigint not null default 0,
    created_at timestamptz not null default now(),
    unique (tenant_id, external_id)
);

create index idx_orders_tenant_date on orders (tenant_id, ordered_at);

create table order_items (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    order_id uuid not null references orders (id),
    description varchar(200) not null,
    quantity integer not null,
    unit_amount numeric(18, 2) not null
);

create table payments (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    order_id uuid not null references orders (id),
    paid_at timestamptz not null,
    gateway varchar(64) not null,
    method varchar(32) not null,
    gross_amount numeric(18, 2) not null,
    fees numeric(18, 2) not null,
    net_amount numeric(18, 2) not null,
    status varchar(32) not null,
    reconciliation_status varchar(40) not null,
    version bigint not null default 0,
    unique (tenant_id, external_id)
);

create index idx_payments_order on payments (order_id);

create table payouts (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    gateway varchar(64) not null,
    paid_at timestamptz not null,
    gross_amount numeric(18, 2) not null,
    fees numeric(18, 2) not null,
    net_amount numeric(18, 2) not null,
    status varchar(32) not null,
    reconciliation_status varchar(40) not null,
    version bigint not null default 0,
    unique (tenant_id, external_id)
);

create table payout_orders (
    payout_id uuid not null references payouts (id),
    order_id uuid not null references orders (id),
    primary key (payout_id, order_id)
);

create table settlements (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    payment_id uuid not null references payments (id),
    order_id uuid not null references orders (id),
    settled_on date not null,
    expected_net numeric(18, 2) not null,
    actual_net numeric(18, 2),
    status varchar(40) not null,
    snapshot_external_id varchar(64),
    version bigint not null default 0,
    unique (tenant_id, external_id)
);

create table settlement_items (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    settlement_id uuid not null references settlements (id),
    label varchar(120) not null,
    amount numeric(18, 2) not null
);

create table fees (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    order_id uuid not null references orders (id),
    fee_type varchar(80) not null,
    expected_amount numeric(18, 2) not null,
    actual_amount numeric(18, 2) not null,
    source varchar(80) not null,
    occurred_at timestamptz not null,
    unique (tenant_id, external_id)
);

create table refunds (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    order_id uuid,
    refund_type varchar(32) not null,
    occurred_at timestamptz not null,
    amount numeric(18, 2) not null,
    reflected_in_settlement boolean not null default false,
    status varchar(120) not null,
    unique (tenant_id, external_id)
);

create table adjustments (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    order_id uuid references orders (id),
    label varchar(120) not null,
    amount numeric(18, 2) not null,
    created_at timestamptz not null default now()
);
