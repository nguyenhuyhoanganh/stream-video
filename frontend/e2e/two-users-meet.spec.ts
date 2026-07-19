import { expect, test, type Page } from '@playwright/test';

async function registerAndLogin(page: Page, name: string, email: string) {
  await page.goto('/register');
  await page.getByPlaceholder('Họ tên').fill(name);
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Mật khẩu (≥ 8 ký tự)').fill('secret123');
  await page.getByRole('button', { name: 'Đăng ký' }).click();
  await expect(page.getByRole('button', { name: 'Họp ngay' })).toBeVisible();
}

test('hai người join cùng phòng kín (được mời) và thấy video của nhau', async ({ browser }) => {
  const contextA = await browser.newContext();
  const contextB = await browser.newContext();
  const pageA = await contextA.newPage();
  const pageB = await contextB.newPage();
  const bobEmail = `bob-${Date.now()}@e2e.meetly.dev`;

  // Bob đăng ký trước để có tài khoản nhận lời mời
  await registerAndLogin(pageB, 'Bob', bobEmail);

  // Alice tạo phòng kín qua form đặt lịch rồi mời Bob làm Diễn giả
  await registerAndLogin(pageA, 'Alice', `alice-${Date.now()}@e2e.meetly.dev`);
  await pageA.getByPlaceholder('Họp team tuần').fill('Họp 2 người');
  await pageA.getByRole('button', { name: 'Đặt lịch' }).click();
  await pageA.getByRole('button', { name: 'Thành viên' }).first().click();
  await pageA.getByPlaceholder('email@congty.vn').fill(bobEmail);
  await pageA.locator('select').nth(1).selectOption('SPEAKER');
  await pageA.getByRole('button', { name: 'Thêm' }).click();
  await expect(pageA.getByText(bobEmail)).toBeVisible();
  await pageA.getByRole('button', { name: 'Đóng' }).click();

  // Alice vào phòng
  await pageA.getByRole('button', { name: 'Vào phòng' }).first().click();
  await pageA.waitForURL(/\/m\/[a-z]{3}-[a-z]{4}-[a-z]{3}$/);
  const code = pageA.url().split('/m/')[1];
  await pageA.getByRole('button', { name: 'Vào phòng' }).click();
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // Bob (đã là member SPEAKER) join bằng code
  await pageB.goto(`/m/${code}`);
  await pageB.getByRole('button', { name: 'Vào phòng' }).click();

  // Cả hai thấy 2 tiles
  await expect(pageA.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });
  await expect(pageB.locator('.lk-participant-tile')).toHaveCount(2, { timeout: 20_000 });

  await contextA.close();
  await contextB.close();
});
