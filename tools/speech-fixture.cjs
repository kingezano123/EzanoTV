const http = require('http'), fs = require('fs'), path = require('path');
const file = path.join(__dirname, '..', 'test-media', 'speech-fixture.wav');
http.createServer((req, res) => {
  const size = fs.statSync(file).size;
  let start = 0, end = size - 1, status = 200;
  const match = /bytes=(\d+)-(\d*)/.exec(req.headers.range || '');
  if (match) { start = +match[1]; if (match[2]) end = Math.min(end, +match[2]); status = 206; }
  if (start > end || start >= size) { res.writeHead(416); res.end(); return; }
  res.setHeader('content-type', 'audio/wav');
  res.setHeader('accept-ranges', 'bytes');
  res.setHeader('content-length', end - start + 1);
  if (status === 206) res.setHeader('content-range', `bytes ${start}-${end}/${size}`);
  res.writeHead(status);
  fs.createReadStream(file, { start, end }).pipe(res);
}).listen(8767, '127.0.0.1', () => console.log('Speech test fixture on 8767'));
