begin;

do $$
declare
  v_customer uuid;
  v_subscription uuid;
  v_renewal uuid;
  first_reminder uuid;
begin
  insert into public.customers (name, phone) values ('Reminder Customer', '+966522222222') returning id into v_customer;
  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (v_customer, 'Flexible Plan', 100, current_date, current_date + 20, 'active') returning id into v_subscription;

  perform public.create_subscription_reminders(v_subscription, array[3]);
  if (select count(*) from public.reminders where subscription_id = v_subscription) <> 1 then raise exception 'One reminder failed'; end if;
  perform public.create_subscription_reminders(v_subscription, array[7, 4]);
  if (select count(*) from public.reminders where subscription_id = v_subscription) <> 3 then raise exception 'Two custom reminders failed'; end if;
  perform public.create_subscription_reminders(v_subscription, array[9, 2, 1]);
  if (select count(*) from public.reminders where subscription_id = v_subscription) <> 6 then raise exception 'Three custom reminders failed'; end if;

  if not exists (
    select 1 from public.reminders
    where subscription_id = v_subscription and scheduled_date::date = current_date + 15 and reminder_type = '5_DAYS_BEFORE'
  ) then
    perform public.create_subscription_reminders(v_subscription, array[5]);
  end if;
  if not exists (select 1 from public.reminders where subscription_id = v_subscription and scheduled_date::date = current_date + 15) then
    raise exception 'Scheduled date calculation failed';
  end if;

  begin
    perform public.create_subscription_reminders(v_subscription, array[4, 4]);
    raise exception 'Duplicate days were accepted';
  exception when unique_violation then null;
  end;

  perform public.create_subscription_reminders(v_subscription, array[3]);
  if (select count(*) from public.reminders where subscription_id = v_subscription and reminder_type = '3_DAYS_BEFORE') <> 1 then
    raise exception 'Duplicate reminder was created';
  end if;

  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (v_customer, 'Flexible Plan', 100, current_date + 21, current_date + 40, 'active') returning id into v_renewal;
  perform public.create_subscription_reminders(v_renewal, array[7, 1]);
  if (select count(*) from public.reminders where subscription_id = v_renewal) <> 2 or
     (select count(*) from public.reminders where subscription_id = v_subscription) <> 7 then
    raise exception 'Renewal reminders did not preserve history';
  end if;

  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (v_customer, 'Due Plan', 10, current_date - 10, current_date + 1, 'active') returning id into v_subscription;
  perform public.create_subscription_reminders(v_subscription, array[1]);
  select id into first_reminder from public.reminders where subscription_id = v_subscription;
  if (select status from public.reminders where id = first_reminder) <> 'DUE' then raise exception 'DUE status failed'; end if;
  update public.reminders set status = 'SENT', sent_at = now() where id = first_reminder;
  if not exists (select 1 from public.reminders where id = first_reminder and status = 'SENT' and sent_at is not null) then
    raise exception 'SENT recording failed';
  end if;

  if not exists (select 1 from public.subscriptions where customer_id = v_customer and end_date < current_date) then
    insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
    values (v_customer, 'Expired Plan', 10, current_date - 20, current_date - 1, 'active');
  end if;
end;
$$;

rollback;
