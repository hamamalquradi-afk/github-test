import assert from "node:assert/strict";
import test from "node:test";
import { IDBFactory } from "fake-indexeddb";
import {
  BACKUP_APP,
  BACKUP_VERSION,
  backupFilename,
  parseBackupJson,
  serializeBackup,
  validateBackup,
  type BackupDocument,
} from "../lib/backup.ts";
import {
  clearLocalDatabaseForTests,
  createCustomer,
  createPayment,
  createSubscription,
  exportAllData,
  getReminders,
  markReminderSent,
  resetLocalDatabaseConnectionForTests,
  restoreBackup,
} from "../lib/repository/indexeddb.ts";

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

async function seedData() {
  const customer = await createCustomer({ name: "عميل النسخة", phone: "777111222", notes: "سجل كامل" });
  const historical = await createSubscription({
    customer_id: customer, subscription_name: "قديم", amount: 100,
    start_date: dateFromToday(-60), end_date: dateFromToday(-30), reminder_days: [7],
  });
  await createPayment({ customer_id: customer, subscription_id: historical, amount: 100, payment_date: dateFromToday(-40), notes: "دفعة تاريخية" });

  const active = await createSubscription({
    customer_id: customer, subscription_name: "حالي", amount: 200,
    start_date: dateFromToday(-5), end_date: dateFromToday(10), reminder_days: [15, 10, 5],
  });
  await createPayment({ customer_id: customer, subscription_id: active, amount: 50, payment_date: dateFromToday(-1), notes: "دفعة حالية" });
  const reminders = await getReminders(dateFromToday(0));
  const overdue = reminders.find((item) => item.subscription_id === active && item.days_before === 15)!;
  await markReminderSent(overdue.id);
  return { customer, historical, active, sentReminder: overdue.id };
}

function copy<T>(value: T): T {
  return structuredClone(value);
}

test("export empty database includes versioned metadata and zero counts", async () => {
  await fresh();
  const backup = await exportAllData();
  assert.equal(backup.app, BACKUP_APP);
  assert.equal(backup.backupVersion, BACKUP_VERSION);
  assert.equal(backup.schemaVersion, 1);
  assert.deepEqual(backup.counts, { customers: 0, subscriptions: 0, payments: 0, reminders: 0 });
  assert.deepEqual(backup.data, { customers: [], subscriptions: [], payments: [], reminders: [] });
  assert.deepEqual(parseBackupJson(serializeBackup(backup)), backup);
  assert.match(backupFilename(backup.exportedAt), /^subscription-tracker-backup-\d{4}-\d{2}-\d{2}-\d{2}-\d{2}\.json$/);
});

test("export populated database preserves entities, fields, history, counts and SENT metadata", async () => {
  await fresh();
  const ids = await seedData();
  const backup = await exportAllData();

  assert.deepEqual(backup.counts, { customers: 1, subscriptions: 2, payments: 2, reminders: 3 });
  assert.equal(backup.data.customers[0].id, ids.customer);
  assert.ok(backup.data.customers[0].created_at);
  assert.ok(backup.data.subscriptions.some((item) => item.id === ids.historical && item.end_date === dateFromToday(-30)));
  assert.ok(backup.data.subscriptions.some((item) => item.id === ids.active));
  assert.equal(backup.data.payments.length, 2);
  const sent = backup.data.reminders.find((item) => item.id === ids.sentReminder)!;
  assert.equal(sent.status, "SENT");
  assert.ok(sent.sent_at);
  assert.equal(sent.days_before, 15);
});

test("backup validation rejects malformed, unrelated, unsupported and relationally corrupt files", async () => {
  await fresh();
  await seedData();
  const valid = await exportAllData();

  assert.throws(() => parseBackupJson("{bad json"), /JSON/);

  const wrongApp = copy(valid) as any;
  wrongApp.app = "other-app";
  assert.throws(() => validateBackup(wrongApp), /لا تخص/);

  const future = copy(valid) as any;
  future.backupVersion = BACKUP_VERSION + 1;
  assert.throws(() => validateBackup(future), /أحدث/);

  const missingCustomers = copy(valid) as any;
  delete missingCustomers.data.customers;
  assert.throws(() => validateBackup(missingCustomers), /customers/);

  const duplicate = copy(valid) as any;
  duplicate.data.customers.push(copy(duplicate.data.customers[0]));
  duplicate.counts.customers += 1;
  assert.throws(() => validateBackup(duplicate), /مكرر/);

  const brokenReference = copy(valid) as any;
  brokenReference.data.subscriptions[0].customer_id = "missing-customer";
  assert.throws(() => validateBackup(brokenReference), /عميل غير موجود/);

  const invalidPayment = copy(valid) as any;
  invalidPayment.data.payments[0].amount = -1;
  assert.throws(() => validateBackup(invalidPayment), /قيمة الدفعة/);

  const invalidReminder = copy(valid) as any;
  invalidReminder.data.reminders[0].status = "BROKEN";
  assert.throws(() => validateBackup(invalidReminder), /حالة تنبيه/);

  assert.throws(() => validateBackup({ app: BACKUP_APP, backupVersion: 1 }));
});

test("valid restore fully replaces current data while preserving IDs, history, expired subscriptions and SENT state", async () => {
  await fresh();
  const ids = await seedData();
  const backup = await exportAllData();
  await createCustomer({ name: "سجل يجب استبداله", phone: "999" });

  await restoreBackup(backup);
  const restored = await exportAllData();

  assert.deepEqual(restored.data, backup.data);
  assert.equal(restored.counts.customers, 1);
  assert.ok(restored.data.subscriptions.some((item) => item.id === ids.historical && item.end_date === dateFromToday(-30)));
  const sent = restored.data.reminders.find((item) => item.id === ids.sentReminder)!;
  assert.equal(sent.status, "SENT");
  assert.equal(sent.sent_at, backup.data.reminders.find((item) => item.id === ids.sentReminder)!.sent_at);
});

test("failed restore validation leaves existing local data unchanged with no partial replacement", async () => {
  await fresh();
  await seedData();
  const before = await exportAllData();
  const invalid = copy(before) as any;
  invalid.data.payments[0].subscription_id = "missing-subscription";

  await assert.rejects(() => restoreBackup(invalid as BackupDocument), /اشتراك غير صالح/);
  const after = await exportAllData();
  assert.deepEqual(after.data, before.data);
  assert.deepEqual(after.counts, before.counts);
});

test("backup round-trip restores equivalent application data", async () => {
  await fresh();
  await seedData();
  const original = await exportAllData();

  await clearLocalDatabaseForTests();
  await restoreBackup(original);
  const roundTrip = await exportAllData();

  assert.deepEqual(roundTrip.data, original.data);
  assert.deepEqual(roundTrip.counts, original.counts);
});
