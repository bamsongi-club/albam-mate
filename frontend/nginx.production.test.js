import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const configuration = readFileSync(resolve(process.cwd(), 'nginx.production.conf'), 'utf8');

function locationBody(locationHeader) {
  const locationStart = configuration.indexOf(`        location ${locationHeader} {`);
  expect(locationStart).toBeGreaterThanOrEqual(0);

  const bodyStart = configuration.indexOf('\n', locationStart) + 1;
  const bodyEnd = configuration.indexOf('\n        }', bodyStart);
  expect(bodyEnd).toBeGreaterThan(bodyStart);

  return configuration.slice(bodyStart, bodyEnd);
}

describe('production nginx WebSocket proxy', () => {
  const websocketLocation = '~ ^/api/rooms/[1-9][0-9]*/chat/ws$';

  it('routes the chat WebSocket through a dedicated upgraded proxy location', () => {
    const body = locationBody(websocketLocation);

    expect(body).toContain('proxy_http_version 1.1;');
    expect(body).toContain('proxy_set_header Upgrade $http_upgrade;');
    expect(body).toContain('proxy_set_header Connection "upgrade";');
    expect(body).toContain('proxy_pass http://spring_backend;');
  });

  it('keeps an idle WebSocket open longer than the generic HTTP timeout', () => {
    const body = locationBody(websocketLocation);

    expect(body).toContain('proxy_read_timeout 1h;');
    expect(body).toContain('proxy_send_timeout 1h;');
  });

  it('does not widen the timeout of ordinary API or upload requests', () => {
    expect(locationBody('/api/')).toContain('proxy_read_timeout 60s;');
    expect(locationBody('/api/')).toContain('proxy_send_timeout 60s;');
    expect(locationBody('/uploads/')).toContain('proxy_read_timeout 60s;');
    expect(locationBody('/uploads/')).toContain('proxy_send_timeout 60s;');
  });
});
