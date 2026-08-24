import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const sourceDirectory = dirname(fileURLToPath(import.meta.url));
const stylesCss = readFileSync(join(sourceDirectory, 'styles.css'), 'utf8');
const indexHtml = readFileSync(join(sourceDirectory, '..', 'index.html'), 'utf8');

describe('#1070 iOS 입력 확대 방지', () => {
  it('실제 폼 컨트롤과 검색·채팅 입력의 글자 크기를 16px 이상으로 유지한다', () => {
    expect(stylesCss).toMatch(/input, select, textarea\s*\{[^}]*font-size:\s*16px;/s);
    expect(stylesCss).toMatch(/\.searchbox input\s*\{[^}]*font-size:\s*16px;/s);
    expect(stylesCss).toMatch(/\.field-input\s*\{[^}]*font-size:\s*16px;/s);
    expect(stylesCss).toMatch(/\.chat-compose textarea\s*\{[^}]*font-size:\s*16px;/s);
    expect(stylesCss).toMatch(/\.chat-compose input\s*\{[^}]*font-size:\s*16px;/s);
  });

  it('모바일 브라우저가 입력 포커스에서 텍스트를 자동 보정하지 않도록 고정한다', () => {
    expect(stylesCss).toMatch(/html\s*,\s*body\s*\{[^}]*-webkit-text-size-adjust:\s*100%;[^}]*text-size-adjust:\s*100%;/s);
  });

  it('사용자 수동 확대를 막는 viewport 설정을 추가하지 않는다', () => {
    expect(indexHtml).toMatch(/width=device-width/);
    expect(indexHtml).not.toMatch(/user-scalable\s*=\s*no/i);
    expect(indexHtml).not.toMatch(/maximum-scale\s*=\s*1(?:\.0+)?/i);
  });
});
