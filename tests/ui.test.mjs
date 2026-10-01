import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
const home = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
const customers = await readFile(new URL("../app/customers/page.tsx", import.meta.url), "utf8");
const detail = await readFile(new URL("../app/customers/[id]/page.tsx", import.meta.url), "utf8");
const reminders = await readFile(new URL("../app/reminder-list.tsx", import.meta.url), "utf8");
const actions = await readFile(new URL("../app/actions.ts", import.meta.url), "utf8");
const data = await readFile(new URL("../lib/data.ts", import.meta.url), "utf8");
const repository = await readFile(new URL("../lib/repository/indexeddb.ts", import.meta.url), "utf8");
const pkg = await readFile(new URL("../package.json", import.meta.url), "utf8");
const settings = await readFile(new URL("../app/settings/page.tsx", import.meta.url), "utf8");
const layout = await readFile(new URL("../app/layout.tsx", import.meta.url), "utf8");
const backup = await readFile(new URL("../lib/backup.ts", import.meta.url), "utf8");
const domain = await readFile(new URL("../lib/domain.ts", import.meta.url), "utf8");
const localDate = await readFile(new URL("../lib/local-date.ts", import.meta.url), "utf8");
const browserFallbacks = await readFile(new URL("../lib/browser-fallbacks.ts", import.meta.url), "utf8");
const contact = await readFile(new URL("../app/contact/page.tsx", import.meta.url), "utf8");
const appInfo = await readFile(new URL("../lib/app-info.ts", import.meta.url), "utf8");
const customerManagement = await readFile(new URL("../lib/customer-management.ts", import.meta.url), "utf8");
const brandIcon = await readFile(new URL("../public/branding/hamnova-icon.png", import.meta.url));
const brandLogo = await readFile(new URL("../public/branding/hamnova-logo.png", import.meta.url));

test("Phase 7B keeps the customer management table primary on mobile and desktop", () => {
  assert.match(customers, /customer-table-shell/);
  assert.match(customers, /customer-management-table/);
  assert.doesNotMatch(customers, /customer-cards/);
  assert.match(css, /\.customer-table-shell\{display:block;max-width:100%;overflow-x:auto/);
  assert.match(css, /\.customer-management-table\{[^}]*min-width:1080px/);
  assert.match(css, /\.customer-management-table \.sticky-name\{position:sticky;right:0/);
});

test("Phase 4 guards narrow screens and long Arabic content", () => {
  assert.match(css, /overflow-wrap:anywhere/);
  assert.match(css, /min-width:0/);
  assert.match(css, /max-width:100%/);
  assert.match(css, /min-height:46px/);
  assert.doesNotMatch(css, /overflow-x:hidden/);
});

test("home dashboard exposes Phase 7B collection metrics and priority sections", () => {
  for (const label of ["إجمالي العملاء", "الاشتراكات النشطة", "تنتهي خلال 7 أيام", "تنبيهات اليوم", "إجمالي قيمة الاشتراكات الحالية", "إجمالي المدفوع", "إجمالي المتبقي", "أعلى المبالغ المتبقية", "الأقرب للانتهاء"]) {
    assert.match(home, new RegExp(label));
  }
  assert.match(home, /dashboardMetrics\(customers, today\)/);
});

test("customers table has required columns filters sorting and compact actions", () => {
  for (const label of ["العميل", "الاشتراك الحالي", "رقم الهاتف", "قيمة الاشتراك", "المدفوع", "المتبقي", "تاريخ الانتهاء", "الحالة", "الإجراءات"]) {
    assert.match(customers, new RegExp(label));
  }
  for (const label of ["الكل", "نشط", "منتهي", "عليه متبقي", "مدفوع بالكامل", "بدون اشتراك"]) {
    assert.match(customerManagement + customers, new RegExp(label));
  }
  for (const label of ["فتح", "تعديل", "إضافة دفعة", "تجديد", "حذف"]) assert.match(customers, new RegExp(label));
  assert.match(customers, /حذف العميل نهائيًا/);
  assert.match(customers, /showModal\(\)/);
});

test("customer details preserve actions and expose all histories", () => {
  for (const label of ["إضافة دفعة", "تجديد الاشتراك", "تعديل العميل", "سجل الاشتراكات", "سجل الدفعات", "سجل التنبيهات"]) assert.match(detail, new RegExp(label));
});

test("reminder cards retain Web Share success behavior and SENT protection", () => {
  assert.match(reminders, /shareReminderMessage/);
  assert.match(reminders, /navigator\.share\.bind\(navigator\)/);
  assert.match(reminders, /await markReminderSent/);
  assert.match(reminders, /disabled=\{sending \|\| !eligible/);
  assert.ok(browserFallbacks.indexOf("await share") < browserFallbacks.indexOf("await onShared"));
});

test("compact reminder UI keeps overdue unsent reminders visible and eligibility-gated", () => {
  assert.match(reminders, /التنبيهات المتأخرة/);
  assert.match(reminders, /!compact \|\| index < 3/);
  assert.match(reminders, /const eligible = canSendReminder/);
  assert.match(reminders, /disabled=\{sending \|\| !eligible/);
});

test("add-customer failures remain on customers page and expose the error", () => {
  assert.ok(customers.includes("router.replace(`/customers?error="));
  assert.match(customers, /error && <p className="notice error"/);
  assert.ok(customers.includes("router.push(`/customers/${id}?success="));
});

test("Phase 5 runtime uses IndexedDB repository and has no Supabase runtime dependency", () => {
  assert.match(repository, /indexedDB/);
  assert.match(repository, /transaction\(\[STORES\.customers, STORES\.subscriptions, STORES\.reminders\], "readwrite"\)/);
  assert.match(data, /repository\/indexeddb/);
  assert.doesNotMatch(actions + data + home + customers + detail + reminders, /supabase|NEXT_PUBLIC_SUPABASE/i);
  assert.doesNotMatch(pkg, /@supabase\/supabase-js/);
});


test("Phase 6 backup and restore UI is explicit, local-only and storage-isolated", () => {
  for (const label of ["إنشاء نسخة احتياطية", "استعادة نسخة احتياطية", "تأكيد الاستعادة واستبدال البيانات الحالية", "Google Drive"]) {
    assert.match(settings, new RegExp(label));
  }
  assert.match(settings, /type="file"/);
  assert.match(settings, /navigator\.share/);
  assert.match(settings, /downloadFile/);
  assert.match(settings, /exportAllData, restoreBackup/);
  assert.doesNotMatch(settings, /indexedDB|objectStore/);
  assert.doesNotMatch(settings + backup, /googleapis|accounts\.google|oauth/i);
  assert.match(layout, /href="\/settings">الإعدادات/);
});


test("Phase 7A uses local business date consistently in runtime decision points", () => {
  assert.match(localDate, /getFullYear\(\)/);
  assert.match(localDate, /getMonth\(\)/);
  assert.match(localDate, /getDate\(\)/);
  assert.match(home, /const today = localToday\(\)/);
  assert.match(customers, /const today = localToday\(\)/);
  assert.match(detail, /const date = localToday\(\)/);
  assert.match(domain, /today: string = localToday\(\)/);
  assert.match(repository, /localToday\(\)/);
  assert.doesNotMatch(home + customers + detail, /toISOString\(\)\.slice\(0, 10\)/);
  assert.doesNotMatch(domain + repository, /utcToday|todayUtc/);
});

test("Phase 7A exposes copy fallback without treating copy as SENT", () => {
  assert.match(reminders, /نسخ الرسالة/);
  assert.match(reminders, /نص رسالة التذكير/);
  assert.match(reminders, /تم نسخ الرسالة\. لم يتم تعليم التنبيه كمرسل\./);
  assert.match(reminders, /messageRef/);
  assert.match(browserFallbacks, /AbortError/);
  const copyBlock = reminders.slice(reminders.indexOf("async function copyMessage"), reminders.indexOf("return <article"));
  assert.doesNotMatch(copyBlock, /markReminderSent/);
  assert.doesNotMatch(browserFallbacks, /markReminderSent/);
});

test("Phase 7A backup creation remains download-first when file sharing is unavailable", () => {
  assert.match(settings, /downloadFile\(file\)/);
  assert.match(settings, /backupFile && shareAvailable/);
  assert.match(settings, /المشاركة غير متاحة في هذا المتصفح/);
  assert.match(settings, /تم إنشاء النسخة الاحتياطية وتنزيلها على الجهاز/);
  assert.match(settings, /supportsFileShare/);
});


test("HAMNOVA branding metadata navigation and approved assets are integrated", () => {
  assert.match(appInfo, /APP_NAME = "HAMNOVA"/);
  assert.match(appInfo, /APP_SUBTITLE = "إدارة الاشتراكات والتحصيل"/);
  assert.match(layout, /hamnova-icon\.png/);
  assert.match(layout, /brand-wordmark/);
  assert.match(layout, /href="\/contact">التواصل/);
  assert.match(layout, /description: APP_DESCRIPTION/);
  assert.ok(brandIcon.length > 1_000_000);
  assert.ok(brandLogo.length > 500_000);
});

test("HAMNOVA visual system centralizes brand colors and responsive header behavior", () => {
  for (const variable of ["--brand-navy", "--brand-primary", "--brand-accent", "--surface", "--border", "--muted", "--danger", "--success", "--warning"]) {
    assert.match(css, new RegExp(variable));
  }
  assert.match(css, /\.brand-icon\{width:40px;height:40px/);
  assert.match(css, /\.brand-subtitle\{display:none/);
  assert.match(css, /@media \(min-width:380px\)/);
  assert.match(css, /:focus-visible/);
  assert.doesNotMatch(css, /overflow-x:hidden/);
});

test("customer edit quick actions and destructive confirmation are discoverable", () => {
  assert.match(customers, /\?edit=1#edit-customer/);
  assert.match(customers, /\?action=payment#payment-action/);
  assert.match(customers, /\?action=renew#renew-action/);
  assert.match(customers, /deleteTarget\.subscriptions/);
  assert.match(customers, /deleteTarget\.payments/);
  assert.match(customers, /deleteTarget\.reminders/);
  assert.match(detail, /id="edit-customer"/);
  assert.match(detail, /id="payment-action"/);
  assert.match(detail, /id="renew-action"/);
  assert.match(detail, /scrollIntoView/);
});

test("customer presentation uses Arabic statuses and the shared amount formatter", () => {
  for (const label of ["مدفوع", "مدفوع جزئيًا", "غير مدفوع", "منتهي", "بدون اشتراك"]) {
    assert.match(customerManagement, new RegExp(label));
  }
  assert.match(customerManagement, /Intl\.NumberFormat/);
  assert.match(customers, /formatAmount/);
  assert.match(detail, /formatAmount/);
  assert.match(home, /formatAmount/);
});

test("contact page uses approved HAMNOVA identity owner and local-first privacy without fake contact values", () => {
  assert.match(contact, /hamnova-logo\.png/);
  assert.match(contact, /عن HAMNOVA والتواصل/);
  assert.match(contact, /OWNER_NAME/);
  assert.match(contact, /تُخزّن بيانات العمل محليًا/);
  assert.match(appInfo, /OWNER_NAME = "همام"/);
  assert.match(appInfo, /phone: null/);
  assert.match(appInfo, /whatsapp: null/);
  assert.match(appInfo, /email: null/);
  assert.match(contact, /hasContact &&/);
});

test("Phase 7B preserves the same IndexedDB identity and backup format", () => {
  assert.match(repository, /const DATABASE_NAME = "subscription-collection-tracker"/);
  assert.match(repository, /const DATABASE_VERSION = 1/);
  assert.match(backup, /BACKUP_VERSION = 1/);
  assert.match(backup, /BACKUP_SCHEMA_VERSION = 1/);
  assert.match(repository, /transaction\(\[STORES\.customers, STORES\.subscriptions, STORES\.payments, STORES\.reminders\], "readwrite"\)/);
});

test("settings explains local-first storage while preserving Phase 6 and 7A backup fallbacks", () => {
  assert.match(settings, /بياناتك محلية أولًا/);
  assert.match(settings, /downloadFile\(file\)/);
  assert.match(settings, /backupFile && shareAvailable/);
  assert.match(settings, /تأكيد الاستعادة واستبدال البيانات الحالية/);
  assert.doesNotMatch(settings, /indexedDB|objectStore/);
});
