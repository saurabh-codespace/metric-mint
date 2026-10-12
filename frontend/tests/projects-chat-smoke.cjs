const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  const base = process.env.METRIC_MINT_TEST_URL || 'http://127.0.0.1:5173';
  const username = process.env.METRIC_MINT_TEST_USERNAME || 'admin';
  const password = process.env.METRIC_MINT_TEST_PASSWORD || 'admin';
  const headers = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}` };
  const output = path.resolve(__dirname, '../test-results');
  await fs.mkdir(output, { recursive: true });
  const checkpointPath = path.join(output, 'projects-chat-checkpoint.json');
  const errors = [], created = new Set(), tracking = [];
  let project, checkpoint, passed = false;
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  const page = await context.newPage();
  const track = target => {
    target.on('pageerror', error => errors.push(error.message));
    target.on('response', response => {
      if (response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/conversations' && response.status() === 201) {
        tracking.push(response.json().then(body => created.add(body.id)));
      }
    });
  };
  track(page);
  const api = async suffix => {
    const response = await context.request.get(`${base}/api${suffix}`, { headers });
    assert.equal(response.status(), 200); return response.json();
  };
  const login = async target => {
    await target.getByLabel('Username', { exact: true }).fill(username);
    await target.getByLabel('Password', { exact: true }).fill(password);
    await target.getByRole('button', { name: 'Sign in', exact: true }).click();
    await target.getByRole('button', { name: 'New chat', exact: true }).waitFor();
  };
  const expandProject = async target => {
    const button = target.getByRole('button', { name: `Open project ${project.name}`, exact: true });
    if (await button.getAttribute('aria-expanded') === 'false') await button.click();
  };
  const openChat = async (target, id) => {
    await expandProject(target);
    const item = target.locator(`.chat-project[data-project-id="${project.id}"] .chat-history-item[data-conversation-id="${id}"]`);
    await item.locator('.chat-history-select').click();
  };
  const send = async (target, question) => {
    const pending = target.waitForResponse(response => /\/api\/conversations\/[^/]+\/questions$/.test(response.url())
      && response.request().method() === 'POST' && response.request().postDataJSON().question === question);
    await target.getByLabel('Message Metric Mint', { exact: true }).fill(question);
    await target.waitForFunction(() => {
      const send = document.querySelector('button[aria-label="Send message"]');
      return send && !send.disabled;
    });
    await target.getByLabel('Message Metric Mint', { exact: true }).press('Enter');
    const response = await pending;
    assert.equal(response.status(), 201);
    const answer = await response.json();
    created.add(answer.conversationId);
    assert.equal(answer.status, 'complete');
    await target.locator('.chat-answer-text').last().filter({ hasText: answer.answer }).waitFor();
    return answer;
  };
  const assertRestored = async (target, autoRestored = false) => {
    if (!autoRestored) await openChat(target, checkpoint.conversationId);
    const answer = target.locator('.chat-answer');
    await answer.locator('.chat-answer-text').filter({ hasText: checkpoint.expectedAnswer }).waitFor();
    assert.equal(await target.locator(`.chat-history-item[data-conversation-id="${checkpoint.conversationId}"] .chat-history-select`).getAttribute('aria-current'), 'page',
      autoRestored ? 'Reload automatically restores the previously selected chat' : 'Saved conversation is selected');
    assert.equal(await target.locator('.chat-user-bubble').innerText(), checkpoint.question);
    assert.equal(await answer.locator('tbody tr').count(), checkpoint.expectedQuestion.rows.length);
    const evidence = answer.locator('.chat-evidence');
    await evidence.locator(':scope > summary').click();
    const evidenceText = await evidence.innerText();
    assert.match(evidenceText, /Executed/);
    if (checkpoint.expectedQuestion.lineage) {
      assert.ok(evidenceText.includes(checkpoint.expectedQuestion.lineage.source));
      for (const table of checkpoint.expectedQuestion.lineage.tables) assert.ok(evidenceText.includes(table));
    }
    const stored = await api(`/conversations/${checkpoint.conversationId}/questions`);
    assert.deepEqual(stored, checkpoint.expectedQuestions, 'Full stored answer, evidence, rows and timestamps are unchanged');
  };
  try {
    const baseline = await api('/conversations');
    await page.goto(base); await login(page);
    const projectName = `Project persistence check ${new Date().toISOString().replace(/[:.]/g, '-')}`;
    await page.getByRole('button', { name: 'New project', exact: true }).click();
    await page.getByLabel('Project name', { exact: true }).fill(projectName);
    const projectCreated = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/projects');
    await page.getByRole('button', { name: 'Create project', exact: true }).click();
    const projectResponse = await projectCreated;
    assert.equal(projectResponse.status(), 201);
    project = await projectResponse.json();
    assert.equal(project.name, projectName);
    await fs.writeFile(checkpointPath, JSON.stringify({ projectId: project.id, projectName: project.name, cleanupNeeded: true }, null, 2));
    await page.getByRole('button', { name: `New chat in ${project.name}`, exact: true }).click();
    const question = 'Show monthly invoice volume';
    const first = await send(page, question);
    assert.ok(first.rows.length > 0, 'Real monthly query returns data');
    assert.ok(first.executedAt, 'Stored result includes execution evidence');
    const metadata = (await api('/conversations')).find(item => item.id === first.conversationId);
    assert.equal(metadata.projectId, project.id);
    assert.equal(metadata.questionCount, 1);
    const expectedQuestions = await api(`/conversations/${first.conversationId}/questions`);
    assert.equal(expectedQuestions[0].answer, first.answer);
    assert.deepEqual(expectedQuestions[0].rows, first.rows);
    checkpoint = { projectId: project.id, projectName: project.name, conversationId: first.conversationId,
      question, expectedAnswer: first.answer, expectedQuestion: expectedQuestions[0], expectedQuestions, cleanupNeeded: true };
    await fs.writeFile(checkpointPath, JSON.stringify(checkpoint, null, 2));
    await page.reload(); await page.getByRole('button', { name: 'New chat', exact: true }).waitFor();
    await assertRestored(page, true);
    await page.screenshot({ path: path.join(output, 'projects-chat-reloaded.png'), fullPage: true });

    const freshContext = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    const otherPage = await freshContext.newPage(); track(otherPage);
    await otherPage.goto(base); await login(otherPage); await assertRestored(otherPage);
    await otherPage.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login(otherPage); await assertRestored(otherPage);
    await otherPage.getByRole('button', { name: `New chat in ${project.name}`, exact: true }).click();
    const second = await send(otherPage, 'Show invoice status distribution');
    const members = (await api('/conversations')).filter(item => item.projectId === project.id);
    assert.deepEqual(new Set(members.map(item => item.id)), new Set([first.conversationId, second.conversationId]));
    await otherPage.getByRole('button', { name: 'New chat in General', exact: true }).click();
    await expandProject(otherPage);
    const secondItem = otherPage.locator(`.chat-project[data-project-id="${project.id}"] .chat-history-item[data-conversation-id="${second.conversationId}"]`);
    await secondItem.locator('.chat-history-select').click();
    await otherPage.locator('.chat-answer-text').filter({ hasText: second.answer }).waitFor();
    otherPage.once('dialog', dialog => dialog.accept());
    const deletion = otherPage.waitForResponse(response => response.request().method() === 'DELETE' && response.url().endsWith(`/conversations/${second.conversationId}`));
    await secondItem.locator('.chat-history-delete').click();
    assert.equal((await deletion).status(), 204);
    await otherPage.reload(); await otherPage.getByRole('button', { name: 'New chat', exact: true }).waitFor();
    await assertRestored(otherPage);
    assert.ok(!(await api('/conversations')).some(item => item.id === second.conversationId));
    assert.equal(await otherPage.locator(`.chat-history-item[data-conversation-id="${second.conversationId}"]`).count(), 0);
    await otherPage.screenshot({ path: path.join(output, 'projects-chat-persistent.png'), fullPage: true });
    const remaining = await api('/conversations');
    for (const item of baseline) assert.ok(remaining.some(saved => saved.id === item.id), 'Existing user chats remain untouched');
    assert.deepEqual(errors, []);
    await freshContext.close();
    passed = true;
    console.log(`PASS: project creation, real query, complete evidence after reload/new browser context/sign-in, two project chats and durable deletion. Restart checkpoint: ${checkpointPath}`);
  } catch (error) {
    await page.screenshot({ path: path.join(output, 'projects-chat-failure.png'), fullPage: true }); throw error;
  } finally {
    await Promise.allSettled(tracking);
    for (const id of created) {
      if (passed && id === checkpoint.conversationId) continue;
      const response = await context.request.delete(`${base}/api/conversations/${id}`, { headers });
      if (![204, 404].includes(response.status())) console.error(`Cleanup failed for project smoke conversation ${id}: ${response.status()}`);
    }
    if (project) console.log(`Cleanup needed after restart verification: exact project ${project.id}${passed ? ` and conversation ${checkpoint.conversationId}` : ''}. Project deletion has no API.`);
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
