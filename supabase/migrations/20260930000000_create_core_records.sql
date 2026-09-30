begin;

create extension if not exists pgcrypto;

create table public.customers (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(trim(name)) > 0),
  phone text not null check (length(trim(phone)) > 0),
  notes text,
  created_at timestamptz not null default now()
);

create table public.subscriptions (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.customers (id) on update restrict on delete restrict,
  subscription_name text not null check (length(trim(subscription_name)) > 0),
  amount numeric(12, 2) not null check (amount >= 0),
  start_date date not null,
  end_date date not null,
  status text not null check (length(trim(status)) > 0),
  created_at timestamptz not null default now(),
  constraint subscriptions_valid_date_range check (end_date >= start_date),
  constraint subscriptions_id_customer_unique unique (id, customer_id)
);

create table public.payments (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.customers (id) on update restrict on delete restrict,
  subscription_id uuid not null,
  amount numeric(12, 2) not null check (amount >= 0),
  payment_date date not null,
  notes text,
  created_at timestamptz not null default now(),
  constraint payments_subscription_customer_fk
    foreign key (subscription_id, customer_id)
    references public.subscriptions (id, customer_id)
    on update restrict on delete restrict
);

create table public.reminders (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.customers (id) on update restrict on delete restrict,
  subscription_id uuid not null,
  reminder_type text not null check (length(trim(reminder_type)) > 0),
  scheduled_date timestamptz not null,
  sent_at timestamptz,
  status text not null check (length(trim(status)) > 0),
  created_at timestamptz not null default now(),
  constraint reminders_subscription_customer_fk
    foreign key (subscription_id, customer_id)
    references public.subscriptions (id, customer_id)
    on update restrict on delete restrict
);

create index subscriptions_customer_id_idx on public.subscriptions (customer_id);
create index payments_customer_id_idx on public.payments (customer_id);
create index payments_subscription_id_idx on public.payments (subscription_id);
create index reminders_customer_id_idx on public.reminders (customer_id);
create index reminders_subscription_id_idx on public.reminders (subscription_id);
create index reminders_scheduled_date_idx on public.reminders (scheduled_date);

create function public.prevent_historical_record_deletion()
returns trigger
language plpgsql
as $$
begin
  raise exception 'Historical records in % cannot be deleted', tg_table_name
    using errcode = 'integrity_constraint_violation';
end;
$$;

create function public.preserve_subscription_history()
returns trigger
language plpgsql
as $$
begin
  if new.customer_id <> old.customer_id
    or new.subscription_name <> old.subscription_name
    or new.amount <> old.amount
    or new.start_date <> old.start_date
    or new.end_date <> old.end_date then
    raise exception 'Subscription terms are historical; create a new subscription for a renewal'
      using errcode = 'integrity_constraint_violation';
  end if;

  return new;
end;
$$;

create trigger customers_prevent_delete
before delete on public.customers
for each row execute function public.prevent_historical_record_deletion();

create trigger subscriptions_prevent_delete
before delete on public.subscriptions
for each row execute function public.prevent_historical_record_deletion();

create trigger subscriptions_preserve_history
before update on public.subscriptions
for each row execute function public.preserve_subscription_history();

create trigger payments_prevent_delete
before delete on public.payments
for each row execute function public.prevent_historical_record_deletion();

create trigger reminders_prevent_delete
before delete on public.reminders
for each row execute function public.prevent_historical_record_deletion();

commit;
