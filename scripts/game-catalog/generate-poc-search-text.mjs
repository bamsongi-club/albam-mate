#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import {
    ARTIFACT_BASENAMES,
    REQUIRED_ARTIFACTS,
    validateCatalogDatasetReleaseManifest,
} from './catalog-dataset-release-manifest.mjs';
import {
    SEARCH_TEXT_FIELD_ORDER,
    validatePocSearchTextManifest,
} from './poc-search-text-manifest.mjs';

const GAMES_MARKER = 'INSERT INTO games (';
const MECHANISM_RELATION_MARKER = 'INSERT INTO game_mechanism_relation_source (bgg_id, bgg_mechanism_id) VALUES';
const THEME_RELATION_MARKER = 'with desired(bgg_id,bgg_theme_id) as (values';
const CATEGORY_RELATION_MARKER = 'with desired(bgg_id,code) as (values';
const TEMPLATE_LINES = [
    ['게임명', ['name', 'alias']], ['영문명', ['englishName']], ['메커니즘', ['mechanism']],
    ['카테고리', ['category']], ['테마', ['theme']], ['설명', ['description', 'detailDescription']],
];

export function sha256(contents) {
    return createHash('sha256').update(contents).digest('hex');
}

export function generatePocSearchText({ manifest, datasetManifest, actualManifestSha256, corpus, actualCorpusSha256, gamesSql, metadataSql }) {
    validatePocSearchTextManifest(manifest, { datasetManifest, actualManifestSha256, actualCorpusSha256 });
    const members = validateCorpus(corpus);
    const games = parseGames(gamesSql, new Set(members.map(({ gameId }) => gameId)));
    const metadata = parseMetadata(metadataSql);
    const approved = new Set(manifest.approvedFields);
    return members.map(({ gameId }) => {
        const game = games.get(gameId);
        if (!game) throw new Error(`corpus gameId is missing from the games artifact: ${gameId}`);
        return {
            gameId,
            searchText: assembleSearchText({
                ...game,
                mechanism: namesFor(metadata.mechanismRelations.get(gameId), metadata.mechanisms, (a, b) => a - b),
                theme: namesFor(metadata.themeRelations.get(gameId), metadata.themes, (a, b) => a - b),
                category: namesFor(metadata.categoryRelations.get(gameId), metadata.categories, (a, b) => a.localeCompare(b, 'en')),
            }, approved),
        };
    });
}

export function renderSearchTextArtifact({ manifest, games }) {
    const canonicalGames = `${JSON.stringify(games)}\n`;
    return `${JSON.stringify({
        schemaVersion: 1,
        kind: 'poc-search-text',
        datasetRelease: {
            releaseId: manifest.datasetRelease.releaseId,
            datasetId: manifest.datasetRelease.datasetId,
            manifestSha256: manifest.datasetRelease.manifestSha256,
        },
        corpus: manifest.corpus,
        approvedFields: SEARCH_TEXT_FIELD_ORDER.filter((field) => manifest.approvedFields.includes(field)),
        gameCount: games.length,
        searchTextSha256: sha256(Buffer.from(canonicalGames, 'utf8')),
        games,
    }, null, 2)}\n`;
}

function assembleSearchText(values, approved) {
    return TEMPLATE_LINES.flatMap(([label, fields]) => {
        const content = uniqueStrings(fields.filter((field) => approved.has(field)).flatMap((field) => Array.isArray(values[field]) ? values[field] : [values[field]]));
        return content.length === 0 ? [] : [`${label}: ${content.join(', ')}`];
    }).join('\n');
}

function validateCorpus(corpus) {
    if (corpus === null || typeof corpus !== 'object' || Array.isArray(corpus)) throw new Error('corpus must be an object');
    if (corpus.schemaVersion !== 1 || corpus.corpusId !== 'boardlife-quality-top1000' || corpus.corpusVersion !== 'boardlife-quality-top1000-v2' || corpus.status !== 'approved' || corpus.releaseStatus !== 'approved' || corpus.rankCutoff !== 1000 || corpus.selection?.targetSize !== 1000 || corpus.selection?.memberCount !== 1000 || !Array.isArray(corpus.members) || corpus.members.length !== 1000) {
        throw new Error('corpus must be the approved boardlife quality top 1000');
    }
    const members = corpus.members.map((member) => ({ gameId: member?.gameId, boardlifeRank: member?.boardlifeRank }));
    if (members.some(({ gameId, boardlifeRank }) => !isPositiveInteger(gameId) || !isPositiveInteger(boardlifeRank)) || new Set(members.map(({ gameId }) => gameId)).size !== 1000 || new Set(members.map(({ boardlifeRank }) => boardlifeRank)).size !== 1000) {
        throw new Error('corpus members must contain 1,000 unique positive gameId and rank values');
    }
    return members.sort((left, right) => left.boardlifeRank - right.boardlifeRank || left.gameId - right.gameId);
}

function parseGames(sql, wanted) {
    sql = Buffer.isBuffer(sql) ? sql.toString('utf8') : String(sql);
    const games = new Map();
    let searchFrom = 0;
    let statementCount = 0;
    for (;;) {
        const found = sql.indexOf(GAMES_MARKER, searchFrom);
        if (found < 0) break;
        statementCount += 1;
        const columns = readTuple(sql, found + GAMES_MARKER.length - 1);
        const index = toIndex(columns.values);
        if (!('bgg_id' in index) || !('name' in index)) throw new Error('games artifact is missing bgg_id or name');
        const rows = readValuesTuples(sql, columns.nextIndex);
        for (const row of rows.values) {
            const gameId = positiveInteger(row[index.bgg_id], 'games.bgg_id');
            if (!wanted.has(gameId)) continue;
            if (games.has(gameId)) throw new Error(`games artifact contains duplicate bgg_id: ${gameId}`);
            games.set(gameId, {
                name: field(row, index, 'name'), englishName: field(row, index, 'english_name'), alias: field(row, index, 'alias'),
                description: field(row, index, 'description'), detailDescription: field(row, index, 'detail_description'),
            });
        }
        searchFrom = rows.nextIndex;
    }
    if (statementCount === 0) throw new Error('missing games artifact marker');
    return games;
}

function parseMetadata(sql) {
    sql = Buffer.isBuffer(sql) ? sql.toString('utf8') : String(sql);
    return {
        mechanisms: parseDictionary(sql, 'game_mechanisms', 'bgg_mechanism_id', true),
        themes: parseDictionary(sql, 'game_themes', 'bgg_theme_id', true),
        categories: parseDictionary(sql, 'game_categories', 'code', false),
        mechanismRelations: parseRelations(sql, MECHANISM_RELATION_MARKER, true, 'mechanism'),
        themeRelations: parseRelations(sql, THEME_RELATION_MARKER, true, 'theme'),
        categoryRelations: parseRelations(sql, CATEGORY_RELATION_MARKER, false, 'category'),
    };
}

function parseDictionary(sql, table, idField, numeric) {
    const marker = new RegExp(`insert\\s+into\\s+${table}\\s*\\(`, 'giu');
    const entries = new Map();
    for (const match of sql.matchAll(marker)) {
        const columns = readTuple(sql, match.index + match[0].length - 1);
        const index = toIndex(columns.values);
        if (!(idField in index) || !('name_ko' in index)) throw new Error(`${table} dictionary is missing required columns`);
        for (const row of readValuesTuples(sql, columns.nextIndex).values) {
            const id = numeric ? positiveInteger(row[index[idField]], `${table}.${idField}`) : nonEmptyString(row[index[idField]], `${table}.${idField}`);
            entries.set(id, nonEmptyString(row[index.name_ko], `${table}.name_ko`));
        }
    }
    if (entries.size === 0) throw new Error(`missing ${table} dictionary`);
    return entries;
}

function parseRelations(sql, marker, numeric, label) {
    const found = sql.indexOf(marker);
    if (found < 0) throw new Error(`missing ${label} relation marker`);
    const byGame = new Map();
    for (const [gameToken, relatedToken] of readValuesTuples(sql, found + marker.length).values) {
        const gameId = positiveInteger(gameToken, `${label} relation bgg_id`);
        const related = numeric ? positiveInteger(relatedToken, `${label} relation id`) : nonEmptyString(relatedToken, `${label} relation code`);
        if (!byGame.has(gameId)) byGame.set(gameId, []);
        byGame.get(gameId).push(related);
    }
    return byGame;
}

function namesFor(ids, dictionary, compare) {
    return uniqueStrings([...(ids ?? [])].sort(compare).map((id) => dictionary.get(id)));
}

function readTuple(sql, start) {
    if (sql[start] !== '(') throw new Error('expected SQL tuple');
    const values = [];
    let current = '';
    let quoted = false;
    let wasQuoted = false;
    for (let index = start + 1; index < sql.length; index += 1) {
        const character = sql[index];
        if (quoted) {
            if (character === "'" && sql[index + 1] === "'") { current += "'"; index += 1; } else if (character === "'") quoted = false; else current += character;
            continue;
        }
        if (character === "'") { quoted = true; wasQuoted = true; continue; }
        if (character === ',') { values.push(parseToken(current, wasQuoted)); current = ''; wasQuoted = false; continue; }
        if (character === ')') { values.push(parseToken(current, wasQuoted)); return { values, nextIndex: index + 1 }; }
        current += character;
    }
    throw new Error('unterminated SQL tuple');
}

function readValuesTuples(sql, from) {
    let cursor = skipWhitespace(sql, from);
    if (/^values\b/iu.test(sql.slice(cursor))) cursor = skipWhitespace(sql, cursor + 6);
    const values = [];
    while (sql[cursor] === '(') {
        const tuple = readTuple(sql, cursor);
        values.push(tuple.values);
        cursor = skipWhitespace(sql, tuple.nextIndex);
        if (sql[cursor] !== ',') break;
        cursor = skipWhitespace(sql, cursor + 1);
    }
    if (values.length === 0) throw new Error('missing SQL values');
    return { values, nextIndex: cursor };
}

function toIndex(columns) { return Object.fromEntries(columns.map((column, index) => [String(column).trim(), index])); }
function field(row, index, name) { return index[name] === undefined ? null : row[index[name]]; }
function parseToken(value, wasQuoted) { const normalized = value.trim(); return wasQuoted ? value : normalized === '' || normalized === 'NULL' ? null : normalized; }
function skipWhitespace(value, index) { let cursor = index; while (/\s/u.test(value[cursor] ?? '')) cursor += 1; return cursor; }
function positiveInteger(value, fieldName) { const number = Number(value); if (!isPositiveInteger(number)) throw new Error(`${fieldName} must be a positive integer`); return number; }
function nonEmptyString(value, fieldName) { if (typeof value !== 'string' || value.trim() === '') throw new Error(`${fieldName} must be a non-empty string`); return value; }
function isPositiveInteger(value) { return Number.isSafeInteger(value) && value > 0; }
function uniqueStrings(values) {
    const seen = new Set();
    return values.flatMap((value) => {
        if (typeof value !== 'string' || value.trim() === '') return [];
        const normalized = value.trim();
        if (seen.has(normalized)) return [];
        seen.add(normalized);
        return [normalized];
    });
}

function readJson(path, role) {
    let contents;
    try {
        contents = readFileSync(path);
        return { contents, value: JSON.parse(contents.toString('utf8')) };
    } catch (cause) {
        throw new Error(`${role} could not be read as JSON`, { cause });
    }
}

function resolveReference(manifestPath, reference, role) {
    const root = realpathSync(dirname(manifestPath));
    const actual = realpathSync(resolve(root, reference));
    const fromRoot = relative(root, actual);
    if (!fromRoot || fromRoot.startsWith('..') || isAbsolute(fromRoot)) throw new Error(`${role} resolves outside the manifest directory`);
    return actual;
}

function validateArtifactFiles(datasetManifest, artifactsRoot) {
    const root = realpathSync(artifactsRoot);
    const actualArtifacts = {};
    const seen = new Set();
    for (const name of REQUIRED_ARTIFACTS) {
        const artifact = datasetManifest.artifacts?.[name];
        const path = realpathSync(resolve(root, artifact.path));
        const fromRoot = relative(root, path);
        if (!fromRoot || fromRoot.startsWith('..') || isAbsolute(fromRoot) || basename(path) !== ARTIFACT_BASENAMES[name] || seen.has(path) || !statSync(path).isFile()) {
            throw new Error(`artifacts.${name}.path is not a distinct regular file inside artifacts root`);
        }
        seen.add(path);
        const contents = readFileSync(path);
        actualArtifacts[name] = { sha256: sha256(contents), bytes: contents.length, contents, path };
    }
    return actualArtifacts;
}

function parseOptions(args) {
    if (args.length !== 8) usage();
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        if (!['--manifest', '--artifacts-root', '--corpus', '--out'].includes(key) || values[key] || !args[index + 1]) usage();
        values[key] = resolve(args[index + 1]);
    }
    if (Object.keys(values).length !== 4) usage();
    return { manifest: values['--manifest'], artifactsRoot: values['--artifacts-root'], corpus: values['--corpus'], out: values['--out'] };
}

function usage() {
    throw new Error('usage: node generate-poc-search-text.mjs --manifest <poc-manifest.json> --artifacts-root <v4-artifacts-dir> --corpus <quality-corpus.json> --out <search-text.json>');
}

function main() {
    const options = parseOptions(process.argv.slice(2));
    const poc = readJson(options.manifest, 'poc manifest');
    const datasetPath = resolveReference(options.manifest, poc.value.datasetRelease?.manifestPath, 'datasetRelease.manifestPath');
    const corpusPath = resolveReference(options.manifest, poc.value.corpus?.path, 'corpus.path');
    if (realpathSync(options.corpus) !== corpusPath) throw new Error('--corpus must match manifest.corpus.path');
    const dataset = readJson(datasetPath, 'dataset release manifest');
    const corpus = readJson(corpusPath, 'corpus');
    const artifacts = validateArtifactFiles(dataset.value, options.artifactsRoot);
    assertOutputIsSeparate(options.out, [options.manifest, datasetPath, corpusPath, ...Object.values(artifacts).map(({ path }) => path)]);
    validateCatalogDatasetReleaseManifest(dataset.value, { actualArtifacts: artifacts });
    validatePocSearchTextManifest(poc.value, { datasetManifest: dataset.value, actualManifestSha256: sha256(dataset.contents), actualCorpusSha256: sha256(corpus.contents) });
    const games = generatePocSearchText({ manifest: poc.value, datasetManifest: dataset.value, actualManifestSha256: sha256(dataset.contents), corpus: corpus.value, actualCorpusSha256: sha256(corpus.contents), gamesSql: artifacts['01'].contents, metadataSql: artifacts['02'].contents });
    writeFileSync(options.out, renderSearchTextArtifact({ manifest: poc.value, games }), 'utf8');
}

function assertOutputIsSeparate(out, inputs) {
    const actualOut = realPathIfPresent(out);
    if (inputs.some((input) => actualOut === realPathIfPresent(input))) {
        throw new Error('--out must not overwrite an input file');
    }
}

function realPathIfPresent(path) {
    try { return realpathSync(path); } catch { return resolve(path); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
