import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const migrationUrl = new URL(
  "../supabase/migrations/20260930000000_create_core_records.sql",
  import.meta.url,
);
const schema = (await readFile(migrationUrl, "utf8"))
  .replace(/\s+/g, " ")
  .toLowerCase();

test("creates only the four Phase 1 tables", () => {
  const tables = [...schema.matchAll(/create table public\.(\w+)/g)].map(
    ([, table]) => table,
  );
  assert.deepEqual(tables, ["customers", "subscriptions", "payments", "reminders"]);
});

test("supports multiple immutable subscription records per customer", () => {
  assert.match(schema, /customer_id uuid not null references public\.customers/);
  assert.doesNotMatch(schema, /unique \(customer_id\)/);
  assert.match(schema, /subscriptions_prevent_delete/);
  assert.match(schema, /subscriptions_preserve_history before update/);
});

test("links payments and reminders to the same customer as their subscription", () => {
  assert.match(schema, /payments_subscription_customer_fk foreign key \(subscription_id, customer_id\)/);
  assert.match(schema, /reminders_subscription_customer_fk foreign key \(subscription_id, customer_id\)/);
});

test("rejects negative amounts and reversed subscription dates", () => {
  assert.equal((schema.match(/check \(amount >= 0\)/g) ?? []).length, 2);
  assert.match(schema, /check \(end_date >= start_date\)/);
});

test("protects all historical record types from deletion", () => {
  for (const table of ["customers", "subscriptions", "payments", "reminders"]) {
    assert.match(schema, new RegExp(`${table}_prevent_delete before delete`));
  }
});

const reminders = (await readFile(new URL(
  "../supabase/migrations/20260930010000_phase3_reminders.sql", import.meta.url,
), "utf8")).replace(/\s+/g, " ").toLowerCase();

test("Phase 3 constrains reminder states, sent timestamps, and unique schedules", () => {
  assert.match(reminders, /status in \('pending', 'due', 'sent'\)/);
  assert.match(reminders, /status = 'sent' and sent_at is not null/);
  assert.match(reminders, /status <> 'sent' and sent_at is null/);
  assert.match(reminders, /unique \(subscription_id, scheduled_date\)/);
  assert.match(reminders, /on conflict \(subscription_id, scheduled_date\) do nothing/);
});

test("Phase 3 validates one to three distinct positive custom days and locks configuration", () => {
  assert.match(reminders, /cardinality\(reminder_days\), 0\) not between 1 and 3/);
  assert.match(reminders, /day is null or day <= 0/);
  assert.match(reminders, /count\(distinct day\)/);
  assert.match(reminders, /order by day desc/);
  assert.match(reminders, /where id = target_subscription_id for update/);
  assert.match(reminders, /if total_dates > 3/);
  assert.match(reminders, /subscription_record.end_date - reminder_day/);
});

test("Phase 3 creates subscriptions and reminders atomically and preserves earlier schema", () => {
  assert.match(reminders, /create function public.create_subscription_with_reminders/);
  assert.match(reminders, /insert into public.subscriptions/);
  assert.match(reminders, /perform public.create_subscription_reminders\(created_id/);
  assert.doesNotMatch(reminders, /delete from public\.(?:customers|subscriptions|payments)|drop table|update public.subscriptions|update public.payments/);
  assert.match(reminders, /reminders_preserve_history before update/);
  assert.match(reminders, /new.scheduled_date is distinct from old.scheduled_date/);
});

test("Phase 3 creates and refreshes reminders for active unexpired subscriptions only", () => {
  assert.match(reminders, /lower\(subscription_record.status\) <> 'active'/);
  assert.match(reminders, /subscription_record.end_date < \(now\(\) at time zone 'utc'\)::date/);
  assert.match(reminders, /lower\(subscription.status\) = 'active'/);
  assert.match(reminders, /subscription.end_date >= \(now\(\) at time zone 'utc'\)::date/);
});

test("Phase 3 sent recording is idempotent and preserves original sent time", () => {
  assert.match(reminders, /create function public.mark_reminder_sent/);
  assert.match(reminders, /if reminder_record.status = 'sent' then return/);
  assert.match(reminders, /set status = 'sent', sent_at = now\(\)/);
  assert.match(reminders, /old.status = 'sent'/);
});

test("legacy duplicates are archived before targeted removal and uniqueness", () => {
  const archive = reminders.indexOf("insert into public.reminder_duplicate_history");
  const removal = reminders.indexOf("delete from public.reminders");
  const unique = reminders.indexOf("add constraint reminders_subscription_schedule_unique");
  assert.ok(archive >= 0 && archive < removal && removal < unique);
  assert.match(reminders, /lock table public.reminders in access exclusive mode/);
  assert.match(reminders, /partition by subscription_id, scheduled_date/);
  assert.match(reminders, /order by sent_at asc nulls last, created_at asc, id asc/);
  assert.match(reminders, /to_jsonb\(reminder\)/);
  assert.match(reminders, /where reminder.id = duplicate.id/);
  assert.match(reminders, /enable trigger reminders_prevent_delete/);
  assert.match(reminders, /reminder_duplicate_history_prevent_update/);
});

test("RPC and direct-update trigger reject PENDING and future reminders", () => {
  assert.match(reminders, /reminder_record.status <> 'due' or reminder_record.scheduled_date > now\(\)/);
  assert.match(reminders, /old.status <> 'due' or old.scheduled_date > now\(\)/);
});

test("historical subscription creation skips reminder generation", () => {
  assert.match(reminders, /if target_end_date >= \(now\(\) at time zone 'utc'\)::date then perform public.create_subscription_reminders/);
});

const reminderUI = await readFile(new URL("../app/reminder-list.tsx", import.meta.url), "utf8");
test("share uses a fresh local-date message and both handler and button check eligibility", () => {
  assert.match(reminderUI, /const eligible = canSendReminder\(\{ \.\.\.reminder, status \}, today\)/);
  assert.match(reminderUI, /if \(sending \|\| !eligible\) return/);
  assert.match(reminderUI, /disabled=\{sending \|\| !eligible\}/);
  assert.match(reminderUI, /reminderMessage\(customer.name, subscription.subscription_name, subscription.end_date, today\)/);
  assert.match(reminderUI, /shareReminderMessage\(message, navigator.share.bind\(navigator\)/);
  assert.ok(reminderUI.indexOf("await shareReminderMessage") < reminderUI.indexOf("await markReminderSent"));
});
