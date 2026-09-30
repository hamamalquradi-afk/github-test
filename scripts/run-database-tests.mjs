import { readFile } from "node:fs/promises";
import pg from "pg";

if (!process.env.DATABASE_URL) {
  console.error("DATABASE_URL is required to run database integration tests.");
  process.exit(1);
}

const { Client } = pg;
const client = new Client({ connectionString: process.env.DATABASE_URL });
const migration = await readFile(
  new URL("../supabase/migrations/20260930000000_create_core_records.sql", import.meta.url),
  "utf8",
);
const tests = await readFile(new URL("../tests/database.sql", import.meta.url), "utf8");
const phaseTwoTests = await readFile(new URL("../tests/database-phase2.sql", import.meta.url), "utf8");

try {
  await client.connect();
  const { rows } = await client.query("select to_regclass('public.customers') as customers");
  if (!rows[0].customers) await client.query(migration);
  await client.query(tests);
  await client.query(phaseTwoTests);
  console.log("Database integration tests passed.");
} finally {
  await client.end();
}
