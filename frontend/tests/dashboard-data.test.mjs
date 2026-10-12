import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transformWithOxc } from 'vite';

const source = await readFile(new URL('../src/dashboardData.ts', import.meta.url), 'utf8');
const { code: compiled } = await transformWithOxc(source, 'dashboardData.ts');
const { selectInvoices, summarize, buildOverview } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);
const invoice = { id: 'INV-1', vendor: 'Test vendor', unit: 'Finance', date: '2026-10-09', asOf: '2026-10-09', amount: 100.25, currency: 'USD', status: 'Paid', cycle: 5, automated: true, age: 0 };

test('date filters have nonoverlapping current and previous periods, scoped to the business unit', () => {
  const rows = [invoice, { ...invoice, id: '2', age: 29 }, { ...invoice, id: '3', age: 30 }, { ...invoice, id: '4', age: 59 }, { ...invoice, id: '5', age: 60 }, { ...invoice, id: '6', unit: 'People' }];
  assert.deepEqual(selectInvoices(rows, 30, 'Finance').map(row => row.id), ['INV-1', '2']);
  assert.deepEqual(selectInvoices(rows, 30, 'Finance', true).map(row => row.id), ['3', '4']);
});

test('financial and operational metrics reconcile, and SLA includes the fifth day', () => {
  const rows = [invoice, { ...invoice, id: '2', amount: 50.5, cycle: 6, automated: false }, { ...invoice, id: '3', amount: 25.25, status: 'Exception', age: 5 }, { ...invoice, id: '4', amount: 24, status: 'In review', age: 6, automated: false }];
  const result = summarize(rows);
  assert.equal(result.amount, 200);
  assert.equal(result.outstanding, 49.25);
  assert.equal(result.completion, 50);
  assert.equal(result.cycle, 5.5);
  assert.equal(result.sla, 50);
  assert.equal(result.automation, 50);
  assert.equal(result.exceptions, 1);
  const overview = buildOverview(rows);
  assert.equal(overview.statuses.reduce((sum, item) => sum + item.count, 0), result.total);
  assert.equal(overview.lobs.reduce((sum, item) => sum + item.total, 0), result.total);
});

test('empty selections never produce invalid numbers', () => {
  for (const value of Object.values(summarize([]))) assert.equal(value, 0);
  assert.equal(buildOverview([]).lobs.length, 0);
});
