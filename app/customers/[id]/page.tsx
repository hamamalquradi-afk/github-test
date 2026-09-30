import { notFound } from "next/navigation";
import { addPayment, addSubscription, configureReminders, renewSubscription, updateCustomer } from "../../actions";
import { paymentSummary } from "../../../lib/domain";
import { getCustomer } from "../../../lib/data";

export const dynamic = "force-dynamic";
const today = () => new Date().toISOString().slice(0, 10);

export default async function CustomerPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ error?: string; success?: string }> }) {
  const { id } = await params;
  const query = await searchParams;
  const customer = await getCustomer(id);
  if (!customer) notFound();
  const current = customer.subscriptions[0];
  const currentSummary = current ? paymentSummary(current.amount, current.payments) : null;
  return <>
    <a href="/" className="back">← قائمة العملاء</a><section className="title-row"><div><h1>{customer.name}</h1><p dir="ltr">{customer.phone}</p></div>{currentSummary && <span className={`badge ${currentSummary.status.toLowerCase()}`}>{currentSummary.status}</span>}</section>
    {query.error && <p className="notice error">{query.error}</p>}{query.success && <p className="notice success">{query.success}</p>}
    <details className="card"><summary>تعديل بيانات العميل</summary><form action={updateCustomer} className="form-grid"><input type="hidden" name="customer_id" value={id}/><label>الاسم<input name="name" defaultValue={customer.name} required/></label><label>الهاتف<input name="phone" defaultValue={customer.phone} required/></label><label className="wide">ملاحظات<textarea name="notes" defaultValue={customer.notes ?? ""}/></label><button className="primary">حفظ التعديل</button></form></details>
    {!current ? <section className="card"><h2>إضافة اشتراك</h2><SubscriptionForm action={addSubscription} customerId={id}/></section> : <>
      <section className="stats"><div><small>الاشتراك الحالي</small><strong>{current.subscription_name}</strong></div><div><small>قيمة الاشتراك</small><strong>{Number(current.amount).toFixed(2)}</strong></div><div><small>إجمالي المدفوع</small><strong>{currentSummary!.totalPaid.toFixed(2)}</strong></div><div><small>المتبقي</small><strong>{currentSummary!.remaining.toFixed(2)}</strong></div></section>
      <div className="actions-grid"><details className="card"><summary>+ إضافة دفعة</summary><form action={addPayment} className="form-grid"><input type="hidden" name="customer_id" value={id}/><input type="hidden" name="subscription_id" value={current.id}/><label>قيمة الدفعة<input name="amount" type="number" min="0" max={currentSummary!.remaining} step="0.01" required/></label><label>تاريخ الدفعة<input name="payment_date" type="date" defaultValue={today()} required/></label><label className="wide">ملاحظات<textarea name="notes"/></label><button className="primary">تسجيل الدفعة</button></form></details><details className="card"><summary>إعدادات التنبيهات</summary><ReminderSettings action={configureReminders} customerId={id} subscriptionId={current.id}/></details><details className="card"><summary>تجديد الاشتراك</summary><SubscriptionForm action={renewSubscription} customerId={id} source={current}/></details></div>
    </>}
    <section className="card"><h2>سجل الاشتراكات</h2>{customer.subscriptions.map((subscription, index) => { const summary = paymentSummary(subscription.amount, subscription.payments); return <article className="history" key={subscription.id}><div className="history-title"><strong>{subscription.subscription_name}</strong><span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span></div><p>{subscription.start_date} — {subscription.end_date} · القيمة {Number(subscription.amount).toFixed(2)} · المدفوع {summary.totalPaid.toFixed(2)} · المتبقي {summary.remaining.toFixed(2)} {index === 0 && "· الحالي"}</p><h3>الدفعات</h3>{subscription.payments.length ? <ul>{subscription.payments.map((payment) => <li key={payment.id}>{payment.payment_date}: <strong>{Number(payment.amount).toFixed(2)}</strong>{payment.notes && ` — ${payment.notes}`}</li>)}</ul> : <p className="muted">لا توجد دفعات</p>}</article>; })}{!customer.subscriptions.length && <p className="empty">لا توجد اشتراكات</p>}</section>
  </>;
}

function SubscriptionForm({ action, customerId, source }: { action: (data: FormData) => Promise<void>; customerId: string; source?: { subscription_name: string; amount: number | string } }) {
  return <form action={action} className="form-grid"><input type="hidden" name="customer_id" value={customerId}/><label>اسم الاشتراك<input name="subscription_name" defaultValue={source?.subscription_name} required/></label><label>قيمة الاشتراك<input name="amount" type="number" min="0" step="0.01" defaultValue={source ? Number(source.amount) : undefined} required/></label><label>تاريخ البداية<input name="start_date" type="date" required/></label><label>تاريخ النهاية<input name="end_date" type="date" required/></label><label className="wide">أيام التنبيه قبل الانتهاء<input name="reminder_days" placeholder="مثال: 7, 4, 1" required/><small>من تنبيه واحد إلى ثلاثة، افصل الأيام بفاصلة</small></label><button className="primary">{source ? "إنشاء اشتراك التجديد" : "إضافة الاشتراك"}</button></form>;
}

function ReminderSettings({ action, customerId, subscriptionId }: { action: (data: FormData) => Promise<void>; customerId: string; subscriptionId: string }) {
  return <form action={action} className="form-grid"><input type="hidden" name="customer_id" value={customerId}/><input type="hidden" name="subscription_id" value={subscriptionId}/><label className="wide">أيام التنبيه قبل الانتهاء<input name="reminder_days" placeholder="مثال: 5, 3" required/><small>أرقام صحيحة موجبة، من تنبيه واحد إلى ثلاثة</small></label><button className="primary">إنشاء التنبيهات</button></form>;
}
