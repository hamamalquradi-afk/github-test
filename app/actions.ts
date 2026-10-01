"use client";

import { assertValidAmount, assertValidDates, parseReminderDays } from "../lib/domain";
import {
  configureReminders as saveReminderConfiguration,
  createCustomer,
  createPayment,
  deleteCustomer as removeCustomerRecord,
  createSubscription,
  markReminderSent as saveReminderSent,
  updateCustomer as saveCustomer,
} from "../lib/repository/indexeddb";

const text = (data: FormData, key: string) => String(data.get(key) ?? "").trim();
const required = (data: FormData, key: string, label: string) => {
  const value = text(data, key);
  if (!value) throw new Error(`${label} مطلوب`);
  return value;
};

export async function addCustomer(data: FormData): Promise<string> {
  return createCustomer({ name: required(data, "name", "الاسم"), phone: required(data, "phone", "الهاتف"), notes: text(data, "notes") || null });
}

export async function updateCustomer(data: FormData): Promise<void> {
  await saveCustomer(required(data, "customer_id", "العميل"), {
    name: required(data, "name", "الاسم"), phone: required(data, "phone", "الهاتف"), notes: text(data, "notes") || null,
  });
}

export async function addSubscription(data: FormData): Promise<string> {
  const startDate = required(data, "start_date", "تاريخ البداية");
  const endDate = required(data, "end_date", "تاريخ النهاية");
  assertValidDates(startDate, endDate);
  return createSubscription({
    customer_id: required(data, "customer_id", "العميل"), subscription_name: required(data, "subscription_name", "اسم الاشتراك"),
    amount: assertValidAmount(data.get("amount"), "المبلغ"), start_date: startDate, end_date: endDate,
    reminder_days: parseReminderDays(required(data, "reminder_days", "أيام التنبيه")),
  });
}

export async function renewSubscription(data: FormData): Promise<string> {
  return addSubscription(data);
}

export async function configureReminders(data: FormData): Promise<void> {
  await saveReminderConfiguration(
    required(data, "subscription_id", "الاشتراك"), required(data, "customer_id", "العميل"),
    parseReminderDays(required(data, "reminder_days", "أيام التنبيه")),
  );
}

export async function markReminderSent(reminderId: string): Promise<void> {
  await saveReminderSent(reminderId);
}

export async function addPayment(data: FormData): Promise<string> {
  return createPayment({
    customer_id: required(data, "customer_id", "العميل"), subscription_id: required(data, "subscription_id", "الاشتراك"),
    amount: assertValidAmount(data.get("amount"), "الدفعة"), payment_date: required(data, "payment_date", "تاريخ الدفعة"), notes: text(data, "notes") || null,
  });
}


export async function deleteCustomer(customerId: string): Promise<void> {
  await removeCustomerRecord(customerId);
}
