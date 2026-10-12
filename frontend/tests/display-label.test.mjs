import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { transformWithOxc } from 'vite';

const { code } = await transformWithOxc(readFileSync(new URL('../src/displayLabel.ts', import.meta.url), 'utf8'), 'displayLabel.ts');
const { displayLabel } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
assert.equal(displayLabel('Accounts Payable / I2P'), 'Accounts Payable');
assert.equal(displayLabel('Monthly I2P invoice intake'), 'Monthly invoice intake');
assert.equal(displayLabel('Toscana-IFRS'), 'Connected source');
assert.equal(displayLabel('Toscana_I2P-SIT'), 'Connected source');
assert.equal(displayLabel('IFRS reports'), 'financial reporting reports');
assert.equal(displayLabel('Supplier invoices'), 'Supplier invoices');
console.log('Display label checks passed');
