import { createHash } from 'node:crypto';
import {
    basename,
    isAbsolute,
    relative,
    resolve,
} from 'node:path';
import { realpathSync, statSync } from 'node:fs';

import {
    ARTIFACT_BASENAMES,
    COVERAGE_SERIALIZATIONS,
    REQUIRED_ARTIFACTS,
} from './catalog-dataset-release-manifest.mjs';

const MECHANISM_SOURCE_MARKER =
    'INSERT INTO game_mechanism_relation_source (bgg_id, bgg_mechanism_id) VALUES';
const THEME_RELATION_MARKER =
    'with desired(bgg_id,bgg_theme_id) as (values';
const PLAYER_PREFERENCE_MARKER =
    'with desired(bgg_id,player_count,is_recommended,is_best) as (values';

export function resolveArtifactPaths(artifacts, artifactsRoot) {
    const root = realpathSync(artifactsRoot);
    const paths = {};
    const seen = new Set();

    for (const artifactName of REQUIRED_ARTIFACTS) {
        const candidate = resolve(root, artifacts[artifactName].path);
        const actual = realpathSync(candidate);
        const relativePath = relative(root, actual);
        if (!relativePath || relativePath.startsWith('..') || isAbsolute(relativePath)) {
            throw new Error(`artifacts.${artifactName}.path resolves outside artifacts root`);
        }
        if (basename(actual) !== ARTIFACT_BASENAMES[artifactName]) {
            throw new Error(`artifacts.${artifactName}.path resolves to an unexpected file name`);
        }
        if (seen.has(actual)) {
            throw new Error(`artifacts.${artifactName}.path resolves to a duplicate file`);
        }
        const stat = statSync(actual);
        if (!stat.isFile()) {
            throw new Error(`artifacts.${artifactName}.path must resolve to a regular file`);
        }
        seen.add(actual);
        paths[artifactName] = actual;
    }
    return paths;
}

export function measureCatalogDatasetCoverage({ datasetIds, mechanismSql, metadataSql }) {
    const idSet = new Set(datasetIds);
    if (idSet.size !== datasetIds.length || [...idSet].some((id) => !isPositiveInteger(id))) {
        throw new Error('datasetIds must contain unique positive integers');
    }

    const mechanismRows = uniquePairs(
        parseValuesTuples(toText(mechanismSql), MECHANISM_SOURCE_MARKER, 'mechanism relations')
            .map(([bggId, mechanismId]) => [
                requireDatasetId(bggId, idSet, 'mechanism relation'),
                requirePositiveInteger(mechanismId, 'mechanism relation mechanism id'),
            ]),
    );
    const themeRows = uniquePairs(
        parseValuesTuples(toText(metadataSql), THEME_RELATION_MARKER, 'theme relations')
            .map(([bggId, themeId]) => [
                requireDatasetId(bggId, idSet, 'theme relation'),
                requirePositiveInteger(themeId, 'theme relation theme id'),
            ]),
    );
    const playerRows = aggregatePlayerPreferences(
        parseValuesTuples(toText(metadataSql), PLAYER_PREFERENCE_MARKER, 'player preferences')
            .map(([bggId, playerCount, isRecommended, isBest]) => ({
                bggId: requireDatasetId(bggId, idSet, 'player preference'),
                playerCount: requirePositiveInteger(playerCount, 'player preference player count'),
                isRecommended: requireBoolean(isRecommended, 'player preference is_recommended'),
                isBest: requireBoolean(isBest, 'player preference is_best'),
            })),
    );

    return {
        catalogIds: coverageEntry(
            [...idSet].sort((left, right) => left - right).map((id) => `${id}`),
            COVERAGE_SERIALIZATIONS.catalogIds,
        ),
        mechanismRelations: coverageEntry(
            mechanismRows.map(([bggId, mechanismId]) => `${bggId},${mechanismId}`),
            COVERAGE_SERIALIZATIONS.mechanismRelations,
        ),
        themeRelations: coverageEntry(
            themeRows.map(([bggId, themeId]) => `${bggId},${themeId}`),
            COVERAGE_SERIALIZATIONS.themeRelations,
        ),
        playerPreferences: coverageEntry(
            playerRows.map(({ bggId, playerCount, isRecommended, isBest }) =>
                `${bggId},${playerCount},${isRecommended},${isBest}`),
            COVERAGE_SERIALIZATIONS.playerPreferences,
        ),
    };
}

export function sha256(contents) {
    return createHash('sha256').update(contents).digest('hex');
}

function aggregatePlayerPreferences(rows) {
    const byKey = new Map();
    for (const row of rows) {
        const key = `${row.bggId}\u0000${row.playerCount}`;
        const previous = byKey.get(key);
        byKey.set(key, {
            bggId: row.bggId,
            playerCount: row.playerCount,
            isRecommended: (previous?.isRecommended ?? false) || row.isRecommended || row.isBest,
            isBest: (previous?.isBest ?? false) || row.isBest,
        });
    }
    return [...byKey.values()].sort((left, right) =>
        left.bggId - right.bggId
        || left.playerCount - right.playerCount
        || Number(left.isRecommended) - Number(right.isRecommended)
        || Number(left.isBest) - Number(right.isBest));
}

function uniquePairs(rows) {
    const unique = new Map(rows.map((row) => [`${row[0]}\u0000${row[1]}`, row]));
    return [...unique.values()].sort((left, right) => left[0] - right[0] || left[1] - right[1]);
}

function coverageEntry(lines, serialization) {
    const canonical = `${lines.join('\n')}\n`;
    return {
        rows: lines.length,
        sha256: sha256(Buffer.from(canonical, 'utf8')),
        serialization,
    };
}

function parseValuesTuples(sql, marker, label) {
    const markerIndex = sql.indexOf(marker);
    if (markerIndex < 0) {
        throw new Error(`missing ${label} values marker`);
    }
    let cursor = skipWhitespace(sql, markerIndex + marker.length);
    const rows = [];
    while (sql[cursor] === '(') {
        const tuple = readTuple(sql, cursor, label);
        rows.push(tuple.values);
        cursor = skipWhitespace(sql, tuple.nextIndex);
        if (sql[cursor] !== ',') {
            break;
        }
        cursor = skipWhitespace(sql, cursor + 1);
    }
    if (rows.length === 0) {
        throw new Error(`missing ${label} values`);
    }
    return rows;
}

function readTuple(sql, startIndex, label) {
    const values = [];
    let current = '';
    let quoted = false;
    for (let index = startIndex + 1; index < sql.length; index += 1) {
        const character = sql[index];
        if (quoted) {
            if (character === "'" && sql[index + 1] === "'") {
                current += "'";
                index += 1;
            } else if (character === "'") {
                quoted = false;
            } else {
                current += character;
            }
            continue;
        }
        if (character === "'") {
            quoted = true;
        } else if (character === ',') {
            values.push(parseLiteral(current, label));
            current = '';
        } else if (character === ')') {
            values.push(parseLiteral(current, label));
            return { values, nextIndex: index + 1 };
        } else {
            current += character;
        }
    }
    throw new Error(`unterminated ${label} tuple`);
}

function parseLiteral(value, label) {
    const normalized = value.trim();
    if (/^-?\d+$/u.test(normalized)) {
        return Number(normalized);
    }
    if (normalized === 'true') return true;
    if (normalized === 'false') return false;
    if (normalized === 'NULL') return null;
    throw new Error(`unsupported ${label} literal: ${normalized.slice(0, 40)}`);
}

function skipWhitespace(value, index) {
    let cursor = index;
    while (/\s/u.test(value[cursor] ?? '')) cursor += 1;
    return cursor;
}

function requireDatasetId(value, ids, label) {
    const id = requirePositiveInteger(value, `${label} bgg id`);
    if (!ids.has(id)) {
        throw new Error(`${label} references bgg id outside dataset: ${id}`);
    }
    return id;
}

function requirePositiveInteger(value, label) {
    if (!isPositiveInteger(value)) {
        throw new Error(`${label} must be a positive integer`);
    }
    return value;
}

function requireBoolean(value, label) {
    if (typeof value !== 'boolean') {
        throw new Error(`${label} must be boolean`);
    }
    return value;
}

function isPositiveInteger(value) {
    return Number.isSafeInteger(value) && value > 0;
}

function toText(value) {
    return Buffer.isBuffer(value) ? value.toString('utf8') : String(value);
}
