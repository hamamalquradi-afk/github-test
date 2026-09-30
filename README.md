# Subscription Collection Tracker

Subscription Collection Tracker is an Arabic RTL, mobile-first tracker for customers,
subscriptions, payments, renewal history, and configurable reminders.

Phase 5 moves normal application data to a browser-local IndexedDB repository. The
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

Data belongs to the current browser profile/device. Clearing site data or browser
storage removes that local data. Backup/restore and Google Drive integration are
intentionally deferred to a later phase.

## Requirements

- Node.js 20 or newer
- A modern browser with IndexedDB for application runtime
- A Supabase/PostgreSQL database only if you intentionally run the legacy `test:db`
  integration suite

## Commands

```bash
npm install
npm test
npm run typecheck
npm run build
npm run dev
```

No `NEXT_PUBLIC_SUPABASE_URL` or `NEXT_PUBLIC_SUPABASE_ANON_KEY` is required.

Historical database integration tests can still be run separately:

```bash
DATABASE_URL='postgresql://...' npm run test:db
```
