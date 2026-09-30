import assert from "node:assert/strict";
import test from "node:test";
import { assertValidDates, effectiveSubscriptionStatus, parseReminderDays, paymentSummary, reminderDate, reminderMessage } from "../lib/domain.ts";

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
  assert.equal(reminderMessage("أحمد", "الذهبي", "2026-10-20", 5), "مرحبًا أحمد، نذكرك بأن اشتراكك الذهبي سينتهي بتاريخ 2026-10-20. متبقي 5 أيام على انتهاء الاشتراك.");
});
