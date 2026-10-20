import assert from 'node:assert/strict';
import { test } from 'node:test';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { BUDGET, check } from '../check-compose.mjs';

const MB = 1024 * 1024;

function service(overrides = {}) {
  return {
    image: 'example/app:1@sha256:' + 'a'.repeat(64),
    user: '10001:10001',
    read_only: true,
    cap_drop: ['ALL'],
    security_opt: ['no-new-privileges:true'],
    pids_limit: 256,
    mem_limit: String(256 * MB),
    cpus: 1,
    logging: { driver: 'json-file', options: { 'max-size': '10m', 'max-file': '5' } },
    healthcheck: { test: ['CMD', 'true'] },
    ...overrides,
  };
}

const good = () => ({ services: { proxy: service({ ports: [{ target: 8443, published: '443' }] }), 'core-backend': service({ mem_limit: String(1536 * MB) }) } });

test('a hardened configuration passes', () => {
  assert.deepEqual(check(good()), []);
});

test('an empty configuration is a problem', () => {
  assert.match(check({}).join('\n'), /no services/);
});

const cases = [
  ['capabilities not dropped', { cap_drop: [] }, /cap_drop/],
  ['privilege escalation allowed', { security_opt: [] }, /no-new-privileges/],
  ['privileged container', { privileged: true }, /privileged/],
  ['host network', { network_mode: 'host' }, /host namespaces/],
  ['docker socket', { volumes: [{ type: 'bind', source: '/var/run/docker.sock', target: '/s' }] }, /Docker socket/],
  ['docker socket short syntax', { volumes: ['/var/run/docker.sock:/s'] }, /Docker socket/],
  ['no pids limit', { pids_limit: undefined }, /pids_limit/],
  ['no memory limit', { mem_limit: undefined }, /mem_limit is missing/],
  ['memory above the budget', { mem_limit: String(2048 * MB) }, /exceeds the budget/],
  ['no cpu limit', { cpus: undefined }, /cpus/],
  ['no log rotation', { logging: { driver: 'json-file' } }, /logging/],
  ['writable root file system', { read_only: false }, /read_only/],
  ['runs as root', { user: '0:0' }, /non-root/],
  ['no user at all', { user: undefined }, /non-root/],
  ['no health check', { healthcheck: undefined }, /healthcheck/],
  ['image without a digest', { image: 'example/app:1' }, /pinned by digest/],
  ['publishes an extra port', { ports: ['8080:8080'] }, /publishes port 8080/],
  ['literal secret in the environment', { environment: { DB_PASSWORD: 'hunter2' } }, /literal value/],
  ['literal secret in list syntax', { environment: ['API_TOKEN=abc'] }, /literal value/],
];

for (const [name, override, pattern] of cases) {
  test(`rejects: ${name}`, () => {
    const config = good();
    config.services['core-backend'] = service({ mem_limit: String(1536 * MB), ...override });
    const problems = check(config).join('\n');
    assert.match(problems, pattern);
  });
}

test('secret files and the allowed test credential are fine', () => {
  const config = good();
  config.services['core-backend'].environment = { DB_PASSWORD_FILE: '/run/secrets/x', E2E_USER_PASSWORD: 'generated', SESSION_KEY_ID: 'k1' };
  assert.deepEqual(check(config), []);
});

test('secrets must come from files', () => {
  const config = good();
  config.secrets = { inline: { environment: 'X' } };
  assert.match(check(config).join('\n'), /secret inline/);
});

test('a service without a budget needs one', () => {
  const config = good();
  config.services.mystery = service();
  assert.match(check(config).join('\n'), /no memory budget/);
});

test('documented exceptions apply to keycloak and ldap only', () => {
  const config = good();
  config.services.keycloak = service({ read_only: false, user: undefined, mem_limit: String(BUDGET.keycloak) });
  config.services.ldap = service({ user: undefined, read_only: false, healthcheck: undefined, image: 'osixia/openldap:1.5.0' });
  assert.deepEqual(check(config), []);
  config.services['core-backend'].read_only = false;
  assert.match(check(config).join('\n'), /core-backend: read_only/);
});

test('the command line reads the configuration from standard input and fails on problems', () => {
  const script = fileURLToPath(new URL('../check-compose.mjs', import.meta.url));
  const ok = spawnSync('node', [script], { input: JSON.stringify(good()), encoding: 'utf8' });
  assert.equal(ok.status, 0);
  assert.match(ok.stdout, /meets the hardening rules/);
  const bad = good();
  bad.services['core-backend'].privileged = true;
  const failed = spawnSync('node', [script], { input: JSON.stringify(bad), encoding: 'utf8' });
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /privileged/);
});

test('memory limits may be written with units', () => {
  const config = good();
  config.services.proxy.mem_limit = '256m';
  assert.deepEqual(check(config), []);
  config.services.proxy.mem_limit = 'lots';
  assert.match(check(config).join('\n'), /mem_limit is missing/);
});
