import { reminderDate } from "./domain.ts";
import type { Payment, Reminder } from "./types.ts";

export const BACKUP_APP = "subscription-collection-tracker";
export const BACKUP_VERSION = 1;
export const BACKUP_SCHEMA_VERSION = 1;

export interface BackupCustomer {
  id: string;
  name: string;
  phone: string;
  notes: string | null;
  created_at: string;
  updated_at?: string;
}

export interface BackupSubscription {
  id: string;
  customer_id: string;
  subscription_name: string;
  amount: number | string;
  start_date: string;
  end_date: string;
  status: string;
  created_at: string;
}

export interface BackupData {
  customers: BackupCustomer[];
  subscriptions: BackupSubscription[];
  payments: Payment[];
  reminders: Reminder[];
}

export interface BackupCounts {
  customers: number;
  subscriptions: number;
  payments: number;
  reminders: number;
}

export interface BackupDocument {
  app: typeof BACKUP_APP;
  backupVersion: typeof BACKUP_VERSION;
  exportedAt: string;
  schemaVersion: typeof BACKUP_SCHEMA_VERSION;
  counts: BackupCounts;
  data: BackupData;
}

type UnknownRecord = Record<string, unknown>;

function fail(message: string): never {
  throw new Error(message);
}

function record(value: unknown, label: string): UnknownRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(`${label} غير صالح`);
  return value as UnknownRecord;
}

function requiredString(value: unknown, label: string): string {
  if (typeof value !== "string" || !value.trim()) fail(`${label} غير صالح`);
  return value;
}

function nullableString(value: unknown, label: string): string | null {
  if (value === null) return null;
  if (typeof value !== "string") fail(`${label} غير صالح`);
  return value;
}

function numeric(value: unknown, label: string): number {
  if ((typeof value !== "number" && typeof value !== "string") || value === "") fail(`${label} غير صالح`);
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) fail(`${label} غير صالح`);
  return parsed;
}

function positiveInteger(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) fail(`${label} غير صالح`);
  return value;
}

function isoDate(value: unknown, label: string): string {
  const text = requiredString(value, label);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) fail(`${label} غير صالح`);
  const parsed = new Date(`${text}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== text) fail(`${label} غير صالح`);
  return text;
}

function isoTimestamp(value: unknown, label: string): string {
  const text = requiredString(value, label);
  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString() !== text) fail(`${label} غير صالح`);
  return text;
}

function array(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) fail(`${label} مفقود أو غير صالح`);
  return value;
}

function uniqueId(set: Set<string>, id: string, label: string): void {
  if (set.has(id)) fail(`معرّف مكرر في ${label}: ${id}`);
  set.add(id);
}

function validateCounts(value: unknown, data: BackupData): BackupCounts {
  const counts = record(value, "أعداد السجلات");
  const expected: BackupCounts = {
    customers: data.customers.length,
    subscriptions: data.subscriptions.length,
    payments: data.payments.length,
    reminders: data.reminders.length,
  };
  for (const key of Object.keys(expected) as (keyof BackupCounts)[]) {
    if (counts[key] !== expected[key]) fail(`عدد سجلات ${key} لا يطابق محتوى النسخة`);
  }
  return expected;
}

export function validateBackup(value: unknown): BackupDocument {
  const root = record(value, "ملف النسخة الاحتياطية");
  if (root.app !== BACKUP_APP) fail("هذه النسخة لا تخص تطبيق متابعة الاشتراكات");
  if (root.backupVersion !== BACKUP_VERSION) {
    fail(typeof root.backupVersion === "number" && root.backupVersion > BACKUP_VERSION
      ? "إصدار النسخة الاحتياطية أحدث من الإصدار المدعوم"
      : "إصدار النسخة الاحتياطية غير مدعوم");
  }
  if (root.schemaVersion !== BACKUP_SCHEMA_VERSION) fail("إصدار بنية البيانات غير مدعوم");
  const exportedAt = isoTimestamp(root.exportedAt, "تاريخ إنشاء النسخة");
  const rawData = record(root.data, "بيانات النسخة");

  const customerIds = new Set<string>();
  const customers = array(rawData.customers, "customers").map((value, index): BackupCustomer => {
    const item = record(value, `العميل رقم ${index + 1}`);
    const id = requiredString(item.id, "معرّف العميل");
    uniqueId(customerIds, id, "العملاء");
    const customer: BackupCustomer = {
      id,
      name: requiredString(item.name, "اسم العميل"),
      phone: requiredString(item.phone, "هاتف العميل"),
      notes: nullableString(item.notes, "ملاحظات العميل"),
      created_at: isoTimestamp(item.created_at, "تاريخ إنشاء العميل"),
    };
    if (item.updated_at !== undefined) customer.updated_at = isoTimestamp(item.updated_at, "تاريخ تحديث العميل");
    return customer;
  });

  const subscriptionIds = new Set<string>();
  const subscriptions = array(rawData.subscriptions, "subscriptions").map((value, index): BackupSubscription => {
    const item = record(value, `الاشتراك رقم ${index + 1}`);
    const id = requiredString(item.id, "معرّف الاشتراك");
    uniqueId(subscriptionIds, id, "الاشتراكات");
    const customerId = requiredString(item.customer_id, "عميل الاشتراك");
    if (!customerIds.has(customerId)) fail("يوجد اشتراك مرتبط بعميل غير موجود");
    const startDate = isoDate(item.start_date, "تاريخ بداية الاشتراك");
    const endDate = isoDate(item.end_date, "تاريخ نهاية الاشتراك");
    if (endDate < startDate) fail("يوجد اشتراك بتاريخ نهاية يسبق تاريخ البداية");
    numeric(item.amount, "قيمة الاشتراك");
    return {
      id,
      customer_id: customerId,
      subscription_name: requiredString(item.subscription_name, "اسم الاشتراك"),
      amount: item.amount as number | string,
      start_date: startDate,
      end_date: endDate,
      status: requiredString(item.status, "حالة الاشتراك"),
      created_at: isoTimestamp(item.created_at, "تاريخ إنشاء الاشتراك"),
    };
  });
  const subscriptionMap = new Map(subscriptions.map((item) => [item.id, item]));

  const paymentIds = new Set<string>();
  const paidBySubscription = new Map<string, number>();
  const payments = array(rawData.payments, "payments").map((value, index): Payment => {
    const item = record(value, `الدفعة رقم ${index + 1}`);
    const id = requiredString(item.id, "معرّف الدفعة");
    uniqueId(paymentIds, id, "الدفعات");
    const customerId = requiredString(item.customer_id, "عميل الدفعة");
    const subscriptionId = requiredString(item.subscription_id, "اشتراك الدفعة");
    const subscription = subscriptionMap.get(subscriptionId);
    if (!subscription || subscription.customer_id !== customerId || !customerIds.has(customerId)) {
      fail("يوجد دفع مرتبط بعميل أو اشتراك غير صالح");
    }
    const amount = numeric(item.amount, "قيمة الدفعة");
    paidBySubscription.set(subscriptionId, (paidBySubscription.get(subscriptionId) ?? 0) + amount);
    return {
      id,
      customer_id: customerId,
      subscription_id: subscriptionId,
      amount: item.amount as number | string,
      payment_date: isoDate(item.payment_date, "تاريخ الدفعة"),
      notes: nullableString(item.notes, "ملاحظات الدفعة"),
      created_at: isoTimestamp(item.created_at, "تاريخ إنشاء الدفعة"),
    };
  });
  for (const [subscriptionId, paid] of paidBySubscription) {
    const subscription = subscriptionMap.get(subscriptionId)!;
    if (paid > numeric(subscription.amount, "قيمة الاشتراك")) fail("إجمالي الدفعات يتجاوز قيمة الاشتراك");
  }

  const reminderIds = new Set<string>();
  const reminderSchedules = new Set<string>();
  const reminderOffsets = new Set<string>();
  const reminderCounts = new Map<string, number>();
  const reminders = array(rawData.reminders, "reminders").map((value, index): Reminder => {
    const item = record(value, `التنبيه رقم ${index + 1}`);
    const id = requiredString(item.id, "معرّف التنبيه");
    uniqueId(reminderIds, id, "التنبيهات");
    const customerId = requiredString(item.customer_id, "عميل التنبيه");
    const subscriptionId = requiredString(item.subscription_id, "اشتراك التنبيه");
    const subscription = subscriptionMap.get(subscriptionId);
    if (!subscription || subscription.customer_id !== customerId || !customerIds.has(customerId)) {
      fail("يوجد تنبيه مرتبط بعميل أو اشتراك غير صالح");
    }
    const daysBefore = positiveInteger(item.days_before, "عدد أيام التنبيه");
    const scheduledDate = isoTimestamp(item.scheduled_date, "موعد التنبيه");
    const status = item.status;
    if (status !== "PENDING" && status !== "DUE" && status !== "SENT") fail("حالة تنبيه غير صالحة");
    const sentAt = item.sent_at === null ? null : isoTimestamp(item.sent_at, "وقت إرسال التنبيه");
    if ((status === "SENT") !== (sentAt !== null)) fail("حالة SENT ووقت الإرسال غير متسقين");
    if (reminderDate(subscription.end_date, daysBefore) !== scheduledDate.slice(0, 10)) fail("موعد التنبيه لا يطابق أيام التنبيه");
    const scheduleKey = `${subscriptionId}|${scheduledDate}`;
    const offsetKey = `${subscriptionId}|${daysBefore}`;
    if (reminderSchedules.has(scheduleKey) || reminderOffsets.has(offsetKey)) fail("يوجد تنبيه مكرر لنفس الاشتراك");
    reminderSchedules.add(scheduleKey);
    reminderOffsets.add(offsetKey);
    const count = (reminderCounts.get(subscriptionId) ?? 0) + 1;
    if (count > 3) fail("يوجد أكثر من ثلاثة تنبيهات لاشتراك واحد");
    reminderCounts.set(subscriptionId, count);
    return {
      id,
      customer_id: customerId,
      subscription_id: subscriptionId,
      days_before: daysBefore,
      scheduled_date: scheduledDate,
      status,
      sent_at: sentAt,
      created_at: isoTimestamp(item.created_at, "تاريخ إنشاء التنبيه"),
    };
  });

  const data: BackupData = { customers, subscriptions, payments, reminders };
  const counts = validateCounts(root.counts, data);
  return {
    app: BACKUP_APP,
    backupVersion: BACKUP_VERSION,
    exportedAt,
    schemaVersion: BACKUP_SCHEMA_VERSION,
    counts,
    data,
  };
}

export function createBackupDocument(data: BackupData, exportedAt = new Date().toISOString()): BackupDocument {
  return validateBackup({
    app: BACKUP_APP,
    backupVersion: BACKUP_VERSION,
    exportedAt,
    schemaVersion: BACKUP_SCHEMA_VERSION,
    counts: {
      customers: data.customers.length,
      subscriptions: data.subscriptions.length,
      payments: data.payments.length,
      reminders: data.reminders.length,
    },
    data,
  });
}

export function parseBackupJson(text: string): BackupDocument {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    fail("ملف النسخة الاحتياطية ليس JSON صالحًا");
  }
  return validateBackup(value);
}

export function serializeBackup(backup: BackupDocument): string {
  return JSON.stringify(validateBackup(backup), null, 2);
}

export function backupFilename(exportedAt: string): string {
  const stamp = isoTimestamp(exportedAt, "تاريخ إنشاء النسخة").slice(0, 16).replace("T", "-").replace(":", "-");
  return `subscription-tracker-backup-${stamp}.json`;
}
