// Draws the PWA icons (no image libraries): the default brand color, a white ring and dot. Run "npm run gen:icons" to regenerate.
import { mkdirSync, writeFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';

const BRAND = [0x0b, 0x6e, 0x5c];

function crc32(buf) {
  let c;
  const table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (const b of buf) crc = table[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function png(size, { maskable }) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  const c = size / 2;
  // maskable icons keep their content inside the central 80% (the OS may crop the rest)
  const scale = maskable ? 0.8 : 1;
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x + 0.5 - c, y + 0.5 - c) / (size / 2);
      const r = d / scale;
      const white = (r > 0.5 && r < 0.62) || r < 0.2;
      const rounded = maskable || d < 1.5; // full-bleed square background
      const px = y * (size * 4 + 1) + 1 + x * 4;
      const color = white ? [255, 255, 255] : BRAND;
      raw[px] = color[0];
      raw[px + 1] = color[1];
      raw[px + 2] = color[2];
      raw[px + 3] = rounded ? 255 : 0;
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bit depth
  header[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync('public/icons', { recursive: true });
writeFileSync('public/icons/icon-192.png', png(192, { maskable: false }));
writeFileSync('public/icons/icon-512.png', png(512, { maskable: false }));
writeFileSync('public/icons/maskable-512.png', png(512, { maskable: true }));
console.log('Wrote public/icons/*.png');
