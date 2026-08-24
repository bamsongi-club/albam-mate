import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const sourceDirectory = dirname(fileURLToPath(import.meta.url));
const stylesCss = readFileSync(join(sourceDirectory, 'styles.css'), 'utf8');

describe('MATCH-01 하단 footer 레이아웃', () => {
  it('plain footer는 full-bleed footer의 좌우·하단 음수 여백을 물려받지 않는다', () => {
    expect(stylesCss).toMatch(/\.match-footer\.plain\s*\{[^}]*margin:\s*34px\s+0\s+0;/s);
  });
});
