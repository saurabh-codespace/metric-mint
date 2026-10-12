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
  const created = new Set(), tracking = [], chatIds = new Map();
  page.on('response', response => {
    if (response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/conversations' && response.status() === 201) {
      tracking.push(response.json().then(body => { created.add(body.id); chatIds.set(body.title, body.id); }));
    }
  });
  page.on('pageerror', error => errors.push(error.message));
  const output = path.resolve(__dirname, '../test-results');
  await fs.mkdir(output, { recursive: true });
  const endpoint = '**/api/conversations/*/questions';
  const savedChats = async () => {
    const response = await page.request.get(`${base}/api/conversations`, { headers });
    assert.equal(response.status(), 200); return response.json();
  };
  const login = async () => {
    await page.getByLabel('Username', { exact: true }).fill(process.env.METRIC_MINT_TEST_USERNAME || 'admin');
    await page.getByLabel('Password', { exact: true }).fill(process.env.METRIC_MINT_TEST_PASSWORD || 'admin');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'New chat', exact: true }).waitFor();
  };
  const composer = () => page.getByLabel('Message Metric Mint', { exact: true });
  const submit = async question => {
    await composer().fill(question);
    await page.waitForFunction(() => {
      const send = document.querySelector('button[aria-label="Send message"]');
      return send && !send.disabled;
    });
    await composer().press('Enter');
  };
  const send = async question => {
    const responsePromise = page.waitForResponse(response => /\/api\/conversations\/[^/]+\/questions$/.test(response.url()) && response.request().method() === 'POST' && response.request().postDataJSON().question === question);
    await submit(question);
    const response = await responsePromise;
    assert.equal(response.status(), 201);
    const answer = await response.json();
    assert.equal(answer.status, 'complete');
    created.add(answer.conversationId); if (!chatIds.has(question)) chatIds.set(question, answer.conversationId);
    await page.locator('.chat-answer-text').last().filter({ hasText: answer.answer }).waitFor();
    return answer;
  };
  const history = title => page.locator(`.chat-history-item[data-conversation-id="${chatIds.get(title)}"] .chat-history-select`);
  const openHistory = async title => {
    const general = page.getByRole('button', { name: 'Open project General', exact: true });
    if (await general.getAttribute('aria-expanded') === 'false') await general.click();
    await history(title).click();
  };
  let release = () => {};
  try {
    const baseline = await savedChats();
    await page.goto(base);
    await login();
    await page.getByRole('button', { name: 'New chat in General', exact: true }).click();
    await page.screenshot({ path: path.join(output, 'chat-home.png'), fullPage: true });
    assert.equal((await savedChats()).length, baseline.length);
    await composer().fill('First line'); await composer().press('Shift+Enter'); await composer().type('Second line');
    assert.equal(await composer().inputValue(), 'First line\nSecond line');
    assert.equal(await page.locator('.chat-message').count(), 0, 'Shift+Enter does not submit');
    const firstQuestion = 'Show monthly invoice volume';
    const first = await send(firstQuestion);
    assert.ok(first.rows.length > 0, 'Real backend returned data');
    assert.ok(first.executedAt, 'Response carries query time');
    assert.equal(await page.locator('.chat-answer tbody tr').count(), first.rows.length);
    await page.locator('.chat-data-details > summary').click();
    await page.locator('.chat-evidence > summary').click();
    await page.screenshot({ path: path.join(output, 'chat-answer.png'), fullPage: true });
    await page.getByRole('button', { name: 'Reports', exact: true }).click();
    await page.getByRole('button', { name: 'Report templates', exact: true }).click();
    await page.getByRole('button', { name: 'Create template', exact: true }).waitFor();
    await openHistory(firstQuestion);
    assert.equal(await page.locator('.chat-message.user').count(), 1, 'Conversation survives navigation');
    await page.getByRole('button', { name: 'New chat', exact: true }).click();
    await page.getByRole('heading', { name: 'What would you like to explore?' }).waitFor();
    const secondQuestion = 'Which exception queues need attention?';
    await send(secondQuestion);
    assert.equal(await page.locator('.chat-message.user').count(), 1);
    assert.equal((await savedChats()).length, baseline.length + 2);

    let gate = new Promise(resolve => { release = resolve; });
    const delayedQuestion = 'Compare invoice completion rates by LOB';
    await page.route(endpoint, async route => {
      if (route.request().method() !== 'POST' || route.request().postDataJSON().question !== delayedQuestion) return route.continue();
      const response = await route.fetch(); await gate;
      await route.fulfill({ response }).catch(() => {});
    });
    await page.getByRole('button', { name: 'New chat', exact: true }).click();
    await submit(delayedQuestion);
    await page.getByRole('button', { name: 'Stop response', exact: true }).waitFor();
    await openHistory(firstQuestion);
    release();
    await page.locator(`.chat-history-item[data-conversation-id="${chatIds.get(delayedQuestion)}"] .chat-history-pending`).waitFor({ state: 'detached' });
    assert.equal(await page.locator('.chat-message.user').count(), 1);
    assert.equal(await page.locator('.chat-user-bubble').innerText(), firstQuestion, 'Late response does not switch threads');
    await openHistory(delayedQuestion);
    await page.locator('.chat-answer').waitFor();
    await page.unroute(endpoint);

    gate = new Promise(resolve => { release = resolve; });
    await page.route(endpoint, async route => { if (route.request().method() !== 'POST') return route.continue(); await gate; await route.continue().catch(() => {}); });
    await submit('Show invoice volume');
    await page.getByRole('button', { name: 'Stop response', exact: true }).click();
    await page.getByText('Stopped waiting. The server may finish this response; reopen the chat to load it.', { exact: true }).waitFor();
    release(); await page.unroute(endpoint);
    assert.equal(await page.locator('.chat-answer').count(), 1, 'Stopped request adds no answer');

    let failedOnce = false;
    await page.route(endpoint, route => {
      if (route.request().method() !== 'POST' || failedOnce) return route.continue();
      failedOnce = true;
      return route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"Unavailable for test"}' });
    });
    await submit('Show invoice status distribution');
    await page.locator('.chat-response-error').waitFor();
    await page.unroute(endpoint);
    assert.equal(await page.getByRole('button', { name: 'Try again', exact: true }).isDisabled(), true,
      'An unconfirmed save cannot be blindly submitted twice');
    assert.equal(await composer().inputValue(), 'Show invoice status distribution', 'Unconfirmed question remains in the composer');
    await page.getByRole('button', { name: 'Reload conversation', exact: true }).click();
    await send('Show invoice status distribution');
    const clarification = await send('Tell me about unicorns');
    assert.ok(clarification.clarification);
    assert.equal(clarification.rows.length, 0);
    assert.equal(await page.locator('.chat-answer').last().locator('table').count(), 0);

    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await page.screenshot({ path: path.join(output, 'chat-dark.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Chat fits mobile width');
    const box = await composer().boundingBox();
    assert.ok(box && box.y >= 0 && box.y + box.height <= 844, 'Composer is visible at mobile height');
    await page.screenshot({ path: path.join(output, 'chat-mobile.png'), fullPage: true });
    await page.getByRole('button', { name: 'Open navigation', exact: true }).click();
    await page.screenshot({ path: path.join(output, 'chat-sidebar-mobile.png'), fullPage: true });
    await page.getByRole('button', { name: 'New chat', exact: true }).click();
    await page.getByRole('heading', { name: 'What would you like to explore?' }).waitFor();
    await page.setViewportSize({ width: 1440, height: 1000 });
    page.once('dialog', dialog => dialog.accept());
    const deletion = page.waitForResponse(response => response.request().method() === 'DELETE' && response.url().endsWith(`/conversations/${chatIds.get(secondQuestion)}`));
    await page.locator(`.chat-history-item[data-conversation-id="${chatIds.get(secondQuestion)}"] .chat-history-delete`).click();
    assert.equal((await deletion).status(), 204);
    await history(secondQuestion).waitFor({ state: 'detached' });
    assert.equal(await history(secondQuestion).count(), 0);
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login();
    await openHistory(firstQuestion);
    await page.locator('.chat-answer-text').filter({ hasText: first.answer }).waitFor();
    const saved = await savedChats();
    assert.ok(saved.some(item => item.id === first.conversationId), 'Sign-in restores saved history');
    assert.ok(!saved.some(item => item.id === chatIds.get(secondQuestion)), 'Deleted conversation stays deleted');
    for (const item of baseline) assert.ok(saved.some(other => other.id === item.id), 'Existing user conversations are untouched');
    assert.deepEqual(errors, [], 'No browser runtime errors');
    console.log('PASS: persistent chat APIs, real results/evidence, multiline prompt, saved history, report navigation, request isolation, cancellation/retry, clarification, mobile layout, deletion and sign-in restoration.');
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'chat-failure.png'), fullPage: true });
    throw error;
  } finally {
    release(); await page.unrouteAll({ behavior: 'ignoreErrors' }); await Promise.allSettled(tracking);
    for (const id of created) {
      const response = await page.request.delete(`${base}/api/conversations/${id}`, { headers });
      if (![204, 404].includes(response.status())) console.error(`Cleanup failed for smoke conversation ${id}: ${response.status()}`);
    }
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
