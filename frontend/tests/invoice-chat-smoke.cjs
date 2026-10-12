const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  const errors = [];
  const base = process.env.METRIC_MINT_TEST_URL || 'http://127.0.0.1:5173';
  const username = process.env.METRIC_MINT_TEST_USERNAME || 'admin';
  const password = process.env.METRIC_MINT_TEST_PASSWORD || 'admin';
  const headers = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}` };
  const created = new Set(), tracking = [];
  page.on('response', response => {
    if (response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/conversations' && response.status() === 201) {
      tracking.push(response.json().then(body => created.add(body.id)));
    }
  });
  page.on('pageerror', error => errors.push(error.message));
  const output = path.resolve(__dirname, '../test-results');
  await fs.mkdir(output, { recursive: true });
  const send = async question => {
    const responsePromise = page.waitForResponse(response => /\/api\/conversations\/[^/]+\/questions$/.test(response.url()) && response.request().method() === 'POST' && response.request().postDataJSON().question === question);
    await page.getByLabel('Message Metric Mint', { exact: true }).fill(question);
    await page.waitForFunction(() => {
      const send = document.querySelector('button[aria-label="Send message"]');
      return send && !send.disabled;
    });
    await page.getByLabel('Message Metric Mint', { exact: true }).press('Enter');
    const response = await responsePromise;
    assert.equal(response.status(), 201);
    const answer = await response.json();
    assert.equal(answer.status, 'complete'); created.add(answer.conversationId);
    await page.locator('.chat-answer-text').last().filter({ hasText: answer.answer }).waitFor();
    return answer;
  };
  try {
    await page.goto(base);
    await page.getByLabel('Username', { exact: true }).fill(process.env.METRIC_MINT_TEST_USERNAME || 'admin');
    await page.getByLabel('Password', { exact: true }).fill(process.env.METRIC_MINT_TEST_PASSWORD || 'admin');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'New chat in General', exact: true }).click();
    await page.getByRole('heading', { name: 'What would you like to explore?' }).waitFor();
    await page.getByText('Show the latest invoice in the duplicate queue', { exact: true }).waitFor();
    const prompts = [
      'can you pull the last invoice number which is assigned to whom and is on duplicate queue',
      'can you pull the latest invoice which is one duplicate queue',
    ];
    for (const [index, question] of prompts.entries()) {
      if (index) await page.getByRole('button', { name: 'New chat', exact: true }).click();
      const answer = await send(question);
      assert.equal(answer.visualization, 'table');
      assert.equal(answer.rows.length, 1, 'Latest lookup returns a single invoice, not regional aggregates');
      const invoice = answer.rows[0];
      for (const field of ['invoiceNumber', 'assignedTo', 'queue']) assert.ok(Object.hasOwn(invoice, field), `Invoice includes ${field}`);
      assert.ok(!Object.hasOwn(invoice, 'total') && !Object.hasOwn(invoice, 'lob'), 'No aggregate totals presented as invoice details');
      assert.match(invoice.queue, /^Duplicate (Check|Identifier)$/);
      assert.ok(String(invoice.assignedTo).length > 0, 'Assignee is explicitly provided, including unassigned invoices');
      assert.equal(answer.lineage.metricStatus, 'OPERATIONAL');
      assert.ok(answer.lineage.tables.includes('dbo.NeonExternal'));
      assert.ok(answer.lineage.filters.some(filter => /lobID\s*=\s*92/i.test(filter)));
      assert.match(answer.explanation, /Latest means CreatedDate descending, then RowID descending/i);
      const rendered = page.locator('.chat-answer').last();
      for (const heading of [/^Invoice Number$/i, /^Assigned To$/i, /^Queue$/i]) await rendered.getByRole('columnheader', { name: heading }).waitFor();
      assert.equal(await rendered.locator('tbody tr').count(), 1);
      assert.ok((await rendered.locator('tbody').innerText()).includes(String(invoice.invoiceNumber ?? '—')));
      assert.ok((await rendered.locator('tbody').innerText()).includes(String(invoice.assignedTo)));
      assert.equal(await rendered.locator('.chat-chart').count(), 0, 'Invoice detail is displayed as a table');
      await rendered.locator('.chat-evidence > summary').click();
      const evidence = await rendered.locator('.chat-evidence').innerText();
      assert.match(evidence, /Operational lookup/);
      assert.match(evidence, /Lookup/);
      assert.match(evidence, /Latest I2P invoice by creation time/);
      assert.match(evidence, /NeonExternal/);
      assert.match(evidence, /lobID\s*=\s*92/i);
      assert.doesNotMatch(evidence, /Version 0|Verified metric|OPERATIONAL|Governed analytics/);
      await page.screenshot({ path: path.join(output, `invoice-chat-${index + 1}.png`), fullPage: true });
      const storedResponse = await page.request.get(`${base}/api/conversations/${answer.conversationId}/questions`, { headers });
      assert.equal(storedResponse.status(), 200);
      const stored = (await storedResponse.json())[0];
      assert.equal(stored.answer, answer.answer);
      assert.deepEqual(stored.rows, answer.rows);
      assert.deepEqual(stored.lineage, answer.lineage);
      assert.equal(stored.executedAt, answer.executedAt);
      await page.reload();
      await page.locator('.chat-answer-text').filter({ hasText: answer.answer }).waitFor();
      const general = page.getByRole('button', { name: 'Open project General', exact: true });
      if (await general.getAttribute('aria-expanded') === 'false') await general.click();
      assert.equal(await page.locator(`.chat-history-item[data-conversation-id="${answer.conversationId}"] .chat-history-select`).getAttribute('aria-current'), 'page',
        'Reload automatically restores the selected saved conversation');
      assert.equal(await page.locator('.chat-answer tbody tr').count(), 1, 'Invoice details survive page reload');
    }
    const unsupported = await send('Show the latest invoice in the duplicate queue on 2026-10-01');
    assert.ok(unsupported.clarification, 'Unsupported date filters require clarification');
    assert.equal(unsupported.rows.length, 0);
    assert.equal(await page.locator('.chat-answer').last().locator('table').count(), 0);
    assert.deepEqual(errors, [], 'No browser runtime errors');
    console.log('PASS: both invoice prompts render live duplicate-queue invoice/assignee details, honest lookup lineage/latest ordering, and date-filter clarification.');
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'invoice-chat-failure.png'), fullPage: true });
    throw error;
  } finally {
    await Promise.allSettled(tracking);
    for (const id of created) {
      const response = await page.request.delete(`${base}/api/conversations/${id}`, { headers });
      if (![204, 404].includes(response.status())) console.error(`Cleanup failed for smoke conversation ${id}: ${response.status()}`);
    }
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
