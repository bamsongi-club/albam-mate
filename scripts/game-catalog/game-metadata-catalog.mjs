import crypto from 'node:crypto';

export const CATEGORY_DEFINITIONS = Object.freeze([
  ['STRATEGY', '전략', 'Strategy', 'strategygames', 1],
  ['ABSTRACT_STRATEGY', '추상 전략', 'Abstract Strategy', 'abstracts', 2],
  ['COLLECTIBLE', '컬렉터블', 'Collectible', 'cgs', 3],
  ['FAMILY', '가족', 'Family', 'familygames', 4],
  ['CHILDREN', '어린이', 'Children', 'childrensgames', 5],
  ['THEMATIC', '테마', 'Thematic', 'thematic', 6],
  ['PARTY', '파티', 'Party', 'partygames', 7],
  ['WARGAME', '워게임', 'Wargame', 'wargames', 8],
]);

export const CATEGORY_SUBDOMAINS = Object.freeze(
  Object.fromEntries(CATEGORY_DEFINITIONS.map(([code,,, subdomain]) => [subdomain, code]))
);

export function categoryCodesFromRanks(row) {
  return Object.entries(CATEGORY_SUBDOMAINS)
    .filter(([column]) => Number(row[column]) > 0).map(([, code]) => code);
}

export function stableThemeCode(nameEn, bggThemeId, usedCodes = new Set()) {
  const base = nameEn.normalize('NFKD').replace(/[^\w]+/g, '_').replace(/^_+|_+$/g, '').toUpperCase();
  if (!base) throw new Error(`theme code conflict: ${bggThemeId}`);
  let code = usedCodes.has(base) ? `${base}_BGG_${bggThemeId}` : base;
  for (let suffix = 2; usedCodes.has(code); suffix += 1) code = `${base}_BGG_${bggThemeId}_${suffix}`;
  usedCodes.add(code); return code;
}

export function playerPreferences(polls, maxPlayers) {
  if (!Number.isInteger(maxPlayers) || maxPlayers < 1) throw new Error('valid maxPlayers required');
  const result = [];
  for (const poll of polls ?? []) {
    const label = poll.numPlayers;
    const first = /^([1-9]\d*)\+?$/.exec(label)?.[1];
    if (!first) continue;
    const start = Number(first); if (start > maxPlayers) throw new Error(`poll exceeds maxPlayers: ${label}`);
    const best = Number(poll.bestVotes), recommended = Number(poll.recommendedVotes), notRecommended = Number(poll.notRecommendedVotes);
    if (![best, recommended, notRecommended].every(Number.isFinite) || best + recommended + notRecommended === 0) continue;
    const isRecommended = best + recommended > notRecommended;
    const isBest = best > recommended && best > notRecommended;
    for (let playerCount = start; playerCount <= (label.endsWith('+') ? maxPlayers : start); playerCount += 1) {
      result.push({ playerCount, isRecommended, isBest: isRecommended && isBest });
    }
  }
  return result;
}

export function parseBggMetadataXml(xml) {
  const games = [];
  for (const item of xml.matchAll(/<item[^>]*id="(\d+)"[\s\S]*?<\/item>/g)) {
    const body = item[0]; const maxPlayers = Number(/<maxplayers value="(\d+)"/.exec(body)?.[1]);
    const themes = [...body.matchAll(/<link type="boardgamecategory" id="(\d+)" value="([^"]+)"/g)]
      .map(match => ({ bggThemeId: Number(match[1]), nameEn: match[2] }));
    const poll = /<poll name="suggested_numplayers"[\s\S]*?<\/poll>/.exec(body)?.[0];
    const polls = poll ? [...poll.matchAll(/<results numplayers="([^"]+)">([\s\S]*?)<\/results>/g)].map(match => ({
      numPlayers: match[1], bestVotes: Number(/value="Best" numvotes="(\d+)"/.exec(match[2])?.[1]),
      recommendedVotes: Number(/value="Recommended" numvotes="(\d+)"/.exec(match[2])?.[1]),
      notRecommendedVotes: Number(/value="Not Recommended" numvotes="(\d+)"/.exec(match[2])?.[1]) })) : [];
    games.push({ bggId: Number(item[1]), maxPlayers, themes, polls });
  }
  return games;
}

export function validateThemeDictionary(entries) {
  const errors = []; const byId = new Map(); const names = new Set();
  for (const entry of entries ?? []) {
    if (!Number.isInteger(entry?.bggThemeId) || !entry.nameEn?.trim() || !entry.nameKo?.trim()) errors.push(`invalid theme: ${entry?.bggThemeId}`);
    if (byId.has(entry?.bggThemeId) || names.has(entry?.nameEn) || names.has(entry?.nameKo)) errors.push(`duplicate theme: ${entry?.bggThemeId}`);
    byId.set(entry?.bggThemeId, entry); names.add(entry?.nameEn); names.add(entry?.nameKo);
  }
  return { errors, byId };
}

export function buildMetadataArtifact({ games, rankRows, dictionary }) {
  const { errors, byId } = validateThemeDictionary(dictionary); const usedCodes = new Set(); const themes = new Map(); const themeRelations = []; const preferences = []; const seenRelations = new Set();
  for (const game of games) {
    for (const theme of game.themes) { const mapped = byId.get(theme.bggThemeId); if (!mapped || mapped.nameEn !== theme.nameEn) { errors.push(`theme mismatch: ${theme.bggThemeId}`); continue; } themes.set(theme.bggThemeId, mapped); const key=`${game.bggId}:${theme.bggThemeId}`; if (seenRelations.has(key)) errors.push(`duplicate theme relation: ${key}`); seenRelations.add(key); themeRelations.push({ bggId: game.bggId, bggThemeId: theme.bggThemeId }); }
    try { preferences.push(...playerPreferences(game.polls, game.maxPlayers).map(value => ({ bggId: game.bggId, ...value }))); } catch (error) { errors.push(error.message); }
  }
  const rankedThemes = [...themes.entries()].sort(([left], [right]) => left - right).map(([id, theme]) => ({ ...theme, bggThemeId: id, code: stableThemeCode(theme.nameEn, id, usedCodes) }));
  const ranks = new Map(rankRows.map(row => [Number(row.id), row]));
  return { errors, artifact: { themes: rankedThemes, categoryRelations: games.flatMap(game => categoryCodesFromRanks(ranks.get(game.bggId) ?? {}).map(category => ({ bggId: game.bggId, category }))), themeRelations, preferences } };
}

export function sha256(value) { return crypto.createHash('sha256').update(value).digest('hex'); }
