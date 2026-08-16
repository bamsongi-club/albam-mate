#!/usr/bin/env node

import {
    mkdirSync,
    readFileSync,
    realpathSync,
    rmSync,
    statSync,
    writeFileSync,
} from "node:fs";
import { basename, dirname, isAbsolute, relative, resolve } from "node:path";

import { analyzeCatalog, parseRankRows, sha256 } from "./catalog-analysis.mjs";
import { validateApprovedReleaseManifest } from "./catalog-release-manifest.mjs";
import {
    CATALOG_DATASET_RELEASE_KIND,
    validateCatalogDatasetReleaseManifest,
    validateCatalogDatasetReleaseReference,
} from "./catalog-dataset-release-manifest.mjs";
import {
    buildQualityReport,
    renderJson,
    renderUpsertSql,
} from "./catalog-artifact-renderer.mjs";
import { extractMechanismCatalog, renderMechanismUpsertSql } from "./mechanism-catalog.mjs";

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
        "upsert-games.sql", "service-mechanism-catalog.json", "upsert-game-mechanisms.sql",
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
    for (const fileName of ["service-catalog.json", "upsert-games.sql", "service-mechanism-catalog.json", "upsert-game-mechanisms.sql"]) {
        rmSync(resolve(out, fileName), { force: true });
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
        return parseRankRows(decodeUtf8(contents, "ranks"));
    } catch (cause) {
        if (cause instanceof InputError) {
            throw cause;
        }
        throw new InputError("INVALID_RANKS_CSV", "BGG 기준 CSV를 해석할 수 없습니다.", cause);
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
    const resolvedManifest = manifest ? resolveManifest(manifest, manifestPath) : null;
    const analysis = analyzeCatalog({
        games,
        rankRows,
        manifest: resolvedManifest,
        gamesPath,
        gamesContents,
        ranksPath,
        ranksContents,
    });
    const actualInputs = {
        games: {
            fileName: basename(gamesPath),
            sha256: sha256(gamesContents),
            rows: Array.isArray(games) ? games.length : null,
        },
        ranks: {
            fileName: basename(ranksPath),
            sha256: sha256(ranksContents),
            rows: rankRows.length,
        },
    };
    if (resolvedManifest) {
        analysis.errors.push(...validateManifestGate(resolvedManifest, { actualInputs, manifestPath }));
    }
    const mechanisms = extractMechanismCatalog(games, resolvedManifest);
    if (mechanisms) {
        analysis.errors.push(...mechanisms.errors);
    }
    const reportInput = {
        ...analysis,
        gamesPath,
        gamesContents,
        ranksPath,
        ranksContents,
    };

    if (analysis.errors.length > 0) {
        writeJson(resolve(out, "quality-report.json"), buildQualityReport(reportInput));
        process.exitCode = 1;
        return;
    }

    const catalogText = renderJson(analysis.catalog);
    const sqlText = renderUpsertSql(analysis.catalog);
    const actualOutputs = {
        serviceCatalog: {
            fileName: "service-catalog.json",
            sha256: sha256(catalogText),
            rows: analysis.catalog.length,
        },
        upsertSql: {
            fileName: "upsert-games.sql",
            sha256: sha256(sqlText),
            rows: analysis.catalog.length,
        },
    };
    const outputSummary = {
        catalogRows: analysis.catalog.length,
        catalogSha256: actualOutputs.serviceCatalog.sha256,
        sqlSha256: actualOutputs.upsertSql.sha256,
    };
    analysis.errors.push(
        ...validateManifestGate(resolvedManifest, { actualInputs, actualOutputs, manifestPath }),
    );
    if (analysis.errors.length > 0) {
        writeJson(
            resolve(out, "quality-report.json"),
            buildQualityReport({ ...reportInput, outputs: outputSummary }),
        );
        process.exitCode = 1;
        return;
    }
    writeFileSync(resolve(out, "service-catalog.json"), catalogText, "utf8");
    writeFileSync(resolve(out, "upsert-games.sql"), sqlText, "utf8");
    if (mechanisms) {
        const mechanismCatalogText = renderJson(mechanisms.catalog);
        const mechanismSqlText = renderMechanismUpsertSql(mechanisms.catalog, mechanisms.relations);
        writeFileSync(resolve(out, "service-mechanism-catalog.json"), mechanismCatalogText, "utf8");
        writeFileSync(resolve(out, "upsert-game-mechanisms.sql"), mechanismSqlText, "utf8");
        reportInput.mechanismCatalog = {
            publishedCount: mechanisms.catalog.length,
            relationCount: mechanisms.relations.length,
        };
    }
    writeJson(
        resolve(out, "quality-report.json"),
        buildQualityReport({
            ...reportInput,
            outputs: outputSummary,
        }),
    );
}

function resolveManifest(manifest, manifestPath) {
    if (!manifest.baseManifest) {
        return manifest;
    }
    const basePath = resolve(dirname(manifestPath), manifest.baseManifest);
    const baseManifest = parseJson(
        readInput(basePath, "base manifest"),
        "INVALID_BASE_MANIFEST_JSON",
        "기준 manifest를 해석할 수 없습니다.",
        "base manifest",
    );
    return {
        ...baseManifest,
        ...manifest,
        sources: { ...baseManifest.sources, ...manifest.sources },
        provenance: { ...baseManifest.provenance, ...manifest.provenance },
        fieldSources: { ...baseManifest.fieldSources, ...manifest.fieldSources },
        review: { ...baseManifest.review, ...manifest.review },
        approval: mergeOptionalObject(baseManifest.approval, manifest.approval),
        search_text: mergeOptionalObject(baseManifest.search_text, manifest.search_text),
        embedding: mergeEmbedding(baseManifest.embedding, manifest.embedding),
        datasetRelease: mergeOptionalObject(baseManifest.datasetRelease, manifest.datasetRelease),
        inputs: mergeOptionalObject(baseManifest.inputs, manifest.inputs),
        coverage: mergeOptionalObject(baseManifest.coverage, manifest.coverage),
        outputs: mergeOptionalObject(baseManifest.outputs, manifest.outputs),
    };
}

function mergeOptionalObject(baseValue, overrideValue) {
    if (baseValue === undefined && overrideValue === undefined) {
        return undefined;
    }
    return { ...baseValue, ...overrideValue };
}

function mergeEmbedding(baseValue, overrideValue) {
    const merged = mergeOptionalObject(baseValue, overrideValue);
    if (!merged) {
        return undefined;
    }
    const output = mergeOptionalObject(baseValue?.output, overrideValue?.output);
    return output ? { ...merged, output } : merged;
}

function validateManifestGate(manifest, context) {
    if (!requiresApprovedReleaseGate(manifest)) {
        return [];
    }
    if (manifest.kind === CATALOG_DATASET_RELEASE_KIND) {
        return [
            {
                code: "DATASET_RELEASE_MANIFEST_NOT_EXECUTION",
                message: "catalog dataset release manifest는 execution manifest에 참조해야 하며 runner에 직접 전달할 수 없습니다.",
            },
        ];
    }
    if (manifest.datasetRelease !== undefined) {
        try {
            validateDatasetReleaseHandoff(manifest, context);
        } catch (error) {
            return [
                {
                    code: "INVALID_DATASET_RELEASE_REFERENCE",
                    message: error instanceof Error ? error.message : String(error),
                },
            ];
        }
    }
    try {
        validateApprovedReleaseManifest(manifest, context);
        return [];
    } catch (error) {
        return [
            {
                code: "INVALID_APPROVED_RELEASE_MANIFEST",
                message: error instanceof Error ? error.message : String(error),
            },
        ];
    }
}

function validateDatasetReleaseHandoff(manifest, { manifestPath }) {
    if (!manifestPath) {
        throw new Error("datasetRelease reference requires the execution manifest path");
    }
    const referencePath = manifest.datasetRelease?.manifestPath;
    assertSafeRelativeManifestPath(referencePath);
    const baseDirectory = realpathSync(dirname(manifestPath));
    const candidatePath = resolve(baseDirectory, referencePath);
    const datasetManifestPath = realpathSync(candidatePath);
    const relativePath = relative(baseDirectory, datasetManifestPath);
    if (!relativePath || relativePath.startsWith("..") || isAbsolute(relativePath)) {
        throw new Error("datasetRelease.manifestPath resolves outside the execution manifest directory");
    }
    const datasetManifestContents = readInput(datasetManifestPath, "dataset release manifest");
    const datasetManifest = parseJson(
        datasetManifestContents,
        "INVALID_DATASET_RELEASE_MANIFEST_JSON",
        "dataset release manifest를 해석할 수 없습니다.",
        "dataset release manifest",
    );
    validateCatalogDatasetReleaseManifest(datasetManifest);
    validateCatalogDatasetReleaseReference(
        manifest.datasetRelease,
        datasetManifest,
        sha256(datasetManifestContents),
    );
}

function assertSafeRelativeManifestPath(path) {
    if (
        typeof path !== "string"
        || path.trim() === ""
        || isAbsolute(path)
        || /^[a-zA-Z]:[\\/]/u.test(path)
        || path.split(/[\\/]/u).some((segment) => segment === ".." || segment === "")
    ) {
        throw new Error("datasetRelease.manifestPath must be a safe relative path");
    }
}

function requiresApprovedReleaseGate(manifest) {
    if (!manifest) {
        return false;
    }
    return manifest.kind === CATALOG_DATASET_RELEASE_KIND
        || manifest.datasetRelease !== undefined
        || [
        "approved",
        "testOnly",
        "releaseId",
        "datasetId",
        "approvedFields",
        "approvedProcessingScopes",
        "search_text",
        "embedding",
        "outputs",
    ].some((field) => manifest[field] !== undefined);
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
    writeFileSync(path, renderJson(value), "utf8");
}
