import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const css = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
const home = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
const customers = await readFile(new URL("../app/customers/page.tsx", import.meta.url), "utf8");
const detail = await readFile(new URL("../app/customers/[id]/page.tsx", import.meta.url), "utf8");
const reminders = await readFile(new URL("../app/reminder-list.tsx", import.meta.url), "utf8");

test("Phase 4 keeps mobile layouts primary and desktop tables progressive", () => {
  assert.match(css, /\.customer-cards\{display:grid/);
  assert.match(css, /\.table-wrap\{display:none/);
  assert.match(css, /@media \(min-width:768px\)/);
  assert.match(css, /\.customer-cards\{display:none\}/);
  assert.match(css, /\.table-wrap\{display:block/);
  assert.match(css, /grid-template-columns:minmax\(0,1fr\)/);
});

test("Phase 4 guards narrow screens and long Arabic content", () => {
  assert.match(css, /overflow-wrap:anywhere/);
  assert.match(css, /min-width:0/);
  assert.match(css, /max-width:100%/);
  assert.match(css, /min-height:46px/);
  assert.doesNotMatch(css, /overflow-x:hidden/);
});

test("home dashboard exposes required mobile summary information", () => {
  for (const label of ["الاشتراكات النشطة", "تنبيهات اليوم", "إجمالي المتبقي", "التنبيهات القادمة"]) {
    assert.match(home + reminders, new RegExp(label));
  }
});

test("customers have desktop table and complete mobile cards", () => {
  assert.match(customers, /className="customer-table table-wrap"/);
  assert.match(customers, /className="customer-cards"/);
  for (const label of ["الاشتراك", "المدفوع", "المتبقي", "الانتهاء"]) {
    assert.match(customers, new RegExp(label));
  }
});

test("customer details preserve actions and expose all histories", () => {
  for (const label of ["إضافة دفعة", "تجديد الاشتراك", "تعديل العميل", "سجل الاشتراكات", "سجل الدفعات", "سجل التنبيهات"]) {
    assert.match(detail, new RegExp(label));
  }
});

test("reminder cards retain Web Share send behavior", () => {
  assert.match(reminders, /await navigator\.share/);
  assert.match(reminders, /await markReminderSent/);
  assert.match(reminders, /disabled=\{sending \|\| !canSendReminder/);
  assert.ok(reminders.indexOf("await navigator.share") < reminders.indexOf("await markReminderSent"));
});
