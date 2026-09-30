"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { markReminderSent } from "./actions";
import { daysRemaining, reminderMessage } from "../lib/domain";
import type { ReminderView } from "../lib/types";

export function ReminderList({ reminders, today }: { reminders: ReminderView[]; today: string }) {
  const groups = [
    { title: "تنبيهات اليوم", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) === today) },
    { title: "التنبيهات القادمة", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) > today) },
    { title: "التنبيهات المتأخرة", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) < today && item.status !== "SENT") },
    { title: "التنبيهات المرسلة سابقًا", items: reminders.filter((item) => item.scheduled_date.slice(0, 10) < today && item.status === "SENT") },
  ];
  return <section className="card">{groups.map((group) => <div key={group.title}><h2>{group.title}</h2>{group.items.length ? group.items.map((reminder) => <ReminderItem key={reminder.id} reminder={reminder} today={today}/>) : <p className="empty">لا توجد تنبيهات</p>}</div>)}</section>;
}

function ReminderItem({ reminder, today }: { reminder: ReminderView; today: string }) {
  const router = useRouter();
  const [sending, setSending] = useState(false);
  const [sentAt, setSentAt] = useState<string | null>(null);
  const [error, setError] = useState("");
  const subscription = reminder.subscriptions;
  const customer = subscription.customers;
  const remaining = daysRemaining(subscription.end_date, today);
  const status = sentAt ? "SENT" : reminder.status;

  async function send() {
    if (sending || status === "SENT") return;
    setSending(true);
    setError("");
    try {
      if (!navigator.share) throw new Error("المشاركة غير مدعومة على هذا الجهاز");
      await navigator.share({ text: reminderMessage(customer.name, subscription.subscription_name, subscription.end_date, remaining) });
      // A cancelled or rejected share never changes the database state.
      await markReminderSent(reminder.id);
      setSentAt(new Date().toISOString());
      router.refresh();
    } catch (error) {
      if (!(error instanceof Error && error.name === "AbortError")) {
        setError(error instanceof Error ? error.message : "تعذر إرسال الرسالة");
      }
    } finally {
      setSending(false);
    }
  }

  return <article className="reminder"><div><strong>{customer.name}</strong><p dir="ltr">{customer.phone}</p><p>{subscription.subscription_name} · ينتهي {subscription.end_date} · متبقي {remaining} أيام</p><p>موعد التنبيه: {reminder.scheduled_date.slice(0, 10)} · <span className={`badge ${status.toLowerCase()}`}>{status}</span></p>{(sentAt || reminder.sent_at) && <p>وقت الإرسال: <time dateTime={sentAt || reminder.sent_at!}>{sentAt || reminder.sent_at}</time></p>}{error && <p className="notice error" role="alert">{error}</p>}</div><button className="message-button" type="button" onClick={send} disabled={sending || status === "SENT"}>{status === "SENT" ? "تم الإرسال" : sending ? "جارٍ الإرسال…" : "إرسال رسالة"}</button></article>;
}
