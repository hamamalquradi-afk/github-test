"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { dashboardMetrics, formatAmount } from "../lib/customer-management";
import { localToday } from "../lib/local-date";
import { getCustomers, getReminders } from "../lib/data";
import type { Customer, ReminderView } from "../lib/types";
import { ReminderList } from "./reminder-list";

export default function Home() {
  const today = localToday();
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [reminders, setReminders] = useState<ReminderView[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      setError("");
      const [customerRows, reminderRows] = await Promise.all([getCustomers(), getReminders(today)]);
      setCustomers(customerRows);
      setReminders(reminderRows);
    } catch (value) {
      setError(value instanceof Error ? value.message : "تعذر قراءة البيانات المحلية");
    } finally {
      setLoading(false);
    }
  }, [today]);

  useEffect(() => { void load(); }, [load]);

  const metrics = dashboardMetrics(customers, today);
  const todayReminders = reminders.filter((item) => item.scheduled_date.slice(0, 10) === today && item.status !== "SENT");
  const upcomingReminders = reminders.filter((item) => item.scheduled_date.slice(0, 10) > today && item.status !== "SENT");

  return <>
    <section className="page-heading">
      <div><span className="eyebrow">لوحة HAMNOVA</span><h1>نظرة سريعة على التحصيل</h1><p>مؤشرات عملية من بياناتك المحلية الحالية دون تقارير أو خدمات خارجية.</p></div>
      <Link className="button-link" href="/customers">إدارة العملاء</Link>
    </section>

    {error && <p className="notice error" role="alert">{error}</p>}
    {loading && <p className="empty-state">جارٍ تحميل البيانات المحلية…</p>}

    <section className="summary-grid dashboard-summary" aria-label="ملخص التحصيل">
      <Summary label="إجمالي العملاء" value={String(metrics.totalCustomers)} detail="جميع السجلات المحلية"/>
      <Summary label="الاشتراكات النشطة" value={String(metrics.activeSubscriptions)} detail={`حتى ${today}`}/>
      <Summary label="تنتهي خلال 7 أيام" value={String(metrics.expiringWithin7Days)} detail="من الاشتراكات الحالية"/>
      <Summary label="تنبيهات اليوم" value={String(todayReminders.length)} detail="بانتظار الإرسال"/>
      <Summary label="إجمالي قيمة الاشتراكات الحالية" value={formatAmount(metrics.totalValue)} detail="القيمة الإجمالية"/>
      <Summary label="إجمالي المدفوع" value={formatAmount(metrics.totalPaid)} detail="على الاشتراكات الحالية"/>
      <Summary label="إجمالي المتبقي" value={formatAmount(metrics.totalRemaining)} detail="المبلغ المطلوب تحصيله"/>
    </section>

    <section className="dashboard-collections">
      <article className="dashboard-list-card">
        <div className="section-heading"><div><span className="eyebrow">التحصيل</span><h2>أعلى المبالغ المتبقية</h2></div></div>
        <div className="dashboard-rank-list">
          {metrics.topRemaining.map((row) =>
            <Link href={`/customers/${row.customer.id}`} key={row.customer.id}>
              <span><strong>{row.customer.name}</strong><small>{row.current?.subscription_name}</small></span>
              <b>{formatAmount(row.remaining)}</b>
            </Link>
          )}
          {!loading && !metrics.topRemaining.length && <p className="empty-state">لا توجد مبالغ متبقية حاليًا.</p>}
        </div>
      </article>

      <article className="dashboard-list-card">
        <div className="section-heading"><div><span className="eyebrow">المواعيد</span><h2>الأقرب للانتهاء</h2></div></div>
        <div className="dashboard-rank-list">
          {metrics.nearestExpiry.map((row) =>
            <Link href={`/customers/${row.customer.id}`} key={row.customer.id}>
              <span><strong>{row.customer.name}</strong><small>{row.current?.subscription_name} — {row.endDate}</small></span>
              <b>{row.daysRemaining} يوم</b>
            </Link>
          )}
          {!loading && !metrics.nearestExpiry.length && <p className="empty-state">لا توجد اشتراكات نشطة.</p>}
        </div>
      </article>
    </section>

    <section className="section-block">
      <div className="section-heading"><div><span className="eyebrow">التنبيهات</span><h2>اليوم والقادم</h2></div><span className="section-count">{todayReminders.length + upcomingReminders.length} تنبيه</span></div>
      <ReminderList reminders={reminders} today={today} compact onChanged={load}/>
    </section>
  </>;
}

function Summary({ label, value, detail }: { label: string; value: string; detail: string }) {
  return <article className="summary-card"><span>{label}</span><strong className="amount">{value}</strong><small>{detail}</small></article>;
}
