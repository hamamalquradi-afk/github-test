-- The runner wraps all fixtures, migrations and tests in a rolled-back transaction.
do $$
declare
  v_customer_id uuid;
  historical_id uuid;
  active_id uuid;
  renewal_id uuid;
  pending_id uuid;
  due_id uuid;
  sent_time timestamptz;
  today date := (now() at time zone 'UTC')::date;
  original_sent jsonb;
  original_history jsonb;
begin
  -- Bug 1: duplicate-bearing migration succeeded and retained every original id.
  if (select count(*) from public.reminders) <> 3 or
     (select count(*) from public.reminder_duplicate_history) <> 3 then
    raise exception 'Legacy duplicates were not normalized exactly';
  end if;
  if exists (
    select id from phase3_expected_reminders
    except (select id from public.reminders union select original_id from public.reminder_duplicate_history)
  ) then raise exception 'Legacy reminder history was lost'; end if;
  if exists (
    select 1 from public.reminder_duplicate_history as archive
    join phase3_expected_reminders as expected on expected.id = archive.original_id
    where archive.original_row <> expected.original_row
  ) then raise exception 'Archived originals were altered'; end if;
  if exists (
    select 1 from public.reminders as reminder
    join phase3_expected_reminders as expected on expected.id = reminder.id
    where (to_jsonb(reminder) - 'status') <> (expected.original_row - 'status')
  ) then raise exception 'Retained legacy details were overwritten'; end if;
  if (select status from public.reminders where id = '30000000-0000-0000-0000-000000000001') <> 'SENT' or
     (select sent_at from public.reminders where id = '30000000-0000-0000-0000-000000000001') <> '2020-01-20T10:00:00Z'::timestamptz then
    raise exception 'Earliest sent duplicate was not retained';
  end if;
  if not exists (select 1 from public.reminders where id = '30000000-0000-0000-0000-000000000006') then
    raise exception 'Unrelated unique reminder was removed';
  end if;
  if exists (select original_row from phase3_expected_subscriptions
    except select to_jsonb(subscription) from public.subscriptions as subscription) or
     exists (select original_row from phase3_expected_payments
    except select to_jsonb(payment) from public.payments as payment) then
    raise exception 'Legacy subscription or payment history changed';
  end if;
  begin
    insert into public.reminders (customer_id, subscription_id, reminder_type, scheduled_date, status)
    values ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
      'DUPLICATE', '2020-01-22T00:00:00Z', 'DUE');
    raise exception 'Future duplicate protection failed';
  exception when unique_violation then null; end;
  begin
    delete from public.reminders where id = '30000000-0000-0000-0000-000000000006';
    raise exception 'Reminder delete guard was not restored';
  exception when integrity_constraint_violation then null; end;
  begin
    delete from public.reminder_duplicate_history;
    raise exception 'Archived history could be deleted';
  exception when integrity_constraint_violation then null; end;
  begin
    update public.reminder_duplicate_history set original_row = '{}'::jsonb;
    raise exception 'Archived history could be overwritten';
  exception when integrity_constraint_violation then null; end;

  insert into public.customers (name, phone) values ('Regression Customer', '+2000') returning id into v_customer_id;

  -- Bug 3: use the same RPC as the form for historical entry and keep payments.
  historical_id := public.create_subscription_with_reminders(
    v_customer_id, 'اشتراك قديم يكتبه المستخدم', 100, today - 60, today - 30, array[7, 4, 1]);
  if (select end_date from public.subscriptions where id = historical_id) >= today or
     exists (select 1 from public.reminders where subscription_id = historical_id) then
    raise exception 'Historical entry failed or produced reminders';
  end if;
  insert into public.payments (customer_id, subscription_id, amount, payment_date)
  values (v_customer_id, historical_id, 40, today - 59), (v_customer_id, historical_id, 60, today - 58);
  select to_jsonb(subscription) into original_history from public.subscriptions as subscription where id = historical_id;
  active_id := public.create_subscription_with_reminders(v_customer_id, 'اسم حالي يدوي', 100, today, today + 3, array[1, 2, 4]);
  renewal_id := public.create_subscription_with_reminders(v_customer_id, 'اسم تجديد يدوي', 100, today + 4, today + 30, array[7, 4, 1]);
  if active_id = renewal_id or (select count(*) from public.subscriptions where subscriptions.customer_id = v_customer_id) <> 3 then
    raise exception 'Renewal did not insert independent history';
  end if;
  if (select to_jsonb(subscription) from public.subscriptions as subscription where id = historical_id) <> original_history or
     (select sum(amount) from public.payments where subscription_id = historical_id) <> 100 or
     (select count(*) from public.payments where subscription_id = historical_id) <> 2 then
    raise exception 'Historical entry or payments were modified';
  end if;
  if (select count(*) from public.reminders where subscription_id = active_id) <> 3 or
     (select count(*) from public.reminders where subscription_id = renewal_id) <> 3 then
    raise exception 'Active or future reminders were not created';
  end if;
  perform public.create_subscription_reminders(active_id, array[4, 2, 1], v_customer_id);
  if (select count(*) from public.reminders where subscription_id = active_id) <> 3 then
    raise exception 'Repeated configuration produced duplicate reminders';
  end if;

  -- Bug 2: reject both RPC and direct writes, including a forged future DUE state.
  select id into strict pending_id from public.reminders where subscription_id = active_id and status = 'PENDING' order by scheduled_date limit 1;
  select id into strict due_id from public.reminders where subscription_id = active_id and status = 'DUE';
  begin
    perform public.mark_reminder_sent(pending_id);
    raise exception 'PENDING was sent by RPC';
  exception when check_violation then null; end;
  begin
    update public.reminders set status = 'SENT', sent_at = now() where id = pending_id;
    raise exception 'PENDING was sent by direct update';
  exception when check_violation then null; end;
  update public.reminders set status = 'DUE' where id = pending_id;
  begin
    perform public.mark_reminder_sent(pending_id);
    raise exception 'Future DUE reminder was sent';
  exception when check_violation then null; end;
  begin
    update public.reminders set status = 'SENT', sent_at = now() where id = pending_id;
    raise exception 'Future DUE reminder was sent by direct update';
  exception when check_violation then null; end;
  update public.reminders set status = 'PENDING' where id = pending_id;

  -- PENDING still cannot jump to SENT even after its date becomes due.
  update public.reminders set status = 'PENDING' where id = due_id;
  begin
    perform public.mark_reminder_sent(due_id);
    raise exception 'Unrefreshed PENDING reminder was sent';
  exception when check_violation then null; end;
  perform public.refresh_due_reminders();
  if (select status from public.reminders where id = due_id) <> 'DUE' then
    raise exception 'Due reminder was not refreshed';
  end if;
  begin
    update public.reminders set status = 'SENT', sent_at = null where id = due_id;
    raise exception 'SENT was accepted without sent_at';
  exception when check_violation then null; end;
  perform public.mark_reminder_sent(due_id);
  select sent_at, to_jsonb(reminder) into sent_time, original_sent from public.reminders as reminder where id = due_id;
  if sent_time is null or (select status from public.reminders where id = due_id) <> 'SENT' then
    raise exception 'DUE was not sent with sent_at';
  end if;
  perform public.mark_reminder_sent(due_id);
  perform public.refresh_due_reminders();
  if (select to_jsonb(reminder) from public.reminders as reminder where id = due_id) <> original_sent then
    raise exception 'SENT retry or refresh altered history';
  end if;
  begin
    update public.reminders set status = 'DUE', sent_at = null where id = due_id;
    raise exception 'SENT became sendable again';
  exception when integrity_constraint_violation then null; end;
  begin
    update public.reminders set sent_at = now() + interval '1 second' where id = due_id;
    raise exception 'SENT timestamp could be overwritten';
  exception when integrity_constraint_violation then null; end;
end;
$$;
