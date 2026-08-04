import { basename } from "node:path";

import { CATALOG_FIELDS, sha256 } from "./catalog-analysis.mjs";

export function renderJson(value) {
    return `${JSON.stringify(value, null, 2)}\n`;
}

export function buildQualityReport({
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
    searchNumericFields,
    mechanismCatalog = null,
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
        searchNumericFields,
        mechanismCatalog,
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

export function renderUpsertSql(catalog) {
    const values = catalog
        .map(
            (game) =>
                `    (${CATALOG_FIELDS.map((field) => sqlValue(game[field])).join(", ")}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)`,
        )
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
