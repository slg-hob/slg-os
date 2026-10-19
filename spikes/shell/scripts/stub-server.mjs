// Stub resource server (WP3.4). /echo returns the headers it received; token validation is added in WP3.
import { createServer } from 'node:http';

createServer((req, res) => {
  if (req.url.startsWith('/echo')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ headers: req.headers, remote: req.socket.remoteAddress }));
  }
  res.writeHead(404).end();
}).listen(5300, '127.0.0.1');
