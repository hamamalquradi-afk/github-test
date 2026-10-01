# HAMNOVA v1.0.0

HAMNOVA — إدارة الاشتراكات والتحصيل — is an Arabic RTL, mobile-first tracker for customers,
subscriptions, payments, renewal history, and configurable reminders.

The application stores business data in a browser-local IndexedDB repository. The
runtime no longer requires Supabase, a remote database, authentication, or network
access to read and write business data. Existing PostgreSQL/Supabase migrations remain
in the repository as historical project artifacts and for the legacy database tests;
they are not used by the application runtime.

## Local persistence

IndexedDB stores four logical record types: customers, subscriptions, payments, and
reminders. The repository enforces relationships before writes, uses read/write
transactions for related records, keeps subscriptions/payments/reminders immutable
except for the accepted customer edits and reminder state transition, and preserves
renewals by inserting a new subscription record.

Data belongs to the current browser profile, device, and origin. Create JSON backups
regularly using Settings. Clearing browser/site data may delete local records.
Data does not automatically sync between devices, browsers, or domains.

Records created at `http://127.0.0.1`, `localhost`, or another origin do not
automatically appear on the production HTTPS domain, even on the same device.
Export a backup from the original origin and use Backup / Restore on the destination.
Restore replaces the destination's records after preview and explicit confirmation.
There is no hidden origin migration or cloud synchronization.

تُحفظ بيانات العمل محليًا في المتصفح. أنشئ نسخًا احتياطية بانتظام؛ مسح بيانات
المتصفح أو الموقع قد يحذف السجلات. لا تنتقل البيانات تلقائيًا بين الأجهزة أو
المتصفحات أو عناوين المواقع. استخدم النسخ الاحتياطي والاستعادة لنقل بياناتك
من عنوان محلي مثل localhost أو 127.0.0.1 إلى موقع الإنتاج.

## Requirements

- Node.js >=20.9.0 (use Node.js 22 for the existing CI baseline)
- A modern browser with IndexedDB for application runtime
- A Supabase/PostgreSQL database only if you intentionally run the legacy `test:db`
  integration suite

## Commands

```bash
npm ci
npm test
npm run typecheck
npm run build
npm run dev
```

No `NEXT_PUBLIC_SUPABASE_URL` or `NEXT_PUBLIC_SUPABASE_ANON_KEY` is required.

## Production hosting

Deploy the merged release commit to Vercel or a host supporting Next.js 16.3.8,
App Router, dynamic `/customers/[id]` routes, and HTTPS. Use `npm ci` for installation
and `npm run build` for the production build. Do not use a static GitHub Pages export.
The host serves the application shell; business records remain in browser IndexedDB.
No business database, authentication provider, Google API, or business-data environment
variables are required. Production deployment and the `v1.0.0` tag/release remain
pending until authorized deployment and production QA succeed.

## Owner and contact

- صاحب المشروع: همام القراضي
- Developer: Hammmam Al-quradi
- WhatsApp display number: 775933577
- WhatsApp: https://wa.me/967775933577 (Yemen country code confirmed by the owner)

## v1.0.0 release scope

Customer management; subscriptions and renewals; payment tracking; reminder management;
Arabic RTL; search, filtering and sorting; duplicate protection; atomic customer deletion;
dashboard; backup/restore; local-date correctness; approved HAMNOVA branding and owner/contact
page. Security baseline: Next.js 16.3.8, PostCSS 8.5.23, and Node.js >=20.9.0.

Historical database integration tests can still be run separately:

```bash
DATABASE_URL='postgresql://...' npm run test:db
```
