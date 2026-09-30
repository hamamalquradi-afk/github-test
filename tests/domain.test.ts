import assert from "node:assert/strict";
import test from "node:test";
import { canSendReminder, assertValidDates, currentSubscription, daysRemaining, effectiveSubscriptionStatus, parseReminderDays, paymentSummary, reminderDate, reminderMessage } from "../lib/domain.ts";

test("new subscription is unpaid", () => {
  assert.deepEqual(paymentSummary(100, []), { totalPaid: 0, remaining: 100, status: "UNPAID" });
});

test("partial payment is calculated", () => {
  assert.deepEqual(paymentSummary(100, [{ amount: 35 }]), { totalPaid: 35, remaining: 65, status: "PARTIAL" });
});

test("multiple independent payments produce paid status", () => {
  assert.deepEqual(paymentSummary(100, [{ amount: 35 }, { amount: "65" }]), { totalPaid: 100, remaining: 0, status: "PAID" });
});

test("invalid date ranges are rejected", () => {
  assert.throws(() => assertValidDates("2026-05-02", "2026-05-01"));
});

test("subscriptions past their end date are expired", () => {
  assert.equal(effectiveSubscriptionStatus("2026-05-01", "2026-05-02"), "EXPIRED");
});

test("accepts one, two, or three custom reminder days and sorts descending", () => {
  assert.deepEqual(parseReminderDays("3"), [3]);
  assert.deepEqual(parseReminderDays("3, 5"), [5, 3]);
  assert.deepEqual(parseReminderDays("1,7,4"), [7, 4, 1]);
});

test("rejects duplicate, non-positive, non-integer, and excessive reminder days", () => {
  assert.throws(() => parseReminderDays("5,5"));
  assert.throws(() => parseReminderDays("0"));
  assert.throws(() => parseReminderDays("1.5"));
  assert.throws(() => parseReminderDays("1,2,3,4"));
});

test("calculates reminder date without local timezone drift", () => {
  assert.equal(reminderDate("2026-10-20", 5), "2026-10-15");
});

test("creates the requested Arabic customer message", () => {
  assert.equal(reminderMessage("أحمد", "الذهبي", "2026-10-20", "2026-10-15"), "مرحبًا أحمد، نذكرك بأن اشتراكك الذهبي سينتهي بتاريخ 2026-10-20. متبقي 5 أيام على انتهاء الاشتراك.");
});

test("rejects empty entries, invalid numbers, and values outside SQL integer range", () => {
  for (const value of ["", " ", "1,", ",1", "1,,2", "-1", "NaN", "Infinity", "2147483648"]) {
    assert.throws(() => parseReminderDays(value));
  }
});

test("accepts Arabic digits and comma without changing the selected days", () => {
  assert.deepEqual(parseReminderDays("٣،١١،٢"), [11, 3, 2]);
});

test("expiry is strictly after end date and remaining days stop at zero", () => {
  assert.equal(effectiveSubscriptionStatus("2026-10-20", "2026-10-20"), "ACTIVE");
  assert.equal(daysRemaining("2026-10-20", "2026-10-15"), 5);
  assert.equal(daysRemaining("2026-10-20", "2026-10-21"), 0);
  assert.equal(reminderDate("2026-03-02", 3), "2026-02-27");
});

test("future renewal does not replace an active subscription or mutate history", () => {
  const active = { start_date: "2026-09-01", end_date: "2026-09-30", status: "active", created_at: "2026-09-01" };
  const future = { start_date: "2026-10-01", end_date: "2026-10-31", status: "active", created_at: "2026-09-20" };
  const history = [future, active];
  assert.equal(currentSubscription(history, "2026-09-30"), active);
  assert.equal(currentSubscription(history, "2026-10-01"), future);
  assert.deepEqual(history, [future, active]);
});

test("current subscription has explicit expired and upcoming fallbacks", () => {
  const past = { start_date: "2026-08-01", end_date: "2026-08-31", status: "expired", created_at: "2026-08-01" };
  const next = { start_date: "2026-10-01", end_date: "2026-10-31", status: "active", created_at: "2026-09-20" };
  assert.equal(currentSubscription([next, past], "2026-09-30"), past);
  assert.equal(currentSubscription([next], "2026-09-30"), next);
  assert.equal(currentSubscription([], "2026-09-30"), undefined);
});

test("only currently due reminders expose send, including stale and forged states", () => {
  const reminder = { status: "PENDING" as const, scheduled_date: "2026-10-15T00:00:00Z",
    subscriptions: { status: "active", end_date: "2026-10-20" } };
  assert.equal(canSendReminder(reminder, "2026-10-14"), false);
  assert.equal(canSendReminder(reminder, "2026-10-15"), false);
  assert.equal(canSendReminder({ ...reminder, status: "DUE" }, "2026-10-14"), false);
  assert.equal(canSendReminder({ ...reminder, status: "DUE" }, "2026-10-15"), true);
  assert.equal(canSendReminder({ ...reminder, status: "SENT" }, "2026-10-15"), false);
  assert.equal(canSendReminder({ ...reminder, status: "DUE" }, "2026-10-21"), false);
  assert.equal(canSendReminder({ ...reminder, status: "DUE",
    subscriptions: { ...reminder.subscriptions, status: "inactive" } }, "2026-10-15"), false);
});

test("historical date ranges remain valid and display EXPIRED without changing payment history", () => {
  assert.doesNotThrow(() => assertValidDates("2026-01-01", "2026-01-31"));
  assert.equal(effectiveSubscriptionStatus("2026-01-31", "2026-09-30"), "EXPIRED");
  const payments = [{ amount: 40 }, { amount: 60 }];
  assert.deepEqual(paymentSummary(100, payments), { totalPaid: 100, remaining: 0, status: "PAID" });
  assert.deepEqual(payments, [{ amount: 40 }, { amount: 60 }]);
});

test("Arabic message recomputes remaining days for each send date across midnight", () => {
  assert.equal(reminderMessage("أحمد", "الذهبي", "2026-10-20", "2026-10-15"),
    "مرحبًا أحمد، نذكرك بأن اشتراكك الذهبي سينتهي بتاريخ 2026-10-20. متبقي 5 أيام على انتهاء الاشتراك.");
  assert.equal(reminderMessage("أحمد", "الذهبي", "2026-10-20", "2026-10-16"),
    "مرحبًا أحمد، نذكرك بأن اشتراكك الذهبي سينتهي بتاريخ 2026-10-20. متبقي 4 أيام على انتهاء الاشتراك.");
  assert.match(reminderMessage("أحمد", "الذهبي", "2026-10-20", "2026-10-20"), /متبقي 0 أيام/);
});


test("reminder eligibility preserves overdue, today, future, and SENT behavior", () => {
  const subscription = { status: "active", end_date: "2026-10-20" };
  const overdue = { status: "DUE" as const, scheduled_date: "2026-10-15T00:00:00Z", subscriptions: subscription };
  const today = { status: "DUE" as const, scheduled_date: "2026-10-16T00:00:00Z", subscriptions: subscription };
  const future = { status: "DUE" as const, scheduled_date: "2026-10-17T00:00:00Z", subscriptions: subscription };
  const sent = { status: "SENT" as const, scheduled_date: "2026-10-15T00:00:00Z", subscriptions: subscription };

  assert.equal(canSendReminder(overdue, "2026-10-16"), true);
  assert.equal(canSendReminder(today, "2026-10-16"), true);
  assert.equal(canSendReminder(future, "2026-10-16"), false);
  assert.equal(canSendReminder(sent, "2026-10-16"), false);
});
