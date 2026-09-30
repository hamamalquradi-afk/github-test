import assert from "node:assert/strict";
import test from "node:test";
import { assertValidDates, paymentSummary } from "../lib/domain.ts";

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
