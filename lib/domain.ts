export type SubscriptionStatus = "UNPAID" | "PARTIAL" | "PAID";

export type PaymentAmount = { amount: number | string };

export function totalPaid(payments: PaymentAmount[]): number {
  return payments.reduce((total, payment) => total + Number(payment.amount), 0);
}

export function paymentSummary(
  subscriptionAmount: number | string,
  payments: PaymentAmount[],
): { totalPaid: number; remaining: number; status: SubscriptionStatus } {
  const amount = Number(subscriptionAmount);
  const paid = totalPaid(payments);
  const remaining = Math.max(amount - paid, 0);
  const status: SubscriptionStatus = paid <= 0 ? "UNPAID" : remaining > 0 ? "PARTIAL" : "PAID";

  return { totalPaid: paid, remaining, status };
}

export function assertValidAmount(value: FormDataEntryValue | null, label: string): number {
  const amount = Number(value);
  if (value === null || value === "" || !Number.isFinite(amount) || amount < 0) {
    throw new Error(`${label} يجب أن يكون صفرًا أو أكثر`);
  }
  return amount;
}

export function assertValidDates(startDate: string, endDate: string): void {
  if (!startDate || !endDate || endDate < startDate) {
    throw new Error("تاريخ النهاية يجب ألا يسبق تاريخ البداية");
  }
}
