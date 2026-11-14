const { defineConfig } = require('@playwright/test')
module.exports = defineConfig({
  testDir: './tests', timeout: 30000, workers: 1,
  use: { baseURL: 'http://127.0.0.1:18407', headless: true, screenshot: 'only-on-failure', launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE } : {} },
  webServer: { command: 'node scripts/preview.js', url: 'http://127.0.0.1:18407', reuseExistingServer: false },
  reporter: [['list'], ['html', { open: 'never' }]]
})
