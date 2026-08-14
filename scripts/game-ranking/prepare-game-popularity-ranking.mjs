#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';

const args = parseArgs(process.argv.slice(2));
if (!args.manifest || !args.out) {
    throw new Error('usage: --manifest <path> --out <path>');
}

const manifestPath = resolve(args.manifest);
const outputDirectory = resolve(args.out);
const sqlFileName = 'upsert-game-popularity.sql';
const reportFileName = 'quality-report.json';
const RANK_CSV_HEADERS = {
    BoardLife: [
        ['bggId', 'rank'],
        ['bgg_id', 'rank'],
        ['id', 'rank'],
    ],
    BGG: [
        [
            'id',
            'name',
            'yearpublished',
            'rank',
            'bayesaverage',
            'average',
            'usersrated',
            'is_expansion',
            'abstracts_rank',
            'cgs_rank',
            'childrensgames_rank',
            'familygames_rank',
            'partygames_rank',
            'strategygames_rank',
            'thematic_rank',
            'wargames_rank',
        ],
    ],
};
let stagingDirectory;

try {
    mkdirSync(dirname(outputDirectory), { recursive: true });
    stagingDirectory = mkdtempSync(join(dirname(outputDirectory), `.${basename(outputDirectory)}-`));
    const manifest = readJson(manifestPath);
    const inputs = validateManifest(manifest);
    const boardlifeRows = readRankRows(inputs.boardlife.path, inputs.boardlife.rows, 'BoardLife');
    const bggRows = readRankRows(inputs.bgg.path, inputs.bgg.rows, 'BGG');
    const scoreInput = readScoreInput(inputs.scoreInput.path, inputs.scoreInput.rows);
    const scores = buildExternalScores(scoreInput, boardlifeRows, bggRows);
    const sqlPath = join(stagingDirectory, sqlFileName);
    const reportPath = join(stagingDirectory, reportFileName);
    writeFileSync(sqlPath, renderSql(scores), 'utf8');
    writeFileSync(
        reportPath,
        JSON.stringify(
            {
                status: 'approved',
                batchId: manifest.batchId,
                inputs: {
                    boardlife: inputReport(inputs.boardlife),
                    bgg: inputReport(inputs.bgg),
                    scoreInput: inputReport(inputs.scoreInput),
                },
                scores: {
                    rows: scores.length,
                    boardlifeRankedRows: scores.filter((row) => row.boardlifeRank !== null).length,
                    bggRankedRows: scores.filter((row) => row.bggRank !== null).length,
                },
                output: {
                    path: join(outputDirectory, sqlFileName),
                    sha256: sha256File(sqlPath),
                },
            },
            null,
            2,
        ),
        'utf8',
    );
    publish(stagingDirectory, outputDirectory);
} catch (error) {
    rmSync(join(outputDirectory, sqlFileName), { force: true });
    mkdirSync(outputDirectory, { recursive: true });
    const reportPath = join(outputDirectory, reportFileName);
    writeFileSync(
        reportPath,
        JSON.stringify({ status: 'blocked', errors: [error instanceof Error ? error.message : String(error)] }, null, 2),
        'utf8',
    );
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
} finally {
    if (stagingDirectory) {
        rmSync(stagingDirectory, { recursive: true, force: true });
    }
}

function parseArgs(values) {
    const parsed = {};
    for (let index = 0; index < values.length; index += 1) {
        const flag = values[index];
        if (flag === '--manifest' || flag === '--out') {
            parsed[flag.slice(2)] = values[index + 1];
            index += 1;
        }
    }
    return parsed;
}

function validateManifest(manifest) {
    if (manifest?.schemaVersion !== 1 || manifest.status !== 'approved' || !nonBlank(manifest.batchId)) {
        throw new Error('approved ranking manifest is required');
    }
    const sources = manifest.sources ?? {};
    const boardlife = validateInput(sources.boardlife, 'boardlife');
    const bgg = validateInput(sources.bgg, 'bgg');
    const scoreInput = validateInput(manifest.scoreInput, 'scoreInput');
    if (scoreInput.grain !== '1 row per bggId') {
        throw new Error('scoreInput grain must be 1 row per bggId');
    }
    if (scoreInput.reviewRequiredRows !== 0) {
        throw new Error('scoreInput contains rows requiring review');
    }
    return { boardlife, bgg, scoreInput };
}

function validateInput(input, name) {
    if (
        !input
        || !nonBlank(input.path)
        || !Number.isInteger(input.rows)
        || input.rows < 0
        || !/^[a-f0-9]{64}$/u.test(input.sha256 ?? '')
        || !existsSync(input.path)
        || sha256File(input.path) !== input.sha256
    ) {
        throw new Error(`invalid ${name} input or checksum`);
    }
    return input;
}

function readRankRows(path, expectedRows, source) {
    const content = readFileSync(path, 'utf8');
    const parsed = path.endsWith('.csv') ? parseCsv(content, source) : readJson(path);
    const rows = Array.isArray(parsed) ? parsed : parsed?.rows;
    if (!Array.isArray(rows) || rows.length !== expectedRows) {
        throw new Error(`${source} row count does not match manifest`);
    }
    return validateRankRows(rows, source);
}

function readScoreInput(path, expectedRows) {
    const parsed = readJson(path);
    const rows = Array.isArray(parsed) ? parsed : parsed?.rows;
    if (!Array.isArray(rows) || rows.length !== expectedRows) {
        throw new Error('scoreInput row count does not match manifest');
    }
    const ids = new Set();
    return rows.map((row, index) => {
        const bggId = positiveInteger(row?.bggId ?? row?.bgg_id);
        if (bggId === null || ids.has(bggId)) {
            throw new Error(`invalid or duplicate scoreInput bggId at row ${index}`);
        }
        ids.add(bggId);
        if (row?.reviewRequired === true) {
            throw new Error(`scoreInput row ${bggId} requires review`);
        }
        return {
            bggId,
            boardlifeRank: optionalRank(row?.boardlifeRank ?? row?.boardlife_rank),
            bggRank: optionalRank(row?.bggRank ?? row?.bgg_rank),
        };
    });
}

function buildExternalScores(scoreInput, boardlifeRows, bggRows) {
    const boardlifeByBggId = minRankByBggId(boardlifeRows);
    const bggByBggId = minRankByBggId(bggRows);
    const rows = scoreInput.map((input) => ({
        bggId: input.bggId,
        boardlifeRank: boardlifeByBggId.get(input.bggId) ?? input.boardlifeRank,
        bggRank: bggByBggId.get(input.bggId) ?? input.bggRank,
    }));
    const boardlifeRanks = rows.map((row) => row.boardlifeRank).filter((rank) => rank !== null);
    const bggRanks = rows.map((row) => row.bggRank).filter((rank) => rank !== null);
    const boardlifeMaxRank = maxRank(boardlifeRanks);
    const bggMaxRank = maxRank(bggRanks);
    return rows.map((row) => ({
        bggId: row.bggId,
        boardlifeRank: row.boardlifeRank,
        bggRank: row.bggRank,
        boardlifeScore: normalizeRank(row.boardlifeRank, boardlifeRanks.length, boardlifeMaxRank),
        bggScore: normalizeRank(row.bggRank, bggRanks.length, bggMaxRank),
    }));
}

function minRankByBggId(rows) {
    const result = new Map();
    for (const row of rows) {
        const bggId = rankBggId(row);
        const rank = optionalRank(row?.rank);
        if (rank === null) {
            continue;
        }
        const current = result.get(bggId);
        if (current === undefined || rank < current) {
            result.set(bggId, rank);
        }
    }
    return result;
}

function normalizeRank(rank, rankCount, maxRank) {
    if (rank === null || rankCount === 0) {
        return 0;
    }
    if (rankCount === 1) {
        return 1;
    }
    if (maxRank === 1) {
        return rank === 1 ? 1 : 0;
    }
    return clamp((maxRank - rank) / (maxRank - 1));
}

function publish(stagingDirectory, outputDirectory) {
    mkdirSync(outputDirectory, { recursive: true });
    renameSync(join(stagingDirectory, sqlFileName), join(outputDirectory, sqlFileName));
    renameSync(join(stagingDirectory, reportFileName), join(outputDirectory, reportFileName));
}

function maxRank(ranks) {
    let maximum = 0;
    for (const rank of ranks) {
        if (rank > maximum) {
            maximum = rank;
        }
    }
    return maximum;
}

function renderSql(scores) {
    const externalValues = scores.length === 0
        ? 'select null::bigint as bgg_id, null::numeric as boardlife_score, null::numeric as bgg_score where false'
        : `values ${scores.map((row) => `(${row.bggId}, ${decimal(row.boardlifeScore)}, ${decimal(row.bggScore)})`).join(',\n')}`;
    return `BEGIN;
WITH external_scores(bgg_id, boardlife_score, bgg_score) AS (
    ${externalValues}
), albam_counts AS (
    SELECT game.id AS game_id, COUNT(room.id) AS room_count
    FROM games game
    LEFT JOIN rooms room
        ON room.game_id = game.id
       AND room.room_type = 'GAME_FOCUSED'
       AND room.status <> 'CANCELED'
    GROUP BY game.id
), albam_ranked AS (
    SELECT game_id,
           ROW_NUMBER() OVER (ORDER BY room_count DESC, game_id ASC) AS albam_rank
    FROM albam_counts
    WHERE room_count > 0
), albam_max AS (
    SELECT COALESCE(MAX(albam_rank), 0) AS max_rank
    FROM albam_ranked
), derived_scores AS (
    SELECT game.id AS game_id,
           COALESCE(external_scores.boardlife_score, 0) AS boardlife_score,
           CASE
               WHEN albam_ranked.game_id IS NULL THEN 0
               WHEN albam_max.max_rank = 1 THEN 1
               ELSE (albam_max.max_rank - albam_ranked.albam_rank)::numeric
                    / (albam_max.max_rank - 1)
           END AS albam_score,
           COALESCE(external_scores.bgg_score, 0) AS bgg_score
    FROM games game
    LEFT JOIN external_scores ON external_scores.bgg_id = game.bgg_id
    LEFT JOIN albam_ranked ON albam_ranked.game_id = game.id
    CROSS JOIN albam_max
)
UPDATE games game
SET popularity_score = ROUND(
    derived_scores.boardlife_score * 0.6
    + derived_scores.albam_score * 0.3
    + derived_scores.bgg_score * 0.1,
    6
)
FROM derived_scores
WHERE game.id = derived_scores.game_id;
COMMIT;
`;
}

function parseCsv(content, source) {
    const lines = content.split(/\r?\n/u).filter((line) => line.length > 0);
    if (lines.length === 0) {
        return [];
    }
    const header = parseCsvLine(lines[0]);
    const allowedHeaders = RANK_CSV_HEADERS[source];
    if (!allowedHeaders?.some((allowed) => sameColumns(header, allowed))) {
        throw new Error(`invalid ${source} CSV header`);
    }
    return lines.slice(1).map((line, index) => {
        const values = parseCsvLine(line);
        if (values.length !== header.length) {
            throw new Error(`invalid ${source} CSV column count at row ${index + 1}`);
        }
        return Object.fromEntries(header.map((name, index) => [name, values[index] ?? '']));
    });
}

function parseCsvLine(line) {
    const result = [];
    let field = '';
    let quoted = false;
    for (let index = 0; index < line.length; index += 1) {
        const character = line[index];
        if (character === '"') {
            if (quoted && line[index + 1] === '"') {
                field += '"';
                index += 1;
            } else {
                quoted = !quoted;
            }
        } else if (character === ',' && !quoted) {
            result.push(field);
            field = '';
        } else {
            field += character;
        }
    }
    result.push(field);
    return result;
}

function readJson(path) {
    return JSON.parse(readFileSync(path, 'utf8'));
}

function validateRankRows(rows, source) {
    return rows.map((row, index) => {
        if (rankBggId(row) === null) {
            throw new Error(`invalid ${source} bggId at row ${index}`);
        }
        if (!interpretableRank(row?.rank)) {
            throw new Error(`invalid ${source} rank at row ${index}`);
        }
        return row;
    });
}

function rankBggId(row) {
    return positiveInteger(row?.bggId ?? row?.bgg_id ?? row?.id);
}

function interpretableRank(value) {
    if (value === null || value === undefined || value === '') {
        return true;
    }
    if (typeof value !== 'number' && typeof value !== 'string') {
        return false;
    }
    return Number.isSafeInteger(Number(value));
}

function sameColumns(left, right) {
    return left.length === right.length && left.every((column, index) => column === right[index]);
}

function inputReport(input) {
    return { path: input.path, rows: input.rows, sha256: input.sha256 };
}

function sha256File(path) {
    return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function positiveInteger(value) {
    const number = Number(value);
    return Number.isSafeInteger(number) && number > 0 ? number : null;
}

function optionalRank(value) {
    if (value === null || value === undefined || value === '' || value === '0') {
        return null;
    }
    const number = Number(value);
    return Number.isSafeInteger(number) && number > 0 ? number : null;
}

function decimal(value) {
    return value.toFixed(6);
}

function clamp(value) {
    return Math.max(0, Math.min(1, value));
}

function nonBlank(value) {
    return typeof value === 'string' && value.trim().length > 0;
}
