import assert from "node:assert/strict";
import test from "node:test";
import { IDBFactory } from "fake-indexeddb";
import {
  copyReminderMessage,
  shareReminderMessage,
  supportsFileShare,
} from "../lib/browser-fallbacks.ts";
import { BACKUP_SCHEMA_VERSION, BACKUP_VERSION, createBackupDocument } from "../lib/backup.ts";
import {
  currentSubscription,
  daysRemaining,
  effectiveSubscriptionStatus,
  paymentSummary,
} from "../lib/domain.ts";
import { localToday, type LocalCalendarSource } from "../lib/local-date.ts";
import {
  clearLocalDatabaseForTests,
  getReminders,
  resetLocalDatabaseConnectionForTests,
  restoreBackup,
} from "../lib/repository/indexeddb.ts";

function sourceForTimeZone(instant: Date, timeZone: string): LocalCalendarSource {
  const values = new Map(
    new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "numeric",
      day: "numeric",
    }).formatToParts(instant).map((part) => [part.type, part.value]),
  );
  const year = Number(values.get("year"));
  const month = Number(values.get("month"));
  const day = Number(values.get("day"));
  return {
    getFullYear: () => year,
    getMonth: () => month - 1,
    getDate: () => day,
  };
}

function installDatabase(): void {
  Object.defineProperty(globalThis, "indexedDB", { value: new IDBFactory(), configurable: true, writable: true });
  resetLocalDatabaseConnectionForTests();
}

async function freshDatabase(): Promise<void> {
  installDatabase();
  await clearLocalDatabaseForTests();
}

test("Yemen early local morning uses 2026-10-01 rather than previous UTC date", () => {
  const instant = new Date("2026-09-30T22:30:00.000Z");
  assert.equal(instant.toISOString().slice(0, 10), "2026-09-30");

  const today = localToday(sourceForTimeZone(instant, "Asia/Aden"));
  assert.equal(today, "2026-10-01");

  const subscription = {
    id: "sub-1",
    start_date: "2026-10-01",
    end_date: "2026-10-31",
    status: "active",
    created_at: "2026-09-30T20:00:00.000Z",
    amount: 10000,
    payments: [{ amount: 2500 }],
  };

  assert.equal(currentSubscription([subscription], today)?.id, "sub-1");
  assert.equal(effectiveSubscriptionStatus("2026-09-30", today), "EXPIRED");
  assert.equal(effectiveSubscriptionStatus("2026-10-01", today), "ACTIVE");
  assert.equal(daysRemaining("2026-10-02", today), 1);

  const dashboardSubscriptions = [{ subscriptions: [subscription] }]
    .map((customer) => currentSubscription(customer.subscriptions, today))
    .filter((item) => item && item.status.toLowerCase() === "active" && item.start_date <= today && effectiveSubscriptionStatus(item.end_date, today) === "ACTIVE");
  const remainingTotal = dashboardSubscriptions.reduce(
    (total, item) => total + paymentSummary(item!.amount, item!.payments).remaining,
    0,
  );

  assert.equal(dashboardSubscriptions.length, 1);
  assert.equal(remainingTotal, 7500);
});

test("reminder scheduled for the local business date becomes DUE deterministically", async () => {
  await freshDatabase();
  const backup = createBackupDocument({
    customers: [{
      id: "customer-1",
      name: "عميل",
      phone: "777000000",
      notes: null,
      created_at: "2026-09-01T00:00:00.000Z",
      updated_at: "2026-09-01T00:00:00.000Z",
    }],
    subscriptions: [{
      id: "subscription-1",
      customer_id: "customer-1",
      subscription_name: "اشتراك",
      amount: 100,
      start_date: "2026-10-01",
      end_date: "2026-10-08",
      status: "active",
      created_at: "2026-09-01T00:00:00.000Z",
    }],
    payments: [],
    reminders: [{
      id: "reminder-1",
      customer_id: "customer-1",
      subscription_id: "subscription-1",
      days_before: 7,
      scheduled_date: "2026-10-01T00:00:00.000Z",
      status: "PENDING",
      sent_at: null,
      created_at: "2026-09-01T00:00:00.000Z",
    }],
  }, "2026-09-30T22:30:00.000Z");

  await restoreBackup(backup);
  const reminders = await getReminders("2026-10-01");
  assert.equal(reminders[0].status, "DUE");
});

test("successful Web Share runs SENT callback only after share resolves", async () => {
  let markedSent = 0;
  const outcome = await shareReminderMessage(
    "رسالة",
    async ({ text }) => { assert.equal(text, "رسالة"); },
    async () => { markedSent += 1; },
  );
  assert.equal(outcome, "shared");
  assert.equal(markedSent, 1);
});

test("cancelled Web Share does not run SENT callback", async () => {
  let markedSent = 0;
  const cancelled = new Error("cancelled");
  cancelled.name = "AbortError";

  const outcome = await shareReminderMessage(
    "رسالة",
    async () => { throw cancelled; },
    async () => { markedSent += 1; },
  );

  assert.equal(outcome, "cancelled");
  assert.equal(markedSent, 0);
});

test("copy fallback copies when possible and remains manual when no copy API works", async () => {
  let copied = "";
  const clipboardOutcome = await copyReminderMessage("النص", async (text) => { copied = text; });
  assert.equal(clipboardOutcome, "copied");
  assert.equal(copied, "النص");

  const fallbackOutcome = await copyReminderMessage("النص", undefined, () => true);
  assert.equal(fallbackOutcome, "copied");

  const manualOutcome = await copyReminderMessage("النص", undefined, () => false);
  assert.equal(manualOutcome, "manual");
});

test("file-share capability is optional and backup format versions remain unchanged", () => {
  const file = {} as File;
  assert.equal(supportsFileShare(undefined, file), false);
  assert.equal(supportsFileShare({ share: async () => undefined }, file), true);
  assert.equal(supportsFileShare({ share: async () => undefined, canShare: () => false }, file), false);
  assert.equal(BACKUP_VERSION, 1);
  assert.equal(BACKUP_SCHEMA_VERSION, 1);
});
