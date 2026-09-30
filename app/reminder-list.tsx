"use client";

import { useEffect, useRef, useState } from "react";
import { copyReminderMessage, shareReminderMessage } from "../lib/browser-fallbacks";
import { canSendReminder, daysRemaining, reminderMessage } from "../lib/domain";
import type { ReminderView } from "../lib/types";
import { markReminderSent } from "./actions";

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
  const [copyFeedback, setCopyFeedback] = useState("");
  const [shareSupported, setShareSupported] = useState(true);
  const messageRef = useRef<HTMLTextAreaElement>(null);
  const subscription = reminder.subscriptions;
  const customer = subscription.customers;
  const remaining = daysRemaining(subscription.end_date, today);
  const status = sentAt ? "SENT" : reminder.status;
  const eligible = canSendReminder({ ...reminder, status }, today);
  const message = reminderMessage(customer.name, subscription.subscription_name, subscription.end_date, today);

  useEffect(() => {
    setShareSupported(typeof navigator !== "undefined" && typeof navigator.share === "function");
  }, []);

  async function send() {
    if (sending || !eligible) return;
    if (typeof navigator === "undefined" || typeof navigator.share !== "function") {
      setShareSupported(false);
      return;
    }
    setSending(true);
    setError("");
    setCopyFeedback("");
    try {
      await shareReminderMessage(message, navigator.share.bind(navigator), async () => {
        await markReminderSent(reminder.id);
        setSentAt(new Date().toISOString());
        await onChanged?.();
      });
    } catch (value) {
      setError(value instanceof Error ? value.message : "تعذر إرسال الرسالة");
    } finally {
      setSending(false);
    }
  }

  async function copyMessage() {
    setError("");
    setCopyFeedback("");
    const clipboardWrite = typeof navigator !== "undefined" && navigator.clipboard?.writeText
      ? navigator.clipboard.writeText.bind(navigator.clipboard)
      : undefined;
    const outcome = await copyReminderMessage(message, clipboardWrite, () => {
      const field = messageRef.current;
      if (!field) return false;
      field.focus();
      field.select();
      try {
        return typeof document.execCommand === "function" && document.execCommand("copy");
      } catch {
        return false;
      }
    });
    setCopyFeedback(outcome === "copied"
      ? "تم نسخ الرسالة. لم يتم تعليم التنبيه كمرسل."
      : "تعذر النسخ التلقائي. يمكنك تحديد النص ونسخه يدويًا. لم يتم تعليم التنبيه كمرسل.");
  }

  return <article className="reminder-card">
    <div className="reminder-card-head"><div><strong>{customer.name}</strong><span dir="ltr">{customer.phone}</span></div><span className={`badge ${status.toLowerCase()}`}>{status}</span></div>
    <dl className="reminder-facts"><div><dt>الاشتراك</dt><dd>{subscription.subscription_name}</dd></div><div><dt>الانتهاء</dt><dd>{subscription.end_date}</dd></div><div><dt>المتبقي</dt><dd>{remaining} أيام</dd></div><div><dt>موعد التنبيه</dt><dd>{reminder.scheduled_date.slice(0, 10)}</dd></div></dl>
    {(sentAt || reminder.sent_at) && <p className="sent-time">وقت الإرسال: <time dateTime={sentAt || reminder.sent_at!}>{sentAt || reminder.sent_at}</time></p>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {copyFeedback && <p className="notice success">{copyFeedback}</p>}
    {!shareSupported && status !== "SENT" && eligible && <div className="message-fallback">
      <label htmlFor={`reminder-message-${reminder.id}`}>نص رسالة التذكير</label>
      <textarea ref={messageRef} id={`reminder-message-${reminder.id}`} className="message-text" value={message} readOnly rows={5}/>
      <button className="message-button" type="button" onClick={copyMessage}>نسخ الرسالة</button>
    </div>}
    {(shareSupported || status === "SENT") && <button className="message-button" type="button" onClick={send} disabled={sending || !eligible}>{status === "SENT" ? "تم الإرسال" : sending ? "جارٍ الإرسال…" : "إرسال رسالة"}</button>}
  </article>;
}
