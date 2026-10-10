// After "npm run build": the shipped files must be self-contained. Fails on anything that would need the CSP loosened or that leaked
// from the end-to-end-only code.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

// URLs that only appear as XML namespaces or in library comments / error-message links: they are never requested
const ALLOWED = [
  /^http:\/\/www\.w3\.org\//,
  /^https:\/\/reactrouter\.com\//,
  /^https:\/\/react\.dev\//,
  /^https:\/\/github\.com\/ungap\//,
  /^https:\/\/github\.com\/syntax-tree\/hast-util-to-jsx-runtime$/,   // react-markdown: the link inside one of its error messages
  /^https:\/\/bit\.ly\/wb-precache/,
  /^https:\/\/wa\.me\/(57)?(\?text=)?$/,   // the WhatsApp link the coach opens BY HAND (a normal link, target _blank): the page never requests it
  /^http:\/\/localhost/,
  /^http:\/\/placeholder/,
];

const problems = [];
for (const file of files('dist')) {
  if (!/\.(js|css|html|webmanifest)$/.test(file)) continue;
  const text = readFileSync(file, 'utf8');
  if (/url\(\s*["']?data:/.test(text)) problems.push(`${file}: a data: URL (font-src/img-src data: would be needed)`);
  if (/<script(?![^>]*\ssrc=)[^>]*>/.test(text) && file.endsWith('.html')) problems.push(`${file}: inline <script>`);
  if (/SmokePage|__smoke|jsqr|RadixEvidence|react-remove-scroll/i.test(text)) problems.push(`${file}: end-to-end-only code in the production build`);
  for (const url of text.match(/https?:\/\/[a-zA-Z0-9._:/#?=&%~+-]+/g) ?? []) {
    if (!ALLOWED.some((re) => re.test(url))) problems.push(`${file}: unexpected URL ${url}`);
  }
}
if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
console.log('dist is self-contained: no data: URLs, no inline scripts, no third-party URLs, no end-to-end-only code.');
