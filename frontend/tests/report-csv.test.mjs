import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transformWithOxc } from 'vite';

const source = await readFile(new URL('../src/reportCsv.ts', import.meta.url), 'utf8');
const { code } = await transformWithOxc(source, 'reportCsv.ts');
const { reportCsv } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);

test('report exports preserve quoted text, column order and numeric values', () => {
  assert.equal(reportCsv([{ category: 'Vendor, "A"', amount: 25.5 }, { amount: -10, category: 'Other' }]),
    '"category","amount"\r\n"Vendor, ""A""","25.5"\r\n"Other","-10"');
});

test('report exports neutralize spreadsheet formula strings and handle empty rows', () => {
  assert.equal(reportCsv([{ name: '=1+1', note: '\t@SUM(A1)' }]), '"name","note"\r\n"\'=1+1","\'\t@SUM(A1)"');
  assert.equal(reportCsv([]), '');
});
