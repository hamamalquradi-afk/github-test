import Link from "next/link";
import { notFound } from "next/navigation";
import { addPayment, addSubscription, configureReminders, renewSubscription, updateCustomer } from "../../actions";
import { currentSubscription, effectiveSubscriptionStatus, paymentSummary } from "../../../lib/domain";
import { getCustomer, getCustomerReminders } from "../../../lib/data";

export const dynamic = "force-dynamic";
const today = () => new Date().toISOString().slice(0, 10);

export default async function CustomerPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ error?: string; success?: string }> }) {
  const { id } = await params;
  const query = await searchParams;
  const [customer, reminders] = await Promise.all([getCustomer(id), getCustomerReminders(id)]);
  if (!customer) notFound();
  const date = today();
  const current = currentSubscription(customer.subscriptions, date);
  const expired = current ? effectiveSubscriptionStatus(current.end_date, date) === "EXPIRED" : false;
  const summary = current ? paymentSummary(current.amount, current.payments) : null;

  return <>
    <Link href="/customers" className="back">← قائمة العملاء</Link>
    <section className="customer-hero"><div className="customer-identity"><span className="eyebrow">بيانات العميل</span><h1>{customer.name}</h1><p dir="ltr">{customer.phone}</p>{customer.notes && <p className="customer-notes">{customer.notes}</p>}</div>{expired ? <span className="badge expired">EXPIRED</span> : summary && <span className={`badge ${summary.status.toLowerCase()}`}>{summary.status}</span>}</section>
    {query.error && <p className="notice error">{query.error}</p>}{query.success && <p className="notice success">{query.success}</p>}

    {current ? <section className="current-subscription"><div className="section-heading"><div><span className="eyebrow">الاشتراك الحالي</span><h2>{current.subscription_name}</h2></div></div><div className="detail-stats"><Stat label="المتبقي" value={summary!.remaining.toFixed(2)}/><Stat label="تاريخ الانتهاء" value={current.end_date}/><Stat label="إجمالي المدفوع" value={summary!.totalPaid.toFixed(2)}/><Stat label="قيمة الاشتراك" value={Number(current.amount).toFixed(2)}/></div></section> : <section className="card"><h2>إضافة اشتراك</h2><SubscriptionForm action={addSubscription} customerId={id}/></section>}

    <section className="section-block"><div className="section-heading"><div><span className="eyebrow">إجراءات سريعة</span><h2>إدارة العميل</h2></div></div><div className="quick-actions">
      {current && <details className="card action-card"><summary>+ إضافة دفعة</summary><form action={addPayment} className="form-grid"><input type="hidden" name="customer_id" value={id}/><input type="hidden" name="subscription_id" value={current.id}/><label>قيمة الدفعة<input name="amount" type="number" min="0" max={summary!.remaining} step="0.01" required/></label><label>تاريخ الدفعة<input name="payment_date" type="date" defaultValue={date} required/></label><label className="wide">ملاحظات<textarea name="notes"/></label><button className="primary">تسجيل الدفعة</button></form></details>}
      {current && <details className="card action-card"><summary>تجديد الاشتراك</summary><SubscriptionForm action={renewSubscription} customerId={id} source={current}/></details>}
      <details className="card action-card"><summary>تعديل العميل</summary><form action={updateCustomer} className="form-grid"><input type="hidden" name="customer_id" value={id}/><label>الاسم<input name="name" defaultValue={customer.name} required/></label><label>الهاتف<input name="phone" defaultValue={customer.phone} required/></label><label className="wide">ملاحظات<textarea name="notes" defaultValue={customer.notes ?? ""}/></label><button className="primary">حفظ التعديل</button></form></details>
      {current && !expired && current.status.toLowerCase() === "active" && <details className="card action-card"><summary>إعدادات التنبيهات</summary><ReminderSettings action={configureReminders} customerId={id} subscriptionId={current.id}/></details>}
    </div></section>

    <History title="سجل الاشتراكات">{customer.subscriptions.map((sub) => { const s = paymentSummary(sub.amount, sub.payments); return <article className="history-card" key={sub.id}><div className="history-title"><strong>{sub.subscription_name}</strong><span className={effectiveSubscriptionStatus(sub.end_date,date)==="EXPIRED"?"badge expired":`badge ${s.status.toLowerCase()}`}>{effectiveSubscriptionStatus(sub.end_date,date)==="EXPIRED"?"EXPIRED":s.status}</span></div><dl className="history-facts"><Fact label="الفترة" value={`${sub.start_date} — ${sub.end_date}`}/><Fact label="القيمة" value={Number(sub.amount).toFixed(2)}/><Fact label="المدفوع" value={s.totalPaid.toFixed(2)}/><Fact label="المتبقي" value={s.remaining.toFixed(2)}/></dl></article>; })}</History>
    <History title="سجل الدفعات">{customer.subscriptions.flatMap((sub) => sub.payments.map((p) => <article className="timeline-item" key={p.id}><div><strong>{Number(p.amount).toFixed(2)}</strong><span>{sub.subscription_name}</span></div><div><time>{p.payment_date}</time>{p.notes && <p>{p.notes}</p>}</div></article>))}</History>
    <History title="سجل التنبيهات">{reminders.map((r) => <article className="timeline-item" key={r.id}><div><strong>{r.subscriptions.subscription_name}</strong><span>{r.scheduled_date.slice(0,10)}</span></div><div><span className={`badge ${r.status.toLowerCase()}`}>{r.status}</span>{r.sent_at && <p>أرسل: {r.sent_at}</p>}</div></article>)}</History>
  </>;
}

function Stat({label,value}:{label:string;value:string}){return <div><small>{label}</small><strong>{value}</strong></div>}
function Fact({label,value}:{label:string;value:string}){return <div><dt>{label}</dt><dd>{value}</dd></div>}
function History({title,children}:{title:string;children:React.ReactNode}){return <section className="history-section"><div className="section-heading"><div><span className="eyebrow">السجل</span><h2>{title}</h2></div></div><div className={title==="سجل الاشتراكات"?"history-list":"timeline"}>{children}</div></section>}

function SubscriptionForm({ action, customerId, source }: { action: (data: FormData) => Promise<void>; customerId: string; source?: { subscription_name: string; amount: number | string } }) {
  return <form action={action} className="form-grid"><input type="hidden" name="customer_id" value={customerId}/><label>اسم الاشتراك<input name="subscription_name" defaultValue={source?.subscription_name} required/></label><label>قيمة الاشتراك<input name="amount" type="number" min="0" step="0.01" defaultValue={source ? Number(source.amount) : undefined} required/></label><label>تاريخ البداية<input name="start_date" type="date" required/></label><label>تاريخ النهاية<input name="end_date" type="date" required/></label><label className="wide">أيام التنبيه قبل الانتهاء<input name="reminder_days" placeholder="مثال: 7, 4, 1" required/></label><button className="primary">{source ? "إنشاء اشتراك التجديد" : "إضافة الاشتراك"}</button></form>;
}
function ReminderSettings({ action, customerId, subscriptionId }: { action: (data: FormData) => Promise<void>; customerId: string; subscriptionId: string }) {
  return <form action={action} className="form-grid"><input type="hidden" name="customer_id" value={customerId}/><input type="hidden" name="subscription_id" value={subscriptionId}/><label className="wide">أيام التنبيه قبل الانتهاء<input name="reminder_days" placeholder="مثال: 5, 3" required/></label><button className="primary">إنشاء التنبيهات</button></form>;
}
