"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { assertValidAmount, assertValidDates, paymentSummary } from "../lib/domain";
import { getSupabase } from "../lib/supabase";

const text = (data: FormData, key: string) => String(data.get(key) ?? "").trim();
const required = (data: FormData, key: string, label: string) => {
  const value = text(data, key);
  if (!value) throw new Error(`${label} مطلوب`);
  return value;
};
const target = (path: string, type: "error" | "success", message: string) =>
  `${path}?${type}=${encodeURIComponent(message)}`;

export async function addCustomer(data: FormData) {
  const supabase = getSupabase();
  const { data: customer, error } = await supabase
    .from("customers")
    .insert({ name: required(data, "name", "الاسم"), phone: required(data, "phone", "الهاتف"), notes: text(data, "notes") || null })
    .select("id")
    .single();
  if (error) redirect(target("/", "error", error.message));
  revalidatePath("/");
  redirect(target(`/customers/${customer.id}`, "success", "تمت إضافة العميل"));
}

export async function updateCustomer(data: FormData) {
  const id = required(data, "customer_id", "العميل");
  const { error } = await getSupabase().from("customers").update({
    name: required(data, "name", "الاسم"),
    phone: required(data, "phone", "الهاتف"),
    notes: text(data, "notes") || null,
  }).eq("id", id);
  if (error) redirect(target(`/customers/${id}`, "error", error.message));
  revalidatePath("/");
  revalidatePath(`/customers/${id}`);
  redirect(target(`/customers/${id}`, "success", "تم تحديث بيانات العميل"));
}

export async function addSubscription(data: FormData) {
  const customerId = required(data, "customer_id", "العميل");
  const startDate = required(data, "start_date", "تاريخ البداية");
  const endDate = required(data, "end_date", "تاريخ النهاية");
  try {
    assertValidDates(startDate, endDate);
    const { error } = await getSupabase().from("subscriptions").insert({
      customer_id: customerId,
      subscription_name: required(data, "subscription_name", "اسم الاشتراك"),
      amount: assertValidAmount(data.get("amount"), "المبلغ"),
      start_date: startDate,
      end_date: endDate,
      status: "active",
    });
    if (error) throw new Error(error.message);
  } catch (error) {
    redirect(target(`/customers/${customerId}`, "error", error instanceof Error ? error.message : "بيانات غير صحيحة"));
  }
  revalidatePath("/");
  revalidatePath(`/customers/${customerId}`);
  redirect(target(`/customers/${customerId}`, "success", "تمت إضافة الاشتراك"));
}

export async function renewSubscription(data: FormData) {
  // Renewal deliberately inserts a new row; the historical subscription is never updated.
  await addSubscription(data);
}

export async function addPayment(data: FormData) {
  const customerId = required(data, "customer_id", "العميل");
  const subscriptionId = required(data, "subscription_id", "الاشتراك");
  try {
    const amount = assertValidAmount(data.get("amount"), "الدفعة");
    const supabase = getSupabase();
    const { data: subscription, error: subscriptionError } = await supabase
      .from("subscriptions")
      .select("id, amount, payments(amount)")
      .eq("id", subscriptionId)
      .eq("customer_id", customerId)
      .single();
    if (subscriptionError || !subscription) throw new Error("الدفعة غير مرتبطة باشتراك صحيح");
    const summary = paymentSummary(subscription.amount, subscription.payments ?? []);
    if (amount > summary.remaining) throw new Error("قيمة الدفعة أكبر من المبلغ المتبقي");
    const { error } = await supabase.from("payments").insert({
      customer_id: customerId,
      subscription_id: subscriptionId,
      amount,
      payment_date: required(data, "payment_date", "تاريخ الدفعة"),
      notes: text(data, "notes") || null,
    });
    if (error) throw new Error(error.message);
  } catch (error) {
    redirect(target(`/customers/${customerId}`, "error", error instanceof Error ? error.message : "بيانات غير صحيحة"));
  }
  revalidatePath("/");
  revalidatePath(`/customers/${customerId}`);
  redirect(target(`/customers/${customerId}`, "success", "تم تسجيل الدفعة"));
}
