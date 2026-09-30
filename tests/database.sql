begin;

do $$
declare
  customer_one uuid;
  customer_two uuid;
  first_subscription uuid;
  renewed_subscription uuid;
  payment_subscription uuid;
begin
  insert into public.customers (name, phone, notes)
  values ('Test Customer', '+10000000000', 'database test')
  returning id into customer_one;

  insert into public.customers (name, phone)
  values ('Other Customer', '+20000000000')
  returning id into customer_two;

  insert into public.subscriptions (
    customer_id, subscription_name, amount, start_date, end_date, status
  ) values (
    customer_one, 'Monthly plan', 50, date '2026-01-01', date '2026-01-31', 'expired'
  ) returning id into first_subscription;

  insert into public.subscriptions (
    customer_id, subscription_name, amount, start_date, end_date, status
  ) values (
    customer_one, 'Monthly plan', 50, date '2026-02-01', date '2026-02-28', 'active'
  ) returning id into renewed_subscription;

  if first_subscription = renewed_subscription or
     (select count(*) from public.subscriptions where customer_id = customer_one) <> 2 then
    raise exception 'Renewal did not preserve both subscription records';
  end if;

  begin
    update public.subscriptions
    set end_date = date '2026-02-15'
    where id = first_subscription;
    raise exception 'Historical subscription terms could be overwritten';
  exception when integrity_constraint_violation then
    null;
  end;

  insert into public.payments (
    customer_id, subscription_id, amount, payment_date, notes
  ) values (
    customer_one, renewed_subscription, 50, date '2026-02-01', 'paid in full'
  );

  select subscription_id into payment_subscription
  from public.payments
  where customer_id = customer_one;

  if payment_subscription <> renewed_subscription then
    raise exception 'Payment was not linked to its subscription';
  end if;

  begin
    insert into public.subscriptions (
      customer_id, subscription_name, amount, start_date, end_date, status
    ) values (
      customer_one, 'Invalid amount', -1, date '2026-03-01', date '2026-03-31', 'active'
    );
    raise exception 'Negative subscription amount was accepted';
  exception when check_violation then
    null;
  end;

  begin
    insert into public.subscriptions (
      customer_id, subscription_name, amount, start_date, end_date, status
    ) values (
      customer_one, 'Invalid dates', 10, date '2026-04-30', date '2026-04-01', 'active'
    );
    raise exception 'Reversed dates were accepted';
  exception when check_violation then
    null;
  end;

  begin
    insert into public.payments (customer_id, subscription_id, amount, payment_date)
    values (customer_two, renewed_subscription, 10, date '2026-02-02');
    raise exception 'Mismatched customer/subscription payment was accepted';
  exception when foreign_key_violation then
    null;
  end;

  begin
    insert into public.payments (customer_id, subscription_id, amount, payment_date)
    values (customer_one, renewed_subscription, -1, date '2026-02-02');
    raise exception 'Negative payment amount was accepted';
  exception when check_violation then
    null;
  end;

  begin
    delete from public.subscriptions where id = first_subscription;
    raise exception 'Historical subscription deletion was accepted';
  exception when integrity_constraint_violation then
    null;
  end;
end;
$$;

rollback;
