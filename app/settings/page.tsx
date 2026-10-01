"use client";

import { ChangeEvent, useRef, useState } from "react";
import { APP_NAME, APP_SUBTITLE } from "../../lib/app-info";
import { supportsFileShare } from "../../lib/browser-fallbacks";
import { backupFilename, parseBackupJson, serializeBackup, type BackupDocument } from "../../lib/backup";
import { exportAllData, restoreBackup } from "../../lib/repository/indexeddb";

type PendingRestore = { backup: BackupDocument; fileName: string };

function downloadFile(file: File): void {
  const url = URL.createObjectURL(file);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = file.name;
  anchor.click();
  queueMicrotask(() => URL.revokeObjectURL(url));
}

export default function SettingsPage() {
  const inputRef = useRef<HTMLInputElement>(null);
  const [backupFile, setBackupFile] = useState<File | null>(null);
  const [shareAvailable, setShareAvailable] = useState(false);
  const [shareNotice, setShareNotice] = useState("");
  const [pending, setPending] = useState<PendingRestore | null>(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function createBackup() {
    setBusy(true);
    setError("");
    setNotice("");
    setShareNotice("");
    try {
      const backup = await exportAllData();
      const file = new File([serializeBackup(backup)], backupFilename(backup.exportedAt), { type: "application/json" });
      const canShare = supportsFileShare(typeof navigator === "undefined" ? undefined : navigator, file);
      setBackupFile(file);
      setShareAvailable(canShare);
      downloadFile(file);
      setNotice("تم إنشاء النسخة الاحتياطية وتنزيلها على الجهاز.");
      if (!canShare) setShareNotice("المشاركة غير متاحة في هذا المتصفح، ويمكنك استخدام الملف الذي تم تنزيله.");
    } catch (value) {
      setError(value instanceof Error ? value.message : "تعذر إنشاء النسخة الاحتياطية");
    } finally {
      setBusy(false);
    }
  }

  async function shareBackup() {
    if (!backupFile) return;
    setError("");
    try {
      const shareData = { files: [backupFile], title: "نسخة احتياطية HAMNOVA" };
      if (typeof navigator.share !== "function" || !supportsFileShare(navigator, backupFile)) {
        setShareAvailable(false);
        setShareNotice("المشاركة غير متاحة في هذا المتصفح، ويمكنك استخدام الملف الذي تم تنزيله.");
        return;
      }
      await navigator.share(shareData);
    } catch (value) {
      if (!(value instanceof Error && value.name === "AbortError")) {
        setError(value instanceof Error ? value.message : "تعذر مشاركة النسخة الاحتياطية");
      }
    }
  }

  async function selectRestoreFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = "";
    if (!file) return;
    setError("");
    setNotice("");
    try {
      const backup = parseBackupJson(await file.text());
      setPending({ backup, fileName: file.name });
    } catch (value) {
      setPending(null);
      setError(value instanceof Error ? value.message : "ملف النسخة الاحتياطية غير صالح");
    }
  }

  async function confirmRestore() {
    if (!pending) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await restoreBackup(pending.backup);
      setPending(null);
      setBackupFile(null);
      setShareAvailable(false);
      setShareNotice("");
      setNotice("تمت استعادة النسخة الاحتياطية واستبدال البيانات المحلية بنجاح.");
    } catch (value) {
      setError(value instanceof Error ? value.message : "تعذر استعادة النسخة الاحتياطية");
    } finally {
      setBusy(false);
    }
  }

  return <>
    <section className="page-heading"><div><span className="eyebrow">{APP_NAME}</span><h1>النسخ الاحتياطي والاستعادة</h1><p>{APP_SUBTITLE} — احفظ نسخة محلية كاملة من بياناتك أو استعد نسخة سابقة بعد التحقق منها.</p></div></section>
    <section className="settings-local-info" aria-label="معلومات التخزين المحلي">
      <strong>بياناتك محلية أولًا</strong>
      <p>تُخزّن بيانات العملاء والاشتراكات والتحصيل على هذا الجهاز. لا يتم رفع النسخة الاحتياطية تلقائيًا إلى خادم خارجي.</p>
    </section>
    {error && <p className="notice error" role="alert">{error}</p>}
    {notice && <p className="notice success">{notice}</p>}
    {shareNotice && <p className="notice">{shareNotice}</p>}

    <section className="backup-grid">
      <article className="card backup-card">
        <h2>إنشاء نسخة احتياطية</h2>
        <p>يتم إنشاء ملف JSON محلي يحتوي العملاء والاشتراكات والدفعات والتنبيهات بكل تاريخها.</p>
        <div className="backup-actions">
          <button className="primary" type="button" onClick={createBackup} disabled={busy}>{busy ? "جارٍ الإنشاء…" : "إنشاء نسخة احتياطية"}</button>
          {backupFile && <button type="button" onClick={() => downloadFile(backupFile)}>تنزيل الملف مرة أخرى</button>}
          {backupFile && shareAvailable && <button type="button" onClick={shareBackup}>مشاركة / حفظ عبر الجهاز</button>}
        </div>
        <p className="privacy-note">لا يتم رفع الملف تلقائيًا لأي خدمة. إذا ظهر Google Drive في واجهة المشاركة أو الحفظ على جهازك، يمكنك اختياره يدويًا.</p>
      </article>

      <article className="card backup-card">
        <h2>استعادة نسخة احتياطية</h2>
        <p>اختر ملف النسخة أولًا. سيتم التحقق من البنية والعلاقات كاملة قبل السماح بالاستعادة.</p>
        <input ref={inputRef} className="sr-only" type="file" accept=".json,application/json" onChange={selectRestoreFile}/>
        <button type="button" onClick={() => inputRef.current?.click()} disabled={busy}>اختيار ملف نسخة احتياطية</button>

        {pending && <div className="restore-confirmation">
          <h3>تأكيد الاستعادة</h3>
          <p className="restore-warning">سيتم حذف البيانات المحلية الحالية واستبدالها بالكامل بمحتوى النسخة المحددة. لا يتم دمج السجلات.</p>
          <dl className="backup-summary">
            <div><dt>الملف</dt><dd>{pending.fileName}</dd></div>
            <div><dt>تاريخ النسخة</dt><dd>{pending.backup.exportedAt}</dd></div>
            <div><dt>العملاء</dt><dd>{pending.backup.counts.customers}</dd></div>
            <div><dt>الاشتراكات</dt><dd>{pending.backup.counts.subscriptions}</dd></div>
            <div><dt>الدفعات</dt><dd>{pending.backup.counts.payments}</dd></div>
            <div><dt>التنبيهات</dt><dd>{pending.backup.counts.reminders}</dd></div>
          </dl>
          <div className="backup-actions">
            <button className="danger-button" type="button" onClick={confirmRestore} disabled={busy}>{busy ? "جارٍ الاستعادة…" : "تأكيد الاستعادة واستبدال البيانات الحالية"}</button>
            <button type="button" onClick={() => setPending(null)} disabled={busy}>إلغاء</button>
          </div>
        </div>}
      </article>
    </section>
  </>;
}
