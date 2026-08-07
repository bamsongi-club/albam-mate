import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  publishArtifacts,
  validateMechanismDictionary,
  validateSnapshotBatch,
} from './prepare-game-mechanism-catalog.mjs';

test('XML batch는 요청·응답·bytes·취득 시각·파싱 ID를 모두 검증한다', () => {
  const body = '<items><item id="1"><link type="boardgamemechanic" id="2040" value="Hand Management"/></item></items>';
  const batch = {
    file: 'batch-00001.xml',
    requestIds: [1],
    responseIds: [1],
    httpStatus: 200,
    bytes: Buffer.byteLength(body),
    sha256: sha256(body),
    acquiredAt: '2026-08-07T00:00:00Z',
  };

  const result = validateSnapshotBatch({ batch, body, targetIds: new Set([1]) });
  assert.deepEqual(result.requestIds, [1]);
  assert.equal(result.games[0].bggId, 1);

  for (const [property, value] of [
    ['requestIds', [1, 1]],
    ['responseIds', [2]],
    ['bytes', batch.bytes + 1],
    ['acquiredAt', '2026-02-30T00:00:00Z'],
  ]) {
    assert.throws(
      () => validateSnapshotBatch({ batch: { ...batch, [property]: value }, body, targetIds: new Set([1]) }),
      /invalid|do not match|parsed IDs|unexpected/u,
    );
  }
});

test('메커니즘 사전은 검수 설명을 필수·비공백·300자 이하로 검증한다', () => {
  const description = '검수된 설명';
  const dictionary = validateMechanismDictionary({
    entries: [{ bgg_id: '2040', name: 'Hand Management', name_ko: '핸드 관리', description_ko: description }],
  });
  assert.equal(dictionary.get('2040').description_ko, description);

  for (const descriptionKo of [undefined, '   ', '가'.repeat(301)]) {
    assert.throws(
      () => validateMechanismDictionary({
        entries: [{ bgg_id: '2040', name: 'Hand Management', name_ko: '핸드 관리', description_ko: descriptionKo }],
      }),
      /invalid mechanism dictionary/u,
    );
  }
});

test('산출물 기록이 중간에 실패하면 최종 경로에 부분 artifact를 publish하지 않는다', () => {
  const root = mkdtempSync(join(tmpdir(), 'albam-mate-mechanism-output-'));
  const output = join(root, 'output');
  try {
    assert.throws(
      () => publishArtifacts(output, { 'first.json': '{}', 'second.sql': 'BEGIN;' }, (path, contents) => {
        if (path.endsWith('second.sql')) throw new Error('simulated write failure');
        writeFileSync(path, contents);
      }),
      /simulated write failure/u,
    );
    assert.equal(existsSync(output), false);
    assert.deepEqual(readdirSync(root), []);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}
