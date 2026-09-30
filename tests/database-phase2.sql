begin;

do $$
declare
  v_customer_id uuid;
  old_subscription_id uuid;
  renewal_id uuid;
  paid numeric;
begin
  insert into public.customers (name, phone) values ('Phase 2 Customer', '+966500000000') returning id into v_customer_id;
  update public.customers set phone = '+966511111111' where id = v_customer_id;
  if (select phone from public.customers where id = v_customer_id) <> '+966511111111' then raise exception 'Customer update failed'; end if;

  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (v_customer_id, 'Plan', 100, '2026-01-01', '2026-01-31', 'active') returning id into old_subscription_id;

  insert into public.payments (customer_id, subscription_id, amount, payment_date)
  values (v_customer_id, old_subscription_id, 40, '2026-01-02');
  select sum(amount) into paid from public.payments where subscription_id = old_subscription_id;
  if paid <> 40 or 100 - paid <> 60 then raise exception 'Partial payment calculation is incorrect'; end if;

  insert into public.payments (customer_id, subscription_id, amount, payment_date)
  values (v_customer_id, old_subscription_id, 60, '2026-01-03');
  select sum(amount) into paid from public.payments where subscription_id = old_subscription_id;
  if paid <> 100 or 100 - paid <> 0 then raise exception 'Full payment calculation is incorrect'; end if;

  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (v_customer_id, 'Plan', 100, '2026-02-01', '2026-02-28', 'active') returning id into renewal_id;
  if renewal_id = old_subscription_id or (select count(*) from public.subscriptions where customer_id = v_customer_id) <> 2 then
    raise exception 'Renewal history was not retained';
  end if;
end;
$$;

rollback;
