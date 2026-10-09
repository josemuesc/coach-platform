// The local back end for development and for the end-to-end tests: a THROWAWAY PostgreSQL 17 in Docker plus the back end jar.
// It never touches Supabase: the database is a container started here with a random password and removed on exit.
//
//   node scripts/stack.mjs [--frontend-origin http://127.0.0.1:4173]   start, stay in the foreground, clean up on Ctrl-C (Playwright)
//   node scripts/stack.mjs --keep [--frontend-origin ...]              start, then return, leaving it running (development)
//   node scripts/stack.mjs --down                                      stop what --keep started
//
// The back end listens on 127.0.0.1:8081 with: registration OPEN (this is a throwaway database), OpenAPI served, CORS for the front end
// origin, and the per-IP throttles raised so the tests can hammer them (the per-email+IP login limit stays at its real value).
import { spawn, execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, openSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};

const root = resolve(import.meta.dirname, '..');
const backend = resolve(root, '..', 'backend');
const stateDir = join(root, '.stack');
const stateFile = join(stateDir, 'state.json');
const PORT = 8081;
const frontendOrigin = option('--frontend-origin', 'http://127.0.0.1:5173');

function toolsEnv() {
  const env = { ...process.env };
  const jdk = join(homedir(), 'tools', 'jdk-21', 'Contents', 'Home');
  const mvn = join(homedir(), 'tools', 'maven', 'bin');
  if (existsSync(jdk)) {
    env.JAVA_HOME = jdk;
    env.PATH = `${join(jdk, 'bin')}:${existsSync(mvn) ? mvn + ':' : ''}${env.PATH}`;
  }
  return env;
}

function down() {
  if (!existsSync(stateFile)) return console.log('Nothing to stop.');
  const state = JSON.parse(readFileSync(stateFile, 'utf8'));
  try { process.kill(state.pid); } catch { /* already gone */ }
  try { execFileSync('docker', ['rm', '-f', state.container], { stdio: 'ignore' }); } catch { /* already gone */ }
  rmSync(stateFile, { force: true });
  console.log('Stopped.');
}

if (flag('--down')) {
  down();
  process.exit(0);
}

function jarPath() {
  const target = join(backend, 'target');
  const jars = existsSync(target) ? readdirSync(target).filter((f) => /^coach-platform-backend-.*\.jar$/.test(f) && !f.endsWith('.original')) : [];
  return jars.length ? join(target, jars[0]) : null;
}

function newestSource(dir) {
  let newest = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    newest = Math.max(newest, entry.isDirectory() ? newestSource(p) : statSync(p).mtimeMs);
  }
  return newest;
}

const env = toolsEnv();
let jar = jarPath();
if (!jar || statSync(jar).mtimeMs < newestSource(join(backend, 'src', 'main'))) {
  console.log('Building the back end jar (mvn package, tests skipped: run "mvn verify" yourself)...');
  execFileSync('mvn', ['-q', '-DskipTests', 'package'], { cwd: backend, env, stdio: 'inherit' });
  jar = jarPath();
}
if (!jar) throw new Error('back end jar not found');

mkdirSync(stateDir, { recursive: true });
// leftovers of a run that was killed without cleaning up (Playwright stops us with a signal we cannot always catch)
const stale = execFileSync('docker', ['ps', '-aq', '--filter', 'label=coach-local-stack']).toString().split('\n').filter(Boolean);
if (stale.length) execFileSync('docker', ['rm', '-f', ...stale], { stdio: 'ignore' });
const dbPassword = randomBytes(18).toString('hex');
const container = `coach-local-pg-${process.pid}`;
execFileSync('docker', ['run', '-d', '--rm', '--label', 'coach-local-stack', '--name', container, '-e', `POSTGRES_PASSWORD=${dbPassword}`, '-p', '127.0.0.1::5432', 'postgres:17-alpine'], { stdio: 'ignore' });
let dbPort = '';
for (let i = 0; !dbPort; i++) {
  // Docker may need a moment before it reports the published port
  const line = execFileSync('docker', ['port', container, '5432/tcp']).toString().trim().split('\n')[0] ?? '';
  dbPort = /:(\d+)$/.exec(line)?.[1] ?? '';
  if (!dbPort) {
    if (i > 40) throw new Error('docker did not publish the postgres port');
    await new Promise((r) => setTimeout(r, 250));
  }
}
for (let i = 0; ; i++) {
  try {
    execFileSync('docker', ['exec', container, 'pg_isready', '-U', 'postgres'], { stdio: 'ignore' });
    break;
  } catch {
    if (i > 60) throw new Error('postgres did not become ready');
    await new Promise((r) => setTimeout(r, 500));
  }
}
await new Promise((r) => setTimeout(r, 1500)); // pg_isready answers a moment before the init scripts finish

const log = openSync(join(stateDir, 'backend.log'), 'w');
const server = spawn('java', ['-jar', jar], {
  cwd: backend,
  env: {
    ...env,
    DB_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/postgres`,
    DB_USER: 'postgres',
    DB_PASSWORD: dbPassword,
    JWT_SECRET: randomBytes(48).toString('base64'),
    APP_QR_SECRET: randomBytes(48).toString('base64'),
    SERVER_PORT: String(PORT),
    APP_FRONTEND_URL: frontendOrigin,
    APP_REGISTRATION_OPEN: 'true',
    APP_OPENAPI_ENABLED: 'true',
    APP_SECURITY_LOGIN_IP_MAX_FAILURES: '100000',
    APP_SECURITY_REGISTER_IP_MAX_FAILURES: '100000',
    APP_SECURITY_RESET_PASSWORD_IP_MAX_FAILURES: '100000',
    APP_SECURITY_INVITATION_IP_MAX_FAILURES: '100000',
    APP_SECURITY_QR_SCAN_IP_MAX_FAILURES: '100000',
  },
  stdio: ['ignore', log, log],
  detached: false,
});
writeFileSync(stateFile, JSON.stringify({ pid: server.pid, container, dbPort }));

let stopping = false;
function cleanup() {
  if (stopping) return;
  stopping = true;
  try { server.kill('SIGTERM'); } catch { /* gone */ }
  try { execFileSync('docker', ['rm', '-f', container], { stdio: 'ignore' }); } catch { /* gone */ }
  rmSync(stateFile, { force: true });
}
server.on('exit', (code) => {
  if (!stopping) {
    console.error(`back end exited early (code ${code}); see ${join(stateDir, 'backend.log')}`);
    cleanup();
    process.exit(1);
  }
});

for (let i = 0; ; i++) {
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/v3/api-docs`);
    if (res.ok) break;
  } catch { /* not up yet */ }
  if (i > 240) {
    cleanup();
    throw new Error('back end did not start in time');
  }
  await new Promise((r) => setTimeout(r, 500));
}
console.log(`STACK READY: back end http://127.0.0.1:${PORT} (CORS for ${frontendOrigin}), throwaway Postgres on 127.0.0.1:${dbPort}`);

if (flag('--keep')) {
  server.unref();
  process.exit(0); // leaves java and the container running; "--down" stops them
}
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) process.on(signal, () => { cleanup(); process.exit(0); });
process.on('exit', cleanup);
await new Promise(() => {}); // stay in the foreground (Playwright stops us with a signal)
