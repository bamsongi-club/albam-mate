#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { extractMechanismCatalog, renderMechanismUpsertSql } from './mechanism-catalog.mjs';
import { parseBggMetadataXml } from './game-metadata-catalog.mjs';

const [flag, manifestPath, outFlag, out] = process.argv.slice(2);
if (flag !== '--input-manifest' || outFlag !== '--out' || !manifestPath || !out) throw new Error('usage: --input-manifest <path> --out <path>');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
if (manifest?.approved !== true || manifest?.testOnly !== false || !manifest?.mechanismCatalog) throw new Error('approved production mechanism manifest required');
const dictionary = JSON.parse(readFileSync(manifest.mechanismDictionary.path, 'utf8'));
if (hash(readFileSync(manifest.mechanismDictionary.path)) !== manifest.mechanismDictionary.sha256) throw new Error('mechanism dictionary checksum mismatch');
if (hash(readFileSync(manifest.games.path)) !== manifest.games.sha256) throw new Error('games checksum mismatch');
const targetIds = new Set(JSON.parse(readFileSync(manifest.games.path, 'utf8')).map(({ bgg_id }) => Number(bgg_id)));
if (targetIds.size !== 170000) throw new Error('production target must contain 170000 unique game IDs');
const dictionaryById = new Map();
for (const entry of dictionary.entries ?? []) {
  if (!/^[1-9]\d*$/.test(entry?.bgg_id ?? '') || !text(entry?.name) || !text(entry?.name_ko) || dictionaryById.has(entry.bgg_id)) throw new Error(`invalid mechanism dictionary: ${entry?.bgg_id}`);
  dictionaryById.set(entry.bgg_id, entry);
}
if (dictionaryById.size !== manifest.mechanismCatalog.publishedCount) throw new Error('mechanism dictionary count mismatch');
const snapshot = JSON.parse(readFileSync(manifest.xmlSnapshot.manifestPath, 'utf8'));
if (hash(readFileSync(manifest.xmlSnapshot.manifestPath)) !== manifest.xmlSnapshot.manifestSha256) throw new Error('XML snapshot manifest checksum mismatch');
const games = [];
const seenGameIds = new Set();
for (const batch of snapshot.files ?? []) {
  const file = join(manifest.xmlSnapshot.rawDirectory, batch.file);
  const body = readFileSync(file, 'utf8');
  if (batch.httpStatus !== 200 || hash(body) !== batch.sha256) throw new Error(`invalid XML batch: ${batch.file}`);
  for (const game of parseBggMetadataXml(body)) {
    if (!targetIds.has(game.bggId)) throw new Error(`unexpected snapshot game: ${game.bggId}`);
    if (seenGameIds.has(game.bggId)) throw new Error(`duplicate snapshot game: ${game.bggId}`);
    seenGameIds.add(game.bggId);
    games.push({
      bgg_id: game.bggId,
      mechanisms: (game.mechanisms ?? []).map((mechanism) => {
        const translated = dictionaryById.get(mechanism.bgg_id);
        if (!translated || translated.name !== mechanism.name) throw new Error(`unapproved or mismatched mechanism: ${mechanism.bgg_id}`);
        return { ...mechanism, name_ko: translated.name_ko, description_ko: descriptionFor(translated) };
      }),
    });
  }
}
if (new Set(games.map(({ bgg_id }) => bgg_id)).size !== targetIds.size) throw new Error('snapshot response IDs do not match 170000 targets');
const built = extractMechanismCatalog(games, manifest);
if (built.errors.length) throw new Error(built.errors.map(({ message }) => message).join('; '));
mkdirSync(out, { recursive: true });
const artifactPath = join(out, 'service-mechanism-catalog.json');
const sqlPath = join(out, 'upsert-game-mechanisms.sql');
const reportPath = join(out, 'mechanism-quality-report.json');
for (const path of [artifactPath, sqlPath, reportPath]) if (existsSync(path)) throw new Error(`output must be empty: ${path}`);
writeFileSync(artifactPath, JSON.stringify(built.catalog));
writeFileSync(sqlPath, renderMechanismUpsertSql(built.catalog, built.relations));
writeFileSync(reportPath, JSON.stringify({ status: 'approved', testOnly: false, targetGames: targetIds.size, mechanisms: built.catalog.length, relations: built.relations.length, descriptions: built.catalog.filter(({ description_ko }) => text(description_ko)).length, outputs: { artifactSha256: hash(readFileSync(artifactPath)), sqlSha256: hash(readFileSync(sqlPath)) } }, null, 2));

function descriptionFor({ name_ko, name }) {
  const specific = {
    'Auction: Dexterity': '손기술로 물건을 조작해 더 좋은 입찰 결과를 노려요.',
    'Impulse Movement': '한쪽이 짧은 행동을 하면 다른 쪽도 이어서 행동하며 진행해요.',
    'Passed Action Token': '행동 토큰을 다음 플레이어에게 넘기며 선택 기회를 이어 가요.',
    'Lane Battler': '여러 줄로 나뉜 전장에서 유닛을 배치해 맞서요.',
    'Visual Restriction': '보이는 정보나 시야가 제한된 상태에서 판단해요.',
    'Facing': '말이 바라보는 방향에 따라 이동·공격·효과가 달라져요.',
    'Single Play': '각자 한 번의 행동 또는 한 장의 카드로 진행해요.',
  };
  if (specific[name]) return specific[name];
  if (name.startsWith('Auction:')) return `${name_ko} 방식으로 입찰해 원하는 보상이나 순서를 정해요.`;
  if (name.startsWith('Turn Order:')) return `${name_ko} 기준으로 다음 차례를 정해요.`;
  if (name.includes('Movement')) return `${name_ko} 규칙에 따라 말이나 유닛을 이동해요.`;
  if (name.includes('Drafting')) return `${name_ko} 방식으로 선택지를 고르고 가져가요.`;
  if (name.includes('Game')) return `${name_ko} 방식이 게임의 진행 구조를 정해요.`;
  return `${name_ko} 규칙을 활용해 선택과 결과를 만들어 가요.`;
}
function text(value) { return typeof value === 'string' && value.trim() !== ''; }
function hash(value) { return createHash('sha256').update(value).digest('hex'); }
