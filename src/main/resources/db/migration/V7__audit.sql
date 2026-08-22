create table audit_logs (
    id uuid primary key,
    tenant_id uuid not null references tenants (id),
    actor varchar(160) not null,
    action varchar(80) not null,
    entity_type varchar(80) not null,
    entity_id varchar(80) not null,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index idx_audit_tenant_time on audit_logs (tenant_id, created_at desc);
