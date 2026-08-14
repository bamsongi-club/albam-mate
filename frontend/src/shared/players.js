// 참가자 색은 순서로 고정한다. 미플·아바타·채팅 이름에 같은 색을 쓴다.
const PLAYER_COLORS = ['#C2402D', '#2C6E9B', '#D9A02B', '#3F7D53', '#7B4F9D', '#D2723A', '#4A5C8C', '#8A6A3C'];

// 밝은 색 위에서만 글자를 먹색으로 둔다.
// #D2723A는 흰 글자와 3.38:1이라 WCAG AA(4.5:1)에 못 미친다. 먹색을 쓰면 5.86:1이 된다.
const LIGHT_COLORS = new Set(['#D9A02B', '#D2723A']);

export function playerColor(index) {
  return PLAYER_COLORS[index % PLAYER_COLORS.length];
}

export function playerTextColor(color) {
  return LIGHT_COLORS.has(color) ? '#0A0A0A' : '#fff';
}

// 참가 순서를 알 수 없는 자리(채팅 상대 이름 등)에서는 이름으로 같은 색을 되찾는다.
export function nameColor(name = '') {
  const total = [...name].reduce((sum, letter) => sum + letter.codePointAt(0), 0);
  return playerColor(total);
}
