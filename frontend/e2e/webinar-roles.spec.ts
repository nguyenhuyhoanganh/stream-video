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

test('webinar: guest is an attendee, host promotes them, chat works', async ({ browser }) => {
  const host = await (await browser.newContext()).newPage();
  const guest = await (await browser.newContext()).newPage();

  // the host schedules a webinar
  await registerAndLogin(host, 'Host');
  await host.getByPlaceholder('Weekly team sync').fill('Webinar e2e');
  await host.locator('select').first().selectOption('WEBINAR');
  await host.getByRole('button', { name: 'Schedule' }).click();
  await host.getByRole('button', { name: 'Join' }).first().click();
  await host.waitForURL(/\/m\//);
  const code = host.url().split('/m/')[1];
  await host.getByRole('button', { name: 'Join' }).click();
  await expect(host.locator('.lk-participant-tile')).toHaveCount(1, { timeout: 20_000 });

  // a guest (not signed in) opens the link and types a name
  await guest.goto(`/m/${code}`);
  await guest.locator('input#username, input[name="username"]').fill('Guest Duy');
  await guest.getByRole('button', { name: 'Join' }).click();

  // the guest is an ATTENDEE: no publish controls (mic/cam/share)
  await expect(guest.getByText('Raise hand')).toBeVisible({ timeout: 20_000 });
  await expect(guest.getByTestId('publish-controls')).toHaveCount(0);

  // chat both ways
  await guest.getByPlaceholder('Type a message...').fill('Hello from the guest');
  await guest.getByPlaceholder('Type a message...').press('Enter');
  await expect(host.getByText('Hello from the guest')).toBeVisible({ timeout: 10_000 });

  // host promotes the guest → toast appears and publish controls show up at runtime
  await host.getByTitle('Allow to speak').first().click();
  await expect(guest.getByText('You can now speak 🎤')).toBeVisible({ timeout: 10_000 });
  await expect(guest.getByTestId('publish-controls')).toHaveCount(1, { timeout: 10_000 });

  await host.context().close();
  await guest.context().close();
});
