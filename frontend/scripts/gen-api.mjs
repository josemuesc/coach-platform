// Generates src/api/schema.d.ts from openapi/openapi.json (the snapshot the BACKEND test exports and keeps in sync).
//   node scripts/gen-api.mjs           write
//   node scripts/gen-api.mjs --check   fail when the committed file is not what the snapshot generates
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const check = process.argv.includes('--check');
const target = 'src/api/schema.d.ts';
const out = check ? join(mkdtempSync(join(tmpdir(), 'cp-api-')), 'schema.d.ts') : target;
const bin = join('node_modules', '.bin', 'openapi-typescript');
execFileSync(bin, ['openapi/openapi.json', '-o', out], { stdio: 'inherit' });
if (check) {
  const same = readFileSync(out, 'utf8') === readFileSync(target, 'utf8');
  rmSync(join(out, '..'), { recursive: true, force: true });
  if (!same) {
    console.error(`${target} is out of date with openapi/openapi.json: run "npm run gen:api" and commit it.`);
    process.exit(1);
  }
  console.log('API client is up to date.');
}
