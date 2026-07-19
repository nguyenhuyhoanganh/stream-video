import { expect, test, type Page } from '@playwright/test';

async function registerAndLogin(page: Page, name: string) {
  const email = `${name}-${Date.now()}@e2e.meetly.dev`;
  await page.goto('/register');
  await page.getByPlaceholder('Họ tên').fill(name);
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Mật khẩu (≥ 8 ký tự)').fill('secret123');
  await page.getByRole('button', { name: 'Đăng ký' }).click();
  await expect(page.getByRole('button', { name: 'Họp ngay' })).toBeVisible();
}

test('hai người join cùng phòng và thấy video của nhau', async ({ browser }) => {
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();

  // User A: đăng ký → Họp ngay → vào phòng
  await registerAndLogin(pageA, 'Alice');
  await pageA.getByRole('button', { name: 'Họp ngay' }).click();
  await pageA.waitForURL(/\/m\/[a-z]{3}-[a-z]{4}-[a-z]{3}$/);
  const code = pageA.url().split('/m/')[1];
  await pageA.getByRole('button', { name: 'Vào phòng' }).click();
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // User B: đăng ký → join bằng code
  await registerAndLogin(pageB, 'Bob');
  await pageB.goto(`/m/${code}`);
  await pageB.getByRole('button', { name: 'Vào phòng' }).click();

  // Cả hai thấy 2 tiles
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });
  await expect(pageB.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });

  await contextA.close();
  await contextB.close();
});
