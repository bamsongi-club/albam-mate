import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const gameDirectory = path.resolve(testDirectory, '..');

for (const scenario of ['02-game-keyword.js', '08-game-realistic.js']) {
  test(`${scenario} is a valid k6 bundle`, () => {
    const result = spawnSync('k6', ['inspect', path.join(gameDirectory, scenario)], {
      encoding: 'utf8',
    });

    assert.equal(
      result.status,
      0,
      result.stderr || result.stdout || `k6 inspect failed for ${scenario}`
    );
  });
}

test('index comparison runner is valid Bash and requires an index state', () => {
  const runner = path.join(gameDirectory, 'run-index-comparison.sh');
  const syntax = spawnSync('bash', ['-n', runner], { encoding: 'utf8' });
  assert.equal(syntax.status, 0, syntax.stderr || syntax.stdout);

  const result = spawnSync('bash', [runner], { encoding: 'utf8' });
  assert.equal(result.status, 2, result.stderr || result.stdout);
  assert.match(result.stderr, /INDEX_STATE must be no-pg-trgm or pg-trgm-gin/);
});
