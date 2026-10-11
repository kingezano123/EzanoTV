// Synthetic Xtream server for GuideIsolationTest. Start with `node tools/guide-isolation-fixture.cjs`.
const http = require('http');

const base = 'http://10.0.2.2:8768';
let mode = 'full';
let catalogRequests = 0;
let guideRequests = 0;
const live = [1, 2, 3, 4].map(id => ({
  stream_id: id,
  name: `Isolation live ${id}`,
  category_id: '1',
  epg_channel_id: `isolation.${id}`,
}));
const movies = Array.from({ length: 6 }, (_, index) => ({
  stream_id: index + 1,
  name: `Isolation movie ${index + 1}`,
  category_id: '1',
  container_extension: 'mp4',
}));

const server = http.createServer((req, res) => {
  const url = new URL(req.url, base);
  const json = value => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(value));
  };

  if (url.pathname === '/mode') {
    const value = url.searchParams.get('value');
    if (!['full', 'slow', 'truncated'].includes(value)) {
      res.writeHead(400);
      res.end('mode must be full, slow, or truncated');
      return;
    }
    mode = value;
    if (url.searchParams.get('reset') === '1') {
      catalogRequests = 0;
      guideRequests = 0;
    }
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end(mode);
    return;
  }

  if (url.pathname === '/stats') {
    json({ catalogRequests, guideRequests });
    return;
  }

  if (url.pathname === '/player_api.php') {
    if (url.searchParams.get('username') !== 'test' || url.searchParams.get('password') !== 'test') {
      json({ user_info: { auth: 0 } });
      return;
    }
    const action = url.searchParams.get('action');
    if (!action) {
      catalogRequests++;
      json({ user_info: { auth: 1, status: 'Active' } });
      return;
    }
    if (action.endsWith('_categories')) { json([{ category_id: '1', category_name: 'Guide isolation' }]); return; }
    if (action === 'get_live_streams') { json(live); return; }
    if (action === 'get_vod_streams') { json(movies); return; }
    if (action === 'get_series') { json([{ series_id: 1, name: 'Isolation series 1', category_id: '1' }]); return; }
    if (action === 'get_series_info') { json({ episodes: {} }); return; }
    json([]);
    return;
  }

  if (url.pathname === '/xmltv.php') {
    guideRequests++;
    if (mode === 'slow') {
      setTimeout(() => {
        if (res.destroyed) return;
        res.writeHead(503, { 'content-type': 'text/plain' });
        res.end('synthetic slow guide failure');
      }, 8_000);
      return;
    }

    const stamp = ms => new Date(ms).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
    const now = Math.floor(Date.now() / 3600000) * 3600000;
    const program = (channel, offset, title) => {
      const start = now + offset * 3600000;
      const stop = start + 3600000;
      return `<programme channel="${channel}" start="${stamp(start)}" stop="${stamp(stop)}"><title>${title}</title><desc>Guide isolation fixture.</desc></programme>`;
    };
    const first = program('isolation.1', 1, mode === 'truncated' ? 'Truncated complete listing' : 'Isolation guide 1-1');
    const programs = mode === 'truncated'
      ? `${first}<programme channel="isolation.2" start="${stamp(now + 7200000)}" stop="${stamp(now + 10800000)}"><title>Malformed trailing entry`
      : live.flatMap(channel => Array.from({ length: 8 }, (_, index) =>
        program(`isolation.${channel.stream_id}`, index + 1, `Isolation guide ${channel.stream_id}-${index + 1}`))).join('');
    res.writeHead(200, { 'content-type': 'application/xml' });
    res.end(`<tv>${programs}</tv>`);
    return;
  }

  res.writeHead(404);
  res.end();
});

server.listen(8768, '0.0.0.0', () => console.log('Guide isolation fixture ready on port 8768'));
