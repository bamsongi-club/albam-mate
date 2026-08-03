import { createHash } from "node:crypto";
import { basename } from "node:path";

export const CATALOG_FIELDS = [
    "bgg_id",
    "name",
    "english_name",
    "alias",
    "image_url",
    "supported_player_count",
    "tag",
    "estimated_play_time",
    "min_players",
    "max_players",
    "min_play_time_minutes",
    "max_play_time_minutes",
    "complexity",
    "description",
    "detail_description",
];
const SEARCH_NUMERIC_FIELD_LABELS = {
    players: "가능 인원",
    playTimeMinutes: "예상 플레이 시간",
    complexity: "복잡도",
};
const TEXT_FIELDS = [
    "name",
    "english_name",
    "alias",
    "image_url",
    "supported_player_count",
    "tag",
    "estimated_play_time",
    "description",
    "detail_description",
];
const REQUIRED_FIELDS = [
    "bgg_id",
    "name",
    "english_name",
    "supported_player_count",
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
    supported_player_count: 50,
    tag: 30,
    estimated_play_time: 50,
};
const OPTIONAL_TEXT_FIELDS = new Set(["alias", "image_url"]);
const POSTGRES_INTEGER_MAX = 2_147_483_647;

export function analyzeCatalog({
    games,
    rankRows,
    manifest,
    gamesPath,
    gamesContents,
    ranksPath,
    ranksContents,
}) {
    const gameRows = Array.isArray(games) ? games : [];
    const validGameRows = gameRows.filter(isRecord);
    const errors = validateManifest(manifest, gamesPath, gamesContents, ranksPath, ranksContents);
    errors.push(...validateData(games, rankRows));
    errors.push(...validateSearchNumericDisplayValues(gameRows));
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
    const normalizedGames = validGameRows.map((game) => normalizeGame(game));
    const catalog = normalizedGames
        .sort((left, right) => left.bgg_id - right.bgg_id);
    errors.push(...validateSelectionCounts(manifest, gameRows.length, catalog.length, catalog));
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

    return {
        catalog,
        manifest: manifest ?? {},
        gamesCount: Array.isArray(games) ? games.length : null,
        ranksCount: rankRows.length,
        errors,
        warnings,
        checks,
        searchNumericFields: searchNumericFieldSummary(validGameRows),
    };
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
        "LOW_SUPPORTED_PLAYER_COUNT_DIVERSITY",
        "가능 인원",
        games.map((game) => game.supported_player_count),
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
    return isValidComplexityValue(value) && value >= 1 && value <= 5;
}

function isValidComplexityValue(value) {
    return (
        typeof value === "number" &&
        Number.isFinite(value) &&
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
    const players = normalizePositiveRange(game.supported_player_count, "명");
    const playTime = normalizePositiveRange(game.estimated_play_time, "분");
    const complexity = normalizeComplexity(game.complexity);
    return {
        ...Object.fromEntries(
            CATALOG_FIELDS.map((field) => [
                field,
                field === "bgg_id" ? bggId : (game[field] ?? null),
            ]),
        ),
        min_players: players.min,
        max_players: players.max,
        min_play_time_minutes: playTime.min,
        max_play_time_minutes: playTime.max,
        complexity: complexity.value,
    };
}

function searchNumericFieldSummary(games) {
    const fields = {
        players: games.map((game) => normalizePositiveRange(game.supported_player_count, "명")),
        playTimeMinutes: games.map((game) => normalizePositiveRange(game.estimated_play_time, "분")),
        complexity: games.map((game) => normalizeComplexity(game.complexity)),
    };
    return Object.fromEntries(
        Object.entries(fields).map(([field, results]) => [
            field,
            summarizeNormalization(SEARCH_NUMERIC_FIELD_LABELS[field], results),
        ]),
    );
}

function summarizeNormalization(label, results) {
    const reasonCounts = new Map();
    let valid = 0;
    let missing = 0;
    let excluded = 0;
    let normalizedToNull = 0;
    for (const result of results) {
        if (result.status === "valid") {
            valid += 1;
        } else if (result.status === "missing") {
            missing += 1;
        } else {
            excluded += 1;
        }
        if (result.normalizedToNull) {
            normalizedToNull += 1;
        }
        if (result.reason) {
            reasonCounts.set(result.reason, (reasonCounts.get(result.reason) ?? 0) + 1);
        }
    }
    return {
        label,
        total: results.length,
        valid,
        missing,
        excluded,
        normalizedToNull,
        exclusionReasons: [...reasonCounts.entries()]
            .map(([code, count]) => ({ code, count }))
            .sort((left, right) => left.code.localeCompare(right.code)),
    };
}

function normalizePositiveRange(value, suffix) {
    if (blank(value)) {
        return { min: null, max: null, status: "missing" };
    }
    const matched =
        typeof value === "string" &&
        new RegExp(`^(-?\\d+)(?:~(-?\\d+))?${suffix}$`).exec(value);
    if (!matched) {
        return {
            min: null,
            max: null,
            status: "excluded",
            reason: "UNPARSABLE_DISPLAY_VALUE",
        };
    }
    const min = Number(matched[1]);
    const max = Number(matched[2] ?? matched[1]);
    if (
        !Number.isSafeInteger(min) ||
        !Number.isSafeInteger(max) ||
        min > POSTGRES_INTEGER_MAX ||
        max > POSTGRES_INTEGER_MAX
    ) {
        return {
            min: null,
            max: null,
            status: "excluded",
            reason: "OUT_OF_POSTGRES_INTEGER_RANGE",
        };
    }
    if (min <= 0 || max <= 0) {
        return { min: null, max: null, status: "excluded", reason: "NON_POSITIVE_VALUE" };
    }
    if (min > max) {
        return { min: null, max: null, status: "excluded", reason: "MINIMUM_EXCEEDS_MAXIMUM" };
    }
    return { min, max, status: "valid" };
}

function validateSearchNumericDisplayValues(games) {
    const invalidValues = [];
    for (const [index, game] of games.entries()) {
        if (!isRecord(game)) {
            continue;
        }
        for (const [field, suffix] of [
            ["supported_player_count", "명"],
            ["estimated_play_time", "분"],
        ]) {
            const normalized = normalizePositiveRange(game[field], suffix);
            if (normalized.status === "excluded") {
                invalidValues.push({
                    row: index + 1,
                    bgg_id: canonicalBggId(game.bgg_id),
                    field,
                    value: game[field],
                    reason: normalized.reason,
                });
            }
        }
    }
    const errors = [];
    addValidationError(
        errors,
        "INVALID_SEARCH_NUMERIC_DISPLAY_VALUE",
        "비어 있지 않은 인원·시간 표시값은 PostgreSQL INTEGER 범위의 양의 오름차순 정수 또는 범위여야 합니다.",
        invalidValues,
    );
    return errors;
}

function normalizeComplexity(value) {
    if (value === null || value === undefined) {
        return { value: null, status: "missing" };
    }
    if (!isValidComplexityValue(value)) {
        return { value: null, status: "excluded", reason: "INVALID_COMPLEXITY" };
    }
    if (value === 0) {
        return {
            value: null,
            status: "missing",
            normalizedToNull: true,
            reason: "ZERO_NORMALIZED_TO_NULL",
        };
    }
    if (value >= 1 && value <= 5) {
        return { value, status: "valid" };
    }
    return { value: null, status: "excluded", reason: "OUT_OF_RANGE" };
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

        if (game.complexity !== undefined && game.complexity !== null &&
            !isValidComplexityValue(game.complexity)) {
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

function validateSelectionCounts(manifest, inputRows, catalogRows, catalog) {
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
    const catalogBggIds = new Set(
        catalog.map(({ bgg_id }) => canonicalBggId(bgg_id)).filter(Boolean),
    );
    const overlappingExclusions = [
        ...new Set(
            selection.exclusions
                .map(exclusionBggId)
                .filter((bggId) => bggId !== null && catalogBggIds.has(bggId)),
        ),
    ];
    if (overlappingExclusions.length > 0) {
        errors.push({
            code: "SELECTION_EXCLUSION_OVERLAPS_CATALOG",
            message: "실제 카탈로그에 포함된 bgg_id를 제외할 수 없습니다.",
            count: overlappingExclusions.length,
            sample: overlappingExclusions.slice(0, 10),
        });
    }
    const duplicateExclusions = duplicateValues(
        selection.exclusions.map(normalizedExclusionIdentifier).filter(Boolean),
    );
    if (duplicateExclusions.length > 0) {
        errors.push({
            code: "SELECTION_EXCLUSION_DUPLICATE",
            message: "제외 목록에 같은 식별자가 중복됩니다.",
            count: duplicateExclusions.length,
            sample: duplicateExclusions.slice(0, 10),
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

function exclusionBggId(exclusion) {
    return canonicalExclusionBggId(exclusionIdentifier(exclusion));
}

function normalizedExclusionIdentifier(exclusion) {
    const identifier = exclusionIdentifier(exclusion);
    if (!isExclusionIdentifier(identifier)) {
        return null;
    }
    return canonicalExclusionBggId(identifier) ?? String(identifier).trim();
}

function canonicalExclusionBggId(value) {
    const canonical = canonicalBggId(value);
    if (canonical !== null) {
        return canonical;
    }
    if (typeof value !== "string") {
        return null;
    }
    const prefixed = /^bgg_id:([1-9]\d*)$/.exec(value);
    return canonicalBggId(prefixed?.[1]);
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

export function parseRankRows(contents) {
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

export function sha256(value) {
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
