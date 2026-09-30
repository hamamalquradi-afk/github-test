begin;

-- Keep concurrent writes out while archiving exact legacy duplicate rows and
-- installing the uniqueness rule. The whole migration is one transaction.
lock table public.reminders in access exclusive mode;

-- A separate immutable archive retains the full original duplicate row, including
-- its id, original state, timestamps, type and sent_at. Prefer the earliest SENT
-- row as canonical so a delivered reminder cannot become sendable again.
create table public.reminder_duplicate_history (
  original_id uuid primary key,
  canonical_reminder_id uuid not null references public.reminders(id),
  original_row jsonb not null,
  archived_at timestamptz not null default now()
);

create temporary table phase3_duplicate_reminders on commit drop as
select id, canonical_id from (
  select id,
    first_value(id) over reminder_group as canonical_id,
    row_number() over reminder_group as position
  from public.reminders
  window reminder_group as (
    partition by subscription_id, scheduled_date
    order by sent_at asc nulls last, created_at asc, id asc
  )
) as ranked where position > 1;

insert into public.reminder_duplicate_history (original_id, canonical_reminder_id, original_row)
select reminder.id, duplicate.canonical_id, to_jsonb(reminder)
from public.reminders as reminder
join phase3_duplicate_reminders as duplicate on duplicate.id = reminder.id;

-- Bypass only the delete guard, only for the exact duplicates already archived.
-- Re-enable it before releasing the table lock; rollback restores it on failure.
alter table public.reminders disable trigger reminders_prevent_delete;
delete from public.reminders as reminder
using phase3_duplicate_reminders as duplicate
where reminder.id = duplicate.id;
alter table public.reminders enable trigger reminders_prevent_delete;

create trigger reminder_duplicate_history_prevent_delete
before delete on public.reminder_duplicate_history
for each row execute function public.prevent_historical_record_deletion();
create trigger reminder_duplicate_history_prevent_update
before update on public.reminder_duplicate_history
for each row execute function public.prevent_historical_record_deletion();

-- Normalize workflow state without altering the retained reminder details.
update public.reminders
set status = case when sent_at is not null then 'SENT'
  when scheduled_date <= now() then 'DUE' else 'PENDING' end;

alter table public.reminders
  add constraint reminders_status_check check (status in ('PENDING', 'DUE', 'SENT')),
  add constraint reminders_sent_state_check check (
    (status = 'SENT' and sent_at is not null) or
    (status <> 'SENT' and sent_at is null)
  ),
  add constraint reminders_subscription_schedule_unique unique (subscription_id, scheduled_date);

create function public.create_subscription_reminders(
  target_subscription_id uuid,
  reminder_days integer[],
  target_customer_id uuid default null
)
returns setof public.reminders
language plpgsql
set search_path = public
as $$
declare
  subscription_record public.subscriptions%rowtype;
  reminder_day integer;
  total_dates integer;
begin
  if coalesce(cardinality(reminder_days), 0) not between 1 and 3 then
    raise exception 'Choose between one and three reminders' using errcode = 'check_violation';
  end if;
  if exists (select 1 from unnest(reminder_days) as days(day) where day is null or day <= 0) then
    raise exception 'Reminder days must be positive integers' using errcode = 'check_violation';
  end if;
  if (select count(*) from unnest(reminder_days)) <>
     (select count(distinct day) from unnest(reminder_days) as days(day)) then
    raise exception 'Reminder days cannot be duplicated' using errcode = 'unique_violation';
  end if;

  -- Serialize configurations for the same subscription, including concurrent calls.
  select * into strict subscription_record from public.subscriptions
    where id = target_subscription_id for update;
  if target_customer_id is not null and subscription_record.customer_id <> target_customer_id then
    raise exception 'Subscription does not belong to this customer' using errcode = 'check_violation';
  end if;
  if lower(subscription_record.status) <> 'active' or
     subscription_record.end_date < (now() at time zone 'UTC')::date then
    raise exception 'Reminders can only be created for active subscriptions' using errcode = 'check_violation';
  end if;

  select count(*) into total_dates from (
    select scheduled_date from public.reminders where subscription_id = target_subscription_id
    union
    select (subscription_record.end_date - day)::timestamp at time zone 'UTC'
      from unnest(reminder_days) as days(day)
  ) as configured_dates;
  if total_dates > 3 then
    raise exception 'At most three reminders per subscription; existing history is preserved'
      using errcode = 'check_violation';
  end if;

  for reminder_day in select day from unnest(reminder_days) as days(day) order by day desc loop
    return query
    insert into public.reminders (customer_id, subscription_id, reminder_type, scheduled_date, status)
    values (
      subscription_record.customer_id,
      subscription_record.id,
      reminder_day || '_DAYS_BEFORE',
      (subscription_record.end_date - reminder_day)::timestamp at time zone 'UTC',
      case when (subscription_record.end_date - reminder_day) <= (now() at time zone 'UTC')::date
        then 'DUE' else 'PENDING' end
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
  -- The insert and reminder generation share one transaction; failure rolls back both.
  insert into public.subscriptions (customer_id, subscription_name, amount, start_date, end_date, status)
  values (target_customer_id, target_name, target_amount, target_start_date, target_end_date, 'active')
  returning id into created_id;
  -- Historical Phase 2 entry remains valid and creates no reminders.
  if target_end_date >= (now() at time zone 'UTC')::date then
    perform public.create_subscription_reminders(created_id, reminder_days, target_customer_id);
  end if;
  return created_id;
end;
$$;

create function public.preserve_reminder_history()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if new.customer_id is distinct from old.customer_id
    or new.subscription_id is distinct from old.subscription_id
    or new.reminder_type is distinct from old.reminder_type
    or new.scheduled_date is distinct from old.scheduled_date
    or new.created_at is distinct from old.created_at
    or (old.status = 'SENT' and
      (new.status is distinct from old.status or new.sent_at is distinct from old.sent_at)) then
    raise exception 'Historical reminder details cannot be overwritten'
      using errcode = 'integrity_constraint_violation';
  end if;
  -- Guard direct table writes as well as the RPC. A PENDING reminder must first
  -- become DUE through refresh_due_reminders, even if its date has now arrived.
  if new.status = 'SENT' and old.status <> 'SENT' then
    if old.status <> 'DUE' or old.scheduled_date > now() then
      raise exception 'Only currently DUE reminders can be sent'
        using errcode = 'check_violation';
    end if;
    if not exists (
      select 1 from public.subscriptions
      where id = old.subscription_id and lower(status) = 'active'
        and end_date >= (now() at time zone 'UTC')::date
    ) then
      raise exception 'Cannot send reminders for inactive or expired subscriptions'
        using errcode = 'check_violation';
    end if;
  end if;
  return new;
end;
$;

create trigger reminders_preserve_history
before update on public.reminders
for each row execute function public.preserve_reminder_history();

create function public.refresh_due_reminders()
returns integer
language plpgsql
set search_path = public
as $$
declare updated_count integer;
begin
  update public.reminders as reminder set status = 'DUE'
  from public.subscriptions as subscription
  where reminder.subscription_id = subscription.id
    and reminder.status = 'PENDING' and reminder.scheduled_date <= now()
    and lower(subscription.status) = 'active'
    and subscription.end_date >= (now() at time zone 'UTC')::date;
  get diagnostics updated_count = row_count;
  return updated_count;
end;
$$;

create function public.mark_reminder_sent(target_reminder_id uuid)
returns void
language plpgsql
set search_path = public
as $$
declare
  subscription_record public.subscriptions%rowtype;
  reminder_record public.reminders%rowtype;
begin
  select subscription.* into strict subscription_record
    from public.subscriptions as subscription
    join public.reminders as reminder on reminder.subscription_id = subscription.id
    where reminder.id = target_reminder_id for update of subscription;
  select * into strict reminder_record from public.reminders
    where id = target_reminder_id for update;
  -- Retrying a completed operation never overwrites the original sent_at.
  if reminder_record.status = 'SENT' then return; end if;
  if reminder_record.status <> 'DUE' or reminder_record.scheduled_date > now() then
    raise exception 'Only currently DUE reminders can be sent'
      using errcode = 'check_violation';
  end if;
  if lower(subscription_record.status) <> 'active' or
     subscription_record.end_date < (now() at time zone 'UTC')::date then
    raise exception 'Cannot send reminders for inactive or expired subscriptions'
      using errcode = 'check_violation';
  end if;
  update public.reminders set status = 'SENT', sent_at = now() where id = target_reminder_id;
end;
$$;

commit;
