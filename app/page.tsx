import Link from "next/link";
import { addCustomer } from "./actions";
import { currentSubscription, effectiveSubscriptionStatus, paymentSummary } from "../lib/domain";
import { getCustomers, getReminders } from "../lib/data";
import { ReminderList } from "./reminder-list";

export const dynamic = "force-dynamic";

export default async function Home({ searchParams }: { searchParams: Promise<{ q?: string; error?: string }> }) {
  const params = await searchParams;
  const today = new Date().toISOString().slice(0, 10);
  const [customers, reminders] = await Promise.all([getCustomers(params.q), getReminders(today)]);
  return (
    <>
      <section className="title-row"><div><h1>العملاء</h1><p>العملاء واشتراكاتهم الحالية</p></div></section>
      {params.error && <p className="notice error">{params.error}</p>}
      <form className="search" method="get"><input name="q" defaultValue={params.q} placeholder="بحث بالاسم أو رقم الهاتف"/><button>بحث</button></form>
      <details className="card"><summary>+ إضافة عميل</summary><form action={addCustomer} className="form-grid"><label>اسم العميل<input name="name" required /></label><label>رقم الهاتف<input name="phone" required inputMode="tel" /></label><label className="wide">ملاحظات<textarea name="notes" rows={2} /></label><button className="primary">حفظ العميل</button></form></details>
      <div className="table-wrap"><table><thead><tr><th>العميل</th><th>الهاتف</th><th>الاشتراك الحالي</th><th>القيمة</th><th>البداية</th><th>النهاية</th><th>المدفوع</th><th>المتبقي</th><th>الحالة</th></tr></thead><tbody>
        {customers.map((customer) => { const current = currentSubscription(customer.subscriptions, today); const summary = current ? paymentSummary(current.amount, current.payments) : null; const status = current ? effectiveSubscriptionStatus(current.end_date, today) : null; return <tr key={customer.id}><td><Link href={`/customers/${customer.id}`}>{customer.name}</Link></td><td dir="ltr">{customer.phone}</td><td>{current?.subscription_name ?? "—"}</td><td>{current ? Number(current.amount).toFixed(2) : "—"}</td><td>{current?.start_date ?? "—"}</td><td>{current?.end_date ?? "—"}</td><td>{summary?.totalPaid.toFixed(2) ?? "—"}</td><td>{summary?.remaining.toFixed(2) ?? "—"}</td><td>{status === "EXPIRED" ? <span className="badge expired">EXPIRED</span> : summary ? <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span> : "—"}</td></tr>; })}
        {!customers.length && <tr><td colSpan={9} className="empty">لا توجد نتائج</td></tr>}
      </tbody></table></div>
      <ReminderList reminders={reminders} today={today}/>
    </>
  );
}
