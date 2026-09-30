"use client";

import Link from "next/link";
import { FormEvent, Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { addCustomer } from "../actions";
import { currentSubscription, effectiveSubscriptionStatus, paymentSummary } from "../../lib/domain";
import { getCustomers } from "../../lib/data";
import { createLatestSearchRunner, customerSearchQuery } from "../../lib/customer-search";
import type { Customer } from "../../lib/types";

export default function CustomersPage() {
  return <Suspense fallback={<p className="empty-state">جارٍ تحميل البيانات المحلية…</p>}><CustomersContent /></Suspense>;
}

function CustomersContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const q = customerSearchQuery(searchParams);
  const urlError = searchParams.get("error") ?? "";
  const [searchValue, setSearchValue] = useState(q);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [error, setError] = useState(urlError);
  const [loading, setLoading] = useState(true);
  const searchRunner = useRef(createLatestSearchRunner(getCustomers));
  const today = new Date().toISOString().slice(0, 10);

  useEffect(() => { setSearchValue(q); }, [q]);
  useEffect(() => { setError(urlError); }, [urlError]);
  useEffect(() => {
    setLoading(true);
    void searchRunner.current(q).then((result) => {
      if (!result.current) return;
      if (result.error) setError(result.error instanceof Error ? result.error.message : "تعذر قراءة العملاء");
      else setCustomers(result.value ?? []);
      setLoading(false);
    });
  }, [q]);

  async function submitCustomer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const id = await addCustomer(new FormData(event.currentTarget));
      router.push(`/customers/${id}?success=${encodeURIComponent("تمت إضافة العميل")}`);
    } catch (value) {
      const message = value instanceof Error ? value.message : "تعذر إضافة العميل";
      setError(message);
      router.replace(`/customers?error=${encodeURIComponent(message)}`);
    }
  }

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = searchValue.trim();
    router.push(value ? `/customers?q=${encodeURIComponent(value)}` : "/customers");
  }

  return <>
    <section className="page-heading"><div><span className="eyebrow">إدارة العملاء</span><h1>العملاء</h1><p>ابحث، راجع الاشتراك الحالي، وافتح سجل العميل.</p></div><span className="section-count">{customers.length} عميل</span></section>
    {error && <p className="notice error" role="alert">{error}</p>}
    <form className="search" role="search" onSubmit={submitSearch}><label className="sr-only" htmlFor="customer-search">بحث بالاسم أو رقم الهاتف</label><input id="customer-search" name="q" value={searchValue} onChange={(event) => setSearchValue(event.target.value)} placeholder="بحث بالاسم أو رقم الهاتف"/><button type="submit">بحث</button></form>
    <details className="card action-card"><summary>+ إضافة عميل جديد</summary><form onSubmit={submitCustomer} className="form-grid"><label>اسم العميل<input name="name" required /></label><label>رقم الهاتف<input name="phone" required inputMode="tel" /></label><label className="wide">ملاحظات<textarea name="notes" rows={3} /></label><button className="primary">حفظ العميل</button></form></details>
    {loading && <p className="empty-state">جارٍ تحميل البيانات المحلية…</p>}
    <div className="customer-table table-wrap"><table><thead><tr><th>العميل</th><th>الهاتف</th><th>الاشتراك الحالي</th><th>المدفوع</th><th>المتبقي</th><th>النهاية</th><th>الحالة</th></tr></thead><tbody>
      {customers.map((customer) => { const current = currentSubscription(customer.subscriptions, today); const summary = current ? paymentSummary(current.amount, current.payments) : null; const status = current ? effectiveSubscriptionStatus(current.end_date, today) : null; return <tr key={customer.id}><td><Link href={`/customers/${customer.id}`}>{customer.name}</Link></td><td dir="ltr">{customer.phone}</td><td>{current?.subscription_name ?? "—"}</td><td>{summary?.totalPaid.toFixed(2) ?? "—"}</td><td>{summary?.remaining.toFixed(2) ?? "—"}</td><td>{current?.end_date ?? "—"}</td><td>{status === "EXPIRED" ? <span className="badge expired">EXPIRED</span> : summary ? <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span> : "—"}</td></tr>; })}
      {!loading && !customers.length && <tr><td colSpan={7} className="empty">لا توجد نتائج</td></tr>}
    </tbody></table></div>
    <div className="customer-cards">{customers.map((customer) => { const current = currentSubscription(customer.subscriptions, today); const summary = current ? paymentSummary(current.amount, current.payments) : null; const status = current ? effectiveSubscriptionStatus(current.end_date, today) : null; return <article className="customer-card" key={customer.id}><div className="customer-card-head"><div><Link href={`/customers/${customer.id}`}>{customer.name}</Link><span dir="ltr">{customer.phone}</span></div>{status === "EXPIRED" ? <span className="badge expired">EXPIRED</span> : summary && <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span>}</div><dl className="customer-facts"><div><dt>الاشتراك</dt><dd>{current?.subscription_name ?? "لا يوجد"}</dd></div><div><dt>المدفوع</dt><dd>{summary?.totalPaid.toFixed(2) ?? "—"}</dd></div><div><dt>المتبقي</dt><dd>{summary?.remaining.toFixed(2) ?? "—"}</dd></div><div><dt>الانتهاء</dt><dd>{current?.end_date ?? "—"}</dd></div></dl><Link className="card-action" href={`/customers/${customer.id}`}>فتح تفاصيل العميل</Link></article>; })}{!loading && !customers.length && <p className="empty-state">لا توجد نتائج مطابقة.</p>}</div>
  </>;
}
