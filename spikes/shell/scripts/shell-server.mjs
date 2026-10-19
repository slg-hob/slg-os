// Serves the shell's index.html with a per-request CSP nonce and an inline import map that carries
// the SHA-384 integrity of every registered remote file (WP1.5, WP1.6). Everything else is served by nginx.
import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const mode = process.env.CSP_MODE === 'enforce' ? 'Content-Security-Policy' : 'Content-Security-Policy-Report-Only';
const port = Number(process.env.PORT || 5400);

createServer((req, res) => {
  const html = readFileSync(join(root, 'shell/dist/index.html'), 'utf8');
  const registry = JSON.parse(readFileSync(join(root, '.run/registry/modules.json'), 'utf8'));
  const integrity = Object.assign({}, ...registry.modules.map((m) => m.integrity));
  const nonce = randomBytes(16).toString('base64');
  const importmap = `<script type="importmap" nonce="${nonce}">${JSON.stringify({ imports: {}, integrity })}</script>`;
  const body = html.replace('<head>', `<head>${importmap}`);
  const csp = [
    "default-src 'none'",
    `script-src 'self' 'nonce-${nonce}'`,
    "style-src 'self'", "connect-src 'self'", "img-src 'self'", "font-src 'self'", "frame-src 'self'",
    "form-action 'self'", "worker-src 'none'", "base-uri 'none'", "object-src 'none'", "frame-ancestors 'none'",
  ].join('; ');
  res.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    [mode]: csp,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}).listen(port, '127.0.0.1', () => console.log(`shell-server on ${port}`));
