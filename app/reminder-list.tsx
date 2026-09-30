"use client";

import { useState } from "react";
import { markReminderSent } from "./actions";
import { canSendReminder, daysRemaining, reminderMessage } from "../lib/domain";
import type { ReminderView } from "../lib/types";

export function ReminderList({ reminders, today, compact = false, onChanged }: { reminders: ReminderView[]; today: string; compact?: boolean; onChanged?: () => void | Promise<void> }) {
  const groups = [
    { title: "تنبيهات اليوم", description: "المستحقة اليوم", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) === today) },
    { title: "التنبيهات القادمة", description: "المواعيد القادمة", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) > today) },
    { title: "التنبيهات المتأخرة", description: "تحتاج إلى متابعة", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) < today && item.status !== "SENT") },
    { title: "التنبيهات المرسلة سابقًا", description: "سجل الإرسال", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) < today && item.status === "SENT") },
  ].filter((_, index) => !compact || index < 3);

  return <div className="reminder-groups">{groups.map((group) => <section className="reminder-group" key={group.title}><div className="reminder-group-heading"><div><h3>{group.title}</h3><p>{group.description}</p></div><span className="section-count">{group.items.length}</span></div>{group.items.length ? <div className="reminder-list">{group.items.map((reminder) => <ReminderItem key={reminder.id} reminder={reminder} today={today} onChanged={onChanged}/>)}</div> : <p className="empty-state">لا توجد تنبيهات.</p>}</section>)}</div>;
}

function ReminderItem({ reminder, today, onChanged }: { reminder: ReminderView; today: string; onChanged?: () => void | Promise<void> }) {
  const [sending, setSending] = useState(false);
  const [sentAt, setSentAt] = useState<string | null>(null);
  const [error, setError] = useState("");
  const subscription = reminder.subscriptions;
  const customer = subscription.customers;
  const remaining = daysRemaining(subscription.end_date, today);
  const status = sentAt ? "SENT" : reminder.status;

  async function send() {
    if (sending || !canSendReminder({ ...reminder, status })) return;
    setSending(true);
    setError("");
    try {
      if (!navigator.share) throw new Error("المشاركة غير مدعومة على هذا الجهاز");
      await navigator.share({ text: reminderMessage(customer.name, subscription.subscription_name, subscription.end_date) });
      await markReminderSent(reminder.id);
      setSentAt(new Date().toISOString());
      await onChanged?.();
    } catch (error) {
      if (!(error instanceof Error && error.name === "AbortError")) setError(error instanceof Error ? error.message : "تعذر إرسال الرسالة");
    } finally {
      setSending(false);
    }
  }

  return <article className="reminder-card">
    <div className="reminder-card-head"><div><strong>{customer.name}</strong><span dir="ltr">{customer.phone}</span></div><span className={`badge ${status.toLowerCase()}`}>{status}</span></div>
    <dl className="reminder-facts"><div><dt>الاشتراك</dt><dd>{subscription.subscription_name}</dd></div><div><dt>الانتهاء</dt><dd>{subscription.end_date}</dd></div><div><dt>المتبقي</dt><dd>{remaining} أيام</dd></div><div><dt>موعد التنبيه</dt><dd>{reminder.scheduled_date.slice(0, 10)}</dd></div></dl>
    {(sentAt || reminder.sent_at) && <p className="sent-time">وقت الإرسال: <time dateTime={sentAt || reminder.sent_at!}>{sentAt || reminder.sent_at}</time></p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    <button className="message-button" type="button" onClick={send} disabled={sending || !canSendReminder({ ...reminder, status })}>{status === "SENT" ? "تم الإرسال" : sending ? "جارٍ الإرسال…" : "إرسال رسالة"}</button>
  </article>;
}
