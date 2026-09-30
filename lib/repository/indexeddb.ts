import { createBackupDocument, validateBackup, type BackupDocument, type BackupCustomer, type BackupSubscription } from "../backup.ts";
import { parseReminderDays, reminderDate } from "../domain.ts";
import type { Customer, Payment, Reminder, ReminderView, Subscription } from "../types.ts";

const DATABASE_NAME = "subscription-collection-tracker";
const DATABASE_VERSION = 1;

const STORES = {
  customers: "customers",
  subscriptions: "subscriptions",
  payments: "payments",
  reminders: "reminders",
} as const;

export class LocalStorageError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "LocalStorageError";
  }
}

function localError(message: string, cause?: unknown): LocalStorageError {
  return new LocalStorageError(message, cause === undefined ? undefined : { cause });
}

function request<T>(value: IDBRequest<T>, message: string): Promise<T> {
  return new Promise((resolve, reject) => {
    value.onsuccess = () => resolve(value.result);
    value.onerror = () => reject(localError(message, value.error));
  });
}

function transactionDone(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(localError("تعذر حفظ البيانات المحلية.", transaction.error));
    transaction.onerror = () => reject(localError("حدث خطأ أثناء تحديث البيانات المحلية.", transaction.error));
  });
}

function uuid(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`;
}

function nowIso(): string {
  return new Date().toISOString();
}

function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

function ensureIndexedDb(): IDBFactory {
  if (!globalThis.indexedDB) {
    throw localError("التخزين المحلي غير متاح في هذا المتصفح.");
  }
  return globalThis.indexedDB;
}

let databasePromise: Promise<IDBDatabase> | null = null;

export function resetLocalDatabaseConnectionForTests(): void {
  databasePromise = null;
}

export function openLocalDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise;
  databasePromise = new Promise((resolve, reject) => {
    const open = ensureIndexedDb().open(DATABASE_NAME, DATABASE_VERSION);
    open.onupgradeneeded = () => {
      const db = open.result;
      if (!db.objectStoreNames.contains(STORES.customers)) {
        const customers = db.createObjectStore(STORES.customers, { keyPath: "id" });
        customers.createIndex("created_at", "created_at");
      }
      if (!db.objectStoreNames.contains(STORES.subscriptions)) {
        const subscriptions = db.createObjectStore(STORES.subscriptions, { keyPath: "id" });
        subscriptions.createIndex("customer_id", "customer_id");
        subscriptions.createIndex("end_date", "end_date");
      }
      if (!db.objectStoreNames.contains(STORES.payments)) {
        const payments = db.createObjectStore(STORES.payments, { keyPath: "id" });
        payments.createIndex("customer_id", "customer_id");
        payments.createIndex("subscription_id", "subscription_id");
      }
      if (!db.objectStoreNames.contains(STORES.reminders)) {
        const reminders = db.createObjectStore(STORES.reminders, { keyPath: "id" });
        reminders.createIndex("customer_id", "customer_id");
        reminders.createIndex("subscription_id", "subscription_id");
        reminders.createIndex("scheduled_date", "scheduled_date");
        reminders.createIndex("subscription_schedule", ["subscription_id", "scheduled_date"], { unique: true });
      }
    };
    open.onsuccess = () => {
      const db = open.result;
      db.onversionchange = () => {
        db.close();
        databasePromise = null;
      };
      resolve(db);
    };
    open.onerror = () => {
      databasePromise = null;
      reject(localError("تعذر فتح قاعدة البيانات المحلية.", open.error));
    };
    open.onblocked = () => {
      databasePromise = null;
      reject(localError("تعذر تحديث التخزين المحلي. أغلق النوافذ الأخرى للتطبيق ثم أعد المحاولة."));
    };
  });
  return databasePromise;
}

async function all<T>(store: IDBObjectStore, message: string): Promise<T[]> {
  return request(store.getAll(), message) as Promise<T[]>;
}

async function byIndex<T>(store: IDBObjectStore, index: string, key: IDBValidKey, message: string): Promise<T[]> {
  return request(store.index(index).getAll(key), message) as Promise<T[]>;
}

function sortHistory(customer: Customer): Customer {
  customer.subscriptions.sort((a, b) => b.start_date.localeCompare(a.start_date) || b.created_at.localeCompare(a.created_at));
  for (const subscription of customer.subscriptions) {
    subscription.payments.sort((a, b) => b.payment_date.localeCompare(a.payment_date) || b.created_at.localeCompare(a.created_at));
  }
  return customer;
}

async function hydrateCustomer(db: IDBDatabase, customer: Omit<Customer, "subscriptions">): Promise<Customer> {
  const tx = db.transaction([STORES.subscriptions, STORES.payments], "readonly");
  const subscriptions = await byIndex<Subscription>(tx.objectStore(STORES.subscriptions), "customer_id", customer.id, "تعذر قراءة اشتراكات العميل.");
  const payments = await byIndex<Payment>(tx.objectStore(STORES.payments), "customer_id", customer.id, "تعذر قراءة دفعات العميل.");
  const grouped = new Map<string, Payment[]>();
  for (const payment of payments) {
    const values = grouped.get(payment.subscription_id) ?? [];
    values.push(payment);
    grouped.set(payment.subscription_id, values);
  }
  await transactionDone(tx);
  return sortHistory({ ...customer, subscriptions: subscriptions.map((subscription) => ({ ...subscription, payments: grouped.get(subscription.id) ?? [] })) });
}

export async function getCustomers(search = ""): Promise<Customer[]> {
  const db = await openLocalDatabase();
  const tx = db.transaction(STORES.customers, "readonly");
  const rows = await all<Omit<Customer, "subscriptions">>(tx.objectStore(STORES.customers), "تعذر قراءة العملاء.");
  await transactionDone(tx);
  const term = search.trim().toLocaleLowerCase("ar");
  const filtered = term ? rows.filter((row) => row.name.toLocaleLowerCase("ar").includes(term) || row.phone.includes(term)) : rows;
  const customers = await Promise.all(filtered.map((row) => hydrateCustomer(db, row)));
  return customers.sort((a, b) => b.created_at.localeCompare(a.created_at));
}

export async function getCustomer(id: string): Promise<Customer | null> {
  const db = await openLocalDatabase();
  const tx = db.transaction(STORES.customers, "readonly");
  const customer = await request(tx.objectStore(STORES.customers).get(id), "تعذر قراءة بيانات العميل.") as Omit<Customer, "subscriptions"> | undefined;
  await transactionDone(tx);
  return customer ? hydrateCustomer(db, customer) : null;
}

export async function createCustomer(input: { name: string; phone: string; notes?: string | null }): Promise<string> {
  const name = input.name.trim();
  const phone = input.phone.trim();
  if (!name) throw new Error("الاسم مطلوب");
  if (!phone) throw new Error("الهاتف مطلوب");
  const db = await openLocalDatabase();
  const tx = db.transaction(STORES.customers, "readwrite");
  const timestamp = nowIso();
  const customer = { id: uuid(), name, phone, notes: input.notes?.trim() || null, created_at: timestamp, updated_at: timestamp };
  tx.objectStore(STORES.customers).add(customer);
  await transactionDone(tx);
  return customer.id;
}

export async function updateCustomer(id: string, input: { name: string; phone: string; notes?: string | null }): Promise<void> {
  const name = input.name.trim();
  const phone = input.phone.trim();
  if (!name) throw new Error("الاسم مطلوب");
  if (!phone) throw new Error("الهاتف مطلوب");
  const db = await openLocalDatabase();
  const tx = db.transaction(STORES.customers, "readwrite");
  const store = tx.objectStore(STORES.customers);
  const current = await request(store.get(id), "تعذر قراءة بيانات العميل.") as Omit<Customer, "subscriptions"> | undefined;
  if (!current) {
    tx.abort();
    throw new Error("العميل غير موجود");
  }
  store.put({ ...current, name, phone, notes: input.notes?.trim() || null, updated_at: nowIso() });
  await transactionDone(tx);
}

function assertDateRange(startDate: string, endDate: string): void {
  if (!startDate || !endDate || endDate < startDate) throw new Error("تاريخ النهاية يجب ألا يسبق تاريخ البداية");
}

function assertAmount(value: number, label: string): number {
  if (!Number.isFinite(value) || value < 0) throw new Error(`${label} يجب أن يكون صفرًا أو أكثر`);
  return value;
}

function makeReminder(subscription: Subscription, daysBefore: number, timestamp: string): Reminder {
  const scheduledDate = reminderDate(subscription.end_date, daysBefore);
  return {
    id: uuid(), customer_id: subscription.customer_id, subscription_id: subscription.id,
    days_before: daysBefore, scheduled_date: `${scheduledDate}T00:00:00.000Z`,
    status: scheduledDate <= todayUtc() ? "DUE" : "PENDING", sent_at: null, created_at: timestamp,
  };
}

export async function createSubscription(input: {
  customer_id: string; subscription_name: string; amount: number; start_date: string; end_date: string; reminder_days: number[];
}): Promise<string> {
  const name = input.subscription_name.trim();
  if (!name) throw new Error("اسم الاشتراك مطلوب");
  assertDateRange(input.start_date, input.end_date);
  const amount = assertAmount(input.amount, "المبلغ");
  const days = parseReminderDays(input.reminder_days.join(","));
  const db = await openLocalDatabase();
  const tx = db.transaction([STORES.customers, STORES.subscriptions, STORES.reminders], "readwrite");
  const customer = await request(tx.objectStore(STORES.customers).get(input.customer_id), "تعذر التحقق من العميل.");
  if (!customer) {
    tx.abort();
    throw new Error("العميل غير موجود");
  }
  const timestamp = nowIso();
  const subscription: Subscription = {
    id: uuid(), customer_id: input.customer_id, subscription_name: name, amount,
    start_date: input.start_date, end_date: input.end_date, status: "active", created_at: timestamp, payments: [],
  };
  const persisted = { ...subscription } as Partial<Subscription>;
  delete persisted.payments;
  tx.objectStore(STORES.subscriptions).add(persisted);
  if (input.end_date >= todayUtc()) {
    const reminders = tx.objectStore(STORES.reminders);
    for (const day of days) reminders.add(makeReminder(subscription, day, timestamp));
  }
  await transactionDone(tx);
  return subscription.id;
}

export async function configureReminders(subscriptionId: string, customerId: string, reminderDays: number[]): Promise<void> {
  const days = parseReminderDays(reminderDays.join(","));
  const db = await openLocalDatabase();
  const tx = db.transaction([STORES.subscriptions, STORES.reminders], "readwrite");
  const subscription = await request(tx.objectStore(STORES.subscriptions).get(subscriptionId), "تعذر قراءة الاشتراك.") as Subscription | undefined;
  if (!subscription || subscription.customer_id !== customerId) {
    tx.abort();
    throw new Error("الاشتراك غير مرتبط بهذا العميل");
  }
  if (subscription.status.toLowerCase() !== "active" || subscription.end_date < todayUtc()) {
    tx.abort();
    throw new Error("يمكن إنشاء التنبيهات للاشتراكات النشطة فقط");
  }
  const store = tx.objectStore(STORES.reminders);
  const existing = await byIndex<Reminder>(store, "subscription_id", subscriptionId, "تعذر قراءة التنبيهات الحالية.");
  const requested = days.map((day) => makeReminder(subscription, day, nowIso()));
  const dates = new Set([...existing.map((item) => item.scheduled_date), ...requested.map((item) => item.scheduled_date)]);
  if (dates.size > 3) {
    tx.abort();
    throw new Error("الحد الأقصى ثلاثة تنبيهات لكل اشتراك مع الحفاظ على السجل السابق");
  }
  const existingDates = new Set(existing.map((item) => item.scheduled_date));
  for (const reminder of requested) if (!existingDates.has(reminder.scheduled_date)) store.add(reminder);
  await transactionDone(tx);
}

export async function createPayment(input: { customer_id: string; subscription_id: string; amount: number; payment_date: string; notes?: string | null }): Promise<string> {
  const amount = assertAmount(input.amount, "الدفعة");
  if (!input.payment_date) throw new Error("تاريخ الدفعة مطلوب");
  const db = await openLocalDatabase();
  const tx = db.transaction([STORES.subscriptions, STORES.payments], "readwrite");
  const subscription = await request(tx.objectStore(STORES.subscriptions).get(input.subscription_id), "تعذر قراءة الاشتراك.") as Subscription | undefined;
  if (!subscription || subscription.customer_id !== input.customer_id) {
    tx.abort();
    throw new Error("الدفعة غير مرتبطة باشتراك صحيح");
  }
  const store = tx.objectStore(STORES.payments);
  const existing = await byIndex<Payment>(store, "subscription_id", input.subscription_id, "تعذر قراءة الدفعات السابقة.");
  const paid = existing.reduce((sum, payment) => sum + Number(payment.amount), 0);
  if (amount > Math.max(Number(subscription.amount) - paid, 0)) {
    tx.abort();
    throw new Error("قيمة الدفعة أكبر من المبلغ المتبقي");
  }
  const payment: Payment = {
    id: uuid(), customer_id: input.customer_id, subscription_id: input.subscription_id,
    amount, payment_date: input.payment_date, notes: input.notes?.trim() || null, created_at: nowIso(),
  };
  store.add(payment);
  await transactionDone(tx);
  return payment.id;
}

async function refreshDueReminders(db: IDBDatabase, today: string): Promise<void> {
  const tx = db.transaction([STORES.subscriptions, STORES.reminders], "readwrite");
  const subscriptions = await all<Subscription>(tx.objectStore(STORES.subscriptions), "تعذر قراءة الاشتراكات.");
  const active = new Map(subscriptions.filter((item) => item.status.toLowerCase() === "active" && item.end_date >= today).map((item) => [item.id, item]));
  const reminders = await all<Reminder>(tx.objectStore(STORES.reminders), "تعذر قراءة التنبيهات.");
  const store = tx.objectStore(STORES.reminders);
  for (const reminder of reminders) {
    if (reminder.status === "PENDING" && reminder.scheduled_date.slice(0, 10) <= today && active.has(reminder.subscription_id)) {
      store.put({ ...reminder, status: "DUE" });
    }
  }
  await transactionDone(tx);
}

async function reminderViews(customerId?: string, today?: string): Promise<ReminderView[]> {
  const db = await openLocalDatabase();
  if (today) await refreshDueReminders(db, today);
  const tx = db.transaction([STORES.customers, STORES.subscriptions, STORES.reminders], "readonly");
  const customers = await all<Omit<Customer, "subscriptions">>(tx.objectStore(STORES.customers), "تعذر قراءة العملاء.");
  const subscriptions = await all<Subscription>(tx.objectStore(STORES.subscriptions), "تعذر قراءة الاشتراكات.");
  const reminders = customerId
    ? await byIndex<Reminder>(tx.objectStore(STORES.reminders), "customer_id", customerId, "تعذر قراءة تنبيهات العميل.")
    : await all<Reminder>(tx.objectStore(STORES.reminders), "تعذر قراءة التنبيهات.");
  await transactionDone(tx);
  const customerMap = new Map(customers.map((item) => [item.id, item]));
  const subscriptionMap = new Map(subscriptions.map((item) => [item.id, item]));
  return reminders.flatMap((reminder) => {
    const subscription = subscriptionMap.get(reminder.subscription_id);
    const customer = subscription ? customerMap.get(subscription.customer_id) : undefined;
    if (!subscription || !customer) return [];
    if (today && (subscription.status.toLowerCase() !== "active" || subscription.end_date < today)) return [];
    return [{
      id: reminder.id, customer_id: reminder.customer_id, subscription_id: reminder.subscription_id,
      days_before: reminder.days_before, scheduled_date: reminder.scheduled_date, sent_at: reminder.sent_at,
      status: reminder.status, created_at: reminder.created_at,
      subscriptions: { status: subscription.status, subscription_name: subscription.subscription_name, end_date: subscription.end_date, customers: { name: customer.name, phone: customer.phone } },
    } satisfies ReminderView];
  }).sort((a, b) => customerId ? b.scheduled_date.localeCompare(a.scheduled_date) : a.scheduled_date.localeCompare(b.scheduled_date));
}

export async function getReminders(today: string): Promise<ReminderView[]> {
  return reminderViews(undefined, today);
}

export async function getCustomerReminders(customerId: string): Promise<ReminderView[]> {
  return reminderViews(customerId);
}

export async function markReminderSent(reminderId: string): Promise<void> {
  const db = await openLocalDatabase();
  const tx = db.transaction([STORES.subscriptions, STORES.reminders], "readwrite");
  const store = tx.objectStore(STORES.reminders);
  const reminder = await request(store.get(reminderId), "تعذر قراءة التنبيه.") as Reminder | undefined;
  if (!reminder) {
    tx.abort();
    throw new Error("التنبيه غير موجود");
  }
  if (reminder.status === "SENT") {
    await transactionDone(tx);
    return;
  }
  const subscription = await request(tx.objectStore(STORES.subscriptions).get(reminder.subscription_id), "تعذر قراءة الاشتراك.") as Subscription | undefined;
  const today = todayUtc();
  if (reminder.status !== "DUE" || reminder.scheduled_date.slice(0, 10) > today || !subscription || subscription.status.toLowerCase() !== "active" || subscription.end_date < today) {
    tx.abort();
    throw new Error("يمكن إرسال التنبيهات المستحقة حاليًا فقط");
  }
  store.put({ ...reminder, status: "SENT", sent_at: nowIso() });
  await transactionDone(tx);
}

export async function clearLocalDatabaseForTests(): Promise<void> {
  if (databasePromise) {
    const db = await databasePromise;
    db.close();
    databasePromise = null;
  }
  await new Promise<void>((resolve, reject) => {
    const deletion = ensureIndexedDb().deleteDatabase(DATABASE_NAME);
    deletion.onsuccess = () => resolve();
    deletion.onerror = () => reject(localError("تعذر إعادة ضبط قاعدة البيانات المحلية للاختبار.", deletion.error));
    deletion.onblocked = () => reject(localError("قاعدة البيانات المحلية ما زالت مفتوحة أثناء الاختبار."));
  });
}


export async function exportAllData(): Promise<BackupDocument> {
  const db = await openLocalDatabase();
  const tx = db.transaction([STORES.customers, STORES.subscriptions, STORES.payments, STORES.reminders], "readonly");
  const [customers, subscriptions, payments, reminders] = await Promise.all([
    all<BackupCustomer>(tx.objectStore(STORES.customers), "تعذر تصدير العملاء."),
    all<BackupSubscription>(tx.objectStore(STORES.subscriptions), "تعذر تصدير الاشتراكات."),
    all<Payment>(tx.objectStore(STORES.payments), "تعذر تصدير الدفعات."),
    all<Reminder>(tx.objectStore(STORES.reminders), "تعذر تصدير التنبيهات."),
  ]);
  await transactionDone(tx);
  return createBackupDocument({ customers, subscriptions, payments, reminders });
}

export async function restoreBackup(backup: BackupDocument): Promise<void> {
  const validated = validateBackup(backup);
  const db = await openLocalDatabase();
  const tx = db.transaction([STORES.customers, STORES.subscriptions, STORES.payments, STORES.reminders], "readwrite");
  const completion = transactionDone(tx);
  try {
    const customers = tx.objectStore(STORES.customers);
    const subscriptions = tx.objectStore(STORES.subscriptions);
    const payments = tx.objectStore(STORES.payments);
    const reminders = tx.objectStore(STORES.reminders);

    await Promise.all([
      request(customers.clear(), "تعذر تجهيز العملاء للاستعادة."),
      request(subscriptions.clear(), "تعذر تجهيز الاشتراكات للاستعادة."),
      request(payments.clear(), "تعذر تجهيز الدفعات للاستعادة."),
      request(reminders.clear(), "تعذر تجهيز التنبيهات للاستعادة."),
    ]);

    for (const item of validated.data.customers) customers.add({ ...item });
    for (const item of validated.data.subscriptions) subscriptions.add({ ...item });
    for (const item of validated.data.payments) payments.add({ ...item });
    for (const item of validated.data.reminders) reminders.add({ ...item });

    await completion;
  } catch (error) {
    try { tx.abort(); } catch {}
    try { await completion; } catch {}
    throw localError("تعذر استعادة النسخة الاحتياطية. بقيت البيانات الحالية دون تغيير.", error);
  }
}
