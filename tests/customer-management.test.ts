import assert from "node:assert/strict";
import test from "node:test";
import {
  buildCustomerRows,
  CUSTOMER_STATUS_LABELS,
  customerRowView,
  dashboardMetrics,
  filterCustomerRows,
  formatAmount,
  sortCustomerRows,
} from "../lib/customer-management.ts";
import type { Customer, Subscription } from "../lib/types.ts";

const today = "2026-10-01";

function subscription(
  id: string,
  amount: number,
  startDate: string,
  endDate: string,
  payments: number[],
): Subscription {
  return {
    id,
    customer_id: "unused",
    subscription_name: `اشتراك ${id}`,
    amount,
    start_date: startDate,
    end_date: endDate,
    status: "active",
    created_at: `${startDate}T00:00:00.000Z`,
    payments: payments.map((value, index) => ({
      id: `${id}-payment-${index}`,
      customer_id: "unused",
      subscription_id: id,
      amount: value,
      payment_date: today,
      notes: null,
      created_at: `${today}T00:00:00.000Z`,
    })),
  };
}

function customer(id: string, name: string, subscriptions: Subscription[]): Customer {
  return {
    id,
    name,
    phone: `77${id}`,
    notes: null,
    created_at: "2026-01-01T00:00:00.000Z",
    subscriptions: subscriptions.map((item) => ({ ...item, customer_id: id, payments: item.payments.map((p) => ({ ...p, customer_id: id })) })),
  };
}

const rowsSource = [
  customer("1", "أحمد", [subscription("a", 100, "2026-09-01", "2026-10-05", [30])]),
  customer("2", "بدر", [subscription("b", 200, "2026-09-01", "2026-10-20", [200])]),
  customer("3", "خالد", [subscription("c", 300, "2026-08-01", "2026-09-30", [])]),
  customer("4", "دانا", []),
];

test("amount formatter keeps thousands separators and only meaningful decimals", () => {
  assert.equal(formatAmount(6000), "6,000");
  assert.equal(formatAmount(6000.5), "6,000.50");
  assert.equal(formatAmount("1234.56"), "1,234.56");
});

test("customer rows expose Arabic status labels without changing domain status semantics", () => {
  const partial = customerRowView(rowsSource[0], today);
  const paid = customerRowView(rowsSource[1], today);
  const expired = customerRowView(rowsSource[2], today);
  const none = customerRowView(rowsSource[3], today);
  const unpaid = customerRowView(
    customer("5", "سالم", [subscription("d", 50, "2026-10-01", "2026-11-01", [])]),
    today,
  );

  assert.equal(partial.status, "PARTIAL");
  assert.equal(partial.statusLabel, "مدفوع جزئيًا");
  assert.equal(paid.statusLabel, "مدفوع");
  assert.equal(unpaid.statusLabel, "غير مدفوع");
  assert.equal(expired.statusLabel, "منتهي");
  assert.equal(none.statusLabel, "بدون اشتراك");
  assert.deepEqual(CUSTOMER_STATUS_LABELS, {
    PAID: "مدفوع",
    PARTIAL: "مدفوع جزئيًا",
    UNPAID: "غير مدفوع",
    EXPIRED: "منتهي",
    NONE: "بدون اشتراك",
  });
});

test("required customer filters operate on current local-date rules", () => {
  const rows = rowsSource.map((item) => customerRowView(item, today));
  assert.deepEqual(filterCustomerRows(rows, "all").map((row) => row.customer.id), ["1", "2", "3", "4"]);
  assert.deepEqual(filterCustomerRows(rows, "active").map((row) => row.customer.id), ["1", "2"]);
  assert.deepEqual(filterCustomerRows(rows, "expired").map((row) => row.customer.id), ["3"]);
  assert.deepEqual(filterCustomerRows(rows, "remaining").map((row) => row.customer.id), ["1", "3"]);
  assert.deepEqual(filterCustomerRows(rows, "paid").map((row) => row.customer.id), ["2"]);
  assert.deepEqual(filterCustomerRows(rows, "none").map((row) => row.customer.id), ["4"]);
});

test("customer sorting supports name remaining and nearest end date", () => {
  const rows = rowsSource.map((item) => customerRowView(item, today));
  assert.deepEqual(sortCustomerRows(rows, "name").map((row) => row.customer.id), ["1", "2", "3", "4"]);
  assert.deepEqual(sortCustomerRows(rows, "remaining").map((row) => row.customer.id), ["3", "1", "2", "4"]);
  assert.deepEqual(sortCustomerRows(rows, "end_date").map((row) => row.customer.id), ["3", "1", "2", "4"]);
});

test("search result input can be filtered and sorted without changing search semantics", () => {
  const searchResults = rowsSource.filter((item) => item.name.includes("أ") || item.name.includes("د"));
  const built = buildCustomerRows(searchResults, today, "all", "name");
  assert.deepEqual(built.map((row) => row.customer.name), ["أحمد", "دانا"]);
});

test("dashboard metrics, expiring window, top remaining and nearest expiry are deterministic", () => {
  const metrics = dashboardMetrics(rowsSource, today);

  assert.equal(metrics.totalCustomers, 4);
  assert.equal(metrics.activeSubscriptions, 2);
  assert.equal(metrics.expiringWithin7Days, 1);
  assert.equal(metrics.totalValue, 300);
  assert.equal(metrics.totalPaid, 230);
  assert.equal(metrics.totalRemaining, 70);
  assert.deepEqual(metrics.topRemaining.map((row) => row.customer.id), ["1"]);
  assert.deepEqual(metrics.nearestExpiry.map((row) => row.customer.id), ["1", "2"]);
  assert.deepEqual(metrics.nearestExpiry.map((row) => row.daysRemaining), [4, 19]);
});
