const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const assets = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/studio.css', ['studio.css', 'text/css; charset=utf-8']],
  ['/studio.js', ['studio.js', 'text/javascript; charset=utf-8']]
]);
const server = http.createServer((request, response) => {
  if (!['GET', 'HEAD'].includes(request.method)) { response.writeHead(405); response.end(); return; }
  let pathname;
  try { pathname = new URL(request.url, 'http://127.0.0.1').pathname; }
  catch { response.writeHead(400); response.end(); return; }
  const asset = assets.get(pathname);
  if (!asset) { response.writeHead(404); response.end('Not found'); return; }
  fs.readFile(path.join(__dirname, asset[0]), (error, body) => {
    if (error) { response.writeHead(500); response.end('Preview unavailable'); return; }
    response.writeHead(200, {'Content-Type':asset[1], 'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff'});
    response.end(request.method === 'HEAD' ? undefined : body);
  });
});
server.listen(0, '127.0.0.1', () => console.log(`Interview Studio preview: http://127.0.0.1:${server.address().port}`));
server.on('error', error => { console.error(error.message); process.exitCode=1; });
