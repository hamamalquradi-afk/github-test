"use client";

import { useState } from "react";
import { markReminderSent } from "./actions";

export function SendMessageButton({ reminderId, message }: { reminderId: string; message: string }) {
  const [state, setState] = useState<"idle" | "sending" | "sent">("idle");

  async function send() {
    setState("sending");
    try {
      if (!navigator.share) throw new Error("المشاركة غير مدعومة على هذا الجهاز");
      await navigator.share({ text: message });
      // Persist SENT only after the operating-system share operation resolves.
      await markReminderSent(reminderId);
      setState("sent");
    } catch (error) {
      setState("idle");
      if (error instanceof Error && error.name !== "AbortError") alert(error.message);
    }
  }

  return <button className="message-button" type="button" onClick={send} disabled={state !== "idle"}>{state === "sent" ? "تم الإرسال" : state === "sending" ? "جارٍ الإرسال…" : "إرسال رسالة"}</button>;
}
