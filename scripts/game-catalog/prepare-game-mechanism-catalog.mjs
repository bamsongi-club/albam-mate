#!/usr/bin/env node
import { createHash } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { extractMechanismCatalog, renderMechanismUpsertSql } from './mechanism-catalog.mjs';
import { parseBggMetadataXml } from './game-metadata-catalog.mjs';

const PRODUCTION_TARGET_SIZE = 170000;
const MAX_BATCH_SIZE = 20;

export function validateMechanismDictionary(dictionary) {
  if (!Array.isArray(dictionary?.entries)) throw new Error('mechanism dictionary entries are required');
  const dictionaryById = new Map();
  for (const entry of dictionary.entries) {
    const bggId = entry?.bgg_id;
    const description = entry?.description_ko;
    if (!/^[1-9]\d*$/.test(bggId ?? '')
      || !text(entry?.name)
      || !text(entry?.name_ko)
      || !text(description)
      || [...description].length > 300
      || dictionaryById.has(bggId)) {
      throw new Error(`invalid mechanism dictionary: ${bggId}`);
    }
    dictionaryById.set(bggId, entry);
  }
  return dictionaryById;
}

export function validateSnapshotBatch({ batch, body, targetIds }) {
  if (batch?.httpStatus !== 200) throw new Error(`invalid XML batch status: ${batch?.file}`);
  const requestIds = positiveUniqueIds(batch?.requestIds, 'requestIds');
  const responseIds = positiveUniqueIds(batch?.responseIds, 'responseIds');
  if (!sameIdSet(requestIds, responseIds)) throw new Error(`XML batch request/response IDs do not match: ${batch?.file}`);
  if (requestIds.some((id) => !targetIds.has(id))) throw new Error(`unexpected XML batch request ID: ${batch?.file}`);
  if (!Number.isSafeInteger(batch?.bytes) || batch.bytes < 0 || Buffer.byteLength(body) !== batch.bytes) {
    throw new Error(`invalid XML batch bytes: ${batch?.file}`);
  }
  if (!isoInstant(batch?.acquiredAt)) throw new Error(`invalid XML batch acquiredAt: ${batch?.file}`);
  if (hash(body) !== batch?.sha256) throw new Error(`invalid XML batch checksum: ${batch?.file}`);

  const games = parseBggMetadataXml(body);
  const parsedIds = games.map(({ bggId }) => bggId);
  if (!sameIdSet(parsedIds, responseIds)) throw new Error(`XML batch parsed IDs do not match response IDs: ${batch?.file}`);
  return { games, requestIds };
}

function main() {
  const [flag, manifestPath, outFlag, out] = process.argv.slice(2);
  if (flag !== '--input-manifest' || outFlag !== '--out' || !manifestPath || !out) {
    throw new Error('usage: --input-manifest <path> --out <path>');
  }

  const manifestContents = readFileSync(manifestPath);
  const manifest = JSON.parse(manifestContents.toString('utf8'));
  if (manifest?.approved !== true || manifest?.testOnly !== false || !manifest?.mechanismCatalog) {
    throw new Error('approved production mechanism manifest required');
  }
  const dictionaryMetadata = manifest.mechanismDictionary;
  const dictionaryContents = readFileSync(dictionaryMetadata.path);
  if (hash(dictionaryContents) !== dictionaryMetadata.sha256) throw new Error('mechanism dictionary checksum mismatch');
  const dictionaryById = validateMechanismDictionary(JSON.parse(dictionaryContents.toString('utf8')));

  const gamesContents = readFileSync(manifest.games.path);
  if (hash(gamesContents) !== manifest.games.sha256) throw new Error('games checksum mismatch');
  const targetIds = targetIdsFromGames(JSON.parse(gamesContents.toString('utf8')));
  if (targetIds.size !== PRODUCTION_TARGET_SIZE) throw new Error('production target must contain 170000 unique game IDs');
  if (dictionaryById.size !== manifest.mechanismCatalog.publishedCount) throw new Error('mechanism dictionary count mismatch');

  const snapshotMetadata = manifest.xmlSnapshot;
  const snapshotContents = readFileSync(snapshotMetadata.manifestPath);
  if (hash(snapshotContents) !== snapshotMetadata.manifestSha256) throw new Error('XML snapshot manifest checksum mismatch');
  const snapshot = JSON.parse(snapshotContents.toString('utf8'));
  if (!Array.isArray(snapshot.files)) throw new Error('XML snapshot files are required');

  const games = [];
  const seenGameIds = new Set();
  const seenRequestedIds = new Set();
  for (const batch of snapshot.files) {
    const file = join(snapshotMetadata.rawDirectory, batch.file);
    const body = readFileSync(file, 'utf8');
    const { games: parsedGames, requestIds } = validateSnapshotBatch({ batch, body, targetIds });
    for (const id of requestIds) {
      if (seenRequestedIds.has(id)) throw new Error(`duplicate XML batch request ID: ${id}`);
      seenRequestedIds.add(id);
    }
    for (const game of parsedGames) {
      if (seenGameIds.has(game.bggId)) throw new Error(`duplicate snapshot game: ${game.bggId}`);
      seenGameIds.add(game.bggId);
      if (!targetIds.has(game.bggId)) throw new Error(`unexpected snapshot game: ${game.bggId}`);
      games.push({
        bgg_id: game.bggId,
        mechanisms: (game.mechanisms ?? []).map((mechanism) => {
          const translated = dictionaryById.get(mechanism.bgg_id);
          if (!translated || translated.name !== mechanism.name) {
            throw new Error(`unapproved or mismatched mechanism: ${mechanism.bgg_id}`);
          }
          return {
            ...mechanism,
            name_ko: translated.name_ko,
            description_ko: translated.description_ko,
          };
        }),
      });
    }
  }
  if (seenRequestedIds.size !== targetIds.size || seenGameIds.size !== targetIds.size) {
    throw new Error('snapshot request and response IDs do not match 170000 targets');
  }

  const built = extractMechanismCatalog(games, manifest);
  if (built.errors.length) throw new Error(built.errors.map(({ message }) => message).join('; '));

  const artifactText = JSON.stringify(built.catalog);
  const sqlText = renderMechanismUpsertSql(built.catalog, built.relations);
  const reportText = JSON.stringify(buildMechanismQualityReport({
    manifest,
    inputs: {
      manifest: { path: manifestPath, sha256: hash(manifestContents), rows: null },
      games: { path: manifest.games.path, sha256: hash(gamesContents), rows: targetIds.size },
      mechanismDictionary: {
        path: dictionaryMetadata.path,
        sha256: hash(dictionaryContents),
        rows: dictionaryById.size,
      },
      xmlSnapshotManifest: {
        path: snapshotMetadata.manifestPath,
        sha256: hash(snapshotContents),
        rows: seenGameIds.size,
        batches: snapshot.files.length,
      },
    },
    checks: {
      targetGames: targetIds.size,
      snapshotBatches: snapshot.files.length,
      snapshotGames: seenGameIds.size,
    },
    counts: {
      targetGames: targetIds.size,
      mechanisms: built.catalog.length,
      relations: built.relations.length,
      descriptions: built.catalog.length,
    },
    outputs: {
      artifactSha256: hash(artifactText),
      sqlSha256: hash(sqlText),
    },
  }), null, 2);
  publishArtifacts(out, {
    'service-mechanism-catalog.json': artifactText,
    'upsert-game-mechanisms.sql': sqlText,
    'mechanism-quality-report.json': reportText,
  });
}

export function buildMechanismQualityReport({ manifest, inputs, checks, counts, outputs }) {
  const metadata = manifest.mechanismCatalog;
  return {
    schemaVersion: 1,
    batchId: manifest.batchId ?? null,
    toolCommit: manifest.toolCommit ?? null,
    status: 'approved',
    testOnly: manifest.testOnly,
    inputs,
    provenance: {
      mechanismInput: manifest.provenance?.mechanismInput ?? null,
      sourceReference: metadata.sourceReference,
      reviewedBy: metadata.reviewedBy,
      reviewedAt: metadata.reviewedAt,
      approvalScope: metadata.approvalScope ?? null,
      approvalReferences: manifest.review?.approvalReferences ?? [],
    },
    checks,
    ...counts,
    outputs,
  };
}

export function publishArtifacts(out, artifacts, writeArtifact = writeFileSync) {
  const outputParent = dirname(out);
  mkdirSync(outputParent, { recursive: true });
  if (existsSync(out)) {
    if (!statSync(out).isDirectory()) throw new Error(`output must be a directory: ${out}`);
    if (readdirSync(out).length > 0) throw new Error(`output must be empty: ${out}`);
  }

  const staging = mkdtempSync(join(outputParent, `.${basename(out)}-`));
  let published = false;
  try {
    for (const [name, contents] of Object.entries(artifacts)) {
      writeArtifact(join(staging, name), contents);
    }
    publishDirectory(staging, out);
    published = true;
  } finally {
    if (!published && existsSync(staging)) rmSync(staging, { recursive: true, force: true });
  }
}

function publishDirectory(staging, out) {
  if (!existsSync(out)) {
    renameSync(staging, out);
    return;
  }
  const backup = join(dirname(out), `.${basename(out)}-backup-${process.pid}-${Date.now()}`);
  renameSync(out, backup);
  try {
    renameSync(staging, out);
  } catch (error) {
    renameSync(backup, out);
    throw error;
  }
  rmSync(backup, { recursive: true, force: true });
}

function targetIdsFromGames(games) {
  if (!Array.isArray(games)) throw new Error('games input must be an array');
  const targetIds = new Set();
  for (const row of games) {
    const bggId = Number(row?.bgg_id);
    if (!Number.isSafeInteger(bggId) || bggId < 1 || targetIds.has(bggId)) {
      throw new Error(`invalid or duplicate target game ID: ${row?.bgg_id}`);
    }
    targetIds.add(bggId);
  }
  return targetIds;
}

function positiveUniqueIds(value, field) {
  if (!Array.isArray(value) || value.length < 1 || value.length > MAX_BATCH_SIZE
    || value.some((id) => !Number.isSafeInteger(id) || id < 1)
    || new Set(value).size !== value.length) {
    throw new Error(`invalid XML batch ${field}`);
  }
  return value;
}

function sameIdSet(left, right) {
  return left.length === right.length && left.every((id) => right.includes(id));
}

function isoInstant(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(?:Z|[+-](\d{2}):(\d{2}))$/.exec(value ?? '');
  if (!match) return false;
  const [year, month, day, hour, minute, second] = match.slice(1, 7).map(Number);
  const offsetHour = Number(match[7] ?? 0);
  const offsetMinute = Number(match[8] ?? 0);
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, 0);
  return date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day
    && hour < 24
    && minute < 60
    && second < 60
    && offsetHour < 24
    && offsetMinute < 60
    && !Number.isNaN(Date.parse(value));
}

function text(value) {
  return typeof value === 'string' && value.trim() !== '';
}

function hash(value) {
  return createHash('sha256').update(value).digest('hex');
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) main();
