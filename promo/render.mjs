// Renders scene.html frame by frame with headless Chromium, then encodes MP4 with ffmpeg.
// Usage: node render.mjs <dir-with-page-and-node_modules/three> <out.mp4> [fps] [onlyFrames,comma,separated]
// env: PAGE=dance.html (default scene.html), QUERY=?x=y, AUDIO=music.wav (muxed into the MP4)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const root = path.resolve(process.argv[2] || '.');
const out = path.resolve(process.argv[3] || 'ezano-tv-3d.mp4');
const fps = Number(process.argv[4] || 30);
const only = process.argv[5] ? process.argv[5].split(',').map(Number) : null;
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.otf': 'font/otf' };

const server = http.createServer((req, res) => {
  const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
}).listen(0);
const port = server.address().port;

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
page.on('console', m => console.log('[page]', m.text()));
page.on('pageerror', e => console.error('[page error]', e.message));
await page.goto(`http://127.0.0.1:${port}/${process.env.PAGE || 'scene.html'}${process.env.QUERY || ''}`);
await page.waitForFunction('window.READY === true', null, { timeout: 60000 });
const duration = await page.evaluate('window.DURATION');

const framesDir = path.join(path.dirname(out), 'frames');
fs.rmSync(framesDir, { recursive: true, force: true }); fs.mkdirSync(framesDir, { recursive: true });
const total = Math.round(duration * fps);
const list = only || [...Array(total).keys()];
for (const i of list) {
  await page.evaluate(t => window.renderAt(t), i / fps);
  await page.screenshot({ path: path.join(framesDir, `f${String(i).padStart(4, '0')}.png`) });
  if (i % 30 === 0) console.log(`frame ${i}/${total}`);
}
await browser.close(); server.close();

if (!only) {
  const audio = process.env.AUDIO ? ['-i', path.resolve(process.env.AUDIO), '-c:a', 'aac', '-b:a', '192k', '-shortest'] : [];
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(fps), '-i', path.join(framesDir, 'f%04d.png'), ...audio,
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-preset', 'slow', '-movflags', '+faststart', out], { stdio: 'inherit' });
  console.log('wrote', out);
}
