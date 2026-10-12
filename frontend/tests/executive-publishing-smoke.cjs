const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ reducedMotion: 'reduce' });
  let id;
  try {
    await page.goto('http://127.0.0.1:5173');
    await page.getByLabel('Username', { exact: true }).fill(process.env.METRIC_MINT_TEST_USERNAME || 'admin');
    await page.getByLabel('Password', { exact: true }).fill(process.env.METRIC_MINT_TEST_PASSWORD || 'admin');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByRole('button', { name: 'Overview', exact: true }).click();
    await page.getByLabel('Business unit', { exact: true }).selectOption('Finance');
    await page.getByLabel('Date range', { exact: true }).selectOption('7');
    await page.getByRole('button', { name: 'Value', exact: true }).click();
    const total = await page.locator('.executive-kpi-value').nth(1).innerText();
    await page.getByRole('button', { name: 'Publish dashboard', exact: true }).click();
    await page.getByLabel('Dashboard name').fill('Browser check — executive filters');
    await page.getByRole('button', { name: 'Publish and create link' }).click();
    const link = page.getByLabel('Shareable dashboard link', { exact: true });
    await link.waitFor();
    const url = await link.inputValue();
    const token = url.split('/').pop();
    id = await page.evaluate(async token => (await (await import('/src/api/publications.ts')).publications.get(token)).id, token);
    await page.goto(url);
    await page.getByRole('heading', { name: 'Browser check — executive filters', exact: true }).waitFor();
    assert.equal(await page.getByLabel('Business unit').inputValue(), 'Finance');
    assert.equal(await page.getByLabel('Date range').inputValue(), '7');
    assert.equal(await page.getByLabel('Business unit').isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: 'Value', exact: true }).getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('.executive-kpi-value').nth(1).innerText(), total);
    await page.reload();
    await page.getByRole('heading', { name: 'Browser check — executive filters', exact: true }).waitFor();
    assert.equal(await page.getByLabel('Date range').inputValue(), '7');
    console.log('PASS: executive dashboard publication preserves business unit, date range, chart and KPIs across navigation and reload.');
  } finally {
    if (id) await page.evaluate(async id => (await import('/src/api/publications.ts')).publications.revoke(id), id);
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
