// Renders the SVGs in this folder into the app's launcher icon layers for every screen density,
// as in the Deco launcher. Run from the repository root: node assets/render-icons.js
// (needs Playwright with Chromium).
const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const res = path.join(__dirname, '..', 'app', 'src', 'main', 'res');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const jobs = [];
for (const [name, scale] of Object.entries(densities)) {
  for (const layer of ['background', 'foreground', 'monochrome']) {
    const px = Math.round(108 * scale); // adaptive icon layers are 108dp square
    jobs.push([`icon-${layer}.svg`, px, px, path.join(res, `mipmap-${name}`, `ic_launcher_${layer}.png`)]);
  }
}
jobs.push(['icon.svg', 512, 512, path.join(__dirname, 'icon.png')]);

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [svg, width, height, file] of jobs) {
    await page.setViewportSize({ width, height });
    await page.goto('file://' + path.join(__dirname, svg));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await page.screenshot({ path: file, omitBackground: true });
    console.log(path.relative(path.join(__dirname, '..'), file), `${width}x${height}`);
  }
  await browser.close();
})();
