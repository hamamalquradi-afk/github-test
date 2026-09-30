begin;

update public.reminders
set status = case when sent_at is not null then 'SENT' when scheduled_date <= now() then 'DUE' else 'PENDING' end;

alter table public.reminders
  add constraint reminders_status_check check (status in ('PENDING', 'DUE', 'SENT')),
  add constraint reminders_sent_state_check check (
    (status = 'SENT' and sent_at is not null) or
    (status <> 'SENT' and sent_at is null)
  ),
  add constraint reminders_subscription_schedule_unique unique (subscription_id, scheduled_date);

create function public.create_subscription_reminders(target_subscription_id uuid, reminder_days integer[])
returns setof public.reminders
language plpgsql
set search_path = public
as $$
declare
  subscription_record public.subscriptions%rowtype;
  reminder_day integer;
begin
  if coalesce(cardinality(reminder_days), 0) not between 1 and 3 then
    raise exception 'Choose between one and three reminders' using errcode = 'check_violation';
  end if;
  if exists (select 1 from unnest(reminder_days) day where day <= 0) then
    raise exception 'Reminder days must be positive integers' using errcode = 'check_violation';
  end if;
  if (select count(*) from unnest(reminder_days)) <> (select count(distinct day) from unnest(reminder_days) day) then
    raise exception 'Reminder days cannot be duplicated' using errcode = 'unique_violation';
  end if;

  select * into strict subscription_record from public.subscriptions where id = target_subscription_id;
  if lower(subscription_record.status) <> 'active' or subscription_record.end_date < current_date then
    raise exception 'Reminders can only be created for active subscriptions' using errcode = 'check_violation';
  end if;
  for reminder_day in select day from unnest(reminder_days) day order by day desc loop
    return query
    insert into public.reminders (customer_id, subscription_id, reminder_type, scheduled_date, status)
    values (
      subscription_record.customer_id,
      subscription_record.id,
      reminder_day || '_DAYS_BEFORE',
      (subscription_record.end_date - reminder_day)::timestamp at time zone 'UTC',
      case when (subscription_record.end_date - reminder_day) <= current_date then 'DUE' else 'PENDING' end
    )
    on conflict (subscription_id, scheduled_date) do nothing
    returning *;
  end loop;
end;
$$;

create function public.create_subscription_with_reminders(
  target_customer_id uuid,
  target_name text,
  target_amount numeric,
  target_start_date date,
  target_end_date date,
  reminder_days integer[]
)
returns uuid
language plpgsql
set search_path = public
as $$
declare created_id uuid;
begin
  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (target_customer_id, target_name, target_amount, target_start_date, target_end_date, 'active')
  returning id into created_id;
  perform public.create_subscription_reminders(created_id, reminder_days);
  return created_id;
end;
$$;

create function public.refresh_due_reminders()
returns integer
language plpgsql
set search_path = public
as $$
declare updated_count integer;
begin
  update public.reminders set status = 'DUE' where status = 'PENDING' and scheduled_date <= now();
  get diagnostics updated_count = row_count;
  return updated_count;
end;
$$;

commit;
