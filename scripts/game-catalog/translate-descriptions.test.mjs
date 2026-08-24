import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

import { assertTranslationApproval } from "./translate-descriptions.mjs";

const SCRIPT = new URL("./translate-descriptions.mjs", import.meta.url);

test("구체 release가 없으면 API 호출 전 번역을 차단한다", () => {
    assert.throws(
        () => assertTranslationApproval({ approved: true, testOnly: false }),
        /schemaVersion|releaseId/u,
    );
});

test("번역 원문 artifact의 checksum과 행 수가 release와 다르면 차단한다", () => {
    const manifest = approvedTranslationManifest();

    assert.throws(
        () => assertTranslationApproval(manifest, {
            actualDescriptionInput: {
                fileName: "descriptions.json",
                sha256: "0".repeat(64),
                rows: 2,
            },
        }),
        /inputs\.descriptions\.(sha256|rows)/u,
    );
});

test("승인된 번역 release와 일치하는 원문은 번역 gate를 통과한다", () => {
    const source = JSON.stringify([{ bgg_id: 10, source: "Players draw cards." }]);
    const manifest = approvedTranslationManifest({
        sha256: sha256(source),
        rows: 1,
    });

    assert.doesNotThrow(() => assertTranslationApproval(manifest, {
        actualDescriptionInput: {
            fileName: "descriptions.json",
            sha256: sha256(source),
            rows: 1,
        },
    }));
});

test("CLI는 승인 release가 없을 때 API key 검사보다 먼저 종료한다", () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-translate-gate-"));
    const sourcePath = join(root, "descriptions.json");
    const manifestPath = join(root, "manifest.json");
    const outputPath = join(root, "translated.json");
    writeFileSync(sourcePath, "[]\n");
    writeFileSync(manifestPath, JSON.stringify({ approved: true, testOnly: false }));

    const result = spawnSync(process.execPath, [
        SCRIPT.pathname,
        "--source",
        sourcePath,
        "--manifest",
        manifestPath,
        "--out",
        outputPath,
    ], { encoding: "utf8", env: { ...process.env, ANTHROPIC_API_KEY: "" } });

    assert.equal(result.status, 1);
    assert.match(result.stderr, /description-translation release manifest|schemaVersion/u);
    assert.doesNotMatch(result.stderr, /ANTHROPIC_API_KEY/u);
    assert.throws(() => readFileSync(outputPath));
});

function approvedTranslationManifest({ sha256: descriptionSha256 = "a".repeat(64), rows = 1 } = {}) {
    const artifact = (fileName) => ({
        status: "approved",
        path: `input/${fileName}`,
        sha256: "b".repeat(64),
        rows,
    });
    const provenance = {
        source: "승인된 BGG 원문",
        sourceVersion: "bgg-description-v1",
        processing: "approved-translation",
        status: "approved",
        reviewedBy: "test-reviewer",
        reviewedAt: "2026-08-13T00:00:00Z",
    };
    return {
        schemaVersion: 1,
        releaseId: "catalog-translation-2026-08-13-001",
        datasetId: "bgg-catalog-2026-08-13",
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: "test-reviewer",
            reviewedAt: "2026-08-13T00:00:00Z",
            references: ["https://github.com/bamsongi-club/albam-mate/issues/747"],
        },
        approvedFields: ["name", "english_name", "description", "detail_description"],
        approvedProcessingScopes: [
            "service-load",
            "search-text-assembly",
            "embedding-generation",
            "description-translation",
        ],
        search_text: {
            fields: ["name", "english_name", "description", "detail_description"],
            sourceFieldVersion: "catalog-fields-v1",
            assemblyRuleVersion: "search-text-v1",
        },
        embedding: {
            provider: "test-provider",
            model: "test-model",
            modelVersion: "test-model-v1",
            dimensions: 3,
            indexVersion: "search-04-test-v1",
            output: {
                path: "output/embeddings.json",
                sha256: "c".repeat(64),
                rows,
            },
        },
        inputs: {
            catalog: artifact("catalog.json"),
            names: artifact("names.json"),
            descriptions: {
                status: "approved",
                path: "input/descriptions.json",
                sha256: descriptionSha256,
                rows,
            },
            mechanismDictionary: artifact("mechanisms.json"),
            themeDictionary: artifact("themes.json"),
            relations: artifact("relations.json"),
        },
        coverage: {
            catalogIds: coverage(rows),
            relationGameIds: coverage(rows),
            mechanismIds: coverage(1),
            themeIds: coverage(1),
        },
        sources: {
            games: source("games.json"),
            ranks: source("ranks.csv"),
        },
        provenance: {
            descriptionFields: {
                description: provenance,
                detail_description: provenance,
            },
        },
        outputs: {
            serviceCatalog: output("service-catalog.json"),
            upsertSql: output("upsert-games.sql"),
        },
    };
}

function coverage(rows) {
    return { rows, sha256: "d".repeat(64) };
}

function source(fileName) {
    return { fileName, sha256: "e".repeat(64), rows: 1 };
}

function output(fileName) {
    return { path: `output/${fileName}`, sha256: "f".repeat(64), rows: 1 };
}

function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}
