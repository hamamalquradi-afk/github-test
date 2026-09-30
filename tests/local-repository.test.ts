import assert from "node:assert/strict";
import test from "node:test";
import { IDBFactory } from "fake-indexeddb";
import {
  clearLocalDatabaseForTests,
  configureReminders,
  createCustomer,
  createPayment,
  createSubscription,
  getCustomer,
  getCustomerReminders,
  getCustomers,
  getReminders,
  markReminderSent,
  resetLocalDatabaseConnectionForTests,
  updateCustomer,
} from "../lib/repository/indexeddb.ts";
import { paymentSummary } from "../lib/domain.ts";

function installDatabase(): void {
  Object.defineProperty(globalThis, "indexedDB", { value: new IDBFactory(), configurable: true, writable: true });
  resetLocalDatabaseConnectionForTests();
}

async function fresh(): Promise<void> {
  installDatabase();
  await clearLocalDatabaseForTests();
}

function dateFromToday(offset: number): string {
  const date = new Date();
  date.setUTCHours(0, 0, 0, 0);
  date.setUTCDate(date.getUTCDate() + offset);
  return date.toISOString().slice(0, 10);
}

test("customers persist locally and update without losing existing records", async () => {
  await fresh();
  const first = await createCustomer({ name: "أحمد", phone: "777111111", notes: "قديم" });
  const second = await createCustomer({ name: "سارة", phone: "777222222" });
  await updateCustomer(first, { name: "أحمد علي", phone: "777111111", notes: "محدث" });
  const rows = await getCustomers();
  assert.equal(rows.length, 2);
  assert.equal((await getCustomer(first))?.name, "أحمد علي");
  assert.equal((await getCustomer(second))?.name, "سارة");
});

test("renewal creates a new subscription and preserves expired history", async () => {
  await fresh();
  const customer = await createCustomer({ name: "عميل", phone: "1" });
  const oldId = await createSubscription({ customer_id: customer, subscription_name: "قديم", amount: 100, start_date: dateFromToday(-60), end_date: dateFromToday(-30), reminder_days: [7] });
  const newId = await createSubscription({ customer_id: customer, subscription_name: "تجديد", amount: 120, start_date: dateFromToday(0), end_date: dateFromToday(30), reminder_days: [7, 1] });
  const row = await getCustomer(customer);
  assert.equal(row?.subscriptions.length, 2);
  assert.notEqual(oldId, newId);
  assert.ok(row?.subscriptions.some((item) => item.id === oldId && item.end_date === dateFromToday(-30)));
});

test("payments preserve history and calculations while rejecting overpayment and bad ownership", async () => {
  await fresh();
  const customer = await createCustomer({ name: "عميل", phone: "1" });
  const other = await createCustomer({ name: "آخر", phone: "2" });
  const subscription = await createSubscription({ customer_id: customer, subscription_name: "خدمة", amount: 100, start_date: dateFromToday(0), end_date: dateFromToday(30), reminder_days: [5] });
  await createPayment({ customer_id: customer, subscription_id: subscription, amount: 30, payment_date: dateFromToday(0) });
  await createPayment({ customer_id: customer, subscription_id: subscription, amount: 20, payment_date: dateFromToday(1) });
  await assert.rejects(() => createPayment({ customer_id: customer, subscription_id: subscription, amount: 60, payment_date: dateFromToday(2) }), /أكبر من المبلغ المتبقي/);
  await assert.rejects(() => createPayment({ customer_id: other, subscription_id: subscription, amount: 1, payment_date: dateFromToday(2) }), /غير مرتبطة/);
  const row = await getCustomer(customer);
  const saved = row!.subscriptions[0];
  assert.equal(saved.payments.length, 2);
  assert.deepEqual(paymentSummary(saved.amount, saved.payments), { totalPaid: 50, remaining: 50, status: "PARTIAL" });
});

test("reminders enforce valid sets, duplicate protection, due refresh, overdue behavior and SENT protection", async () => {
  await fresh();
  const customer = await createCustomer({ name: "عميل", phone: "1" });
  const subscription = await createSubscription({ customer_id: customer, subscription_name: "خدمة", amount: 100, start_date: dateFromToday(-20), end_date: dateFromToday(10), reminder_days: [15, 10, 5] });
  await assert.rejects(() => configureReminders(subscription, customer, [2]), /الحد الأقصى ثلاثة/);
  await assert.rejects(() => configureReminders(subscription, customer, [5, 5]), /تكرار/);
  const today = dateFromToday(0);
  const reminders = await getReminders(today);
  assert.equal(reminders.length, 3);
  const overdue = reminders.find((item) => item.days_before === 15)!;
  const dueToday = reminders.find((item) => item.days_before === 10)!;
  const future = reminders.find((item) => item.days_before === 5)!;
  assert.equal(overdue.status, "DUE");
  assert.equal(dueToday.status, "DUE");
  assert.equal(future.status, "PENDING");
  await assert.rejects(() => markReminderSent(future.id), /المستحقة حاليًا/);
  await markReminderSent(overdue.id);
  const sent = (await getCustomerReminders(customer)).find((item) => item.id === overdue.id)!;
  const originalSentAt = sent.sent_at;
  assert.equal(sent.status, "SENT");
  await markReminderSent(overdue.id);
  const retried = (await getCustomerReminders(customer)).find((item) => item.id === overdue.id)!;
  assert.equal(retried.sent_at, originalSentAt);
});

test("historical subscriptions create no reminders and local runtime needs no Supabase environment", async () => {
  await fresh();
  delete process.env.NEXT_PUBLIC_SUPABASE_URL;
  delete process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
  const customer = await createCustomer({ name: "محلي", phone: "1" });
  await createSubscription({ customer_id: customer, subscription_name: "منتهي", amount: 10, start_date: dateFromToday(-20), end_date: dateFromToday(-10), reminder_days: [3] });
  assert.equal((await getCustomerReminders(customer)).length, 0);
  assert.equal((await getCustomers()).length, 1);
});
