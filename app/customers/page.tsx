"use client";

import Link from "next/link";
import { FormEvent, Suspense, useEffect, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { addCustomer, deleteCustomer } from "../actions";
import {
  buildCustomerRows,
  CUSTOMER_FILTER_LABELS,
  formatAmount,
  type CustomerFilter,
  type CustomerSort,
} from "../../lib/customer-management";
import { getCustomerDeletionSummary, getCustomers, type CustomerDeletionSummary } from "../../lib/data";
import { createLatestSearchRunner, customerSearchQuery } from "../../lib/customer-search";
import { localToday } from "../../lib/local-date";
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
  const [filter, setFilter] = useState<CustomerFilter>("all");
  const [sort, setSort] = useState<CustomerSort>("name");
  const [error, setError] = useState(urlError);
  const [loading, setLoading] = useState(true);
  const [deleteTarget, setDeleteTarget] = useState<CustomerDeletionSummary | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const searchRunner = useRef(createLatestSearchRunner(getCustomers));
  const deleteDialogRef = useRef<HTMLDialogElement>(null);
  const today = localToday();

  async function loadCustomers(query: string) {
    setLoading(true);
    const result = await searchRunner.current(query);
    if (!result.current) return;
    if (result.error) setError(result.error instanceof Error ? result.error.message : "تعذر قراءة العملاء");
    else setCustomers(result.value ?? []);
    setLoading(false);
  }

  useEffect(() => { setSearchValue(q); }, [q]);
  useEffect(() => { setError(urlError); }, [urlError]);
  useEffect(() => { void loadCustomers(q); }, [q]);

  useEffect(() => {
    const dialog = deleteDialogRef.current;
    if (!dialog) return;
    if (deleteTarget && !dialog.open) dialog.showModal();
    if (!deleteTarget && dialog.open) dialog.close();
  }, [deleteTarget]);

  const rows = useMemo(
    () => buildCustomerRows(customers, today, filter, sort),
    [customers, filter, sort, today],
  );

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

  async function prepareDelete(customerId: string) {
    setError("");
    try {
      const summary = await getCustomerDeletionSummary(customerId);
      if (!summary) throw new Error("العميل غير موجود");
      setDeleteTarget(summary);
    } catch (value) {
      setError(value instanceof Error ? value.message : "تعذر تجهيز حذف العميل");
    }
  }

  async function confirmDelete() {
    if (!deleteTarget || deleteBusy) return;
    setDeleteBusy(true);
    setError("");
    try {
      await deleteCustomer(deleteTarget.customer_id);
      setDeleteTarget(null);
      await loadCustomers(q);
    } catch (value) {
      setError(value instanceof Error ? value.message : "تعذر حذف العميل");
    } finally {
      setDeleteBusy(false);
    }
  }

  return <>
    <section className="page-heading">
      <div><span className="eyebrow">إدارة العملاء</span><h1>العملاء</h1><p>بحث وفرز ومتابعة التحصيل من جدول إدارة واحد.</p></div>
      <span className="section-count">{rows.length} من {customers.length}</span>
    </section>

    {error && <p className="notice error" role="alert">{error}</p>}

    <form className="search" role="search" onSubmit={submitSearch}>
      <label className="sr-only" htmlFor="customer-search">بحث بالاسم أو رقم الهاتف</label>
      <input id="customer-search" name="q" value={searchValue} onChange={(event) => setSearchValue(event.target.value)} placeholder="بحث بالاسم أو رقم الهاتف"/>
      <button type="submit">بحث</button>
    </form>

    <section className="customer-controls" aria-label="فلترة وفرز العملاء">
      <div className="filter-list" role="group" aria-label="تصفية العملاء">
        {(Object.keys(CUSTOMER_FILTER_LABELS) as CustomerFilter[]).map((key) =>
          <button key={key} type="button" className={filter === key ? "filter-chip active" : "filter-chip"} aria-pressed={filter === key} onClick={() => setFilter(key)}>
            {CUSTOMER_FILTER_LABELS[key]}
          </button>
        )}
      </div>
      <label className="sort-control">الفرز
        <select value={sort} onChange={(event) => setSort(event.target.value as CustomerSort)}>
          <option value="name">الاسم</option>
          <option value="remaining">المتبقي الأعلى</option>
          <option value="end_date">تاريخ الانتهاء الأقرب</option>
        </select>
      </label>
    </section>

    <details className="card action-card">
      <summary>+ إضافة عميل جديد</summary>
      <form onSubmit={submitCustomer} className="form-grid">
        <label>اسم العميل<input name="name" required /></label>
        <label>رقم الهاتف<input name="phone" required inputMode="tel" /></label>
        <label className="wide">ملاحظات<textarea name="notes" rows={3} /></label>
        <button className="primary">حفظ العميل</button>
      </form>
    </details>

    {loading && <p className="empty-state">جارٍ تحميل البيانات المحلية…</p>}

    <p className="table-scroll-hint">يمكن تمرير الجدول أفقيًا على الشاشات الصغيرة لعرض جميع الأعمدة.</p>
    <div className="customer-table-shell" tabIndex={0} aria-label="جدول العملاء — يمكن تمريره أفقيًا">
      <table className="customer-management-table">
        <thead>
          <tr>
            <th className="sticky-name">العميل</th>
            <th>الاشتراك الحالي</th>
            <th>رقم الهاتف</th>
            <th>قيمة الاشتراك</th>
            <th>المدفوع</th>
            <th>المتبقي</th>
            <th>تاريخ الانتهاء</th>
            <th>الحالة</th>
            <th>الإجراءات</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => <tr key={row.customer.id}>
            <td className="sticky-name"><Link href={`/customers/${row.customer.id}`}>{row.customer.name}</Link></td>
            <td>{row.current?.subscription_name ?? "—"}</td>
            <td dir="ltr">{row.customer.phone}</td>
            <td className="amount">{row.current ? formatAmount(row.subscriptionAmount) : "—"}</td>
            <td className="amount">{row.current ? formatAmount(row.totalPaid) : "—"}</td>
            <td className="amount">{row.current ? formatAmount(row.remaining) : "—"}</td>
            <td>{row.endDate || "—"}</td>
            <td><span className={`badge status-${row.status.toLowerCase()}`}>{row.statusLabel}</span></td>
            <td>
              <details className="row-actions">
                <summary aria-label={`إجراءات العميل ${row.customer.name}`}>إجراءات</summary>
                <div className="row-actions-menu">
                  <Link href={`/customers/${row.customer.id}`}>فتح</Link>
                  <Link href={`/customers/${row.customer.id}?edit=1#edit-customer`}>تعديل</Link>
                  {row.current && row.remaining > 0 && <Link href={`/customers/${row.customer.id}?action=payment#payment-action`}>إضافة دفعة</Link>}
                  {row.current && <Link href={`/customers/${row.customer.id}?action=renew#renew-action`}>تجديد</Link>}
                  <button type="button" className="destructive-link" onClick={() => void prepareDelete(row.customer.id)}>حذف</button>
                </div>
              </details>
            </td>
          </tr>)}
          {!loading && !rows.length && <tr><td colSpan={9} className="empty">لا توجد نتائج مطابقة للبحث والفلاتر الحالية.</td></tr>}
        </tbody>
      </table>
    </div>

    <dialog ref={deleteDialogRef} className="delete-dialog" onCancel={() => setDeleteTarget(null)} onClose={() => { if (!deleteBusy) setDeleteTarget(null); }}>
      {deleteTarget && <div>
        <span className="eyebrow danger-text">حذف نهائي</span>
        <h2>حذف العميل «{deleteTarget.customer_name}»؟</h2>
        <p className="delete-warning">سيتم حذف السجل المالي والتاريخي لهذا العميل نهائيًا. لا يمكن التراجع عن هذه العملية.</p>
        <dl className="delete-summary">
          <div><dt>الاشتراكات</dt><dd>{deleteTarget.subscriptions}</dd></div>
          <div><dt>الدفعات</dt><dd>{deleteTarget.payments}</dd></div>
          <div><dt>التنبيهات</dt><dd>{deleteTarget.reminders}</dd></div>
        </dl>
        <div className="dialog-actions">
          <button type="button" onClick={() => setDeleteTarget(null)} disabled={deleteBusy}>إلغاء</button>
          <button type="button" className="danger-button" onClick={() => void confirmDelete()} disabled={deleteBusy}>
            {deleteBusy ? "جارٍ الحذف…" : "حذف العميل نهائيًا"}
          </button>
        </div>
      </div>}
    </dialog>
  </>;
}
