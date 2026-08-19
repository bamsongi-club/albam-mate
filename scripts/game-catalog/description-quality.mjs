const HANGUL_LETTERS = /[가-힣ㄱ-ㅎㅏ-ㅣ]/g;
const LATIN_LETTERS = /[A-Za-z]/g;
const DESCRIPTION_FIELDS = ["description", "detail_description"];
const DESCRIPTION_STATES = ["korean", "english", "mixed", "other", "missing"];
const DESCRIPTION_PROCESSINGS = new Set([
    "source-preserved",
    "human-reviewed",
    "human-authored",
    "approved-translation",
    "approved-rewrite",
]);
const SENTENCE_BOUNDARY = /(?<=[.!?。！？])\s+(?=[가-힣ㄱ-ㅎㅏ-ㅣ])|\n+/u;
const TITLE_LIKE_LATIN_SPAN = /[\p{Script=Latin}][\p{Script=Latin}\p{Number}\p{P}\p{S}\p{M}\p{Zs}]*[\p{Script=Latin}\p{Number}](?=\s*[\p{P}\p{S}\p{M}]*\s*(?:은|는|이|가|을|를|의|에|에서|로|으로|와|과|도|만|까지|부터|처럼|보다))/gu;
const ACRONYM = /\b[A-Z][A-Z0-9/&.-]{1,}\b/gu;
const PROPER_NOUN_TOKEN = /\b[A-Za-z0-9]+(?:[&'’:+\/–—.-][A-Za-z0-9]+)*(?=\s*[\p{P}\p{S}\p{M}]*\s*(?:은|는|이|가|을|를|의|에|에서|로|으로|와|과|도|만|까지|부터|처럼|보다))/gu;
const SHORT_LATIN_TOKEN = /\b[A-Za-z]{1,2}\b/gu;
const LATIN_WORD = /\p{Script=Latin}[\p{Script=Latin}\p{M}]*/gu;
const TITLE_STOPWORDS = new Set([
    "a",
    "an",
    "and",
    "are",
    "about",
    "by",
    "card",
    "cards",
    "for",
    "from",
    "game",
    "in",
    "is",
    "it",
    "it's",
    "of",
    "on",
    "or",
    "player",
    "players",
    "the",
    "that",
    "this",
    "to",
    "was",
    "were",
    "with",
]);
const UTC_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;

export { DESCRIPTION_FIELDS, DESCRIPTION_STATES };

export function classifyDescription(value) {
    const text = String(value ?? "").trim();
    if (text === "") {
        return "missing";
    }

    const hangul = countMatches(text, HANGUL_LETTERS);
    const latin = countMatches(text, LATIN_LETTERS);
    if (hangul + latin === 0) {
        return "other";
    }
    if (hangul === 0) {
        return "english";
    }

    const segments = text.split(SENTENCE_BOUNDARY).filter(Boolean);
    if (segments.some((segment) => hasLatin(segment) && !hasHangul(segment))) {
        return "mixed";
    }

    return hasLatin(removeAllowedLatin(text)) ? "mixed" : "korean";
}

export function analyzeDescriptionQuality(games) {
    const rows = Array.isArray(games) ? games : [];
    const fields = Object.fromEntries(
        DESCRIPTION_FIELDS.map((field) => [field, emptyFieldSummary()]),
    );
    const affectedRows = Object.fromEntries(
        ["mixed", "untranslated", "missing"].map((state) => [state, new Set()]),
    );

    for (const [index, game] of rows.entries()) {
        if (!isRecord(game)) {
            continue;
        }
        const identifier = game.bgg_id ?? `row-${index + 1}`;
        for (const field of DESCRIPTION_FIELDS) {
            const state = classifyDescription(game[field]);
            const summary = fields[field];
            summary.counts[state] += 1;
            if (summary.samples[state].length < 10) {
                summary.samples[state].push({
                    bgg_id: game.bgg_id == null ? null : Number(game.bgg_id),
                    sample: String(game[field] ?? "").slice(0, 300),
                });
            }
            if (state === "mixed") {
                affectedRows.mixed.add(identifier);
            }
            if (state === "english" || state === "other") {
                affectedRows.untranslated.add(identifier);
            }
            if (state === "missing") {
                affectedRows.missing.add(identifier);
            }
        }
    }

    return {
        classifierVersion: "description-language-v2",
        totalRows: rows.length,
        fields,
        rowCounts: {
            mixed: affectedRows.mixed.size,
            untranslated: affectedRows.untranslated.size,
            missing: affectedRows.missing.size,
        },
    };
}

function hasHangul(value) {
    HANGUL_LETTERS.lastIndex = 0;
    return HANGUL_LETTERS.test(value);
}

function hasLatin(value) {
    LATIN_LETTERS.lastIndex = 0;
    return LATIN_LETTERS.test(value);
}

function countMatches(value, pattern) {
    pattern.lastIndex = 0;
    return value.match(pattern)?.length ?? 0;
}

function removeAllowedLatin(value) {
    return value
        .replace(TITLE_LIKE_LATIN_SPAN, (span) => isTitleLikeLatinSpan(span) ? " " : span)
        .replace(ACRONYM, " ")
        .replace(PROPER_NOUN_TOKEN, (token) => isTitleLikeLatinSpan(token) ? " " : token)
        .replace(SHORT_LATIN_TOKEN, (token) =>
            TITLE_STOPWORDS.has(token.toLocaleLowerCase("en-US")) ? token : " ");
}

function isTitleLikeLatinSpan(value) {
    LATIN_WORD.lastIndex = 0;
    const words = value.match(LATIN_WORD) ?? [];
    if (words.length === 0) {
        return false;
    }
    const capitalizedWords = words.filter((word) => /^\p{Lu}/u.test(word));
    if (capitalizedWords.length >= 2) {
        return true;
    }
    return words.length === 1 && !TITLE_STOPWORDS.has(words[0].toLocaleLowerCase("en-US"));
}

export function validateDescriptionProvenance(manifest) {
    const errors = [];
    const descriptionFields = manifest?.provenance?.descriptionFields;
    if (!isRecord(descriptionFields)) {
        return [{
            code: "DESCRIPTION_PROVENANCE_REQUIRED",
            message: "description·detail_description의 source·processing provenance가 필요합니다.",
        }];
    }

    for (const field of DESCRIPTION_FIELDS) {
        const path = `provenance.descriptionFields.${field}`;
        const value = descriptionFields[field];
        if (!isRecord(value)) {
            errors.push({
                code: "DESCRIPTION_PROVENANCE_REQUIRED",
                message: `${path}가 필요합니다.`,
            });
            continue;
        }
        for (const property of ["source", "sourceVersion", "processing", "status", "reviewedBy", "reviewedAt"]) {
            if (!completedProvenanceText(value[property])) {
                errors.push({
                    code: "INVALID_DESCRIPTION_PROVENANCE",
                    message: `${path}.${property}가 필요합니다.`,
                });
            }
        }
        if (completedProvenanceText(value.processing) && !DESCRIPTION_PROCESSINGS.has(value.processing)) {
            errors.push({
                code: "INVALID_DESCRIPTION_PROVENANCE",
                message: `${path}.processing이 허용된 처리 상태가 아닙니다.`,
            });
        }
        if (value.status !== "approved") {
            errors.push({
                code: "DESCRIPTION_PROVENANCE_NOT_APPROVED",
                message: `${path}.status가 approved가 아닙니다.`,
            });
        }
        if (!isUtcInstant(value.reviewedAt)) {
            errors.push({
                code: "INVALID_DESCRIPTION_PROVENANCE",
                message: `${path}.reviewedAt은 UTC ISO-8601 instant여야 합니다.`,
            });
        }
    }
    return errors;
}

function emptyFieldSummary() {
    return {
        counts: Object.fromEntries(DESCRIPTION_STATES.map((state) => [state, 0])),
        samples: Object.fromEntries(DESCRIPTION_STATES.map((state) => [state, []])),
    };
}

function isRecord(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

function completedText(value) {
    return typeof value === "string" && value.trim() !== "";
}

function completedProvenanceText(value) {
    return (
        completedText(value) &&
        !/^TODO\b/i.test(value.trim()) &&
        !/^<.*>$/.test(value.trim())
    );
}

function isUtcInstant(value) {
    if (!completedText(value) || !UTC_INSTANT_PATTERN.test(value)) {
        return false;
    }
    const parsed = Date.parse(value);
    const canonical = value.includes(".") ? value : value.replace(/Z$/u, ".000Z");
    return !Number.isNaN(parsed) && new Date(parsed).toISOString() === canonical;
}
