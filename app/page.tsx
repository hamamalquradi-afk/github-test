import Link from "next/link";
import { addCustomer } from "./actions";
import { daysRemaining, effectiveSubscriptionStatus, paymentSummary, reminderMessage } from "../lib/domain";
import { getCustomers, getReminders } from "../lib/data";
import { SendMessageButton } from "./send-message-button";

export const dynamic = "force-dynamic";

export default async function Home({ searchParams }: { searchParams: Promise<{ q?: string; error?: string }> }) {
  const params = await searchParams;
  const [customers, reminders] = await Promise.all([getCustomers(params.q), getReminders()]);
  const today = new Date().toISOString().slice(0, 10);
  return (
    <>
      <section className="title-row"><div><h1>العملاء</h1><p>العملاء واشتراكاتهم الحالية</p></div></section>
      {params.error && <p className="notice error">{params.error}</p>}
      <form className="search" method="get"><input name="q" defaultValue={params.q} placeholder="بحث بالاسم أو رقم الهاتف"/><button>بحث</button></form>
      <details className="card"><summary>+ إضافة عميل</summary><form action={addCustomer} className="form-grid"><label>اسم العميل<input name="name" required /></label><label>رقم الهاتف<input name="phone" required inputMode="tel" /></label><label className="wide">ملاحظات<textarea name="notes" rows={2} /></label><button className="primary">حفظ العميل</button></form></details>
      <div className="table-wrap"><table><thead><tr><th>العميل</th><th>الهاتف</th><th>الاشتراك الحالي</th><th>القيمة</th><th>البداية</th><th>النهاية</th><th>المدفوع</th><th>المتبقي</th><th>الحالة</th></tr></thead><tbody>
        {customers.map((customer) => { const current = customer.subscriptions[0]; const summary = current ? paymentSummary(current.amount, current.payments) : null; const status = current ? effectiveSubscriptionStatus(current.end_date, today) : null; return <tr key={customer.id}><td><Link href={`/customers/${customer.id}`}>{customer.name}</Link></td><td dir="ltr">{customer.phone}</td><td>{current?.subscription_name ?? "—"}</td><td>{current ? Number(current.amount).toFixed(2) : "—"}</td><td>{current?.start_date ?? "—"}</td><td>{current?.end_date ?? "—"}</td><td>{summary?.totalPaid.toFixed(2) ?? "—"}</td><td>{summary?.remaining.toFixed(2) ?? "—"}</td><td>{status === "EXPIRED" ? <span className="badge expired">EXPIRED</span> : summary ? <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span> : "—"}</td></tr>; })}
        {!customers.length && <tr><td colSpan={9} className="empty">لا توجد نتائج</td></tr>}
      </tbody></table></div>
      <section className="card"><h2>تنبيهات اليوم</h2><ReminderList reminders={reminders.filter((item) => item.status === "DUE")} today={today}/><h2>التنبيهات القادمة</h2><ReminderList reminders={reminders.filter((item) => item.status === "PENDING")} today={today}/></section>
    </>
  );
}

function ReminderList({ reminders, today }: { reminders: Awaited<ReturnType<typeof getReminders>>; today: string }) {
  if (!reminders.length) return <p className="empty">لا توجد تنبيهات</p>;
  return <div className="reminders">{reminders.map((reminder) => { const subscription = reminder.subscriptions; const customer = subscription.customers; const remaining = daysRemaining(subscription.end_date, today); return <article className="reminder" key={reminder.id}><div><strong>{customer.name}</strong><p dir="ltr">{customer.phone}</p><p>{subscription.subscription_name} · ينتهي {subscription.end_date} · متبقي {remaining} أيام</p></div><SendMessageButton reminderId={reminder.id} message={reminderMessage(customer.name, subscription.subscription_name, subscription.end_date, remaining)}/></article>; })}</div>;
}
