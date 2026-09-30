import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import pg from "pg";

if (!process.env.DATABASE_URL) {
  console.error("DATABASE_URL is required to run database integration tests.");
  process.exit(1);
}

const { Client } = pg;
const client = new Client({ connectionString: process.env.DATABASE_URL });
// Exercise the legacy-to-Phase-3 upgrade in isolation, never against application rows.
const testSchema = "phase3_test_" + randomUUID().replaceAll("-", "");
const files = [
  "supabase/migrations/20260930000000_create_core_records.sql",
  "tests/fixtures/phase3-legacy-reminders.sql",
  "supabase/migrations/20260930010000_phase3_reminders.sql",
  "tests/database-phase3.sql",
  "tests/database.sql",
  "tests/database-phase2.sql",
];
const statements = await Promise.all(files.map(async (path) => {
  const sql = await readFile(new URL("../" + path, import.meta.url), "utf8");
  // Keep one outer transaction: strip only the files' outer transaction wrappers.
  return sql.replace(/^\s*begin;\s*/i, "").replace(/\s*(?:commit|rollback);\s*$/i, "")
    .replaceAll("public.", testSchema + ".")
    .replaceAll("set search_path = public", "set search_path = " + testSchema + ", public");
}));

try {
  await client.connect();
  await client.query("begin");
  await client.query("create schema " + testSchema);
  await client.query("set local search_path = " + testSchema + ", public");
  for (const sql of statements) await client.query(sql);
  console.log("Phase 1, Phase 2 and Phase 3 database integration tests passed.");
} finally {
  // Also rolls back the isolated schema and fixtures after an assertion fails.
  try { await client.query("rollback"); } finally { await client.end(); }
}
