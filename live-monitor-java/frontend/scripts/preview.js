const http = require('http')
const fs = require('fs')
const path = require('path')
const root = path.resolve(__dirname, '../dist')
const types = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' }
http.createServer((request, response) => {
  let target
  try { target = path.resolve(root, '.' + decodeURIComponent(new URL(request.url, 'http://localhost').pathname)) }
  catch (_) { response.writeHead(400).end(); return }
  if (target !== root && !target.startsWith(root + path.sep)) { response.writeHead(403).end(); return }
  if (!path.extname(target)) target = path.join(root, 'index.html')
  fs.readFile(target, (error, data) => {
    if (error) { response.writeHead(404).end(); return }
    response.writeHead(200, { 'Content-Type': types[path.extname(target)] || 'application/octet-stream' }); response.end(data)
  })
}).listen(Number(process.env.PORT || 18407), '127.0.0.1')
