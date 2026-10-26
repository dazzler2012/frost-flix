// Renders the PNGs made from docs/brand/src/*.svg (written by tools/brand_icons.py):
// the legacy launcher mipmaps and the README images in docs/brand/.
// Needs Playwright with Chromium. Run from the repository root:
//
//     node tools/brand_render.js [preview-dir]
//
// With a preview directory it also writes previews on dark and light backgrounds there.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const root = path.resolve(__dirname, '..');
const src = (n) => fs.readFileSync(path.join(root, 'docs/brand/src', n), 'utf8');
const res = path.join(root, 'app/src/main/res');

// The full icon as a launcher shows it: the middle 72 of the 108 units, in a rounded square.
function iconSvg(size) {
  const inner = src('icon.svg').replace(/^<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72">
<clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath>
<g clip-path="url(#m)">${inner}</g></svg>`;
}

function bannerSvg(w, h) {
  return src('banner.svg').replace(/width="320" height="180"/, `width="${w}" height="${h}"`);
}

async function shot(page, svg, w, h, out, bg) {
  const body = bg ? `background:${bg};` : 'background:transparent;';
  await page.setViewportSize({ width: w, height: h });
  await page.setContent(`<html><body style="margin:0;${body}">${svg}</body></html>`);
  await page.screenshot({ path: out, omitBackground: !bg, clip: { x: 0, y: 0, width: w, height: h } });
}

(async () => {
  const preview = process.argv[2];
  const browser = await chromium.launch();
  const page = await browser.newPage();

  const mipmaps = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [d, px] of Object.entries(mipmaps)) {
    fs.mkdirSync(path.join(res, `mipmap-${d}`), { recursive: true });
    await shot(page, iconSvg(px), px, px, path.join(res, `mipmap-${d}`, 'ic_launcher.png'));
  }
  await shot(page, iconSvg(512), 512, 512, path.join(root, 'docs/brand/frost-flix-icon-512.png'));
  await shot(page, bannerSvg(1280, 720), 1280, 720, path.join(root, 'docs/brand/frost-flix-banner.png'));

  if (preview) {
    fs.mkdirSync(preview, { recursive: true });
    for (const [name, bg] of [['dark', '#111111'], ['light', '#F2F2F2']]) {
      const sizes = [192, 96, 48, 32];
      const icons = sizes.map((s) => `<div style="display:inline-block;margin:12px;vertical-align:middle">${iconSvg(s)}</div>`).join('');
      const html = `<div style="padding:24px;background:${bg}">${icons}
        <div style="margin:12px">${bannerSvg(320, 180)}</div>
        <div style="margin:12px;display:inline-block">${bannerSvg(160, 90)}</div></div>`;
      await shot(page, html, 640, 560, path.join(preview, `preview_${name}.png`), bg);
    }
    // Adaptive icon in a circle mask, and the monochrome layer tinted, as Android 13 shows it.
    const fg = src('icon_foreground.svg').replace(/^<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
    const bgl = src('icon_background.svg').replace(/^<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
    const mono = src('icon_monochrome.svg').replace(/^<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');
    const circle = (inner, s) => `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="18 18 72 72"><clipPath id="c${s}"><circle cx="54" cy="54" r="36"/></clipPath><g clip-path="url(#c${s})">${inner}</g></svg>`;
    const themed = (s) => `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="18 18 72 72"><circle cx="54" cy="54" r="36" fill="#D8E6F3"/><g style="filter:brightness(0) saturate(100%)" opacity="0.8">${mono}</g></svg>`;
    const html = `<div style="padding:24px;background:#1b1b1b">
      ${circle(bgl + fg, 160)} ${circle(bgl + fg, 64)} ${themed(160)} ${themed(64)}</div>`;
    await shot(page, html, 640, 220, path.join(preview, 'preview_adaptive.png'), '#1b1b1b');
  }
  await browser.close();
})();
