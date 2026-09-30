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
