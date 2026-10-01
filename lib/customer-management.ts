import { currentSubscription, daysRemaining, effectiveSubscriptionStatus, paymentSummary } from "./domain.ts";
import type { Customer, Subscription } from "./types.ts";

export type CustomerFilter = "all" | "active" | "expired" | "remaining" | "paid" | "none";
export type CustomerSort = "name" | "remaining" | "end_date";
export type DisplayCustomerStatus = "PAID" | "PARTIAL" | "UNPAID" | "EXPIRED" | "NONE";

export const CUSTOMER_FILTER_LABELS: Record<CustomerFilter, string> = {
  all: "الكل",
  active: "نشط",
  expired: "منتهي",
  remaining: "عليه متبقي",
  paid: "مدفوع بالكامل",
  none: "بدون اشتراك",
};

export const CUSTOMER_STATUS_LABELS: Record<DisplayCustomerStatus, string> = {
  PAID: "مدفوع",
  PARTIAL: "مدفوع جزئيًا",
  UNPAID: "غير مدفوع",
  EXPIRED: "منتهي",
  NONE: "بدون اشتراك",
};

export function formatAmount(value: number | string): string {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "—";
  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: Number.isInteger(amount) ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(amount);
}

export interface CustomerRowView {
  customer: Customer;
  current?: Subscription;
  totalPaid: number;
  remaining: number;
  subscriptionAmount: number;
  endDate: string;
  status: DisplayCustomerStatus;
  statusLabel: string;
  active: boolean;
}

export function customerRowView(customer: Customer, today: string): CustomerRowView {
  const current = currentSubscription(customer.subscriptions, today);
  if (!current) {
    return {
      customer,
      totalPaid: 0,
      remaining: 0,
      subscriptionAmount: 0,
      endDate: "",
      status: "NONE",
      statusLabel: CUSTOMER_STATUS_LABELS.NONE,
      active: false,
    };
  }

  const summary = paymentSummary(current.amount, current.payments);
  const expired = effectiveSubscriptionStatus(current.end_date, today) === "EXPIRED";
  const status: DisplayCustomerStatus = expired ? "EXPIRED" : summary.status;

  return {
    customer,
    current,
    totalPaid: summary.totalPaid,
    remaining: summary.remaining,
    subscriptionAmount: Number(current.amount),
    endDate: current.end_date,
    status,
    statusLabel: CUSTOMER_STATUS_LABELS[status],
    active: !expired && current.status.toLowerCase() === "active" && current.start_date <= today,
  };
}

export function filterCustomerRows(rows: CustomerRowView[], filter: CustomerFilter): CustomerRowView[] {
  switch (filter) {
    case "active":
      return rows.filter((row) => row.active);
    case "expired":
      return rows.filter((row) => row.status === "EXPIRED");
    case "remaining":
      return rows.filter((row) => Boolean(row.current) && row.remaining > 0);
    case "paid":
      return rows.filter((row) => row.status === "PAID");
    case "none":
      return rows.filter((row) => row.status === "NONE");
    default:
      return rows;
  }
}

export function sortCustomerRows(rows: CustomerRowView[], sort: CustomerSort): CustomerRowView[] {
  return [...rows].sort((a, b) => {
    if (sort === "remaining") {
      return b.remaining - a.remaining || a.customer.name.localeCompare(b.customer.name, "ar");
    }
    if (sort === "end_date") {
      const aDate = a.endDate || "9999-12-31";
      const bDate = b.endDate || "9999-12-31";
      return aDate.localeCompare(bDate) || a.customer.name.localeCompare(b.customer.name, "ar");
    }
    return a.customer.name.localeCompare(b.customer.name, "ar");
  });
}

export function buildCustomerRows(customers: Customer[], today: string, filter: CustomerFilter, sort: CustomerSort): CustomerRowView[] {
  return sortCustomerRows(filterCustomerRows(customers.map((customer) => customerRowView(customer, today)), filter), sort);
}

export function dashboardMetrics(customers: Customer[], today: string) {
  const rows = customers.map((customer) => customerRowView(customer, today));
  const activeRows = rows.filter((row) => row.active && row.current);
  const expiringWithin7Days = activeRows.filter((row) => {
    const remaining = daysRemaining(row.endDate, today);
    return remaining >= 0 && remaining <= 7;
  });
  const totalValue = activeRows.reduce((sum, row) => sum + row.subscriptionAmount, 0);
  const totalPaid = activeRows.reduce((sum, row) => sum + row.totalPaid, 0);
  const totalRemaining = activeRows.reduce((sum, row) => sum + row.remaining, 0);

  const topRemaining = [...activeRows]
    .filter((row) => row.remaining > 0)
    .sort((a, b) => b.remaining - a.remaining || a.endDate.localeCompare(b.endDate))
    .slice(0, 5);

  const nearestExpiry = [...activeRows]
    .sort((a, b) => a.endDate.localeCompare(b.endDate) || b.remaining - a.remaining)
    .slice(0, 5)
    .map((row) => ({ ...row, daysRemaining: daysRemaining(row.endDate, today) }));

  return {
    totalCustomers: customers.length,
    activeSubscriptions: activeRows.length,
    expiringWithin7Days: expiringWithin7Days.length,
    totalValue,
    totalPaid,
    totalRemaining,
    topRemaining,
    nearestExpiry,
  };
}
