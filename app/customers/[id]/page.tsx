"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { addPayment, addSubscription, configureReminders, renewSubscription, updateCustomer } from "../../actions";
import { CUSTOMER_STATUS_LABELS, customerRowView, formatAmount } from "../../../lib/customer-management";
import { effectiveSubscriptionStatus, paymentSummary } from "../../../lib/domain";
import { localToday } from "../../../lib/local-date";
import { getCustomer, getCustomerReminders } from "../../../lib/data";
import type { Customer, ReminderView, Subscription } from "../../../lib/types";

type Mutation = (data: FormData) => Promise<unknown>;

export default function CustomerPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [reminders, setReminders] = useState<ReminderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const paymentRef = useRef<HTMLDetailsElement>(null);
  const renewRef = useRef<HTMLDetailsElement>(null);
  const editRef = useRef<HTMLDetailsElement>(null);

  const load = useCallback(async () => {
    try {
      const [customerRow, reminderRows] = await Promise.all([getCustomer(id), getCustomerReminders(id)]);
      setCustomer(customerRow); setReminders(reminderRows);
    } catch (value) { setError(value instanceof Error ? value.message : "تعذر قراءة بيانات العميل"); }
    finally { setLoading(false); }
  }, [id]);

  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    setError(query.get("error") ?? "");
    setSuccess(query.get("success") ?? "");
    void load();
  }, [load]);

  useEffect(() => {
    if (!customer) return;
    const query = new URLSearchParams(window.location.search);
    const target = query.get("edit") === "1"
      ? editRef.current
      : query.get("action") === "payment"
        ? paymentRef.current
        : query.get("action") === "renew"
          ? renewRef.current
          : null;
    if (target) {
      target.open = true;
      requestAnimationFrame(() => target.scrollIntoView({ block: "center", behavior: "smooth" }));
    }
  }, [customer]);

  const mutate = (action: Mutation, message: string) => async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError(""); setSuccess("");
    try { await action(new FormData(event.currentTarget)); setSuccess(message); await load(); }
    catch (value) { setError(value instanceof Error ? value.message : "تعذر حفظ البيانات المحلية"); }
  };

  if (loading) return <p className="empty-state">جارٍ تحميل البيانات المحلية…</p>;
  if (!customer) return <><Link href="/customers" className="back">← قائمة العملاء</Link><p className="notice error">العميل غير موجود أو تعذر قراءة سجله المحلي.</p></>;

  const date = localToday();
  const view = customerRowView(customer, date);
  const current = view.current;
  const expired = view.status === "EXPIRED";
  const summary = current ? paymentSummary(current.amount, current.payments) : null;

  return <>
    <Link href="/customers" className="back">← قائمة العملاء</Link>
    <section className="customer-hero">
      <div className="customer-identity"><span className="eyebrow">بيانات العميل</span><h1>{customer.name}</h1><p dir="ltr">{customer.phone}</p>{customer.notes && <p className="customer-notes">{customer.notes}</p>}</div>
      <span className={`badge status-${view.status.toLowerCase()}`}>{view.statusLabel}</span>
    </section>
    {error && <p className="notice error" role="alert">{error}</p>}{success && <p className="notice success">{success}</p>}

    {current ? <section className="current-subscription">
      <div className="section-heading"><div><span className="eyebrow">الاشتراك الحالي</span><h2>{current.subscription_name}</h2></div></div>
      <div className="detail-stats">
        <Stat label="المتبقي" value={formatAmount(summary!.remaining)}/>
        <Stat label="تاريخ الانتهاء" value={current.end_date}/>
        <Stat label="إجمالي المدفوع" value={formatAmount(summary!.totalPaid)}/>
        <Stat label="قيمة الاشتراك" value={formatAmount(current.amount)}/>
      </div>
    </section> : <section className="card"><h2>إضافة اشتراك</h2><SubscriptionForm onSubmit={mutate(addSubscription, "تمت إضافة الاشتراك")} customerId={id}/></section>}

    <section className="section-block">
      <div className="section-heading"><div><span className="eyebrow">إجراءات سريعة</span><h2>إدارة العميل</h2></div></div>
      <div className="quick-actions">
        {current && summary!.remaining > 0 && <details ref={paymentRef} id="payment-action" className="card action-card">
          <summary>+ إضافة دفعة</summary>
          <form onSubmit={mutate(addPayment, "تم تسجيل الدفعة")} className="form-grid">
            <input type="hidden" name="customer_id" value={id}/><input type="hidden" name="subscription_id" value={current.id}/>
            <label>قيمة الدفعة<input name="amount" type="number" min="0" max={summary!.remaining} step="0.01" required/></label>
            <label>تاريخ الدفعة<input name="payment_date" type="date" defaultValue={date} required/></label>
            <label className="wide">ملاحظات<textarea name="notes"/></label>
            <button className="primary">تسجيل الدفعة</button>
          </form>
        </details>}
        {current && <details ref={renewRef} id="renew-action" className="card action-card">
          <summary>تجديد الاشتراك</summary>
          <SubscriptionForm onSubmit={mutate(renewSubscription, "تم إنشاء اشتراك التجديد")} customerId={id} source={current}/>
        </details>}
        <details ref={editRef} id="edit-customer" className="card action-card">
          <summary>تعديل العميل</summary>
          <form onSubmit={mutate(updateCustomer, "تم تحديث بيانات العميل")} className="form-grid">
            <input type="hidden" name="customer_id" value={id}/>
            <label>الاسم<input name="name" defaultValue={customer.name} required/></label>
            <label>الهاتف<input name="phone" defaultValue={customer.phone} required/></label>
            <label className="wide">ملاحظات<textarea name="notes" defaultValue={customer.notes ?? ""}/></label>
            <button className="primary">حفظ التعديل</button>
          </form>
        </details>
        {current && !expired && current.status.toLowerCase() === "active" && <details className="card action-card">
          <summary>إعدادات التنبيهات</summary>
          <ReminderSettings onSubmit={mutate(configureReminders, "تم حفظ إعدادات التنبيهات")} customerId={id} subscriptionId={current.id}/>
        </details>}
      </div>
    </section>

    <History title="سجل الاشتراكات">{customer.subscriptions.map((sub) => {
      const s = paymentSummary(sub.amount, sub.payments);
      const isExpired = effectiveSubscriptionStatus(sub.end_date, date) === "EXPIRED";
      const label = isExpired ? CUSTOMER_STATUS_LABELS.EXPIRED : CUSTOMER_STATUS_LABELS[s.status];
      const className = isExpired ? "status-expired" : `status-${s.status.toLowerCase()}`;
      return <article className="history-card" key={sub.id}>
        <div className="history-title"><strong>{sub.subscription_name}</strong><span className={`badge ${className}`}>{label}</span></div>
        <dl className="history-facts">
          <Fact label="الفترة" value={`${sub.start_date} — ${sub.end_date}`}/>
          <Fact label="القيمة" value={formatAmount(sub.amount)}/>
          <Fact label="المدفوع" value={formatAmount(s.totalPaid)}/>
          <Fact label="المتبقي" value={formatAmount(s.remaining)}/>
        </dl>
      </article>;
    })}</History>
    <History title="سجل الدفعات">{customer.subscriptions.flatMap((sub) => sub.payments.map((p) =>
      <article className="timeline-item" key={p.id}><div><strong>{formatAmount(p.amount)}</strong><span>{sub.subscription_name}</span></div><div><time>{p.payment_date}</time>{p.notes && <p>{p.notes}</p>}</div></article>
    ))}</History>
    <History title="سجل التنبيهات">{reminders.map((r) =>
      <article className="timeline-item" key={r.id}><div><strong>{r.subscriptions.subscription_name}</strong><span>{r.scheduled_date.slice(0,10)}</span></div><div><span className={`badge ${r.status.toLowerCase()}`}>{r.status}</span>{r.sent_at && <p>أرسل: {r.sent_at}</p>}</div></article>
    )}</History>
  </>;
}

function Stat({label,value}:{label:string;value:string}){return <div><small>{label}</small><strong>{value}</strong></div>}
function Fact({label,value}:{label:string;value:string}){return <div><dt>{label}</dt><dd>{value}</dd></div>}
function History({title,children}:{title:string;children:React.ReactNode}){return <section className="history-section"><div className="section-heading"><div><span className="eyebrow">السجل</span><h2>{title}</h2></div></div><div className={title==="سجل الاشتراكات"?"history-list":"timeline"}>{children}</div></section>}

function SubscriptionForm({ onSubmit, customerId, source }: { onSubmit: (event: FormEvent<HTMLFormElement>) => void | Promise<void>; customerId: string; source?: Pick<Subscription, "subscription_name" | "amount"> }) {
  return <form onSubmit={onSubmit} className="form-grid"><input type="hidden" name="customer_id" value={customerId}/><label>اسم الاشتراك<input name="subscription_name" defaultValue={source?.subscription_name} required/></label><label>قيمة الاشتراك<input name="amount" type="number" min="0" step="0.01" defaultValue={source ? Number(source.amount) : undefined} required/></label><label>تاريخ البداية<input name="start_date" type="date" required/></label><label>تاريخ النهاية<input name="end_date" type="date" required/></label><label className="wide">أيام التنبيه قبل الانتهاء<input name="reminder_days" placeholder="مثال: 7, 4, 1" required/></label><button className="primary">{source ? "إنشاء اشتراك التجديد" : "إضافة الاشتراك"}</button></form>;
}
function ReminderSettings({ onSubmit, customerId, subscriptionId }: { onSubmit: (event: FormEvent<HTMLFormElement>) => void | Promise<void>; customerId: string; subscriptionId: string }) {
  return <form onSubmit={onSubmit} className="form-grid"><input type="hidden" name="customer_id" value={customerId}/><input type="hidden" name="subscription_id" value={subscriptionId}/><label className="wide">أيام التنبيه قبل الانتهاء<input name="reminder_days" placeholder="مثال: 5, 3" required/></label><button className="primary">إنشاء التنبيهات</button></form>;
}
