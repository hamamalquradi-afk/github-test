import Link from "next/link";
import { addCustomer } from "../actions";
import { currentSubscription, effectiveSubscriptionStatus, paymentSummary } from "../../lib/domain";
import { getCustomers } from "../../lib/data";

export const dynamic = "force-dynamic";

export default async function CustomersPage({ searchParams }: { searchParams: Promise<{ q?: string; error?: string }> }) {
  const params = await searchParams;
  const today = new Date().toISOString().slice(0, 10);
  const customers = await getCustomers(params.q);

  return (
    <>
      <section className="page-heading">
        <div><span className="eyebrow">إدارة العملاء</span><h1>العملاء</h1><p>ابحث، راجع الاشتراك الحالي، وافتح سجل العميل.</p></div>
        <span className="section-count">{customers.length} عميل</span>
      </section>
      {params.error && <p className="notice error">{params.error}</p>}
      <form className="search" method="get" role="search">
        <label className="sr-only" htmlFor="customer-search">بحث بالاسم أو رقم الهاتف</label>
        <input id="customer-search" name="q" defaultValue={params.q} placeholder="بحث بالاسم أو رقم الهاتف"/>
        <button type="submit">بحث</button>
      </form>
      <details className="card action-card">
        <summary>+ إضافة عميل جديد</summary>
        <form action={addCustomer} className="form-grid">
          <label>اسم العميل<input name="name" required /></label>
          <label>رقم الهاتف<input name="phone" required inputMode="tel" /></label>
          <label className="wide">ملاحظات<textarea name="notes" rows={3} /></label>
          <button className="primary">حفظ العميل</button>
        </form>
      </details>

      <div className="customer-table table-wrap">
        <table><thead><tr><th>العميل</th><th>الهاتف</th><th>الاشتراك الحالي</th><th>المدفوع</th><th>المتبقي</th><th>النهاية</th><th>الحالة</th></tr></thead><tbody>
          {customers.map((customer) => {
            const current = currentSubscription(customer.subscriptions, today);
            const summary = current ? paymentSummary(current.amount, current.payments) : null;
            const status = current ? effectiveSubscriptionStatus(current.end_date, today) : null;
            return <tr key={customer.id}>
              <td><Link href={`/customers/${customer.id}`}>{customer.name}</Link></td>
              <td dir="ltr">{customer.phone}</td><td>{current?.subscription_name ?? "—"}</td>
              <td>{summary?.totalPaid.toFixed(2) ?? "—"}</td><td>{summary?.remaining.toFixed(2) ?? "—"}</td>
              <td>{current?.end_date ?? "—"}</td>
              <td>{status === "EXPIRED" ? <span className="badge expired">EXPIRED</span> : summary ? <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span> : "—"}</td>
            </tr>;
          })}
          {!customers.length && <tr><td colSpan={7} className="empty">لا توجد نتائج</td></tr>}
        </tbody></table>
      </div>

      <div className="customer-cards">
        {customers.map((customer) => {
          const current = currentSubscription(customer.subscriptions, today);
          const summary = current ? paymentSummary(current.amount, current.payments) : null;
          const status = current ? effectiveSubscriptionStatus(current.end_date, today) : null;
          return <article className="customer-card" key={customer.id}>
            <div className="customer-card-head"><div><Link href={`/customers/${customer.id}`}>{customer.name}</Link><span dir="ltr">{customer.phone}</span></div>{status === "EXPIRED" ? <span className="badge expired">EXPIRED</span> : summary && <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span>}</div>
            <dl className="customer-facts">
              <div><dt>الاشتراك</dt><dd>{current?.subscription_name ?? "لا يوجد"}</dd></div>
              <div><dt>المدفوع</dt><dd>{summary?.totalPaid.toFixed(2) ?? "—"}</dd></div>
              <div><dt>المتبقي</dt><dd>{summary?.remaining.toFixed(2) ?? "—"}</dd></div>
              <div><dt>الانتهاء</dt><dd>{current?.end_date ?? "—"}</dd></div>
            </dl>
            <Link className="card-action" href={`/customers/${customer.id}`}>فتح تفاصيل العميل</Link>
          </article>;
        })}
        {!customers.length && <p className="empty-state">لا توجد نتائج مطابقة.</p>}
      </div>
    </>
  );
}
