#!/usr/bin/env node

import { createHash } from "node:crypto";
import {
    mkdirSync,
    readFileSync,
    realpathSync,
    rmSync,
    statSync,
    writeFileSync,
} from "node:fs";
import { basename, resolve } from "node:path";

const CATALOG_FIELDS = [
    "bgg_id",
    "name",
    "english_name",
    "alias",
    "image_url",
    "recommended_player_count",
    "tag",
    "estimated_play_time",
    "complexity",
    "description",
    "detail_description",
];
const TEXT_FIELDS = [
    "name",
    "english_name",
    "alias",
    "image_url",
    "recommended_player_count",
    "tag",
    "estimated_play_time",
    "description",
    "detail_description",
];
const REQUIRED_FIELDS = [
    "bgg_id",
    "name",
    "english_name",
    "recommended_player_count",
    "tag",
    "estimated_play_time",
    "description",
    "detail_description",
];
const FIELD_LENGTHS = {
    name: 255,
    english_name: 255,
    alias: 255,
    image_url: 500,
    recommended_player_count: 50,
    tag: 30,
    estimated_play_time: 50,
};
const OPTIONAL_TEXT_FIELDS = new Set(["alias", "image_url"]);
const UTF8_DECODER = new TextDecoder("utf-8", { fatal: true });

class InputError extends Error {
    constructor(code, message, cause, details = {}) {
        super(message, { cause });
        this.code = code;
        Object.assign(this, details);
    }
}

const options = parseOptions(process.argv.slice(2));
mkdirSync(options.out, { recursive: true });
ensureSeparatePaths(options);
try {
    clearLoadArtifacts(options);
    prepareCatalog(options);
} catch (error) {
    if (!(error instanceof InputError)) {
        throw error;
    }
    writeFailureReport(options, error);
    process.exitCode = 1;
}

function ensureSeparatePaths({ games, ranks, manifest, out }) {
    const outputDirectory = realpathSync(out);
    const outputs = [
        "quality-report.json",
        "service-catalog.json",
        "upsert-games.sql",
    ].map((fileName) => resolve(outputDirectory, fileName));
    const inputPaths = [games, ranks, manifest].filter(Boolean);
    for (const input of inputPaths) {
        const inputRealPath = realPathIfPresent(input);
        const conflict = outputs.find(
            (output) =>
                inputRealPath === realPathIfPresent(output) || sameFileIdentity(input, output),
        );
        if (conflict) {
            process.stderr.write(`입력 파일과 출력 파일 경로가 같습니다: ${input}\n`);
            process.exit(2);
        }
    }
}

function sameFileIdentity(leftPath, rightPath) {
    const left = fileIdentity(leftPath);
    const right = fileIdentity(rightPath);
    return left !== null && right !== null && left.dev === right.dev && left.ino === right.ino;
}

function fileIdentity(path) {
    try {
        const { dev, ino } = statSync(path);
        return { dev, ino };
    } catch {
        return null;
    }
}

function decodeUtf8(contents, role) {
    try {
        return UTF8_DECODER.decode(contents).replace(/^\uFEFF/, "");
    } catch (cause) {
        throw new InputError(
            "INVALID_UTF8",
            `${role} 입력 파일이 올바른 UTF-8이 아닙니다.`,
            cause,
            { input: role },
        );
    }
}

function realPathIfPresent(path) {
    try {
        return realpathSync(path);
    } catch {
        return path;
    }
}

function clearLoadArtifacts({ out }) {
    for (const fileName of ["service-catalog.json", "upsert-games.sql"]) {
        const artifact = resolve(out, fileName);
        rmSync(artifact, { force: true });
    }
}

function readInput(path, role) {
    try {
        return readFileSync(path);
    } catch (cause) {
        throw new InputError("INPUT_READ_FAILED", `${role} 입력 파일을 읽을 수 없습니다.`, cause);
    }
}

function parseJson(contents, code, message, role) {
    try {
        return JSON.parse(decodeUtf8(contents, role));
    } catch (cause) {
        if (cause instanceof InputError) {
            throw cause;
        }
        throw new InputError(code, message, cause);
    }
}

function parseRanks(contents) {
    try {
        return rowsFromCsv(decodeUtf8(contents, "ranks"));
    } catch (cause) {
        if (cause instanceof InputError) {
            throw cause;
        }
        throw new InputError("INVALID_RANKS_CSV", "BGG 기준 CSV를 해석할 수 없습니다.", cause);
    }
}

function writeFailureReport({ games, ranks, manifest, out }, error) {
    writeJson(resolve(out, "quality-report.json"), {
        schemaVersion: 1,
        batchId: null,
        toolCommit: null,
        status: "blocked",
        inputs: {
            games: inputMetadata(games),
            ranks: inputMetadata(ranks),
            ...(manifest ? { manifest: inputMetadata(manifest) } : {}),
        },
        errors: [
            {
                code: error.code,
                message: error.message,
                ...(error.input ? { input: error.input } : {}),
            },
        ],
        warnings: [],
        checks: null,
        outputs: null,
    });
}

function inputMetadata(path) {
    try {
        const contents = readFileSync(path);
        return { fileName: basename(path), sha256: sha256(contents), rows: null };
    } catch {
        return { fileName: basename(path), sha256: null, rows: null };
    }
}

function prepareCatalog({ games: gamesPath, ranks: ranksPath, manifest: manifestPath, out }) {
    const gamesContents = readInput(gamesPath, "games");
    const ranksContents = readInput(ranksPath, "ranks");
    const games = parseJson(
        gamesContents,
        "INVALID_GAMES_JSON",
        "games JSON을 해석할 수 없습니다.",
        "games",
    );
    const rankRows = parseRanks(ranksContents);
    const manifest = manifestPath
        ? parseJson(
              readInput(manifestPath, "manifest"),
              "INVALID_MANIFEST_JSON",
              "manifest JSON을 해석할 수 없습니다.",
              "manifest",
          )
        : null;
    const gameRows = Array.isArray(games) ? games : [];
    const validGameRows = gameRows.filter(isRecord);
    const errors = validateManifest(manifest, gamesPath, gamesContents, ranksPath, ranksContents);
    errors.push(...validateData(games, rankRows));
    const duplicatedBggIds = duplicateValues(
        validGameRows.map((game) => canonicalBggId(game.bgg_id)).filter(Boolean),
    );
    if (duplicatedBggIds.length > 0) {
        errors.push({
            code: "DUPLICATE_BGG_ID",
            message: "서비스 입력 안에 같은 bgg_id가 둘 이상 있습니다.",
            count: duplicatedBggIds.length,
            sample: duplicatedBggIds.slice(0, 10),
        });
    }
    const rankByBggId = new Map(
        rankRows.map((row) => [canonicalBggId(row.id), row]).filter(([bggId]) => bggId),
    );
    const catalog = validGameRows
        .map((game) => normalizeGame(game))
        .sort((left, right) => left.bgg_id - right.bgg_id);
    errors.push(...validateSelectionCounts(manifest, gameRows.length, catalog.length));
    const warnings = qualityWarnings(validGameRows, rankByBggId);
    const checks = checkSummary(validGameRows, rankByBggId);
    const acceptedWarnings = new Set(
        Array.isArray(manifest?.review?.acceptedWarnings)
            ? manifest.review.acceptedWarnings
            : [],
    );
    const unacknowledgedWarnings = warnings.filter(({ code }) => !acceptedWarnings.has(code));
    if (unacknowledgedWarnings.length > 0) {
        errors.push({
            code: "UNACKNOWLEDGED_WARNINGS",
            message: "검수자가 승인하지 않은 품질 경고가 있습니다.",
            warnings: unacknowledgedWarnings.map(({ code }) => code),
        });
    }

    if (errors.length > 0) {
        writeJson(resolve(out, "quality-report.json"), reportFor({
            manifest: manifest ?? {},
            gamesPath,
            gamesContents,
            ranksPath,
            ranksContents,
            gamesCount: Array.isArray(games) ? games.length : null,
            ranksCount: rankRows.length,
            errors,
            warnings,
            checks,
        }));
        process.exitCode = 1;
        return;
    }

    const catalogText = `${JSON.stringify(catalog, null, 2)}\n`;
    const sqlText = upsertSql(catalog);
    writeFileSync(resolve(out, "service-catalog.json"), catalogText, "utf8");
    writeFileSync(resolve(out, "upsert-games.sql"), sqlText, "utf8");
    writeJson(resolve(out, "quality-report.json"), reportFor({
        manifest: manifest ?? {},
        gamesPath,
        gamesContents,
        ranksPath,
        ranksContents,
        gamesCount: games.length,
        ranksCount: rankRows.length,
        errors: [],
        warnings,
        checks,
        outputs: {
            catalogRows: catalog.length,
            catalogSha256: sha256(catalogText),
            sqlSha256: sha256(sqlText),
        },
    }));
}

function checkSummary(games, rankByBggId) {
    let matchedRows = 0;
    let missingFromBaselineRows = 0;
    let missingRequiredRows = 0;
    let baselineNameMismatchRows = 0;
    let baselineRankMismatchRows = 0;
    let expansionRows = 0;
    let fieldLengthViolationRows = 0;
    let invalidComplexityRows = 0;
    let invalidImageUrlRows = 0;

    for (const game of games) {
        const rank = rankByBggId.get(canonicalBggId(game.bgg_id));
        if (rank) {
            matchedRows += 1;
            baselineNameMismatchRows += Number(
                normalizeName(game.english_name) !== normalizeName(rank.name),
            );
            baselineRankMismatchRows += Number(
                game.id != null && Number(game.id) !== Number(rank.rank),
            );
            expansionRows += Number(rank.is_expansion === "1");
        } else {
            missingFromBaselineRows += 1;
        }
        missingRequiredRows += Number(REQUIRED_FIELDS.some((field) => blank(game[field])));
        fieldLengthViolationRows += Number(
            Object.entries(FIELD_LENGTHS).some(
                ([field, limit]) => !blank(game[field]) && String(game[field]).length > limit,
            ),
        );
        const complexity = Number(game.complexity);
        const complexityDecimals = String(game.complexity).split(".")[1]?.length ?? 0;
        invalidComplexityRows += Number(
            !blank(game.complexity) &&
                (!Number.isFinite(complexity) ||
                    complexity < 0 ||
                    complexity > 5 ||
                    complexityDecimals > 2),
        );
        if (!blank(game.image_url)) {
            try {
                invalidImageUrlRows += Number(
                    new URL(String(game.image_url)).protocol !== "https:",
                );
            } catch {
                invalidImageUrlRows += 1;
            }
        }
    }

    return {
        matchedRows,
        missingFromBaselineRows,
        duplicateBggIdGroups: duplicateValues(
            games.map(({ bgg_id }) => canonicalBggId(bgg_id)).filter(Boolean),
        ).length,
        missingRequiredRows,
        baselineNameMismatchRows,
        baselineRankMismatchRows,
        expansionRows,
        fieldLengthViolationRows,
        invalidComplexityRows,
        invalidImageUrlRows,
        possibleVersionCollisionGroups: possibleVersionCollisions(games).length,
    };
}

function qualityWarnings(games, rankByBggId) {
    const warnings = [];
    const versionCollisions = possibleVersionCollisions(games);
    if (versionCollisions.length > 0) {
        warnings.push({
            code: "POSSIBLE_VERSION_COLLISION",
            message: "서로 다른 bgg_id가 같은 표시 이름을 사용해 판본·변형 검수가 필요합니다.",
            groupCount: versionCollisions.length,
            sample: versionCollisions.slice(0, 10),
        });
    }
    const correlationWarning = suspiciousComplexityRankCorrelation(games, rankByBggId);
    if (correlationWarning) {
        warnings.push(correlationWarning);
    }
    if (games.length < 20) {
        return warnings;
    }

    addLowDiversityWarning(
        warnings,
        "LOW_RECOMMENDED_PLAYER_COUNT_DIVERSITY",
        "권장 인원",
        games.map((game) => game.recommended_player_count),
        0.95,
    );
    addLowDiversityWarning(
        warnings,
        "LOW_ESTIMATED_PLAY_TIME_DIVERSITY",
        "예상 플레이 시간",
        games.map((game) => game.estimated_play_time),
        0.95,
    );
    addLowDiversityWarning(
        warnings,
        "LOW_DESCRIPTION_DIVERSITY",
        "간단 설명 문구",
        games.map((game) => descriptionTemplate(game, rankByBggId, "description")),
        0.5,
    );
    addLowDiversityWarning(
        warnings,
        "LOW_DETAIL_DESCRIPTION_DIVERSITY",
        "상세 설명 문구",
        games.map((game) => descriptionTemplate(game, rankByBggId, "detail_description")),
        0.5,
    );
    return warnings;
}

function suspiciousComplexityRankCorrelation(games, rankByBggId) {
    const pairs = games
        .map((game) => {
            const rank = rankByBggId.get(canonicalBggId(game.bgg_id));
            const complexity = Number(game.complexity);
            const rankValue = Number(rank?.rank);
            if (
                !rank ||
                !isValidComplexity(game.complexity) ||
                !Number.isSafeInteger(rankValue) ||
                rankValue <= 0
            ) {
                return null;
            }
            return { complexity, rank: rankValue };
        })
        .filter(Boolean);
    if (pairs.length < 20) {
        return null;
    }

    const complexityMean = mean(pairs.map(({ complexity }) => complexity));
    const rankMean = mean(pairs.map(({ rank }) => rank));
    let covariance = 0;
    let complexityVariance = 0;
    let rankVariance = 0;
    for (const pair of pairs) {
        const complexityDelta = pair.complexity - complexityMean;
        const rankDelta = pair.rank - rankMean;
        covariance += complexityDelta * rankDelta;
        complexityVariance += complexityDelta ** 2;
        rankVariance += rankDelta ** 2;
    }
    if (complexityVariance === 0 || rankVariance === 0) {
        return null;
    }

    const correlation = covariance / Math.sqrt(complexityVariance * rankVariance);
    if (Math.abs(correlation) < 0.9) {
        return null;
    }
    return {
        code: "SUSPICIOUS_COMPLEXITY_RANK_CORRELATION",
        message: "complexity와 BGG rank가 비정상적으로 결합된 패턴입니다.",
        sampleSize: pairs.length,
        correlation: Number(correlation.toFixed(6)),
    };
}

function isValidComplexity(value) {
    return value !== null && value !== undefined && isValidComplexityValue(value);
}

function isValidComplexityValue(value) {
    return (
        typeof value === "number" &&
        Number.isFinite(value) &&
        value >= 0 &&
        value <= 5 &&
        /^\d+(?:\.\d{1,2})?$/.test(String(value))
    );
}

function mean(values) {
    return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function possibleVersionCollisions(games) {
    const groups = new Map();
    for (const field of ["name", "english_name"]) {
        for (const game of games) {
            const normalized = normalizeName(game[field]);
            if (!normalized) {
                continue;
            }
            const key = `${field}:${normalized}`;
            const values = groups.get(key) ?? [];
            values.push({ bgg_id: game.bgg_id, name: game.name, english_name: game.english_name });
            groups.set(key, values);
        }
    }
    return [...groups.entries()]
        .filter(([, values]) => new Set(values.map(({ bgg_id }) => String(bgg_id))).size > 1)
        .map(([key, values]) => ({ field: key.split(":", 1)[0], games: values }));
}

function addLowDiversityWarning(warnings, code, label, values, threshold) {
    const counts = new Map();
    for (const value of values) {
        counts.set(value, (counts.get(value) ?? 0) + 1);
    }
    const [topValue, topCount] = [...counts.entries()].sort(
        (left, right) => right[1] - left[1],
    )[0];
    const share = topCount / values.length;
    if (share >= threshold) {
        warnings.push({
            code,
            message: `${label} 값이 소수 값에 과도하게 집중돼 실제 게임별 검수가 필요합니다.`,
            topCount,
            totalCount: values.length,
            topShare: Number(share.toFixed(6)),
            sample: String(topValue).slice(0, 300),
        });
    }
}

function descriptionTemplate(game, rankByBggId, field) {
    const rank = rankByBggId.get(canonicalBggId(game.bgg_id));
    const values = [game.name, game.english_name, rank?.yearpublished]
        .filter(Boolean)
        .map(String)
        .sort((left, right) => right.length - left.length);
    let template = String(game[field] ?? "");
    for (const value of values) {
        template = template.replaceAll(value, "{VALUE}");
    }
    return template.replaceAll(/\d+/g, "{N}");
}

function normalizeGame(game) {
    const bggId = Number(game.bgg_id);
    return Object.fromEntries(
        CATALOG_FIELDS.map((field) => [
            field,
            field === "bgg_id" ? bggId : (game[field] ?? null),
        ]),
    );
}

function validateData(games, rankRows) {
    const errors = [];
    if (!Array.isArray(games)) {
        return [{ code: "INVALID_GAMES_JSON", message: "games 입력은 JSON 배열이어야 합니다." }];
    }
    if (games.length === 0) {
        errors.push({ code: "EMPTY_GAMES_INPUT", message: "games 입력에 적재할 행이 없습니다." });
    }
    const rankHeaders = new Set(Object.keys(rankRows[0] ?? {}));
    const requiredRankHeaders = ["id", "name", "yearpublished", "rank", "is_expansion"];
    const missingRankHeaders = requiredRankHeaders.filter((header) => !rankHeaders.has(header));
    if (missingRankHeaders.length > 0) {
        errors.push({
            code: "MISSING_RANK_HEADERS",
            message: "BGG 기준 CSV 필수 열이 없습니다.",
            fields: missingRankHeaders,
        });
        return errors;
    }

    const rankDuplicates = duplicateValues(rankRows.map((row) => canonicalBggId(row.id)).filter(Boolean));
    if (rankDuplicates.length > 0) {
        errors.push({
            code: "DUPLICATE_BASELINE_BGG_ID",
            message: "BGG 기준 CSV에 같은 id가 둘 이상 있습니다.",
            count: rankDuplicates.length,
            sample: rankDuplicates.slice(0, 10),
        });
    }
    const rankByBggId = new Map(
        rankRows.map((row) => [canonicalBggId(row.id), row]).filter(([bggId]) => bggId),
    );

    const missingRequired = [];
    const invalidBggIds = [];
    const missingFromBaseline = [];
    const nameMismatches = [];
    const rankMismatches = [];
    const lengthViolations = [];
    const invalidComplexities = [];
    const invalidImageUrls = [];
    const invalidGameRows = [];
    const nulCharacterValues = [];
    const invalidFieldTypes = [];

    for (const [index, game] of games.entries()) {
        const rowNumber = index + 1;
        if (!isRecord(game)) {
            invalidGameRows.push({ row: rowNumber, type: valueType(game) });
            continue;
        }
        const missingFields = REQUIRED_FIELDS.filter((field) => blank(game?.[field]));
        if (missingFields.length > 0) {
            missingRequired.push({ row: rowNumber, fields: missingFields });
        }
        for (const field of TEXT_FIELDS) {
            if (containsNul(game[field])) {
                nulCharacterValues.push({ row: rowNumber, field });
            }
            if (
                game[field] !== undefined &&
                !(game[field] === null && OPTIONAL_TEXT_FIELDS.has(field)) &&
                typeof game[field] !== "string"
            ) {
                invalidFieldTypes.push({
                    row: rowNumber,
                    field,
                    expected: OPTIONAL_TEXT_FIELDS.has(field) ? "string|null" : "string",
                    actual: valueType(game[field]),
                });
            }
        }

        const bggId = canonicalBggId(game?.bgg_id);
        if (!bggId) {
            invalidBggIds.push({ row: rowNumber, value: game?.bgg_id ?? null });
        } else {
            const rank = rankByBggId.get(bggId);
            if (!rank) {
                missingFromBaseline.push({ row: rowNumber, bgg_id: bggId });
            } else {
                if (normalizeName(game.english_name) !== normalizeName(rank.name)) {
                    nameMismatches.push({
                        row: rowNumber,
                        bgg_id: bggId,
                        gamesName: game.english_name,
                        ranksName: rank.name,
                    });
                }
                if (game.id != null && Number(game.id) !== Number(rank.rank)) {
                    rankMismatches.push({
                        row: rowNumber,
                        bgg_id: bggId,
                        gamesId: game.id,
                        baselineRank: rank.rank,
                    });
                }
            }
        }

        for (const [field, limit] of Object.entries(FIELD_LENGTHS)) {
            if (!blank(game?.[field]) && String(game[field]).length > limit) {
                lengthViolations.push({
                    row: rowNumber,
                    field,
                    length: String(game[field]).length,
                    limit,
                });
            }
        }

        if (
            game.complexity !== undefined &&
            game.complexity !== null &&
            !isValidComplexityValue(game.complexity)
        ) {
            invalidComplexities.push({ row: rowNumber, value: game.complexity });
        }
        if (!blank(game?.image_url)) {
            try {
                if (new URL(String(game.image_url)).protocol !== "https:") {
                    invalidImageUrls.push({ row: rowNumber, value: game.image_url });
                }
            } catch {
                invalidImageUrls.push({ row: rowNumber, value: game.image_url });
            }
        }
    }

    addValidationError(
        errors,
        "INVALID_GAME_ROW",
        "games 입력의 각 행은 JSON 객체여야 합니다.",
        invalidGameRows,
    );
    addValidationError(
        errors,
        "NUL_CHARACTER_IN_TEXT",
        "서비스 카탈로그 텍스트 필드에 허용되지 않는 U+0000이 있습니다.",
        nulCharacterValues,
    );
    addValidationError(
        errors,
        "INVALID_FIELD_TYPE",
        "서비스 카탈로그 필드의 JSON 타입이 허용 범위가 아닙니다.",
        invalidFieldTypes,
    );
    addValidationError(errors, "MISSING_REQUIRED_VALUE", "필수값이 비어 있습니다.", missingRequired);
    addValidationError(errors, "INVALID_BGG_ID", "bgg_id는 양의 정수여야 합니다.", invalidBggIds);
    addValidationError(
        errors,
        "MISSING_FROM_BASELINE",
        "BGG 기준 CSV에서 bgg_id를 찾을 수 없습니다.",
        missingFromBaseline,
    );
    addValidationError(
        errors,
        "BASELINE_NAME_MISMATCH",
        "서비스 영문명과 BGG 기준 이름이 일치하지 않습니다.",
        nameMismatches,
    );
    addValidationError(
        errors,
        "BASELINE_RANK_MISMATCH",
        "입력 id와 기준 스냅샷 rank가 일치하지 않습니다.",
        rankMismatches,
    );
    addValidationError(errors, "FIELD_LENGTH_EXCEEDED", "DB 필드 길이를 초과했습니다.", lengthViolations);
    addValidationError(
        errors,
        "INVALID_COMPLEXITY",
        "complexity는 0~5 범위의 소수 둘째 자리 값이어야 합니다.",
        invalidComplexities,
    );
    addValidationError(
        errors,
        "INVALID_IMAGE_URL",
        "image_url은 유효한 HTTPS URL이어야 합니다.",
        invalidImageUrls,
    );
    return errors;
}

function addValidationError(errors, code, message, findings) {
    if (findings.length > 0) {
        errors.push({ code, message, count: findings.length, sample: findings.slice(0, 10) });
    }
}

function canonicalBggId(value) {
    if (typeof value === "string" && !/^[1-9]\d*$/.test(value)) {
        return null;
    }
    if (typeof value !== "number" && typeof value !== "string") {
        return null;
    }
    const number = Number(value);
    return Number.isSafeInteger(number) && number > 0 ? String(number) : null;
}

function isRecord(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

function valueType(value) {
    if (value === null) {
        return "null";
    }
    if (Array.isArray(value)) {
        return "array";
    }
    return typeof value;
}

function containsNul(value) {
    return typeof value === "string" && value.includes("\u0000");
}

function blank(value) {
    return value == null || (typeof value === "string" && value.trim() === "");
}

function normalizeName(value) {
    return String(value ?? "")
        .normalize("NFKC")
        .toLocaleLowerCase("en-US")
        .replaceAll(/[^\p{L}\p{N}]+/gu, "");
}

function validateManifest(manifest, gamesPath, gamesContents, ranksPath, ranksContents) {
    if (!manifest) {
        return [
            {
                code: "MISSING_MANIFEST",
                message: "출처와 검수 상태를 기록한 manifest가 필요합니다.",
            },
        ];
    }
    const errors = [];
    if (manifest.schemaVersion !== 1 || !completedText(manifest.batchId)) {
        errors.push({ code: "INVALID_MANIFEST", message: "manifest 기본 정보가 없습니다." });
    }
    if (!/^[0-9a-f]{40}$/.test(manifest.toolCommit ?? "")) {
        errors.push({
            code: "INVALID_TOOL_COMMIT",
            message: "변환 도구가 포함된 전체 Git commit SHA가 필요합니다.",
        });
    }
    if (manifest.review?.status !== "approved") {
        errors.push({ code: "REVIEW_NOT_APPROVED", message: "검수 승인이 필요합니다." });
    }
    for (const [role, path, contents] of [
        ["games", gamesPath, gamesContents],
        ["ranks", ranksPath, ranksContents],
    ]) {
        const source = manifest.sources?.[role];
        if (
            !source ||
            source.fileName !== basename(path) ||
            source.sha256 !== sha256(contents) ||
            !completedText(source.sourceReference) ||
            !isoTimestamp(source.acquiredAt) ||
            !completedText(source.usageTerms)
        ) {
            errors.push({
                code: "INVALID_SOURCE_METADATA",
                message: `${role} 출처 정보 또는 체크섬이 입력 파일과 일치하지 않습니다.`,
            });
        }
    }
    if (CATALOG_FIELDS.some((field) => !completedText(manifest.fieldSources?.[field]))) {
        errors.push({
            code: "INCOMPLETE_FIELD_SOURCES",
            message: "모든 적재 필드의 출처 규칙이 필요합니다.",
        });
    }
    if (
        !isoTimestamp(manifest.review?.reviewedAt) ||
        !Array.isArray(manifest.review?.reviewers) ||
        manifest.review.reviewers.length === 0 ||
        manifest.review.reviewers.some((reviewer) => !completedText(reviewer)) ||
        !Array.isArray(manifest.review?.acceptedWarnings)
    ) {
        errors.push({ code: "INVALID_REVIEW", message: "검수자와 검수 시각이 필요합니다." });
    }
    if (
        !isRecord(manifest.selectionRules) ||
        !completedText(manifest.selectionRules.include) ||
        !completedText(manifest.selectionRules.exclude)
    ) {
        errors.push({
            code: "INVALID_SELECTION_RULES",
            message: "게임 선택·제외 규칙이 필요합니다.",
        });
    }
    if (
        !isRecord(manifest.versionRules) ||
        ["baseGame", "expansion", "variant"].some(
            (field) => !completedText(manifest.versionRules[field]),
        )
    ) {
        errors.push({
            code: "INVALID_VERSION_RULES",
            message: "본판·확장·변형 구분 규칙이 필요합니다.",
        });
    }
    const selection = manifest.selection;
    if (
        !isRecord(selection) ||
        !isNonNegativeSafeInteger(selection.candidateRows) ||
        !isNonNegativeSafeInteger(selection.includedRows) ||
        !isNonNegativeSafeInteger(selection.excludedRows)
    ) {
        errors.push({
            code: "INVALID_SELECTION_COUNTS",
            message: "원본 후보·포함·제외 행 수가 필요합니다.",
        });
    }
    if (!isRecord(selection) || !Array.isArray(selection.exclusions)) {
        errors.push({
            code: "INVALID_SELECTION_EXCLUSIONS",
            message: "구조화된 제외 결과 목록이 필요합니다.",
        });
    } else {
        const invalidExclusions = selection.exclusions
            .map((exclusion, index) => ({ exclusion, index }))
            .filter(
                ({ exclusion }) =>
                    !isRecord(exclusion) ||
                    !isExclusionIdentifier(exclusionIdentifier(exclusion)) ||
                    !completedText(exclusion.reason),
            )
            .map(({ exclusion, index }) => ({
                index,
                identifier: exclusionIdentifier(exclusion),
                reason: exclusion?.reason ?? null,
            }));
        addValidationError(
            errors,
            "INVALID_SELECTION_EXCLUSION",
            "제외 항목에는 식별자와 사유가 필요합니다.",
            invalidExclusions,
        );
    }
    return errors;
}

function validateSelectionCounts(manifest, inputRows, catalogRows) {
    const selection = manifest?.selection;
    if (!isRecord(selection) || !Array.isArray(selection.exclusions)) {
        return [];
    }
    const errors = [];
    if (
        !isNonNegativeSafeInteger(selection.candidateRows) ||
        !isNonNegativeSafeInteger(selection.includedRows) ||
        !isNonNegativeSafeInteger(selection.excludedRows)
    ) {
        return errors;
    }
    if (selection.candidateRows !== selection.includedRows + selection.excludedRows) {
        errors.push({
            code: "SELECTION_COUNT_MISMATCH",
            message: "후보 행 수는 포함 행 수와 제외 건수의 합과 같아야 합니다.",
            candidateRows: selection.candidateRows,
            includedRows: selection.includedRows,
            excludedRows: selection.excludedRows,
        });
    }
    if (selection.excludedRows !== selection.exclusions.length) {
        errors.push({
            code: "SELECTION_EXCLUSION_COUNT_MISMATCH",
            message: "제외 건수와 구조화된 제외 항목 수가 다릅니다.",
            excludedRows: selection.excludedRows,
            exclusionItems: selection.exclusions.length,
        });
    }
    if (selection.includedRows !== catalogRows || inputRows < catalogRows) {
        errors.push({
            code: "SELECTION_INCLUDED_ROWS_MISMATCH",
            message: "manifest 포함 행 수가 실제 서비스 카탈로그 행 수와 다릅니다.",
            manifestIncludedRows: selection.includedRows,
            catalogRows,
            inputRows,
        });
    }
    return errors;
}

function isNonNegativeSafeInteger(value) {
    return Number.isSafeInteger(value) && value >= 0;
}

function isExclusionIdentifier(value) {
    return completedText(value) || canonicalBggId(value) !== null;
}

function exclusionIdentifier(exclusion) {
    return exclusion?.identifier ?? exclusion?.bgg_id ?? exclusion?.id;
}

function completedText(value) {
    return (
        typeof value === "string" &&
        value.trim() !== "" &&
        !/^TODO\b/i.test(value.trim()) &&
        !/^<.*>$/.test(value.trim())
    );
}

function isoTimestamp(value) {
    return (
        completedText(value) &&
        /^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
        !Number.isNaN(Date.parse(value))
    );
}

function reportFor({
    manifest,
    gamesPath,
    gamesContents,
    ranksPath,
    ranksContents,
    gamesCount,
    ranksCount,
    errors,
    warnings,
    checks,
    outputs = null,
}) {
    const report = {
        schemaVersion: 1,
        batchId: manifest.batchId ?? null,
        toolCommit: manifest.toolCommit ?? null,
        status: errors.length === 0 ? "ready" : "blocked",
        inputs: {
            games: {
                fileName: basename(gamesPath),
                sha256: sha256(gamesContents),
                rows: gamesCount,
            },
            ranks: {
                fileName: basename(ranksPath),
                sha256: sha256(ranksContents),
                rows: ranksCount,
            },
        },
        errors,
        warnings,
        checks,
        outputs,
    };
    if (
        manifest.selectionRules !== undefined ||
        manifest.versionRules !== undefined ||
        manifest.selection !== undefined
    ) {
        report.selectionRules = manifest.selectionRules ?? null;
        report.versionRules = manifest.versionRules ?? null;
        report.selection = manifest.selection ?? null;
    }
    return report;
}

function upsertSql(catalog) {
    const values = catalog
        .map((game) => `    (${CATALOG_FIELDS.map((field) => sqlValue(game[field])).join(", ")}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`)
        .join(",\n");
    const columns = [...CATALOG_FIELDS, "created_at", "updated_at"]
        .map((field) => `    ${field}`)
        .join(",\n");
    const updates = CATALOG_FIELDS.filter((field) => field !== "bgg_id")
        .map((field) => `    ${field} = EXCLUDED.${field}`)
        .concat("    updated_at = CURRENT_TIMESTAMP")
        .join(",\n");
    return `BEGIN;\nSET LOCAL standard_conforming_strings = on;\nSET LOCAL TIME ZONE 'UTC';\n\nINSERT INTO games (\n${columns}\n) VALUES\n${values}\nON CONFLICT (bgg_id) DO UPDATE SET\n${updates};\n\nCOMMIT;\n`;
}

function sqlValue(value) {
    if (value === null) {
        return "NULL";
    }
    if (typeof value === "number") {
        return String(value);
    }
    return `'${String(value).replaceAll("'", "''")}'`;
}

function rowsFromCsv(contents) {
    const rows = parseCsv(contents);
    const headers = rows.shift();
    if (
        !Array.isArray(headers) ||
        headers.length === 0 ||
        headers.some((header) => blank(header)) ||
        new Set(headers).size !== headers.length
    ) {
        throw new Error("BGG 기준 CSV 헤더가 비어 있거나 중복됩니다.");
    }
    const dataRows = rows.filter((row) => row.some((value) => value !== ""));
    if (dataRows.some((row) => row.length !== headers.length)) {
        throw new Error("BGG 기준 CSV 행의 열 수가 헤더와 다릅니다.");
    }
    return dataRows.map((row) =>
        Object.fromEntries(headers.map((header, index) => [header, row[index]])),
    );
}

function parseCsv(contents) {
    const rows = [];
    let row = [];
    let value = "";
    let quoted = false;
    for (let index = 0; index < contents.length; index += 1) {
        const character = contents[index];
        if (quoted) {
            if (character === '"' && contents[index + 1] === '"') {
                value += '"';
                index += 1;
            } else if (character === '"') {
                quoted = false;
            } else {
                value += character;
            }
        } else if (character === '"') {
            quoted = true;
        } else if (character === ",") {
            row.push(value);
            value = "";
        } else if (character === "\n") {
            row.push(value.replace(/\r$/, ""));
            rows.push(row);
            row = [];
            value = "";
        } else {
            value += character;
        }
    }
    if (value !== "" || row.length > 0) {
        row.push(value.replace(/\r$/, ""));
        rows.push(row);
    }
    if (quoted) {
        throw new Error("닫히지 않은 CSV 따옴표가 있습니다.");
    }
    return rows;
}

function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

function duplicateValues(values) {
    const counts = new Map();
    for (const value of values) {
        counts.set(value, (counts.get(value) ?? 0) + 1);
    }
    return [...counts.entries()]
        .filter(([, count]) => count > 1)
        .map(([value, count]) => ({ value, count }));
}

function parseOptions(args) {
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        const value = args[index + 1];
        if (!key?.startsWith("--") || !value) {
            failUsage();
        }
        values[key.slice(2)] = value;
    }

    if (!values.games || !values.ranks || !values.out) {
        failUsage();
    }

    return {
        games: resolve(values.games),
        ranks: resolve(values.ranks),
        manifest: values.manifest ? resolve(values.manifest) : null,
        out: resolve(values.out),
    };
}

function failUsage() {
    process.stderr.write(
        "사용법: node prepare-game-catalog.mjs --games <json> --ranks <csv> " +
            "[--manifest <json>] --out <directory>\n",
    );
    process.exit(2);
}

function writeJson(path, value) {
    writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}
