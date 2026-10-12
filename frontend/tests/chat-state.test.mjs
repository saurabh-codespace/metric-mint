import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { transformWithOxc } from 'vite';

const { code } = await transformWithOxc(readFileSync(new URL('../src/chatState.ts', import.meta.url), 'utf8'), 'chatState.ts');
const { questionMessages } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
const saved = {
  id: 'q1', conversationId: 'chat1', question: 'Invoices by region?', answer: '12 invoices', status: 'complete',
  rows: [{ region: 'APAC', count: 12 }], visualization: 'bar', explanation: 'Saved result',
  spec: { metric: 'invoice_count', dimensions: ['region'], filters: [] },
  lineage: { source: 'SQL Server', tables: ['invoices'], sql: 'SELECT ...' },
  warnings: ['Snapshot'], insights: ['APAC has 12 invoices'], aiGenerated: true, executedAt: '2026-10-12T12:00:00Z',
};
const [prompt, reply] = questionMessages(saved);
assert.equal(prompt.text, saved.question);
assert.notEqual(prompt.id, reply.id, 'Restored user and assistant messages have distinct stable IDs');
assert.equal(reply.status, 'complete');
for (const field of ['answer', 'rows', 'visualization', 'lineage', 'warnings', 'insights', 'aiGenerated', 'executedAt']) {
  assert.deepEqual(reply.answer[field], saved[field], `History retains ${field}`);
}
assert.deepEqual(reply.answer.query, saved.spec, 'Saved query uses the chart renderer contract');
for (const status of ['pending', 'error']) {
  const messages = questionMessages({ ...saved, status, error: status === 'error' ? 'Server restarted; retry' : null });
  assert.equal(messages[0].text, saved.question, 'Interrupted or failed answers retain the prompt');
  assert.equal(messages[1].status, status);
  assert.equal(messages[1].answer, undefined, 'Incomplete answers never display stale result rows');
}
const clarification = questionMessages({ ...saved, clarification: 'Which period?', rows: [], spec: null })[1];
assert.equal(clarification.answer.clarification, 'Which period?');
assert.deepEqual(clarification.answer.rows, []);
const legacy = questionMessages({ id: 'old', question: 'Old prompt', answer: 'Old answer', visualization: 'help' })[1];
assert.equal(legacy.status, 'complete');
assert.deepEqual(legacy.answer.rows, []);
assert.deepEqual(legacy.answer.insights, []);
console.log('Saved chat messages retain result evidence and safely restore pending, failed, legacy and clarification responses.');
