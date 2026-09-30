export type SubscriptionStatus = "UNPAID" | "PARTIAL" | "PAID";
export type ReminderStatus = "PENDING" | "DUE" | "SENT";

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

export function parseReminderDays(value: string): number[] {
  const normalized = value.replace(/[٠-٩]/g, (digit) => String("٠١٢٣٤٥٦٧٨٩".indexOf(digit)));
  const parts = normalized.split(/[,،]/).map((part) => part.trim());
  if (parts.length < 1 || parts.length > 3) throw new Error("اختر من تنبيه واحد إلى ثلاثة تنبيهات");
  const days = parts.map(Number);
  if (days.some((day, index) => !/^\d+$/.test(parts[index]) || !Number.isSafeInteger(day) || day <= 0 || day > 2147483647)) {
    throw new Error("أيام التنبيه يجب أن تكون أرقامًا صحيحة موجبة");
  }
  if (new Set(days).size !== days.length) throw new Error("لا يمكن تكرار نفس عدد الأيام");
  return days.sort((a, b) => b - a);
}

export function reminderDate(endDate: string, daysBefore: number): string {
  const date = new Date(`${endDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - daysBefore);
  return date.toISOString().slice(0, 10);
}

export function daysRemaining(endDate: string, today: string): number {
  return Math.max(0, Math.ceil((Date.parse(`${endDate}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000));
}

export function effectiveSubscriptionStatus(endDate: string, today: string): "ACTIVE" | "EXPIRED" {
  return endDate < today ? "EXPIRED" : "ACTIVE";
}

export function utcToday(): string {
  return new Date().toISOString().slice(0, 10);
}

export function canSendReminder(
  reminder: { status: ReminderStatus; scheduled_date: string; subscriptions: { status: string; end_date: string } },
  today: string = utcToday(),
): boolean {
  return reminder.status === "DUE"
    && reminder.scheduled_date.slice(0, 10) <= today
    && reminder.subscriptions.status.toLowerCase() === "active"
    && effectiveSubscriptionStatus(reminder.subscriptions.end_date, today) === "ACTIVE";
}

export function reminderMessage(customerName: string, subscriptionName: string, endDate: string, today: string = utcToday()): string {
  const remaining = daysRemaining(endDate, today);
  return `مرحبًا ${customerName}، نذكرك بأن اشتراكك ${subscriptionName} سينتهي بتاريخ ${endDate}. متبقي ${remaining} أيام على انتهاء الاشتراك.`;
}

export function currentSubscription<T extends { start_date: string; end_date: string; status: string; created_at: string }>(
  subscriptions: T[], today: string,
): T | undefined {
  const sorted = [...subscriptions].sort((a, b) =>
    b.start_date.localeCompare(a.start_date) || b.created_at.localeCompare(a.created_at),
  );
  return sorted.find((item) => item.status.toLowerCase() === "active" && item.start_date <= today && item.end_date >= today)
    ?? sorted.find((item) => item.start_date <= today)
    ?? sorted[sorted.length - 1];
}
