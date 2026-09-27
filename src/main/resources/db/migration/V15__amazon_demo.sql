-- Amazon stays disponivel until a real OAuth grant. SEED_LEDGER=true seeds the demo ledger only.
insert into marketplace_connections (id, tenant_id, external_id, name, kind, status, detail, last_sync_at)
values (
  'a0000000-0000-4000-8000-000000000006',
  '8f2c1a00-0000-4000-8000-000000000001',
  'amazon',
  'Amazon',
  'marketplace',
  'disponivel',
  'Autorize em sellercentral.amazon.com.br (Login with Amazon).',
  null
);

do $$
begin
  if '${seed_ledger}' = 'true' then
    insert into seller_accounts (id, tenant_id, marketplace, seller_external_id)
    values ('8f2c1a00-0000-4000-8000-000000000012', '8f2c1a00-0000-4000-8000-000000000001', 'Amazon', 'A1AMZDEMO204918');

    insert into orders (id, tenant_id, external_id, ordered_at, buyer_name, marketplace, items, gross_amount, expected_net, actual_net, status, reconciliation_status) values
    ('10000000-0000-4000-8000-000000001400', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001400', '2026-05-06 15:10:00+00', 'Laura Bennett', 'Amazon', 1, 259.90, 207.92, 207.92, 'pago', 'reconciliado'),
    ('10000000-0000-4000-8000-000000001401', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001401', '2026-05-19 18:42:00+00', 'Miguel Andrade', 'Amazon', 2, 480.00, 408.00, 378.00, 'pago', 'divergente'),
    ('10000000-0000-4000-8000-000000001402', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001402', '2026-05-27 11:05:00+00', 'Sofia Martins', 'Amazon', 1, 129.00, 103.20, null, 'pago', 'pendente_liquidacao');

    insert into payments (id, tenant_id, external_id, order_id, paid_at, gateway, method, gross_amount, fees, net_amount, status, reconciliation_status) values
    ('20000000-0000-4000-8000-000000001400', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-AMZ-01', '10000000-0000-4000-8000-000000001400', '2026-05-06 15:11:00+00', 'Amazon', 'Cartão', 259.90, 6.50, 253.40, 'aprovado', 'reconciliado'),
    ('20000000-0000-4000-8000-000000001401', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-AMZ-02', '10000000-0000-4000-8000-000000001401', '2026-05-19 18:43:00+00', 'Amazon', 'Cartão', 480.00, 12.00, 468.00, 'aprovado', 'divergente'),
    ('20000000-0000-4000-8000-000000001402', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-AMZ-03', '10000000-0000-4000-8000-000000001402', '2026-05-27 11:06:00+00', 'Amazon', 'Pix', 129.00, 3.20, 125.80, 'aprovado', 'pendente_liquidacao');

    insert into payouts (id, tenant_id, external_id, gateway, paid_at, gross_amount, fees, net_amount, status, reconciliation_status) values
    ('40000000-0000-4000-8000-00000000b400', '8f2c1a00-0000-4000-8000-000000000001', 'REP-AMZ-01', 'Amazon', '2026-05-12 08:00:00+00', 259.90, 51.98, 207.92, 'concluido', 'reconciliado'),
    ('40000000-0000-4000-8000-00000000b401', '8f2c1a00-0000-4000-8000-000000000001', 'REP-AMZ-02', 'Amazon', '2026-05-25 08:00:00+00', 480.00, 102.00, 378.00, 'concluido', 'divergente'),
    ('40000000-0000-4000-8000-00000000b402', '8f2c1a00-0000-4000-8000-000000000001', 'REP-AMZ-03', 'Amazon', '2026-06-03 08:00:00+00', 129.00, 25.80, 103.20, 'previsto', 'pendente_liquidacao');

    insert into payout_orders (payout_id, order_id) values
    ('40000000-0000-4000-8000-00000000b400', '10000000-0000-4000-8000-000000001400'),
    ('40000000-0000-4000-8000-00000000b401', '10000000-0000-4000-8000-000000001401'),
    ('40000000-0000-4000-8000-00000000b402', '10000000-0000-4000-8000-000000001402');

    insert into settlements (id, tenant_id, external_id, payment_id, order_id, settled_on, expected_net, actual_net, status, snapshot_external_id) values
    ('30000000-0000-4000-8000-00000000b400', '8f2c1a00-0000-4000-8000-000000000001', 'SET-AMZ-01', '20000000-0000-4000-8000-000000001400', '10000000-0000-4000-8000-000000001400', '2026-05-12', 207.92, 207.92, 'reconciliado', 'SNAP-140'),
    ('30000000-0000-4000-8000-00000000b401', '8f2c1a00-0000-4000-8000-000000000001', 'SET-AMZ-02', '20000000-0000-4000-8000-000000001401', '10000000-0000-4000-8000-000000001401', '2026-05-25', 408.00, 378.00, 'divergente', 'SNAP-141'),
    ('30000000-0000-4000-8000-00000000b402', '8f2c1a00-0000-4000-8000-000000000001', 'SET-AMZ-03', '20000000-0000-4000-8000-000000001402', '10000000-0000-4000-8000-000000001402', '2026-05-29', 103.20, null, 'pendente_liquidacao', null);

    insert into fees (id, tenant_id, external_id, order_id, fee_type, expected_amount, actual_amount, source, occurred_at) values
    ('50000000-0000-4000-8000-000000000140', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-140', '10000000-0000-4000-8000-000000001400', 'Comissão marketplace', 38.99, 38.99, 'Amazon', '2026-05-06 15:12:00+00'),
    ('50000000-0000-4000-8000-000000000141', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-141', '10000000-0000-4000-8000-000000001400', 'Frete', 12.99, 12.99, 'Amazon', '2026-05-06 15:12:00+00'),
    ('50000000-0000-4000-8000-000000000142', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-142', '10000000-0000-4000-8000-000000001401', 'Comissão marketplace', 72.00, 72.00, 'Amazon', '2026-05-19 18:44:00+00'),
    ('50000000-0000-4000-8000-000000000143', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-143', '10000000-0000-4000-8000-000000001401', 'Tarifa não identificada', 0.00, 30.00, 'Amazon', '2026-05-25 08:00:00+00');

    insert into invoices (id, tenant_id, external_id, order_id, document_type, number, access_key, issued_at, total_amount, status, cbs_amount, ibs_amount) values
    ('60000000-0000-4000-8000-000000100140', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100140', '10000000-0000-4000-8000-000000001400', 'NF-e', '000140', '35260512345678000199550010000001401000014010', '2026-05-06 18:00:00+00', 259.90, 'autorizada', 22.80, 4.70),
    ('60000000-0000-4000-8000-000000100141', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100141', '10000000-0000-4000-8000-000000001401', 'NF-e', '000141', '35260512345678000199550010000001411000014110', '2026-05-19 20:10:00+00', 480.00, 'autorizada', 42.20, 8.60);

    insert into reconciliation_cases (id, tenant_id, external_id, opened_at, order_external_id, payment_external_id, settlement_external_id, payout_external_id, invoice_external_id, cause, description, amount, recoverable_amount, confirmed_loss_amount, impact, status, priority, owner_name, confidence, suggested_action) values
    ('70000000-0000-4000-8000-000000000140', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000140', '2026-05-25 09:30:00+00', 'PED-200001401', 'PAY-AMZ-02', 'SET-AMZ-02', 'REP-AMZ-02', 'NFE-100141', 'tarifa_inesperada', 'Amazon reteve R$ 30,00 de tarifa de indicação não mapeada no motor.', 30.00, 30.00, 0.00, 'recuperavel', 'aberta', 'alta', 'Ana Dantas', 0.9100, 'Mapear a referral fee da Amazon no dicionário de componentes.');

    insert into case_timeline (id, tenant_id, case_id, occurred_at, title, detail, tone) values
    ('72000000-0000-4000-8000-000000000152', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000140', '2026-05-25 09:30:00+00', 'Caso aberto', 'Tarifa Amazon não identificada', 'danger');

    insert into calculation_snapshots (id, tenant_id, external_id, order_id, engine_version, gross_amount, expected_amount, actual_amount, difference_amount, components) values
    ('80000000-0000-4000-8000-000000000140', '8f2c1a00-0000-4000-8000-000000000001', 'SNAP-140', '10000000-0000-4000-8000-000000001400', 'sce-v1.4', 259.90, 207.92, 207.92, 0.00, '[{"label":"Comissão marketplace","amount":-38.99},{"label":"Frete","amount":-12.99}]'),
    ('80000000-0000-4000-8000-000000000141', '8f2c1a00-0000-4000-8000-000000000001', 'SNAP-141', '10000000-0000-4000-8000-000000001401', 'sce-v1.4', 480.00, 408.00, 378.00, 30.00, '[{"label":"Comissão marketplace","amount":-72.00},{"label":"Tarifa não identificada","amount":-30.00}]');
  end if;
end $$;
