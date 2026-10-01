import assert from "node:assert/strict";
import test from "node:test";
import { IDBFactory, IDBObjectStore } from "fake-indexeddb";
import {
  clearLocalDatabaseForTests,
  configureReminders,
  createCustomer,
  createPayment,
  createSubscription,
  deleteCustomer,
  exportAllData,
  getCustomerDeletionSummary,
  getCustomer,
  getCustomerReminders,
  getCustomers,
  getReminders,
  markReminderSent,
  normalizeCustomerPhone,
  resetLocalDatabaseConnectionForTests,
  restoreBackup,
  updateCustomer,
} from "../lib/repository/indexeddb.ts";
import { paymentSummary } from "../lib/domain.ts";
import { createBackupDocument } from "../lib/backup.ts";

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


test("new duplicate normalized phones are blocked while different phones remain allowed", async () => {
  await fresh();
  await createCustomer({ name: "الأول", phone: " 777 111-222 " });
  assert.equal(normalizeCustomerPhone(" 777 111-222 "), "777111222");
  await assert.rejects(
    () => createCustomer({ name: "مكرر", phone: "777111222" }),
    /يوجد عميل مسجل بهذا الرقم بالفعل/,
  );
  const different = await createCustomer({ name: "مختلف", phone: "777111223" });
  assert.ok(await getCustomer(different));
});

test("customer phone update allows self but blocks another customer's normalized phone", async () => {
  await fresh();
  const first = await createCustomer({ name: "الأول", phone: "777 111-222" });
  const second = await createCustomer({ name: "الثاني", phone: "888-000-111" });

  await updateCustomer(first, { name: "الأول", phone: "777111222" });
  assert.equal((await getCustomer(first))?.phone, "777111222");

  await assert.rejects(
    () => updateCustomer(second, { name: "الثاني", phone: "777-111-222" }),
    /يوجد عميل مسجل بهذا الرقم بالفعل/,
  );
  assert.equal((await getCustomer(second))?.phone, "888-000-111");
});

test("historical duplicate phones remain readable after restore", async () => {
  await fresh();
  const backup = createBackupDocument({
    customers: [
      { id: "legacy-1", name: "قديم 1", phone: "777 111", notes: null, created_at: "2026-01-01T00:00:00.000Z" },
      { id: "legacy-2", name: "قديم 2", phone: "777-111", notes: null, created_at: "2026-01-02T00:00:00.000Z" },
    ],
    subscriptions: [],
    payments: [],
    reminders: [],
  }, "2026-10-01T00:00:00.000Z");

  await restoreBackup(backup);
  const rows = await getCustomers();
  assert.equal(rows.length, 2);
  assert.deepEqual(new Set(rows.map((row) => row.id)), new Set(["legacy-1", "legacy-2"]));
});

test("delete customer with no subscriptions removes only that customer", async () => {
  await fresh();
  const target = await createCustomer({ name: "للحذف", phone: "100" });
  const survivor = await createCustomer({ name: "يبقى", phone: "200" });

  const summary = await getCustomerDeletionSummary(target);
  assert.deepEqual(summary, {
    customer_id: target,
    customer_name: "للحذف",
    subscriptions: 0,
    payments: 0,
    reminders: 0,
  });

  await deleteCustomer(target);
  assert.equal(await getCustomer(target), null);
  assert.equal((await getCustomer(survivor))?.name, "يبقى");
});

test("atomic cascade delete removes subscriptions payments reminders and leaves no orphans", async () => {
  await fresh();
  const customer = await createCustomer({ name: "كامل", phone: "300" });
  const subscription = await createSubscription({
    customer_id: customer,
    subscription_name: "اشتراك",
    amount: 100,
    start_date: dateFromToday(0),
    end_date: dateFromToday(30),
    reminder_days: [10, 5],
  });
  await createPayment({
    customer_id: customer,
    subscription_id: subscription,
    amount: 25,
    payment_date: dateFromToday(0),
  });

  const summary = await getCustomerDeletionSummary(customer);
  assert.equal(summary?.subscriptions, 1);
  assert.equal(summary?.payments, 1);
  assert.equal(summary?.reminders, 2);

  await deleteCustomer(customer);
  const backup = await exportAllData();
  assert.deepEqual(backup.counts, { customers: 0, subscriptions: 0, payments: 0, reminders: 0 });
  assert.equal(await getCustomer(customer), null);
});

test("customer delete aborts atomically when a child delete fails", async () => {
  await fresh();
  const customer = await createCustomer({ name: "ذري", phone: "400" });
  const subscription = await createSubscription({
    customer_id: customer,
    subscription_name: "اشتراك",
    amount: 100,
    start_date: dateFromToday(0),
    end_date: dateFromToday(30),
    reminder_days: [5],
  });
  await createPayment({
    customer_id: customer,
    subscription_id: subscription,
    amount: 20,
    payment_date: dateFromToday(0),
  });

  const before = await exportAllData();
  const originalDelete = IDBObjectStore.prototype.delete;
  let injected = false;
  IDBObjectStore.prototype.delete = function (key: IDBValidKey) {
    if (!injected && this.name === "payments") {
      injected = true;
      throw new Error("forced child delete failure");
    }
    return originalDelete.call(this, key);
  };

  try {
    await assert.rejects(() => deleteCustomer(customer), /تعذر حذف العميل نهائيًا/);
  } finally {
    IDBObjectStore.prototype.delete = originalDelete;
  }

  const after = await exportAllData();
  assert.deepEqual(after.data, before.data);
  assert.deepEqual(after.counts, before.counts);
});

test("deleting a nonexistent customer is rejected without changing data", async () => {
  await fresh();
  await createCustomer({ name: "يبقى", phone: "500" });
  const before = await exportAllData();
  await assert.rejects(() => deleteCustomer("missing-customer"), /العميل غير موجود/);
  const after = await exportAllData();
  assert.deepEqual(after.data, before.data);
});
