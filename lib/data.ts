import { getSupabase } from "./supabase";
import type { Customer, ReminderView } from "./types";

const customerSelection = `
  id, name, phone, notes, created_at,
  subscriptions (
    id, customer_id, subscription_name, amount, start_date, end_date, status, created_at,
    payments (id, amount, payment_date, notes)
  )
`;

function sortHistory(customer: Customer): Customer {
  customer.subscriptions = (customer.subscriptions ?? []).sort((a, b) =>
    b.start_date.localeCompare(a.start_date) || b.created_at.localeCompare(a.created_at),
  );
  for (const subscription of customer.subscriptions) {
    subscription.payments = (subscription.payments ?? []).sort((a, b) =>
      b.payment_date.localeCompare(a.payment_date),
    );
  }
  return customer;
}

export async function getCustomers(search = ""): Promise<Customer[]> {
  const supabase = getSupabase();
  let query = supabase.from("customers").select(customerSelection).order("created_at", { ascending: false });
  const term = search.trim().replace(/[%_,()]/g, "");
  if (term) query = query.or(`name.ilike.%${term}%,phone.ilike.%${term}%`);
  const { data, error } = await query;
  if (error) throw new Error(error.message);
  return ((data ?? []) as unknown as Customer[]).map(sortHistory);
}

export async function getCustomer(id: string): Promise<Customer | null> {
  const { data, error } = await getSupabase()
    .from("customers")
    .select(customerSelection)
    .eq("id", id)
    .maybeSingle();
  if (error) throw new Error(error.message);
  return data ? sortHistory(data as unknown as Customer) : null;
}

export async function getReminders(today: string): Promise<ReminderView[]> {
  const supabase = getSupabase();
  const { error: refreshError } = await supabase.rpc("refresh_due_reminders");
  if (refreshError) throw new Error(refreshError.message);
  const { data, error } = await supabase
    .from("reminders")
    .select("id, scheduled_date, sent_at, status, subscriptions!inner(subscription_name, end_date, status, customers!inner(name, phone))")
    .eq("subscriptions.status", "active")
    .gte("subscriptions.end_date", today)
    .order("scheduled_date", { ascending: true });
  if (error) throw new Error(error.message);
  return (data ?? []) as unknown as ReminderView[];
}

export async function getCustomerReminders(customerId: string): Promise<ReminderView[]> {
  const { data, error } = await getSupabase()
    .from("reminders")
    .select("id, scheduled_date, sent_at, status, subscriptions!inner(subscription_name, end_date, status, customers!inner(name, phone))")
    .eq("customer_id", customerId)
    .order("scheduled_date", { ascending: false });
  if (error) throw new Error(error.message);
  return (data ?? []) as unknown as ReminderView[];
}
