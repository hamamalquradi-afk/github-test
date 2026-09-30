# Subscription Collection Tracker

Phase 1 provides the PostgreSQL foundation for customers, subscriptions,
payments, and reminders. The schema is managed as a Supabase migration and is
covered by both fast contract tests and database integration tests.

Phase 2 adds an Arabic, RTL, mobile-first interface for customer, subscription,
renewal, and payment management. Copy `.env.example` to `.env.local` and provide
the Supabase project URL and anonymous key before starting Next.js.

## Requirements

- Node.js 20 or newer
- A Supabase/PostgreSQL database for integration tests

## Commands

```bash
npm install
npm test
NEXT_PUBLIC_SUPABASE_URL='...' NEXT_PUBLIC_SUPABASE_ANON_KEY='...' npm run dev
DATABASE_URL='postgresql://...' npm run test:db
```

Apply migrations with the Supabase CLI in an environment where it is installed:

```bash
supabase db push
```

The database test runs in a transaction and rolls back all test records.
