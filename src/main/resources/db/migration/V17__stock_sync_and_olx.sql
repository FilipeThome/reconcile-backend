-- Sales seen before this instant never move stock (old orders, backfill, re-sent notifications).
alter table marketplace_connections
    add column if not exists stock_sync_since timestamptz;

update marketplace_connections
set stock_sync_since = now()
where status = 'ativa'
  and access_token is not null
  and stock_sync_since is null;

insert into marketplace_connections (id, tenant_id, external_id, name, kind, status, detail)
select gen_random_uuid(), t.id, 'olx', 'OLX', 'marketplace', 'disponivel', 'OAuth OLX. O id do anúncio deve ser o SKU do produto.'
from tenants t
where not exists (
    select 1 from marketplace_connections c where c.tenant_id = t.id and c.external_id = 'olx'
);

-- One row per (order, SKU): idempotency key of the stock propagation and the sale alert shown in the UI.
create table stock_sale_events (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    provider varchar(32) not null,
    order_external_id varchar(120) not null,
    sku varchar(120) not null,
    title varchar(200) not null,
    quantity integer not null,
    status varchar(16) not null,
    targets jsonb not null default '[]',
    occurred_at timestamptz not null,
    created_at timestamptz not null default now(),
    unique (tenant_id, provider, order_external_id, sku)
);

create index idx_stock_sale_events_tenant on stock_sale_events (tenant_id, created_at desc);
create index idx_raw_events_type on raw_events (event_type);
