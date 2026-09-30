-- Runs after Phase 1, BEFORE the Phase 3 uniqueness migration.
insert into public.customers (id, name, phone)
values ('10000000-0000-0000-0000-000000000001', 'Legacy Customer', '+1000');
insert into public.subscriptions (id, customer_id, subscription_name, amount, start_date, end_date, status)
values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001',
  'اسم تاريخي يدوي', 100, '2020-01-01', '2020-01-31', 'active');
insert into public.payments (customer_id, subscription_id, amount, payment_date)
values ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 40, '2020-01-02');

insert into public.reminders (id, customer_id, subscription_id, reminder_type, scheduled_date, sent_at, status)
values
  ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'LEGACY_SENT', '2020-01-20T00:00:00Z', '2020-01-20T10:00:00Z', 'sent'),
  ('30000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'LEGACY_SENT_LATER', '2020-01-20T00:00:00Z', '2020-01-20T11:00:00Z', 'sent'),
  ('30000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'LEGACY_PENDING', '2020-01-20T00:00:00Z', null, 'pending'),
  ('30000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'LEGACY_UNSENT', '2020-01-21T00:00:00Z', null, 'pending'),
  ('30000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'LEGACY_UNSENT_DUPLICATE', '2020-01-21T00:00:00Z', null, 'pending'),
  ('30000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'UNRELATED_UNIQUE', '2020-01-22T00:00:00Z', null, 'pending');

create temporary table phase3_expected_reminders as
select id, to_jsonb(reminder) as original_row from public.reminders as reminder;
create temporary table phase3_expected_subscriptions as
select to_jsonb(subscription) as original_row from public.subscriptions as subscription;
create temporary table phase3_expected_payments as
select to_jsonb(payment) as original_row from public.payments as payment;
