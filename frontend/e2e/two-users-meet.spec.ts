import { expect, test, type Page } from '@playwright/test';

async function registerAndLogin(page: Page, name: string, email: string) {
  await page.goto('/register');
  await page.getByPlaceholder('Full name').fill(name);
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Password (min. 8 characters)').fill('secret123');
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page.getByRole('button', { name: 'Meet now' })).toBeVisible();
}

test('hai người join cùng phòng kín (được mời) và thấy video của nhau', async ({ browser }) => {
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();
  const bobEmail = `bob-${Date.now()}@e2e.meetly.dev`;

  // Bob đăng ký trước để có tài khoản nhận lời mời
  await registerAndLogin(pageB, 'Bob', bobEmail);

  // Alice tạo phòng kín qua form đặt lịch rồi mời Bob làm Speaker
  await registerAndLogin(pageA, 'Alice', `alice-${Date.now()}@e2e.meetly.dev`);
  await pageA.getByPlaceholder('Weekly team sync').fill('Two-person meeting');
  await pageA.getByRole('button', { name: 'Schedule' }).click();
  await pageA.getByRole('button', { name: 'Members' }).first().click();
  await pageA.getByPlaceholder('name@company.com').fill(bobEmail);
  await pageA.locator('select').nth(1).selectOption('SPEAKER');
  await pageA.getByRole('button', { name: 'Add' }).click();
  await expect(pageA.getByText(bobEmail)).toBeVisible();
  await pageA.getByRole('button', { name: 'Close' }).click();

  // Alice vào phòng
  await pageA.getByRole('button', { name: 'Join' }).first().click();
  await pageA.waitForURL(/\/m\/[a-z]{3}-[a-z]{4}-[a-z]{3}$/);
  const code = pageA.url().split('/m/')[1];
  await pageA.getByRole('button', { name: 'Join' }).click();
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // Bob (đã là member SPEAKER) join bằng code
  await pageB.goto(`/m/${code}`);
  await pageB.getByRole('button', { name: 'Join' }).click();

  // Cả hai thấy 2 tiles
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });
  await expect(pageB.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });

  await contextA.close();
  await contextB.close();
});
