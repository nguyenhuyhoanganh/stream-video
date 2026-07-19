import { expect, test, type Page, type APIResponse } from '@playwright/test';

// Phase 3 DoD spec — run on demand, not part of the CI smoke suite.
// It records for ~20s of real video, hence the long timeout.
test.setTimeout(300_000);

async function registerAndLogin(page: Page, name: string) {
  const email = `${name}-${Date.now()}@e2e.meetly.dev`;
  await page.goto('/register');
  await page.getByPlaceholder('Full name').fill(name);
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Password (min. 8 characters)').fill('secret123');
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page.getByRole('button', { name: 'Meet now' })).toBeVisible();
}

test('DoD: host records → MP4 in MinIO → playback; ending the meeting stops recording', async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  await registerAndLogin(page, 'Rec');
  const joinResPromise = page.waitForResponse((r) => r.url().includes('/join') && r.ok());
  await page.getByRole('button', { name: 'Meet now' }).click();
  await page.waitForURL(/\/m\/[a-z]{3}-[a-z]{4}-[a-z]{3}$/);
  await page.getByRole('button', { name: 'Join' }).click();
  const joinRes = await joinResPromise;
  const { meetingId } = (await joinRes.json()) as { meetingId: string };
  await expect(page.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // access token for polling the API (the refresh cookie already lives in this context)
  const refresh = await page.request.post('/api/v1/auth/refresh');
  const { accessToken } = (await refresh.json()) as { accessToken: string };
  const auth = { Authorization: `Bearer ${accessToken}` };

  // 1) Hit record → the button flips to stop
  await page.getByRole('button', { name: '⏺ Record' }).click();
  await expect(page.getByRole('button', { name: '⏹ Stop recording' })).toBeVisible({ timeout: 15_000 });

  // ghi ~20s
  await page.waitForTimeout(20_000);

  // 2) Stop → wait for the egress_ended webhook → COMPLETED
  await page.getByRole('button', { name: '⏹ Stop recording' }).click();
  const rec1 = await pollCompleted(page, meetingId, auth, 1);
  expect(rec1.status).toBe('COMPLETED');

  // 3) The presigned playback URL returns a real file
  const pb = await page.request.get(`/api/v1/recordings/${rec1.id}/playback-url`, { headers: auth });
  expect(pb.ok()).toBeTruthy();
  const { url } = (await pb.json()) as { url: string };
  expect(url).toContain('X-Amz-Signature=');
  const file = await page.request.get(url);
  expect(file.status()).toBe(200);
  const body = await file.body();
  expect(body.byteLength).toBeGreaterThan(100_000); // MP4 ~20s > 100KB
  console.log(`MP4 size: ${body.byteLength} bytes, url: ${url.split('?')[0]}`);

  // 4) Record again, then end the meeting → egress stops on its own → COMPLETED
  await page.getByRole('button', { name: '⏺ Record' }).click();
  await expect(page.getByRole('button', { name: '⏹ Stop recording' })).toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(8_000);
  await page.getByRole('button', { name: 'End meeting' }).click();
  const rec2 = await pollCompleted(page, meetingId, auth, 2);
  expect(rec2.status).toBe('COMPLETED');

  await ctx.close();
});

type Rec = { id: string; status: string };

async function pollCompleted(page: Page, meetingId: string, auth: Record<string, string>,
                             expectCount: number): Promise<Rec> {
  for (let i = 0; i < 45; i++) {
    const res: APIResponse = await page.request.get(
      `/api/v1/meetings/${meetingId}/recordings`, { headers: auth });
    const recs = (await res.json()) as Rec[];
    if (recs.length >= expectCount) {
      const target = recs[0]; // newest first
      if (target.status === 'COMPLETED' || target.status === 'FAILED') return target;
    }
    await page.waitForTimeout(2_000);
  }
  throw new Error('Recording did not reach COMPLETED within 90s');
}
