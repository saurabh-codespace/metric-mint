import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { transformWithOxc } from 'vite';

const { code } = await transformWithOxc(readFileSync(new URL('../src/templateLayout.ts', import.meta.url), 'utf8'), 'templateLayout.ts');
const { parseTemplateUpload, serializeTemplate } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
const widget = { id: 'w1', title: 'Volume', sourceType: 'workbook', sourceId: 'queue-management', chart: 'column', labelKey: 'queue', valueKey: 'volume', columns: [], width: 'half', rows: [{ secret: 'must not export' }], password: 'secret' };
const definition = { version: 1, name: 'Operations', description: 'Layout only', widgets: [widget], data: ['private'] };
const exported = serializeTemplate(definition);
const parsed = parseTemplateUpload(exported);
assert.equal(parsed.widgets[0].sourceId, 'queue-management');
assert.equal(parsed.widgets[0].width, 'half');
assert.ok(!exported.includes('secret') && !exported.includes('private') && !exported.includes('password'));
assert.equal(Object.keys(parsed).length, 4);
assert.throws(() => parseTemplateUpload('{broken'), /not valid JSON/);
assert.throws(() => parseTemplateUpload(JSON.stringify('界'.repeat(22000))), /64 KB/);
console.log('Template layout: import errors, UTF-8 size limit, and data-free export passed.');
