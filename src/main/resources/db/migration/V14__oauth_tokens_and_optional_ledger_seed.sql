alter table marketplace_connections
    add column if not exists access_token text,
    add column if not exists refresh_token text,
    add column if not exists token_expires_at timestamptz,
    add column if not exists seller_external_id varchar(64);

-- When SEED_LEDGER=false, drop demo orders/cases so live Mercado Livre / Shopee OAuth can be tested.
delete from matching_evidence
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from matching_attempts
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from case_comments
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from case_timeline
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from reconciliation_cases
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from calculation_snapshots
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from settlement_items
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from settlements
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from payout_orders
where '${seed_ledger}' <> 'true'
  and payout_id in (select id from payouts where tenant_id = '8f2c1a00-0000-4000-8000-000000000001');
delete from fees
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from refunds
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from adjustments
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from invoices
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from payments
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from order_items
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from payouts
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from orders
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from closing_deltas
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from closings
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from exports
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from raw_events
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from integration_syncs
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from integration_errors
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from seller_accounts
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
delete from audit_logs
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';

update marketplace_connections
set status = 'disponivel',
    last_sync_at = null,
    access_token = null,
    refresh_token = null,
    token_expires_at = null,
    seller_external_id = null,
    detail = case external_id
        when 'ml' then 'Autorize em auth.mercadolivre.com.br (OAuth 2.0).'
        when 'shopee' then 'Autorize em open.shopee.com.br/auth (seller).'
        else detail
    end
where '${seed_ledger}' <> 'true'
  and tenant_id = '8f2c1a00-0000-4000-8000-000000000001';
