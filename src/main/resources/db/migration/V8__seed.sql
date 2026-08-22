-- Demo tenant and users. Password for all: senha123 (DelegatingPasswordEncoder {noop} for local MVP).
insert into tenants (id, external_id, name, settlement_deadline_days, amount_tolerance, date_tolerance_days, raw_event_retention_days, invoice_required, invoice_deadline_hours)
values ('8f2c1a00-0000-4000-8000-000000000001', 'ten_8f2c1a', 'Loja Exemplo', 14, 0.05, 2, 180, true, 48);

insert into users (id, tenant_id, name, email, password_hash, role) values
('8f2c1a00-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', 'Ana Dantas', 'ana.dantas@lojaexemplo.com.br', '{noop}senha123', 'admin'),
('8f2c1a00-0000-4000-8000-000000000003', '8f2c1a00-0000-4000-8000-000000000001', 'Fernando Soller', 'fernando@lojaexemplo.com.br', '{noop}senha123', 'operador'),
('8f2c1a00-0000-4000-8000-000000000004', '8f2c1a00-0000-4000-8000-000000000001', 'Carla Menezes', 'carla@contabilparceiro.com.br', '{noop}senha123', 'contador');

insert into seller_accounts (id, tenant_id, marketplace, seller_external_id)
values ('8f2c1a00-0000-4000-8000-000000000010', '8f2c1a00-0000-4000-8000-000000000001', 'Mercado Livre', 'MLB-204918');

insert into marketplace_connections (id, tenant_id, external_id, name, kind, status, detail, last_sync_at) values
('a0000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', 'ml', 'Mercado Livre', 'marketplace', 'ativa', 'OAuth seller MLB-204918 · backfill 90 dias', '2026-05-31 14:12:00+00'),
('a0000000-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', 'mp', 'Mercado Pago', 'gateway', 'ativa', 'Webhooks + consulta idempotente de detalhe', '2026-05-31 14:15:00+00'),
('a0000000-0000-4000-8000-000000000003', '8f2c1a00-0000-4000-8000-000000000001', 'csv', 'Importação CSV', 'importacao', 'ativa', 'Adapter genérico para pedidos, pagamentos e tarifas', '2026-05-21 08:02:00+00'),
('a0000000-0000-4000-8000-000000000004', '8f2c1a00-0000-4000-8000-000000000001', 'shopee', 'Shopee', 'marketplace', 'disponivel', 'Fora do piloto. Previsto para V1.', null),
('a0000000-0000-4000-8000-000000000005', '8f2c1a00-0000-4000-8000-000000000001', 'fiscal', 'Provedor fiscal', 'fiscal', 'disponivel', 'Adapter para NF-e/NFC-e, XML e DANFE', null);

insert into orders (id, tenant_id, external_id, ordered_at, buyer_name, marketplace, items, gross_amount, expected_net, actual_net, status, reconciliation_status) values
('10000000-0000-4000-8000-000000001234', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001234', '2026-05-04 11:22:00+00', 'Maria Santos', 'Mercado Livre', 2, 1000.00, 842.31, null, 'pago', 'divergente'),
('10000000-0000-4000-8000-000000001235', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001235', '2026-05-05 09:14:00+00', 'João Ribeiro', 'Mercado Livre', 1, 189.90, 158.40, 158.40, 'pago', 'reconciliado'),
('10000000-0000-4000-8000-000000001236', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001236', '2026-05-12 16:40:00+00', 'Helena Costa', 'Mercado Livre', 3, 642.50, 548.10, null, 'pago', 'pendente_liquidacao'),
('10000000-0000-4000-8000-000000001237', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001237', '2026-05-14 08:05:00+00', 'Paulo Mendes', 'Mercado Livre', 1, 79.90, 66.30, 66.30, 'pago', 'reconciliado'),
('10000000-0000-4000-8000-000000001238', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001238', '2026-05-15 19:31:00+00', 'Luiza Prado', 'Mercado Livre', 1, 320.00, 268.40, 160.00, 'cancelado', 'divergente'),
('10000000-0000-4000-8000-000000001239', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001239', '2026-05-18 10:12:00+00', 'Ricardo Alves', 'Mercado Livre', 2, 540.00, 451.20, 451.20, 'pago', 'divergente'),
('10000000-0000-4000-8000-000000001240', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001240', '2026-05-20 13:47:00+00', 'Beatriz Nunes', 'Mercado Livre', 4, 890.00, 748.90, 748.90, 'pago', 'reconciliado'),
('10000000-0000-4000-8000-000000001241', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001241', '2026-05-21 07:55:00+00', 'Sérgio Lima', 'CSV', 1, 210.00, 178.50, 176.20, 'pago', 'reconciliado_alerta'),
('10000000-0000-4000-8000-000000001242', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001242', '2026-05-26 21:08:00+00', 'Amanda Dias', 'Mercado Livre', 1, 149.90, 125.40, null, 'pago', 'pendente_dados'),
('10000000-0000-4000-8000-000000001243', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001243', '2026-05-28 15:20:00+00', 'Thiago Borges', 'Mercado Livre', 2, 430.00, 361.20, 331.20, 'pago', 'divergente'),
('10000000-0000-4000-8000-000000001218', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200001218', '2026-05-10 12:00:00+00', 'Cliente chargeback', 'Mercado Livre', 1, 189.90, 158.40, 158.40, 'pago', 'divergente'),
('10000000-0000-4000-8000-000000000980', '8f2c1a00-0000-4000-8000-000000000001', 'PED-200000980', '2026-04-20 10:00:00+00', 'Pedido residual', 'Mercado Livre', 1, 49.90, 41.90, 41.78, 'pago', 'ignorado');

insert into payments (id, tenant_id, external_id, order_id, paid_at, gateway, method, gross_amount, fees, net_amount, status, reconciliation_status) values
('20000000-0000-4000-8000-000000088421', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88421', '10000000-0000-4000-8000-000000001234', '2026-05-04 11:24:00+00', 'Mercado Pago', 'Cartão', 1000.00, 14.79, 985.21, 'aprovado', 'divergente'),
('20000000-0000-4000-8000-000000088422', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88422', '10000000-0000-4000-8000-000000001235', '2026-05-05 09:16:00+00', 'Mercado Pago', 'Pix', 189.90, 1.90, 188.00, 'aprovado', 'reconciliado'),
('20000000-0000-4000-8000-000000088430', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88430', '10000000-0000-4000-8000-000000001236', '2026-05-12 16:41:00+00', 'Mercado Pago', 'Pix', 642.50, 6.42, 636.08, 'aprovado', 'pendente_liquidacao'),
('20000000-0000-4000-8000-000000088431', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88431', '10000000-0000-4000-8000-000000001237', '2026-05-14 08:06:00+00', 'Mercado Pago', 'Boleto', 79.90, 3.49, 76.41, 'aprovado', 'reconciliado'),
('20000000-0000-4000-8000-000000088440', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88440', '10000000-0000-4000-8000-000000001238', '2026-05-15 19:33:00+00', 'Mercado Pago', 'Cartão', 320.00, 8.20, 311.80, 'estornado', 'divergente'),
('20000000-0000-4000-8000-000000088450', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88450', '10000000-0000-4000-8000-000000001239', '2026-05-18 10:14:00+00', 'Mercado Pago', 'Cartão', 540.00, 12.40, 527.60, 'aprovado', 'divergente'),
('20000000-0000-4000-8000-000000088460', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88460', '10000000-0000-4000-8000-000000001240', '2026-05-20 13:48:00+00', 'Mercado Pago', 'Pix', 890.00, 8.90, 881.10, 'aprovado', 'reconciliado'),
('20000000-0000-4000-8000-000000000012', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-CSV-12', '10000000-0000-4000-8000-000000001241', '2026-05-21 07:56:00+00', 'CSV', 'Pix', 210.00, 2.10, 207.90, 'aprovado', 'reconciliado_alerta'),
('20000000-0000-4000-8000-000000088480', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88480', '10000000-0000-4000-8000-000000001242', '2026-05-26 21:10:00+00', 'Mercado Pago', 'Cartão', 149.90, 4.10, 145.80, 'aprovado', 'pendente_dados'),
('20000000-0000-4000-8000-000000088490', '8f2c1a00-0000-4000-8000-000000000001', 'PAY-88490', '10000000-0000-4000-8000-000000001243', '2026-05-28 15:21:00+00', 'Mercado Pago', 'Cartão', 430.00, 14.79, 415.21, 'aprovado', 'divergente');

insert into payouts (id, tenant_id, external_id, gateway, paid_at, gross_amount, fees, net_amount, status, reconciliation_status) values
('40000000-0000-4000-8000-000000033011', '8f2c1a00-0000-4000-8000-000000000001', 'REP-33011', 'Mercado Pago', '2026-05-08 06:00:00+00', 189.90, 31.50, 158.40, 'concluido', 'reconciliado'),
('40000000-0000-4000-8000-000000033012', '8f2c1a00-0000-4000-8000-000000000001', 'REP-33012', 'Mercado Pago', '2026-05-20 06:00:00+00', 399.90, 73.60, 226.30, 'concluido', 'divergente'),
('40000000-0000-4000-8000-000000033014', '8f2c1a00-0000-4000-8000-000000000001', 'REP-33014', 'Mercado Pago', '2026-05-27 06:00:00+00', 1430.00, 229.90, 1200.10, 'concluido', 'reconciliado'),
('40000000-0000-4000-8000-000000000003', '8f2c1a00-0000-4000-8000-000000000001', 'REP-CSV-03', 'CSV', '2026-05-24 09:00:00+00', 210.00, 33.80, 176.20, 'concluido', 'reconciliado_alerta'),
('40000000-0000-4000-8000-000000033016', '8f2c1a00-0000-4000-8000-000000000001', 'REP-33016', 'Mercado Pago', '2026-06-02 06:00:00+00', 430.00, 98.80, 331.20, 'concluido', 'divergente'),
('40000000-0000-4000-8000-000000033020', '8f2c1a00-0000-4000-8000-000000000001', 'REP-33020', 'Mercado Pago', '2026-06-05 06:00:00+00', 1642.50, 252.10, 1390.40, 'previsto', 'pendente_liquidacao');

insert into payout_orders (payout_id, order_id) values
('40000000-0000-4000-8000-000000033011', '10000000-0000-4000-8000-000000001235'),
('40000000-0000-4000-8000-000000033012', '10000000-0000-4000-8000-000000001237'),
('40000000-0000-4000-8000-000000033012', '10000000-0000-4000-8000-000000001238'),
('40000000-0000-4000-8000-000000033014', '10000000-0000-4000-8000-000000001239'),
('40000000-0000-4000-8000-000000033014', '10000000-0000-4000-8000-000000001240'),
('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000001241'),
('40000000-0000-4000-8000-000000033016', '10000000-0000-4000-8000-000000001243'),
('40000000-0000-4000-8000-000000033020', '10000000-0000-4000-8000-000000001234'),
('40000000-0000-4000-8000-000000033020', '10000000-0000-4000-8000-000000001236');

insert into settlements (id, tenant_id, external_id, payment_id, order_id, settled_on, expected_net, actual_net, status, snapshot_external_id) values
('30000000-0000-4000-8000-000000055102', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55102', '20000000-0000-4000-8000-000000088421', '10000000-0000-4000-8000-000000001234', '2026-05-11', 842.31, null, 'pendente_liquidacao', 'SNAP-001'),
('30000000-0000-4000-8000-000000055103', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55103', '20000000-0000-4000-8000-000000088422', '10000000-0000-4000-8000-000000001235', '2026-05-08', 158.40, 158.40, 'reconciliado', 'SNAP-002'),
('30000000-0000-4000-8000-000000055110', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55110', '20000000-0000-4000-8000-000000088431', '10000000-0000-4000-8000-000000001237', '2026-05-18', 66.30, 66.30, 'reconciliado', 'SNAP-003'),
('30000000-0000-4000-8000-000000055120', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55120', '20000000-0000-4000-8000-000000088440', '10000000-0000-4000-8000-000000001238', '2026-05-20', 268.40, 160.00, 'divergente', 'SNAP-004'),
('30000000-0000-4000-8000-000000055130', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55130', '20000000-0000-4000-8000-000000088450', '10000000-0000-4000-8000-000000001239', '2026-05-25', 451.20, 451.20, 'reconciliado', 'SNAP-005'),
('30000000-0000-4000-8000-000000055140', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55140', '20000000-0000-4000-8000-000000088460', '10000000-0000-4000-8000-000000001240', '2026-05-27', 748.90, 748.90, 'reconciliado', 'SNAP-006'),
('30000000-0000-4000-8000-00000000c012', '8f2c1a00-0000-4000-8000-000000000001', 'SET-CSV-12', '20000000-0000-4000-8000-000000000012', '10000000-0000-4000-8000-000000001241', '2026-05-24', 178.50, 176.20, 'reconciliado_alerta', 'SNAP-007'),
('30000000-0000-4000-8000-000000055160', '8f2c1a00-0000-4000-8000-000000000001', 'SET-55160', '20000000-0000-4000-8000-000000088490', '10000000-0000-4000-8000-000000001243', '2026-06-02', 361.20, 331.20, 'divergente', 'SNAP-008');

insert into fees (id, tenant_id, external_id, order_id, fee_type, expected_amount, actual_amount, source, occurred_at) values
('50000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-01', '10000000-0000-4000-8000-000000001234', 'Comissão marketplace', 120.00, 120.00, 'Mercado Livre', '2026-05-04 11:24:00+00'),
('50000000-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-02', '10000000-0000-4000-8000-000000001234', 'Frete', 22.90, 22.90, 'Mercado Livre', '2026-05-04 11:24:00+00'),
('50000000-0000-4000-8000-000000000003', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-03', '10000000-0000-4000-8000-000000001234', 'Tarifa de pagamento', 14.79, 14.79, 'Mercado Pago', '2026-05-04 11:24:00+00'),
('50000000-0000-4000-8000-000000000004', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-04', '10000000-0000-4000-8000-000000001243', 'Comissão marketplace', 51.60, 51.60, 'Mercado Livre', '2026-05-28 15:21:00+00'),
('50000000-0000-4000-8000-000000000005', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-05', '10000000-0000-4000-8000-000000001243', 'Tarifa não identificada', 0.00, 30.00, 'Mercado Pago', '2026-06-02 06:00:00+00'),
('50000000-0000-4000-8000-000000000006', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-06', '10000000-0000-4000-8000-000000001241', 'Comissão marketplace', 29.40, 31.70, 'CSV', '2026-05-24 09:00:00+00'),
('50000000-0000-4000-8000-000000000007', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-07', '10000000-0000-4000-8000-000000001240', 'Comissão marketplace', 106.80, 106.80, 'Mercado Livre', '2026-05-20 13:48:00+00'),
('50000000-0000-4000-8000-000000000008', '8f2c1a00-0000-4000-8000-000000000001', 'FEE-08', '10000000-0000-4000-8000-000000001235', 'Comissão marketplace', 22.79, 22.79, 'Mercado Livre', '2026-05-05 09:16:00+00');

insert into refunds (id, tenant_id, external_id, order_id, refund_type, occurred_at, amount, reflected_in_settlement, status) values
('51000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', 'REF-01', '10000000-0000-4000-8000-000000001238', 'estorno', '2026-05-16 10:00:00+00', 160.00, false, 'Parcial sem reflexo completo'),
('51000000-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', 'REF-02', '10000000-0000-4000-8000-000000001218', 'chargeback', '2026-05-22 14:20:00+00', 189.90, false, 'Sem reflexo no fechamento');

insert into invoices (id, tenant_id, external_id, order_id, document_type, number, access_key, issued_at, total_amount, status, cbs_amount, ibs_amount) values
('60000000-0000-4000-8000-000000100234', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100234', '10000000-0000-4000-8000-000000001234', 'NF-e', '000234', '35260512345678000199550010000002341000023410', '2026-05-04 14:00:00+00', 1000.00, 'autorizada', 88.00, 18.00),
('60000000-0000-4000-8000-000000100235', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100235', '10000000-0000-4000-8000-000000001235', 'NFC-e', '000235', '35260512345678000199550010000002351000023510', '2026-05-05 10:00:00+00', 189.90, 'autorizada', 16.70, 3.40),
('60000000-0000-4000-8000-000000100236', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100236', '10000000-0000-4000-8000-000000001236', 'NF-e', '000236', '35260512345678000199550010000002361000023610', '2026-05-12 18:00:00+00', 642.50, 'autorizada', 56.50, 11.60),
('60000000-0000-4000-8000-000000100237', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100237', '10000000-0000-4000-8000-000000001237', 'NFC-e', '000237', '35260512345678000199550010000002371000023710', '2026-05-14 09:00:00+00', 79.90, 'autorizada', 7.00, 1.40),
('60000000-0000-4000-8000-000000100238', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100238', '10000000-0000-4000-8000-000000001238', 'NF-e', '000238', '35260512345678000199550010000002381000023810', '2026-05-15 20:10:00+00', 320.00, 'autorizada', 28.20, 5.80),
('60000000-0000-4000-8000-000000100240', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100240', '10000000-0000-4000-8000-000000001240', 'NF-e', '000240', '35260512345678000199550010000002401000024010', '2026-05-20 16:00:00+00', 890.00, 'autorizada', 78.30, 16.00),
('60000000-0000-4000-8000-000000100241', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100241', '10000000-0000-4000-8000-000000001241', 'NF-e', '000241', '35260512345678000199550010000002411000024110', '2026-05-21 11:00:00+00', 219.90, 'autorizada', 19.30, 4.00),
('60000000-0000-4000-8000-000000100243', '8f2c1a00-0000-4000-8000-000000000001', 'NFE-100243', '10000000-0000-4000-8000-000000001243', 'NF-e', '000243', '35260512345678000199550010000002431000024310', '2026-05-28 17:40:00+00', 410.00, 'autorizada', 36.10, 7.40);

insert into reconciliation_rules (id, tenant_id, external_id, name, description, rule_type, active) values
('d0000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', 'r1', 'Pedido pago sem repasse', 'Se o pagamento está aprovado e não há payout até o deadline, abrir divergência financeira. Antes do deadline permanece pendente de liquidação.', 'temporal', true),
('d0000000-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', 'r2', 'Repasse líquido abaixo do esperado', 'Se o valor recebido for menor que o expected settlement do motor de cálculo, abrir caso com decomposição dos componentes.', 'financeira', true),
('d0000000-0000-4000-8000-000000000003', '8f2c1a00-0000-4000-8000-000000000001', 'r3', 'Nota fiscal ausente', 'Se a operação estiver configurada como obrigatória e não houver documento autorizado no prazo, abrir pendência fiscal.', 'fiscal', true),
('d0000000-0000-4000-8000-000000000004', '8f2c1a00-0000-4000-8000-000000000001', 'r4', 'Valor faturado divergente', 'Se invoice_total divergir de gross_amount além da tolerância, abrir caso fiscal.', 'fiscal', true),
('d0000000-0000-4000-8000-000000000005', '8f2c1a00-0000-4000-8000-000000000001', 'r5', 'Chargeback ou estorno sem reflexo', 'Se houver estorno/chargeback sem ajuste no settlement ou fechamento, abrir caso de consistência.', 'operacional', true);

insert into calculation_snapshots (id, tenant_id, external_id, order_id, engine_version, gross_amount, expected_amount, actual_amount, difference_amount, components) values
('80000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', 'SNAP-001', '10000000-0000-4000-8000-000000001234', 'sce-v1.4', 1000.00, 842.31, 0.00, 842.31,
 '[{"label":"Comissão marketplace","amount":-120.00},{"label":"Frete","amount":-22.90},{"label":"Tarifa de pagamento","amount":-14.79}]'),
('80000000-0000-4000-8000-000000000008', '8f2c1a00-0000-4000-8000-000000000001', 'SNAP-008', '10000000-0000-4000-8000-000000001243', 'sce-v1.4', 430.00, 361.20, 331.20, 30.00,
 '[{"label":"Comissão marketplace","amount":-51.60},{"label":"Frete","amount":-2.41},{"label":"Tarifa de pagamento","amount":-14.79},{"label":"Tarifa não identificada","amount":-30.00}]');

insert into reconciliation_cases (id, tenant_id, external_id, opened_at, order_external_id, payment_external_id, settlement_external_id, payout_external_id, invoice_external_id, cause, description, amount, recoverable_amount, confirmed_loss_amount, impact, status, priority, owner_name, confidence, suggested_action) values
('70000000-0000-4000-8000-000000000124', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000124', '2026-05-19 09:10:00+00', 'PED-200001234', 'PAY-88421', 'SET-55102', null, 'NFE-100234', 'repasse_nao_realizado', 'Pagamento aprovado e settlement esperado de R$ 842,31 sem payout identificado após o deadline de 14 dias.', 842.31, 842.31, 0.00, 'recuperavel', 'aberta', 'alta', 'Fernando Soller', 0.9700, 'Conferir se o lote de 05/06 inclui este pedido ou abrir chamado no Mercado Pago.'),
('70000000-0000-4000-8000-000000000125', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000125', '2026-06-02 07:40:00+00', 'PED-200001243', 'PAY-88490', 'SET-55160', 'REP-33016', 'NFE-100243', 'tarifa_inesperada', 'Valor recebido R$ 30,00 abaixo do expected settlement. Componente extra: tarifa não identificada.', 30.00, 30.00, 0.00, 'recuperavel', 'em_analise', 'media', 'Ana Dantas', 0.9100, 'Validar se a tarifa de R$ 30,00 corresponde a campanha ou retenção não mapeada.'),
('70000000-0000-4000-8000-000000000126', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000126', '2026-05-20 08:00:00+00', 'PED-200001239', 'PAY-88450', 'SET-55130', 'REP-33014', null, 'nota_ausente', 'Operação configurada como obrigatória e sem NF-e/NFC-e autorizada após 48h.', 540.00, 0.00, 0.00, 'aguardando', 'aberta', 'alta', 'Ana Dantas', 0.8800, 'Solicitar emissão ao adapter fiscal ou justificar não obrigatoriedade.'),
('70000000-0000-4000-8000-000000000127', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000127', '2026-05-22 11:15:00+00', 'PED-200001241', 'PAY-CSV-12', 'SET-CSV-12', 'REP-CSV-03', 'NFE-100241', 'valor_faturado_divergente', 'invoice_total R$ 219,90 diverge do gross_amount R$ 210,00 além da tolerância de R$ 0,05.', 9.90, 0.00, 0.00, 'aguardando', 'em_analise', 'media', 'Carla Menezes', 0.8400, 'Conferir se frete ou juros foram faturados fora do pedido.'),
('70000000-0000-4000-8000-000000000128', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000128', '2026-05-23 16:00:00+00', 'PED-200001218', null, null, null, null, 'chargeback_sem_reflexo', 'Chargeback de R$ 189,90 sem ajuste correspondente no settlement/fechamento de maio.', 189.90, 0.00, 189.90, 'perda_confirmada', 'aberta', 'alta', 'Fernando Soller', 0.7900, 'Registrar ajuste de fechamento e avaliar contestação no gateway.'),
('70000000-0000-4000-8000-000000000129', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000129', '2026-05-17 12:30:00+00', 'PED-200001238', 'PAY-88440', 'SET-55120', 'REP-33012', 'NFE-100238', 'estorno_parcial', 'Estorno parcial de R$ 160,00 sem reflexo completo no valor líquido do payout.', 108.40, 108.40, 0.00, 'recuperavel', 'aberta', 'media', 'Fernando Soller', 0.8600, 'Aguardar ajuste do lote seguinte ou lançar delta no fechamento.'),
('70000000-0000-4000-8000-000000000130', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000130', '2026-05-08 18:00:00+00', 'PED-200001235', 'PAY-88422', 'SET-55103', 'REP-33011', 'NFE-100235', 'duplicidade', 'Evento de pagamento duplicado no webhook; matching determinístico confirmou o mesmo payment_id.', 0.00, 0.00, 0.00, 'aguardando', 'resolvida', 'baixa', 'Ana Dantas', 0.9900, 'Nenhuma ação financeira. Evento raw marcado como idempotente.'),
('70000000-0000-4000-8000-000000000131', '8f2c1a00-0000-4000-8000-000000000001', 'DIV-2026-000131', '2026-04-29 10:00:00+00', 'PED-200000980', null, null, null, null, 'tarifa_inesperada', 'Diferença de centavos acima da tolerância, classificada como ruído após revisão.', 0.12, 0.00, 0.12, 'perda_confirmada', 'ignorada', 'baixa', 'Ana Dantas', 0.6200, 'Manter ignorado. Tolerância de centavos cobre este caso nas regras atuais.');

insert into case_comments (id, tenant_id, case_id, author, body, created_at) values
('71000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', 'Sistema', 'Caso gerado automaticamente pela regra “pedido pago sem repasse” após o settlement deadline.', '2026-05-19 09:10:00+00'),
('71000000-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000125', 'Ana Dantas', 'Vou cruzar o extrato do Mercado Pago com o componente de campanha desta semana.', '2026-06-03 10:15:00+00');

insert into case_timeline (id, tenant_id, case_id, occurred_at, title, detail, tone) values
('72000000-0000-4000-8000-000000000001', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', '2026-05-04 11:22:00+00', 'Pedido criado', 'PED-200001234 no Mercado Livre — R$ 1.000,00', 'neutral'),
('72000000-0000-4000-8000-000000000002', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', '2026-05-04 11:24:00+00', 'Pagamento aprovado', 'PAY-88421 via cartão no Mercado Pago', 'success'),
('72000000-0000-4000-8000-000000000003', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', '2026-05-04 14:00:00+00', 'NF-e autorizada', 'NFE-100234 — chave armazenada no adapter fiscal', 'success'),
('72000000-0000-4000-8000-000000000004', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', '2026-05-11 00:00:00+00', 'Settlement esperado', 'SET-55102 com expected_net R$ 842,31 (sce-v1.4)', 'neutral'),
('72000000-0000-4000-8000-000000000005', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', '2026-05-18 00:00:00+00', 'Deadline de liquidação', 'Janela de 14 dias encerrada sem payout correspondente', 'warning'),
('72000000-0000-4000-8000-000000000006', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000124', '2026-05-19 09:10:00+00', 'Caso aberto', 'Regra 1 — pedido pago sem repasse. Confiança 97%.', 'danger'),
('72000000-0000-4000-8000-000000000011', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000125', '2026-05-28 15:20:00+00', 'Pedido criado', 'PED-200001243 — R$ 430,00', 'neutral'),
('72000000-0000-4000-8000-000000000012', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000125', '2026-05-28 15:21:00+00', 'Pagamento aprovado', 'PAY-88490', 'success'),
('72000000-0000-4000-8000-000000000013', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000125', '2026-06-02 06:00:00+00', 'Payout recebido', 'REP-33016 líquido R$ 331,20 vs esperado R$ 361,20', 'warning'),
('72000000-0000-4000-8000-000000000014', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000125', '2026-06-02 07:40:00+00', 'Caso aberto', 'Regra 2 — líquido abaixo do expected settlement', 'danger'),
('72000000-0000-4000-8000-000000000015', '8f2c1a00-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000125', '2026-06-03 10:12:00+00', 'Em análise', 'Ana Dantas assumiu o caso', 'neutral');

insert into closings (id, tenant_id, external_id, period, label, status, closed_at, orders_count, gross_amount, expected_amount, received_amount, fees_amount, refunds_amount, open_diff_amount, pending_invoices) values
('90000000-0000-4000-8000-000000202605', '8f2c1a00-0000-4000-8000-000000000001', 'CLO-2026-05', '2026-05', 'Maio/2026', 'open', null, 12684, 1280420.60, 1085670.85, 1060830.50, 148920.40, 21340.00, 24840.35, 37),
('90000000-0000-4000-8000-000000202604', '8f2c1a00-0000-4000-8000-000000000001', 'CLO-2026-04', '2026-04', 'Abril/2026', 'closed', '2026-05-03 18:40:00+00', 11820, 1142110.20, 976430.10, 974880.55, 132410.00, 18220.00, 1549.55, 4),
('90000000-0000-4000-8000-000000202603', '8f2c1a00-0000-4000-8000-000000000001', 'CLO-2026-03', '2026-03', 'Março/2026', 'closed', '2026-04-02 17:10:00+00', 10940, 1018330.80, 870220.40, 869110.20, 121800.00, 15440.00, 1110.20, 2);
