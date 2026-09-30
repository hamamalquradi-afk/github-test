import Link from "next/link";
import { currentSubscription, effectiveSubscriptionStatus, paymentSummary } from "../lib/domain";
import { getCustomers, getReminders } from "../lib/data";
import { ReminderList } from "./reminder-list";

export const dynamic = "force-dynamic";

export default async function Home() {
  const today = new Date().toISOString().slice(0, 10);
  const [customers, reminders] = await Promise.all([getCustomers(), getReminders(today)]);
  const currentSubscriptions = customers
    .map((customer) => ({ customer, subscription: currentSubscription(customer.subscriptions, today) }))
    .filter(({ subscription }) => subscription && subscription.status.toLowerCase() === "active" && subscription.start_date <= today && effectiveSubscriptionStatus(subscription.end_date, today) === "ACTIVE");
  const remainingTotal = currentSubscriptions.reduce(
    (total, { subscription }) => total + paymentSummary(subscription!.amount, subscription!.payments).remaining,
    0,
  );
  const todayReminders = reminders.filter((item) => item.scheduled_date.slice(0, 10) === today && item.status !== "SENT");
  const upcomingReminders = reminders.filter((item) => item.scheduled_date.slice(0, 10) > today && item.status !== "SENT");

  return (
    <>
      <section className="page-heading">
        <div>
          <span className="eyebrow">لوحة المتابعة</span>
          <h1>نظرة سريعة على التحصيل</h1>
          <p>الاشتراكات والمبالغ والتنبيهات المهمة في مكان واحد.</p>
        </div>
        <Link className="button-link" href="/customers">إدارة العملاء</Link>
      </section>

      <section className="summary-grid" aria-label="ملخص اليوم">
        <article className="summary-card"><span>العملاء</span><strong>{customers.length}</strong><small>إجمالي السجلات</small></article>
        <article className="summary-card"><span>الاشتراكات النشطة</span><strong>{currentSubscriptions.length}</strong><small>حتى {today}</small></article>
        <article className="summary-card"><span>تنبيهات اليوم</span><strong>{todayReminders.length}</strong><small>بانتظار الإرسال</small></article>
        <article className="summary-card"><span>إجمالي المتبقي</span><strong className="amount">{remainingTotal.toFixed(2)}</strong><small>على الاشتراكات الحالية</small></article>
      </section>

      <section className="section-block">
        <div className="section-heading"><div><span className="eyebrow">الاشتراكات الحالية</span><h2>المبالغ المتبقية</h2></div><Link href="/customers">عرض جميع العملاء</Link></div>
        <div className="compact-grid">
          {currentSubscriptions.slice(0, 6).map(({ customer, subscription }) => {
            const summary = paymentSummary(subscription!.amount, subscription!.payments);
            return <Link className="compact-card" href={`/customers/${customer.id}`} key={customer.id}>
              <div><strong>{customer.name}</strong><span>{subscription!.subscription_name}</span></div>
              <div className="compact-meta"><span>ينتهي {subscription!.end_date}</span><b>{summary.remaining.toFixed(2)} متبقي</b></div>
            </Link>;
          })}
          {!currentSubscriptions.length && <p className="empty-state">لا توجد اشتراكات نشطة.</p>}
        </div>
      </section>

      <section className="section-block">
        <div className="section-heading"><div><span className="eyebrow">التنبيهات</span><h2>اليوم والقادم</h2></div><span className="section-count">{todayReminders.length + upcomingReminders.length} تنبيه</span></div>
        <ReminderList reminders={reminders} today={today} compact/>
      </section>
    </>
  );
}
