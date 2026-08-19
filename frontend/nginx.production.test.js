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

describe('production nginx OPS-02 timing', () => {
  it('records request and upstream timing with bounded route and upstream fields', () => {
    expect(configuration).toContain('log_format ops_timing escape=json');
    expect(configuration).toContain('"event":"nginx_request_timing"');
    expect(configuration).toContain('"method":"$request_method"');
    expect(configuration).toContain('"routeGroup":"$albam_route_group"');
    expect(configuration).toContain('"status":"$status"');
    expect(configuration).toContain('"requestTimeSeconds":"$request_time"');
    expect(configuration).toContain('"upstreamResponseTimeSeconds":"$upstream_response_time"');
    expect(configuration).toContain('"upstreamAddress":"$upstream_addr"');
  });

  it('does not put raw request or client identity values in the timing record', () => {
    const formatStart = configuration.indexOf('    log_format ops_timing');
    const formatEnd = configuration.indexOf(';', formatStart) + 1;
    const format = configuration.slice(formatStart, formatEnd);

    expect(formatStart).toBeGreaterThanOrEqual(0);
    expect(format).not.toContain('$request ');
    expect(format).not.toContain('$request_uri');
    expect(format).not.toContain('$args');
    expect(format).not.toContain('$remote_addr');
    expect(format).not.toContain('$http_user_agent');
    expect(format).not.toContain('$http_x_forwarded_for');
    expect(format.match(/\$[A-Za-z_][A-Za-z0-9_]*/g)).toEqual([
      '$time_iso8601',
      '$request_method',
      '$albam_route_group',
      '$status',
      '$request_time',
      '$upstream_response_time',
      '$upstream_addr',
    ]);
  });

  it('keeps the private upstream address in the timing log but never exposes it to public responses', () => {
    expect(configuration).toContain('"upstreamAddress":"$upstream_addr"');
    expect(configuration).not.toContain('add_header X-Albam-Mate-Upstream');

    for (const location of ['~ ^/api/rooms/[1-9][0-9]*/chat/ws$', '/api/', '/uploads/']) {
      expect(locationBody(location)).toContain('proxy_pass_header X-Albam-Mate-Upstream;');
    }
  });

  it('enables the timing record only for bounded upstream locations', () => {
    expect(configuration).toContain('map $uri $albam_route_group');
    expect(configuration).toContain('default other;');
    expect(configuration).toContain('~^/api/ api;');
    expect(configuration).toContain('~^/uploads/ uploads;');
    expect(configuration).toContain('~^/api/rooms/[1-9][0-9]*/chat/ws$ chat_websocket;');

    for (const location of ['~ ^/api/rooms/[1-9][0-9]*/chat/ws$', '/api/', '/uploads/']) {
      expect(locationBody(location)).toContain('access_log /dev/stdout ops_timing;');
    }

    expect(configuration.match(/^\s*access_log \/dev\/stdout ops_timing;$/gm)).toHaveLength(3);
  });
});
