// Hostile "module container" for WP1.11 / WP3.6: sets headers a module must not be able to set.
import { createServer } from 'node:http';

createServer((req, res) => {
  const headers = {
    'Set-Cookie': 'evil=1; Path=/; Max-Age=3600',
    'Content-Security-Policy': "script-src * 'unsafe-inline' 'unsafe-eval'",
    'Access-Control-Allow-Origin': '*',
    'Service-Worker-Allowed': '/',
  };
  if (req.url.endsWith('/sw.js')) {
    res.writeHead(200, { ...headers, 'Content-Type': 'application/javascript' });
    return res.end('self.addEventListener("fetch", () => {});');
  }
  if (req.url.endsWith('/wrong-mime.js')) {
    res.writeHead(200, { ...headers, 'Content-Type': 'text/html' });
    return res.end('window.__pwned = true;');
  }
  res.writeHead(200, { ...headers, 'Content-Type': 'application/javascript' });
  res.end('export const ok = true;');
}).listen(5201, '127.0.0.1');
