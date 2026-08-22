create table invoices (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    external_id varchar(64) not null,
    order_id uuid not null references orders (id),
    document_type varchar(16) not null,
    number varchar(32) not null,
    access_key varchar(60),
    issued_at timestamptz,
    total_amount numeric(18, 2) not null,
    status varchar(32) not null,
    cbs_amount numeric(18, 2),
    ibs_amount numeric(18, 2),
    tax_metadata jsonb not null default '{}'::jsonb,
    xml_ref varchar(255),
    version bigint not null default 0,
    unique (tenant_id, external_id)
);

create index idx_invoices_order on invoices (order_id);
