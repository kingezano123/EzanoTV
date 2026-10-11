// Small synthetic Xtream fixture used by PersistenceRegressionTest.
// Start with `node tools/persistence-fixture.cjs`; mode can be changed over HTTP.
const http = require('http');

const base = 'http://10.0.2.2:8767';
let mode = 'full';
const live = [1, 2, 3, 4].map(id => ({
  stream_id: id,
  name: `Persistence live ${id}`,
  category_id: '1',
  epg_channel_id: `persist.${id}`,
  tv_archive: 1,
  tv_archive_duration: 3,
}));
const movies = Array.from({ length: 6 }, (_, index) => ({
  stream_id: index + 1,
  name: `Persistence movie ${index + 1}`,
  category_id: '1',
  container_extension: 'mp4',
}));
const series = [{ series_id: 1, name: 'Persistence series 1', category_id: '1' }];

const server = http.createServer((req, res) => {
  const url = new URL(req.url, base);
  const json = value => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(value));
  };

  if (url.pathname === '/mode') {
    const value = url.searchParams.get('value');
    if (!['full', 'empty', 'empty-guide', 'failed-guide'].includes(value)) {
      res.writeHead(400);
      res.end('mode must be full, empty, empty-guide, or failed-guide');
      return;
    }
    mode = value;
    res.writeHead(200, { 'content-type': 'text/plain' });
    res.end(mode);
    return;
  }

  if (url.pathname === '/player_api.php') {
    if (url.searchParams.get('username') !== 'test' || url.searchParams.get('password') !== 'test') {
      json({ user_info: { auth: 0 } });
      return;
    }
    const action = url.searchParams.get('action');
    if (!action) { json({ user_info: { auth: 1, status: 'Active' } }); return; }
    if (action.endsWith('_categories')) { json([{ category_id: '1', category_name: 'Persistence tests' }]); return; }
    if (action === 'get_live_streams') { json(live); return; }
    if (action === 'get_vod_streams') { json(mode === 'empty' ? [] : movies); return; }
    if (action === 'get_series') { json(mode === 'empty' ? [] : series); return; }
    if (action === 'get_series_info') { json({ episodes: {} }); return; }
    json([]);
    return;
  }

  if (url.pathname === '/xmltv.php') {
    if (mode === 'failed-guide') {
      res.writeHead(503, { 'content-type': 'text/plain' });
      res.end('synthetic unavailable guide');
      return;
    }
    const empty = mode === 'empty-guide' || mode === 'empty';
    const stamp = ms => new Date(ms).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
    const now = Math.floor(Date.now() / 3600000) * 3600000;
    const programs = empty ? '' : live.flatMap(channel =>
      Array.from({ length: 8 }, (_, index) => {
        const offset = index + 1;
        const start = now + offset * 3600000;
        const stop = start + 3600000;
        return `<programme channel="persist.${channel.stream_id}" start="${stamp(start)}" stop="${stamp(stop)}"><title>Persistence guide ${channel.stream_id}-${index + 1}</title><desc>Guide preservation fixture.</desc></programme>`;
      })
    ).join('');
    res.writeHead(200, { 'content-type': 'application/xml' });
    res.end(`<tv>${programs}</tv>`);
    return;
  }

  res.writeHead(404);
  res.end();
});

server.listen(8767, '0.0.0.0', () => console.log('Persistence fixture ready on port 8767'));
