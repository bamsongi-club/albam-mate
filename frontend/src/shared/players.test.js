import { describe, expect, it } from 'vitest';
import { playerColor, playerTextColor } from './players';

function relativeLuminance(hex) {
  const channels = [1, 3, 5].map((start) => {
    const value = parseInt(hex.slice(start, start + 2), 16) / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrast(background, text) {
  const light = Math.max(relativeLuminance(background), relativeLuminance(text));
  const dark = Math.min(relativeLuminance(background), relativeLuminance(text));
  return (light + 0.05) / (dark + 0.05);
}

describe('참가자 색', () => {
  it('모든 참가자 색이 고른 글자색과 WCAG AA 대비를 만족한다', () => {
    // 아바타 이니셜이 이 조합으로 실제 렌더링된다.
    const colors = Array.from({ length: 8 }, (_, index) => playerColor(index));
    expect(new Set(colors).size).toBe(8);
    colors.forEach((color) => {
      const text = playerTextColor(color) === '#fff' ? '#ffffff' : playerTextColor(color);
      expect(contrast(color, text), color + ' 위 ' + text).toBeGreaterThanOrEqual(4.5);
    });
  });
});
