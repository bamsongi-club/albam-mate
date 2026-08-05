#!/usr/bin/env node
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { parseRankRows, sha256 } from './catalog-analysis.mjs';
import { buildMetadataArtifact, CATEGORY_DEFINITIONS, parseBggMetadataXml } from './game-metadata-catalog.mjs';

const [flag, manifestPath, outFlag, out] = process.argv.slice(2);
if (flag !== '--input-manifest' || outFlag !== '--out' || !manifestPath || !out) throw new Error('usage: --input-manifest <path> --out <path>');
mkdirSync(out, { recursive: true });
const qualityPath = join(out, 'quality-report.json');
const outputs = ['service-game-metadata.json', 'upsert-game-metadata.sql'];
for (const file of outputs) { const path = join(out, file); if (existsSync(path)) throw new Error(`output must be empty: ${file}`); }
try {
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const problems = validateManifest(manifest);
  const inputs = ['games', 'ranks', 'themeDictionary'].map(key => ({ key, ...manifest[key], sha256Actual: checksum(manifest[key]?.path) }));
  for (const input of inputs) if (input.sha256 !== input.sha256Actual) problems.push(`checksum mismatch: ${input.key}`);
  const games = JSON.parse(readFileSync(manifest.games.path, 'utf8'));
  if (games.length !== manifest.games.rows || (!manifest.testOnly && games.length !== 170000)) problems.push('games rows must match manifest and production rows must be 170000');
  const gameIds = new Set(games.map(game => Number(game.bgg_id)));
  if (gameIds.size !== games.length || [...gameIds].some(id => !Number.isInteger(id) || id <= 0)) problems.push('invalid or duplicate target game BGG ID');
  const dictionary = JSON.parse(readFileSync(manifest.themeDictionary.path, 'utf8')).entries;
  const dictionaryById = new Map(); const dictionaryNames = new Set();
  for (const entry of dictionary ?? []) {
    if (!entry?.bggThemeId || !entry?.nameEn?.trim() || !entry?.nameKo?.trim() || dictionaryById.has(entry.bggThemeId) || dictionaryNames.has(entry.nameEn) || dictionaryNames.has(entry.nameKo)) problems.push(`invalid theme dictionary: ${entry?.bggThemeId}`);
    dictionaryById.set(entry.bggThemeId, entry); dictionaryNames.add(entry.nameEn); dictionaryNames.add(entry.nameKo);
  }
  const ranks = parseRankRows(readFileSync(manifest.ranks.path, 'utf8'));
  const xml = validateXmlSnapshot(manifest.xmlSnapshot, gameIds);
  problems.push(...xml.errors);
  if (problems.length) throw new Error(problems.join('; '));
  const xmlGames = xml.games;
  const built=buildMetadataArtifact({games:xmlGames,rankRows:ranks,dictionary}); if(built.errors.length) throw new Error(built.errors.join('; '));
  const artifact = { schemaVersion: 1, approved: true, performanceFixtureRelations: false, targetBggIds: [...gameIds].sort((a, b) => a - b), ...built.artifact };
  const artifactPath = join(out, 'service-game-metadata.json');
  const sqlPath = join(out, 'upsert-game-metadata.sql');
  writeFileSync(artifactPath, JSON.stringify(artifact));
  writeFileSync(sqlPath, renderSql(artifact));
  writeFileSync(qualityPath, JSON.stringify({ status: 'approved', inputs, snapshot: { manifestPath: manifest.xmlSnapshot.manifestPath, manifestSha256: checksum(manifest.xmlSnapshot.manifestPath), rawDirectory: manifest.xmlSnapshot.rawDirectory }, outputs: { artifact: { sha256: checksum(artifactPath) }, sql: { sha256: checksum(sqlPath) }, targetGames: artifact.targetBggIds.length, themes: artifact.themes.length, categoryRelations: artifact.categoryRelations.length, themeRelations: artifact.themeRelations.length, preferences: artifact.preferences.length } }, null, 2));
} catch (error) { failure(null, [error.message]); }

function validateManifest(value) { const errors = []; if (value?.schemaVersion !== 1 || value?.approved !== true || !value?.reviewedBy || !value?.reviewedAt) errors.push('unapproved metadata manifest'); for (const key of ['games','ranks','themeDictionary']) if (!value?.[key]?.path || !/^[a-f0-9]{64}$/.test(value[key].sha256 ?? '')) errors.push(`invalid ${key}`); if (!value?.xmlSnapshot?.rawDirectory || !value?.xmlSnapshot?.manifestPath || !/^[a-f0-9]{64}$/.test(value?.xmlSnapshot?.manifestSha256 ?? '')) errors.push('invalid XML snapshot'); return errors; }
function checksum(path) { return path && existsSync(path) ? sha256(readFileSync(path)) : null; }
function failure(manifest, errors) { writeFileSync(qualityPath, JSON.stringify({ status: 'blocked', manifest: manifest ? resolve(manifestPath) : null, errors }, null, 2)); process.exitCode = 1; }
function validateXmlSnapshot(snapshot, expectedGameIds) {
  const errors = [];
  if (checksum(snapshot?.manifestPath) !== snapshot?.manifestSha256) return { errors: ['invalid XML snapshot'], games: [] };
  let acquisition;
  try { acquisition = JSON.parse(readFileSync(snapshot.manifestPath, 'utf8')); } catch { return { errors: ['invalid XML snapshot manifest'], games: [] }; }
  const files = acquisition.files ?? acquisition;
  if (!Array.isArray(files) || !files.length) return { errors: ['missing XML acquisition files'], games: [] };
  const responseIds = new Set(); const xmlGames = [];
  for (const entry of files) {
    const fileName = entry.file ?? entry.path;
    const file = fileName && join(snapshot.rawDirectory, fileName);
    const requestedIds = entry.requestIds ?? entry.ids;
    const declaredResponseIds = entry.responseIds;
    if (!file || !existsSync(file) || !isPositiveUniqueIds(requestedIds, 20) || !isPositiveUniqueIds(declaredResponseIds, 20) || entry.httpStatus !== 200 || !entry.acquiredAt || !/^[a-f0-9]{64}$/.test(entry.sha256 ?? '')) { errors.push(`invalid XML batch: ${fileName}`); continue; }
    const body = readFileSync(file, 'utf8');
    if (Buffer.byteLength(body) !== entry.bytes || sha256(body) !== entry.sha256) { errors.push(`XML batch checksum mismatch: ${fileName}`); continue; }
    const parsed = parseBggMetadataXml(body); const parsedIds = parsed.map(game => game.bggId);
    if (!sameIdSet(parsedIds, declaredResponseIds) || !sameIdSet(requestedIds, declaredResponseIds)) { errors.push(`XML request/response IDs mismatch: ${fileName}`); continue; }
    for (const id of parsedIds) { if (responseIds.has(id)) errors.push(`duplicate XML response ID: ${id}`); responseIds.add(id); }
    xmlGames.push(...parsed);
  }
  if (responseIds.size !== expectedGameIds.size || [...responseIds].some(id => !expectedGameIds.has(id)) || [...expectedGameIds].some(id => !responseIds.has(id))) errors.push('XML response IDs do not exactly match target games');
  return { errors, games: xmlGames };
}
function isPositiveUniqueIds(ids, max) { return Array.isArray(ids) && ids.length > 0 && ids.length <= max && ids.length === new Set(ids).size && ids.every(id => Number.isInteger(id) && id > 0); }
function sameIdSet(left, right) { return isPositiveUniqueIds(left, 20) && isPositiveUniqueIds(right, 20) && left.length === right.length && left.every(id => right.includes(id)); }
function parseXml(xml, gameIds, dictionary, themes, relations, preferences) { for (const item of xml.matchAll(/<item[^>]*id="(\d+)"[\s\S]*?<\/item>/g)) { const bggId = Number(item[1]); if (!gameIds.has(bggId)) continue; const body = item[0]; const max = Number(/<maxplayers value="(\d+)"/.exec(body)?.[1]); const used = new Set([...themes.values()].map(t => t.code)); for (const link of body.matchAll(/<link type="boardgamecategory" id="(\d+)" value="([^"]+)"/g)) { const id = Number(link[1]); const mapped = dictionary.get(id); if (!mapped || mapped.nameEn !== link[2]) throw new Error(`missing or mismatched Korean theme: ${id}`); const theme = themes.get(id) ?? { bggThemeId: id, nameEn: link[2], nameKo: mapped.nameKo, code: stableThemeCode(link[2], id, used) }; themes.set(id, theme); relations.push({ bggId, bggThemeId: id }); } const poll = /<poll name="suggested_numplayers"[\s\S]*?<\/poll>/.exec(body)?.[0]; if (!poll) continue; const values = [...poll.matchAll(/<results numplayers="([^"]+)">([\s\S]*?)<\/results>/g)].map(match => ({ numPlayers: match[1], bestVotes: Number(/value="Best" numvotes="(\d+)"/.exec(match[2])?.[1]), recommendedVotes: Number(/value="Recommended" numvotes="(\d+)"/.exec(match[2])?.[1]), notRecommendedVotes: Number(/value="Not Recommended" numvotes="(\d+)"/.exec(match[2])?.[1]) })); preferences.push(...playerPreferences(values, max).map(value => ({ bggId, ...value }))); } }
function renderSql(artifact) {
  const categories = CATEGORY_DEFINITIONS;
  const quote = value => `'${String(value).replaceAll("'", "''")}'`;
  const values = (rows, casts) => rows.length
    ? `values ${rows.map(row => `(${row.join(',')})`).join(',')}`
    : `select ${casts.map((cast, index) => `null::${cast} as c${index}`).join(', ')} where false`;
  const categorySql = categories.map(row => `insert into game_categories(code,name_ko,name_en,bgg_subdomain,display_order,created_at,updated_at) values (${row.map(quote).join(',')},current_timestamp,current_timestamp) on conflict (code) do update set name_ko=excluded.name_ko,name_en=excluded.name_en,updated_at=current_timestamp;`).join('\n');
  const themeSql = artifact.themes.map(row => `insert into game_themes(bgg_theme_id,code,name_ko,name_en,created_at,updated_at) values (${row.bggThemeId},${quote(row.code)},${quote(row.nameKo)},${quote(row.nameEn)},current_timestamp,current_timestamp) on conflict (bgg_theme_id) do update set code=excluded.code,name_ko=excluded.name_ko,name_en=excluded.name_en,updated_at=current_timestamp;`).join('\n');
  const targets = values(artifact.targetBggIds.map(id => [id]), ['bigint']);
  const categoriesDesired = values(artifact.categoryRelations.map(row => [row.bggId, quote(row.category)]), ['bigint', 'text']);
  const themesDesired = values(artifact.themeRelations.map(row => [row.bggId, row.bggThemeId]), ['bigint', 'bigint']);
  const preferencesDesired = values(artifact.preferences.map(row => [row.bggId, row.playerCount, row.isRecommended, row.isBest]), ['bigint', 'integer', 'boolean', 'boolean']);
  const gameGuard = `do $$ begin if exists (select 1 from (${targets}) target(bgg_id) left join games g on g.bgg_id=target.bgg_id where g.id is null) then raise exception 'unresolved metadata game'; end if; end $$;`;
  const themeGuard = `do $$ begin if exists (select 1 from (${themesDesired}) desired(bgg_id,bgg_theme_id) left join game_themes t on t.bgg_theme_id=desired.bgg_theme_id where t.id is null) then raise exception 'unresolved metadata theme'; end if; end $$;`;
  const categoryRelations = `with target(bgg_id) as (${targets}), desired(bgg_id,code) as (${categoriesDesired}) delete from game_category_relations relation using games game where relation.game_id=game.id and game.bgg_id in (select bgg_id from target) and not exists (select 1 from desired where desired.bgg_id=game.bgg_id and desired.code=(select code from game_categories where id=relation.category_id));\nwith desired(bgg_id,code) as (${categoriesDesired}) insert into game_category_relations(game_id,category_id) select game.id,category.id from desired join games game on game.bgg_id=desired.bgg_id join game_categories category on category.code=desired.code on conflict do nothing;`;
  const themeRelations = `with target(bgg_id) as (${targets}), desired(bgg_id,bgg_theme_id) as (${themesDesired}) delete from game_theme_relations relation using games game where relation.game_id=game.id and game.bgg_id in (select bgg_id from target) and not exists (select 1 from desired join game_themes theme on theme.bgg_theme_id=desired.bgg_theme_id where desired.bgg_id=game.bgg_id and theme.id=relation.theme_id);\nwith desired(bgg_id,bgg_theme_id) as (${themesDesired}) insert into game_theme_relations(game_id,theme_id) select game.id,theme.id from desired join games game on game.bgg_id=desired.bgg_id join game_themes theme on theme.bgg_theme_id=desired.bgg_theme_id on conflict do nothing;`;
  const staleThemes = `with approved(bgg_theme_id) as (${values(artifact.themes.map(theme => [theme.bggThemeId]), ['bigint'])}) delete from game_theme_relations relation using game_themes theme where relation.theme_id=theme.id and not exists (select 1 from approved where approved.bgg_theme_id=theme.bgg_theme_id);\nwith approved(bgg_theme_id) as (${values(artifact.themes.map(theme => [theme.bggThemeId]), ['bigint'])}) delete from game_themes theme where not exists (select 1 from approved where approved.bgg_theme_id=theme.bgg_theme_id);`;
  const preferences = `with target(bgg_id) as (${targets}), desired(bgg_id,player_count,is_recommended,is_best) as (${preferencesDesired}) delete from game_player_preferences preference using games game where preference.game_id=game.id and game.bgg_id in (select bgg_id from target) and not exists (select 1 from desired where desired.bgg_id=game.bgg_id and desired.player_count=preference.player_count);\nwith desired(bgg_id,player_count,is_recommended,is_best) as (${preferencesDesired}) insert into game_player_preferences(game_id,player_count,is_recommended,is_best) select game.id,desired.player_count,desired.is_recommended,desired.is_best from desired join games game on game.bgg_id=desired.bgg_id on conflict (game_id,player_count) do update set is_recommended=excluded.is_recommended,is_best=excluded.is_best;`;
  return `begin;\n${categorySql}\n${staleThemes}\n${themeSql}\n${gameGuard}\n${themeGuard}\n${categoryRelations}\n${themeRelations}\n${preferences}\ncommit;\n`;
}
