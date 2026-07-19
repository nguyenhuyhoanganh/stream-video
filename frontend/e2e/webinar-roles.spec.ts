import { expect, test, type Page } from '@playwright/test';

async function registerAndLogin(page: Page, name: string) {
  const email = `${name}-${Date.now()}@e2e.meetly.dev`;
  await page.goto('/register');
  await page.getByPlaceholder('Full name').fill(name);
  await page.getByPlaceholder('Email').fill(email);
  await page.getByPlaceholder('Password (min. 8 characters)').fill('secret123');
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page.getByRole('button', { name: 'Meet now' })).toBeVisible();
}

test('webinar: guest là khán giả, host promote, chat hoạt động', async ({ browser }) => {
  const host = await (await browser.newContext()).newPage();
  const guest = await (await browser.newContext()).newPage();

  // Host tạo webinar qua form đặt lịch
  await registerAndLogin(host, 'Host');
  await host.getByPlaceholder('Weekly team sync').fill('Webinar e2e');
  await host.locator('select').first().selectOption('WEBINAR');
  await host.getByRole('button', { name: 'Schedule' }).click();
  await host.getByRole('button', { name: 'Join' }).first().click();
  await host.waitForURL(/\/m\//);
  const code = host.url().split('/m/')[1];
  await host.getByRole('button', { name: 'Join' }).click();
  await expect(host.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // Guest (không đăng nhập) vào bằng link, nhập tên
  await guest.goto(`/m/${code}`);
  await guest.locator('input#username, input[name="username"]').fill('Guest Duy');
  await guest.getByRole('button', { name: 'Join' }).click();

  // Guest là ATTENDEE: không có cụm nút publish (mic/cam/share)
  await expect(guest.getByText('Raise hand')).toBeVisible({ timeout: 20_000 });
  await expect(guest.getByTestId('publish-controls')).toHaveCount(0);

  // Chat 2 chiều
  await guest.getByPlaceholder('Type a message...').fill('Hello from the guest');
  await guest.getByPlaceholder('Type a message...').press('Enter');
  await expect(host.getByText('Hello from the guest')).toBeVisible({ timeout: 10_000 });

  // Host promote guest → guest thấy toast + cụm nút publish xuất hiện runtime
  await host.getByTitle('Allow to speak').first().click();
  await expect(guest.getByText('You can now speak 🎤')).toBeVisible({ timeout: 10_000 });
  await expect(guest.getByTestId('publish-controls')).toHaveCount(1, { timeout: 10_000 });

  await host.context().close();
  await guest.context().close();
});
