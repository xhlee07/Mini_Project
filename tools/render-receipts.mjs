import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/User/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const browser = await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});
try {
  for (const width of [480,360]) {
    const page = await browser.newPage({viewport:{width,height:900},deviceScaleFactor:2});
    for (const name of ['receipt-email','receipt-order']) {
      const file = path.resolve('build/test-results/receipt-mail/'+name+'.html');
      await page.goto(pathToFileURL(file).href);
      await page.locator('img').evaluate(image=>image.decode());
      const sizes = await page.evaluate(()=>({page:document.documentElement.scrollWidth,viewport:innerWidth}));
      if(sizes.page>sizes.viewport)throw new Error('Receipt overflows at '+width+'px');
      await page.screenshot({path:'build/test-results/receipt-mail/'+name+'-'+width+'.png',fullPage:true});
    }
    await page.close();
  }
  console.log('PASS: email receipts render at desktop and mobile widths, with logo and no horizontal overflow.');
} finally { await browser.close(); }
