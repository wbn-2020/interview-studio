const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const allowed = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/galaxy.css', ['galaxy.css', 'text/css; charset=utf-8']],
  ['/content.js', ['content.js', 'text/javascript; charset=utf-8']],
  ['/galaxy.js', ['galaxy.js', 'text/javascript; charset=utf-8']],
  ['/core/index.html', ['../core/index.html', 'text/html; charset=utf-8']],
  ['/core/core.css', ['../core/core.css', 'text/css; charset=utf-8']],
  ['/core/core.js', ['../core/core.js', 'text/javascript; charset=utf-8']]
]);
const server = http.createServer((request, response) => {
  if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405); response.end(); return; }
  const route = new URL(request.url, 'http://localhost').pathname;
  const asset = allowed.get(route);
  if (!asset) { response.writeHead(404); response.end('Not found'); return; }
  fs.readFile(path.join(__dirname, asset[0]), (error, data) => {
    if (error) { response.writeHead(500); response.end('Preview unavailable'); return; }
    response.writeHead(200, { 'Content-Type': asset[1], 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    response.end(request.method === 'HEAD' ? undefined : data);
  });
});
server.listen(0, '127.0.0.1', () => console.log(`Knowledge Galaxy preview: http://127.0.0.1:${server.address().port}`));
server.on('error', error => { console.error(error); process.exitCode = 1; });
