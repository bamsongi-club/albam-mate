import { appendFileSync } from 'node:fs';
import http from 'node:http';

const mode = process.argv[2];
const requestLog = process.argv[3];

function json(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

const server = http.createServer((request, response) => {
  appendFileSync(requestLog, `${request.url}\n`);
  const url = new URL(request.url, 'http://127.0.0.1');

  if (url.pathname === '/api/games' && url.searchParams.get('size') === '100') {
    json(response, 200, {
      data: { content: [{ id: 1, name: '무작위게임' }] },
    });
    return;
  }

  if (url.pathname === '/api/game-categories' && mode === 'metadata-503') {
    json(response, 503, { message: 'unavailable' });
    return;
  }

  if (url.pathname === '/api/game-categories' && mode === 'metadata-empty') {
    json(response, 200, { data: [] });
    return;
  }

  if (url.pathname === '/api/game-categories' && mode === 'metadata-malformed') {
    json(response, 200, { data: {} });
    return;
  }

  if (
    ['/api/game-categories', '/api/game-themes', '/api/game-mechanisms'].includes(
      url.pathname
    )
  ) {
    json(response, 200, { data: [{ code: 'TEST' }] });
    return;
  }

  if (
    url.pathname === '/api/games' &&
    url.searchParams.has('keyword') &&
    mode === 'keyword-204'
  ) {
    response.writeHead(204);
    response.end();
    return;
  }

  if (
    url.pathname === '/api/games' &&
    url.searchParams.has('keyword') &&
    mode === 'keyword-wrong-total'
  ) {
    json(response, 200, { data: { content: [], totalElements: 0 } });
    return;
  }

  if (
    url.pathname === '/api/games' &&
    url.searchParams.has('keyword') &&
    mode === 'keyword-malformed'
  ) {
    json(response, 200, { data: { content: {}, totalElements: 1 } });
    return;
  }

  if (mode === 'workload-204') {
    response.writeHead(204);
    response.end();
    return;
  }

  json(response, 200, {
    data: {
      content: [{ id: 1, name: '누스피요르드' }],
      totalElements: 1,
    },
  });
});

server.listen(0, '127.0.0.1', () => {
  process.stdout.write(`${server.address().port}\n`);
});

process.on('SIGTERM', () => server.close());
