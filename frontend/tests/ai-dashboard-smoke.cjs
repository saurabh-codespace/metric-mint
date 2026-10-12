const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
  const base = process.env.METRIC_MINT_TEST_URL || 'http://127.0.0.1:5173';
  const username = process.env.METRIC_MINT_TEST_USERNAME || 'admin';
  const password = process.env.METRIC_MINT_TEST_PASSWORD || 'admin';
  const headers = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`, 'Content-Type': 'application/json' };
  const request = async (suffix, method = 'GET', body, status = 200) => {
    const response = await fetch(`${base}/api${suffix}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    assert.equal(response.status, status, `${method} ${suffix}: ${response.status}`);
    return status === 204 ? undefined : response.json();
  };
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const output = path.resolve(__dirname, '../test-results');
  await fs.mkdir(output, { recursive: true });
  const prompt = 'Create an invoice dashboard with monthly volume and status distribution';
  const name = `Prompt dashboard check ${Date.now()}`;
  let savedId, publicationId;
  const login = async target => {
    await target.getByLabel('Username', { exact: true }).fill(username);
    await target.getByLabel('Password', { exact: true }).fill(password);
    await target.getByRole('button', { name: 'Sign in', exact: true }).click();
  };
  try {
    assert.equal((await fetch(`${base}/api/report-templates/ai/status`)).status, 401);
    await request('/report-templates/generate', 'POST', { prompt: ' ', dataMode: 'live' }, 400);
    await request('/report-templates/generate', 'POST', { prompt: 'a'.repeat(2001), dataMode: 'live' }, 400);
    await request('/report-templates/generate', 'POST', { prompt, dataMode: 'invented' }, 400);
    const status = await request('/report-templates/ai/status');
    const unsupported = await request('/report-templates/generate', 'POST', { prompt: 'Show monthly invoice volume for 2020 only', dataMode: 'live' });
    assert.ok(!unsupported.result && unsupported.clarification, 'Unsupported date filters need clarification');
    const before = (await request('/report-templates')).map(item => item.id).sort();
    const sample = await request('/report-templates/generate', 'POST', { prompt: 'Create a dashboard with queue management and utilization', dataMode: 'sample' });
    assert.ok(sample.result?.widgets.length >= 2, 'Sample request creates a multi-widget layout');
    assert.ok(sample.result.widgets.every(widget => widget.sample), 'Samples are explicitly labeled');
    assert.deepEqual((await request('/report-templates')).map(item => item.id).sort(), before, 'Generation never silently saves a draft');

    await page.goto(base);
    await login(page);
    await page.getByRole('button', { name: 'AI dashboards', exact: true }).click();
    const input = page.getByLabel('Describe your dashboard', { exact: true });
    await input.waitFor();
    if (!status.configured) await page.getByText(/AI is not configured/).waitFor();
    await input.fill(prompt);
    await page.route('**/api/report-templates/generate', route => route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ detail: 'Temporary test outage' }) }), { times: 1 });
    await page.getByRole('button', { name: 'Generate dashboard', exact: true }).click();
    await page.getByRole('alert').waitFor();
    assert.equal(await input.inputValue(), prompt, 'Failed generation preserves the request');
    assert.equal(await page.locator('.template-result-widget').count(), 0);
    const generatedResponse = page.waitForResponse(response => response.url().endsWith('/report-templates/generate') && response.request().method() === 'POST' && response.status() === 200);
    await page.getByRole('button', { name: 'Generate dashboard', exact: true }).click();
    const generated = await (await generatedResponse).json();
    assert.ok(generated.result?.widgets.length >= 2);
    assert.ok(generated.result.widgets.every(widget => !widget.sample), 'Live requests never substitute samples');
    if (!status.configured) {
      assert.equal(generated.aiGenerated, false);
      await page.getByText('Starter layout', { exact: true }).waitFor();
    }
    await page.locator('.template-result-widget').nth(1).waitFor();
    for (const widget of generated.result.widgets) {
      const current = await request(`/report-templates/sources/${widget.widget.sourceType}/${widget.widget.sourceId}`);
      assert.deepEqual(widget.rows, current.rows, 'Displayed data comes from the selected backend source');
    }
    await page.getByLabel('Report name', { exact: true }).fill(name);
    const first = page.locator('.template-widget-editor').first();
    await first.getByLabel('Visualization', { exact: true }).selectOption('table');
    // Existing columns remain valid when changing a generated chart to a table.
    const checkboxes = first.getByRole('checkbox');
    await checkboxes.first().waitFor();
    await checkboxes.first().check();
    await page.getByRole('button', { name: 'Save template', exact: true }).click();
    await page.getByRole('button', { name: 'Publish dashboard', exact: true }).waitFor();
    const saved = (await request('/report-templates')).find(item => item.definition.name === name);
    assert.ok(saved, 'Generated layout is persisted'); savedId = saved.id;
    await page.reload();
    await page.getByRole('button', { name: 'AI dashboards', exact: true }).click();
    await page.getByRole('button', { name: 'Template library', exact: true }).click();
    await page.locator('.template-library-card').filter({ hasText: name }).getByRole('button', { name: 'Open template' }).click();
    await page.locator('.template-result-widget').nth(1).waitFor();
    assert.equal(await page.getByLabel('Report name', { exact: true }).inputValue(), name);
    assert.equal(await page.locator('.template-widget-editor').first().getByLabel('Visualization', { exact: true }).inputValue(), 'table');
    await page.screenshot({ path: path.join(output, 'ai-dashboard-saved.png'), fullPage: true });
    await page.getByRole('button', { name: 'Publish dashboard', exact: true }).click();
    await page.getByRole('button', { name: 'Publish and create link', exact: true }).click();
    const link = page.getByLabel('Shareable dashboard link', { exact: true });
    await link.waitFor();
    const url = await link.inputValue(), token = url.split('/').pop();
    publicationId = (await request(`/published-dashboards/token/${token}`)).id;
    const fresh = await browser.newContext({ viewport: { width: 390, height: 844 }, reducedMotion: 'reduce' });
    const shared = await fresh.newPage();
    await shared.goto(url);
    await shared.getByRole('heading', { name: 'Sign in to your workspace' }).waitFor();
    assert.equal((await fetch(`${base}/api/published-dashboards/token/${token}/data`)).status, 401);
    await login(shared);
    await shared.locator('.template-result-widget').nth(1).waitFor();
    assert.ok(await shared.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Published dashboard fits mobile');
    await shared.screenshot({ path: path.join(output, 'ai-dashboard-published-mobile.png'), fullPage: true });
    await shared.reload();
    await shared.locator('.template-result-widget').nth(1).waitFor();
    await fresh.close();
    assert.deepEqual(errors, []);
    console.log('Natural-language dashboards: generation, failure recovery, live/sample data, save/reload, publishing and mobile passed');
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'ai-dashboard-failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    if (!savedId) savedId = (await request('/report-templates')).find(item => item.definition.name === name)?.id;
    if (publicationId) await request(`/published-dashboards/${publicationId}`, 'DELETE', undefined, 204);
    if (savedId) await request(`/report-templates/${savedId}`, 'DELETE', undefined, 204);
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
