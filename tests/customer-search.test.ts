import assert from "node:assert/strict";
import test from "node:test";
import { createLatestSearchRunner, customerSearchQuery } from "../lib/customer-search.ts";

function queryFrom(url: string): string {
  return customerSearchQuery(new URL(url, "https://local.test").searchParams);
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

test("direct customer URL load starts with the URL query", () => {
  assert.equal(queryFrom("/customers?q=Ali"), "Ali");
});

test("reload preserves the existing customer query", () => {
  const url = "/customers?q=Ali";
  assert.equal(queryFrom(url), "Ali");
  assert.equal(queryFrom(url), "Ali");
});

test("fast customer query changes keep only the newest result current", async () => {
  const pending = new Map<string, ReturnType<typeof deferred<string[]>>>();
  const runner = createLatestSearchRunner((query) => {
    const request = deferred<string[]>();
    pending.set(query, request);
    return request.promise;
  });

  const all = runner("");
  const a = runner("A");
  const ali = runner("Ali");

  pending.get("Ali")!.resolve(["Ali"]);
  assert.deepEqual(await ali, { current: true, value: ["Ali"] });
  pending.get("A")!.resolve(["A", "Ali"]);
  assert.equal((await a).current, false);
  pending.get("")!.resolve(["Ali", "Sara"]);
  assert.equal((await all).current, false);
});

test("out-of-order completion cannot overwrite the newer customer search", async () => {
  const oldRequest = deferred<string[]>();
  const newRequest = deferred<string[]>();
  const runner = createLatestSearchRunner((query) => query ? newRequest.promise : oldRequest.promise);
  let displayed: string[] = [];

  const oldLoad = runner("").then((result) => { if (result.current && result.value) displayed = result.value; });
  const newLoad = runner("Ali").then((result) => { if (result.current && result.value) displayed = result.value; });

  newRequest.resolve(["Ali"]);
  await newLoad;
  assert.deepEqual(displayed, ["Ali"]);

  oldRequest.resolve(["Ali", "Sara"]);
  await oldLoad;
  assert.deepEqual(displayed, ["Ali"]);
});

test("clearing the customer query restores the unfiltered query", () => {
  assert.equal(queryFrom("/customers?q="), "");
  assert.equal(queryFrom("/customers"), "");
});
