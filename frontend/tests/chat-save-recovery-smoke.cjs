const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage();
  const username = process.env.METRIC_MINT_TEST_USERNAME || 'admin';
  const password = process.env.METRIC_MINT_TEST_PASSWORD || 'admin';
  const base = process.env.METRIC_MINT_TEST_URL || 'http://127.0.0.1:5173';
  const headers = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}` };
  let conversationId;
  const endpoint = '**/api/conversations/*/questions';
  const question = 'Show monthly invoice volume';
  const composer = page.getByLabel('Message Metric Mint', { exact: true });
  const ready = () => page.waitForFunction(() => document.querySelector('[aria-label="Send message"]')?.disabled === false);
  try {
    await page.goto(base);
    await page.getByLabel('Username', { exact: true }).fill(username);
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'New chat', exact: true }).click();
    await page.route(endpoint, route => route.request().method() === 'POST'
      ? route.fulfill({ status: 503, contentType: 'application/json', body: '{"detail":"Simulated save failure"}' })
      : route.continue());
    const created = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/conversations');
    await composer.fill(question); await ready(); await composer.press('Enter');
    conversationId = (await (await created).json()).id;
    await page.locator('.chat-response-error').waitFor();
    await page.waitForFunction(text => document.querySelector('[aria-label="Message Metric Mint"]')?.value === text, question);
    assert.equal(await page.getByRole('button', { name: 'Try again', exact: true }).isDisabled(), true);
    const before = await page.request.get(`${base}/api/conversations/${conversationId}/questions`, { headers });
    assert.deepEqual(await before.json(), [], 'Failed first POST did not invent a saved message');
    await page.unroute(endpoint);
    await page.getByRole('button', { name: 'Reload conversation', exact: true }).click();
    await ready();
    assert.equal(await composer.inputValue(), question, 'Reload preserves the unsent first prompt');
    const answered = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith(`/conversations/${conversationId}/questions`));
    await composer.press('Enter');
    const saved = await (await answered).json();
    assert.equal(saved.status, 'complete');
    await page.locator('.chat-answer-text').filter({ hasText: saved.answer }).waitFor();
    assert.equal(await composer.inputValue(), '', 'Confirmed acceptance clears the recovered draft');
    await page.reload();
    await page.locator('.chat-answer-text').filter({ hasText: saved.answer }).waitFor();
    const stored = await page.request.get(`${base}/api/conversations/${conversationId}/questions`, { headers });
    assert.equal((await stored.json()).length, 1, 'Retry saves exactly one prompt and answer');
    console.log('PASS: first-message save failure preserves the draft through conversation creation/reload, retries safely, and restores one saved answer.');
  } finally {
    await page.unrouteAll({ behavior: 'ignoreErrors' });
    if (conversationId) {
      const deleted = await page.request.delete(`${base}/api/conversations/${conversationId}`, { headers });
      assert.ok([204, 404].includes(deleted.status()), 'Clean up only the test conversation');
    }
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
