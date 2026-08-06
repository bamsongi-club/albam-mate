import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

function tokenFromKeychain() {
  const token = execFileSync('security', ['find-generic-password', '-a', process.env.USER, '-s', 'albam-mate-bgg-api', '-w'], { encoding: 'utf8' }).trim();
  if (!token) throw new Error('BGG Keychain token is unavailable');
  return token;
}
export function responseIdsFromXml(body) {
  return [...body.matchAll(/<item[^>]*\bid="(\d+)"/g)].map(match => Number(match[1]));
}

function samePositiveUniqueSet(actual, expected) {
  return Array.isArray(actual) && Array.isArray(expected)
    && actual.length > 0
    && actual.length <= 20
    && actual.length === new Set(actual).size
    && actual.every(value => Number.isInteger(value) && value > 0)
    && actual.length === expected.length
    && actual.every(value => expected.includes(value));
}

export function isCompleteBatch(entry, requestedIds, body) {
  const responseIds = responseIdsFromXml(body);
  return entry?.httpStatus === 200
    && samePositiveUniqueSet(requestedIds, requestedIds)
    && samePositiveUniqueSet(entry.requestIds, requestedIds)
    && samePositiveUniqueSet(entry.responseIds, requestedIds)
    && samePositiveUniqueSet(responseIds, requestedIds)
    && Buffer.byteLength(body) === entry.bytes
    && createHash('sha256').update(body).digest('hex') === entry.sha256;
}

export async function collectBatches({ ids, out, fetchImpl = fetch }) {
  await mkdir(out, { recursive: true }); const manifestPath = join(out, 'manifest.json');
  let manifest = { schemaVersion: 1, files: [] };
  try { manifest = JSON.parse(await readFile(manifestPath, 'utf8')); } catch { /* first batch */ }
  if (!Array.isArray(manifest.files)) manifest.files = [];
  for (let i = 0; i < ids.length; i += 20) {
    const batch = ids.slice(i, i + 20); const name = `batch-${String(i / 20 + 1).padStart(5, '0')}.xml`;
    const complete = manifest.files.find(entry => entry.file === name);
    if (complete) {
      try {
        const body = await readFile(join(out, name), 'utf8');
        if (isCompleteBatch(complete, batch, body)) continue;
      } catch { /* retrieve incomplete or tampered batch again */ }
    }
    const response = await fetchImpl(`https://boardgamegeek.com/xmlapi2/thing?id=${batch.join(',')}&stats=1`, { headers: { Authorization: `Bearer ${tokenFromKeychain()}` } });
    const body = await response.text(); const file = join(out, `batch-${String(i / 20 + 1).padStart(5, '0')}.xml`);
    await writeFile(file, body);
    const entry = {
      file: name,
      requestIds: batch,
      responseIds: responseIdsFromXml(body),
      httpStatus: response.status,
      bytes: Buffer.byteLength(body),
      sha256: createHash('sha256').update(body).digest('hex'),
      acquiredAt: new Date().toISOString(),
    };
    manifest.files = manifest.files.filter(value => value.file !== name);
    manifest.files.push(entry);
    await writeFile(manifestPath, JSON.stringify(manifest, null, 2));
  }
  await writeFile(manifestPath, JSON.stringify(manifest, null, 2)); return manifest;
}
void readFile; // explicit node-only module surface
