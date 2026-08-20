#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";

import { analyzeGameCatalogSqlFile } from "./parse-game-catalog-sql.mjs";

const AUTOMATIC_SOURCE_PREFIX = "추정번역";
const XML_FILES_PATTERN = /\.xml$/u;
const CANDIDATE_FILE_PATTERN = /^bgg-game-name-ko-(?:candidates-.+|needs-review)\.csv$/u;
const TRAILING_BUFFER_BYTES = 8192;

export const NAME_CORRECTION_POLICY_VERSION = "game-name-correction-v1";

export function parseCandidateCsv(contents, fileName = "candidates.csv") {
    const records = parseCsvRecords(contents);
    if (records.length === 0) return { fileName, rows: [], header: [] };

    const header = records[0].map((value) => value.trim());
    const rows = records.slice(1).filter((record) => record.some((value) => value.trim() !== "")).map((record) => {
        const row = Object.fromEntries(header.map((key, index) => [key, record[index] ?? ""]));
        const bggId = Number(firstValue(row, ["bggId", "bgg_id", "id"]));
        return {
            bggId,
            nameEn: firstValue(row, ["nameEn", "english_name", "englishName", "name"]),
            nameKo: firstValue(row, ["nameKo", "nameKo_초안", "name_ko"]),
            source: firstValue(row, ["출처", "source", "비고"]),
            reviewed: firstValue(row, ["검수완료(Y/N)", "reviewed", "reviewedYn"]).trim().toUpperCase(),
        };
    });

    for (const row of rows) {
        if (!Number.isSafeInteger(row.bggId) || row.bggId <= 0) {
            throw new Error(`${fileName}에 유효하지 않은 bggId가 있습니다: ${row.bggId}`);
        }
    }

    return { fileName, rows, header };
}

export function isUnreviewedAutomaticCandidate(row) {
    return row.source.trim().startsWith(AUTOMATIC_SOURCE_PREFIX) && row.reviewed !== "Y";
}

export function selectNameCorrections(candidateFiles) {
    const byBggId = new Map();
    let candidateRows = 0;
    for (const file of candidateFiles) {
        candidateRows += file.rows.length;
        for (const row of file.rows) {
            if (!byBggId.has(row.bggId)) byBggId.set(row.bggId, []);
            byBggId.get(row.bggId).push(row);
        }
    }

    const selected = [];
    for (const [bggId, rows] of byBggId) {
        const hasApprovedName = rows.some((row) => !isUnreviewedAutomaticCandidate(row));
        if (hasApprovedName) continue;
        const automatic = rows.find((row) => isUnreviewedAutomaticCandidate(row));
        if (automatic) selected.push(automatic);
    }

    selected.sort((left, right) => left.bggId - right.bggId);
    return {
        candidateRows,
        candidateIds: byBggId.size,
        duplicateCandidateIds: [...byBggId.values()].filter((rows) => rows.length > 1).length,
        unreviewedAutomaticRows: [...byBggId.values()]
            .flat()
            .filter(isUnreviewedAutomaticCandidate).length,
        corrections: selected,
    };
}

export function extractBggNamesFromXml(contents, targetIds) {
    const result = new Map();
    const itemPattern = /<item\b[^>]*\bid="(\d+)"[^>]*>([\s\S]*?)<\/item>/gu;
    for (const match of contents.matchAll(itemPattern)) {
        const bggId = Number(match[1]);
        if (!targetIds.has(bggId)) continue;
        const names = [];
        const namePattern = /<name\b([^>]*?)\/>/gu;
        for (const nameMatch of match[2].matchAll(namePattern)) {
            const attributes = nameMatch[1];
            const type = readXmlAttribute(attributes, "type");
            const value = readXmlAttribute(attributes, "value");
            if (type && value) names.push({ type, value: decodeXml(value) });
        }
        result.set(bggId, {
            primary: names.find((name) => name.type === "primary")?.value ?? null,
            koreanAlternate: names.find(
                (name) => name.type === "alternate" && /\p{Script=Hangul}/u.test(name.value),
            )?.value ?? null,
        });
    }
    return result;
}

export function buildCorrections(selectedCandidates, xmlNames) {
    return selectedCandidates.map((candidate) => {
        const xml = xmlNames.get(candidate.bggId);
        const name = xml?.koreanAlternate || xml?.primary || candidate.nameEn;
        if (!name || name.trim() === "") {
            throw new Error(`bgg_id ${candidate.bggId}의 primary 영문 fallback을 찾을 수 없습니다.`);
        }
        return {
            bggId: candidate.bggId,
            name,
            source: xml?.koreanAlternate ? "bgg-xml-korean-alternate" : "bgg-xml-primary",
            candidateSource: candidate.source,
        };
    });
}

export function renderCorrectionStatements(corrections) {
    if (corrections.length === 0) return "";
    const lines = [
        "-- [game-name-correction] unreviewed automatic transliterations are excluded",
        `-- policy: ${NAME_CORRECTION_POLICY_VERSION}`,
        ...corrections.map(
            ({ bggId, name }) => `UPDATE games SET name = '${quoteSql(name)}' WHERE bgg_id = ${bggId};`,
        ),
    ];
    return `\n${lines.join("\n")}\n`;
}

export async function correctGameNames({
    inputSql,
    candidatePaths = [],
    candidateDirectory,
    xmlDirectory,
    xmlManifest,
    out,
}) {
    const resolvedInput = resolve(inputSql);
    const resolvedOut = resolve(out);
    mkdirSync(resolvedOut, { recursive: true });
    const outputSql = join(resolvedOut, "01-games-full.sql");
    const reportPath = join(resolvedOut, "game-name-correction-provenance.json");
    if (resolvedInput === outputSql || resolvedInput === resolve(reportPath)) {
        throw new Error("입력 SQL과 출력 경로는 달라야 합니다.");
    }

    const resolvedCandidatePaths = resolveCandidatePaths(candidatePaths, candidateDirectory);
    if (resolvedCandidatePaths.length === 0) throw new Error("candidate CSV 입력을 찾지 못했습니다.");
    const candidateFiles = resolvedCandidatePaths.map((filePath) => {
        const contents = readFileSync(filePath, "utf8");
        const parsed = parseCandidateCsv(contents, basename(filePath));
        return {
            ...parsed,
            path: filePath,
            sha256: sha256(contents),
        };
    });
    const selection = selectNameCorrections(candidateFiles);
    const targetIds = new Set(selection.corrections.map((candidate) => candidate.bggId));
    const xmlNames = await collectXmlNames(xmlDirectory, targetIds);
    const corrections = buildCorrections(selection.corrections, xmlNames);

    await writeCorrectedSql(resolvedInput, outputSql, renderCorrectionStatements(corrections));
    const inputStats = await analyzeSqlShape(resolvedInput);
    const outputStats = await analyzeSqlShape(outputSql);
    if (inputStats.rows !== outputStats.rows || inputStats.uniqueBggIds !== outputStats.uniqueBggIds) {
        throw new Error(
            `이름 보정으로 games INSERT 행 수가 바뀌었습니다: input=${inputStats.rows}/${inputStats.uniqueBggIds}, output=${outputStats.rows}/${outputStats.uniqueBggIds}`,
        );
    }

    const report = {
        schemaVersion: 1,
        policyVersion: NAME_CORRECTION_POLICY_VERSION,
        policy: {
            excludedCandidate: "source starts with 추정번역 and reviewed is not Y",
            preferredName: "BGG XML alternate containing Hangul",
            fallbackName: "BGG XML primary name",
            generatedKoreanName: false,
        },
        inputSqlSha256: inputStats.sha256,
        outputSqlSha256: outputStats.sha256,
        inputBytes: inputStats.bytes,
        outputBytes: outputStats.bytes,
        inputRows: inputStats.rows,
        outputRows: outputStats.rows,
        inputUniqueBggIds: inputStats.uniqueBggIds,
        outputUniqueBggIds: outputStats.uniqueBggIds,
        candidates: {
            files: candidateFiles.map(({ fileName, sha256: fileSha256, rows }) => ({
                fileName,
                sha256: fileSha256,
                rows: rows.length,
            })),
            rows: selection.candidateRows,
            uniqueIds: selection.candidateIds,
            duplicateIds: selection.duplicateCandidateIds,
            unreviewedAutomaticRows: selection.unreviewedAutomaticRows,
        },
        corrections: {
            rows: corrections.length,
            koreanAlternateRows: corrections.filter((correction) => correction.source === "bgg-xml-korean-alternate").length,
            primaryEnglishFallbackRows: corrections.filter((correction) => correction.source === "bgg-xml-primary").length,
            missingXmlRows: corrections.filter((correction) => !xmlNames.has(correction.bggId)).length,
            entries: corrections,
        },
        provenance: {
            source: "BGG XML",
            xmlDirectory: basename(resolve(xmlDirectory)),
            xmlManifestSha256: xmlManifest ? sha256(readFileSync(resolve(xmlManifest))) : null,
            candidateDirectory: candidateDirectory ? basename(resolve(candidateDirectory)) : null,
        },
    };
    writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    return { outputSql, reportPath, report };
}

async function collectXmlNames(xmlDirectory, targetIds) {
    const result = new Map();
    if (targetIds.size === 0) return result;
    const directory = resolve(xmlDirectory);
    const files = readdirSync(directory).filter((file) => XML_FILES_PATTERN.test(file)).sort();
    for (const file of files) {
        if (result.size === targetIds.size) break;
        const contents = readFileSync(join(directory, file), "utf8");
        for (const [bggId, names] of extractBggNamesFromXml(contents, targetIds)) {
            result.set(bggId, names);
        }
    }
    return result;
}

function resolveCandidatePaths(candidatePaths, candidateDirectory) {
    if (candidatePaths.length > 0) return candidatePaths.map((candidatePath) => resolve(candidatePath)).sort();
    if (!candidateDirectory) return [];
    return readdirSync(resolve(candidateDirectory))
        .filter((file) => CANDIDATE_FILE_PATTERN.test(file))
        .map((file) => join(resolve(candidateDirectory), file))
        .sort();
}

async function writeCorrectedSql(inputPath, outputPath, correctionStatements) {
    const input = createReadStream(inputPath, { encoding: "utf8" });
    const output = createWriteStream(outputPath, { encoding: "utf8" });
    let tail = "";
    for await (const chunk of input) {
        tail += chunk;
        if (tail.length <= TRAILING_BUFFER_BYTES) continue;
        const boundary = tail.length - TRAILING_BUFFER_BYTES;
        output.write(tail.slice(0, boundary));
        tail = tail.slice(boundary);
    }
    const commit = /\s*COMMIT;\s*$/iu.exec(tail);
    if (!commit) {
        output.destroy();
        throw new Error("입력 SQL의 마지막 COMMIT;를 찾지 못했습니다.");
    }
    output.write(tail.slice(0, commit.index));
    output.write(correctionStatements);
    output.write(tail.slice(commit.index));
    await new Promise((resolvePromise, reject) => {
        output.end((error) => (error ? reject(error) : resolvePromise()));
        output.on("error", reject);
    });
}

async function analyzeSqlShape(filePath) {
    const analysis = await analyzeGameCatalogSqlFile(filePath);
    return {
        sha256: analysis.source.sha256,
        bytes: analysis.source.bytes,
        rows: analysis.rows,
        uniqueBggIds: analysis.uniqueBggIds,
    };
}

function parseCsvRecords(contents) {
    const records = [];
    let row = [];
    let value = "";
    let quoted = false;
    for (let index = 0; index < contents.length; index += 1) {
        const character = contents[index];
        if (character === '"') {
            if (quoted && contents[index + 1] === '"') {
                value += '"';
                index += 1;
            } else {
                quoted = !quoted;
            }
        } else if (character === "," && !quoted) {
            row.push(value);
            value = "";
        } else if ((character === "\n" || character === "\r") && !quoted) {
            if (character === "\r" && contents[index + 1] === "\n") index += 1;
            row.push(value);
            records.push(row);
            row = [];
            value = "";
        } else {
            value += character;
        }
    }
    if (value !== "" || row.length > 0) {
        row.push(value);
        records.push(row);
    }
    return records;
}

function firstValue(row, keys) {
    for (const key of keys) {
        if (typeof row[key] === "string" && row[key].trim() !== "") return row[key].trim();
    }
    return "";
}

function readXmlAttribute(attributes, key) {
    return attributes.match(new RegExp(`\\b${key}="([^"]*)"`, "u"))?.[1] ?? null;
}

function decodeXml(value) {
    return value
        .replace(/&#x([0-9a-f]+);/giu, (_, hex) => String.fromCodePoint(Number.parseInt(hex, 16)))
        .replace(/&#(\d+);/gu, (_, decimal) => String.fromCodePoint(Number(decimal)))
        .replaceAll("&quot;", '"')
        .replaceAll("&apos;", "'")
        .replaceAll("&amp;", "&")
        .replaceAll("&lt;", "<")
        .replaceAll("&gt;", ">");
}

function quoteSql(value) {
    return String(value).replaceAll("'", "''");
}

function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

function parseOptions(argv) {
    const options = {};
    for (let index = 0; index < argv.length; index += 1) {
        const key = argv[index];
        if (!key.startsWith("--")) throw new Error(`알 수 없는 인자: ${key}`);
        const value = argv[index + 1];
        if (!value || value.startsWith("--")) throw new Error(`${key} 값이 필요합니다.`);
        options[key.slice(2)] = value;
        index += 1;
    }
    for (const key of ["input-sql", "xml-directory", "out"]) {
        if (!options[key]) throw new Error(`--${key}가 필요합니다.`);
    }
    return options;
}

if (import.meta.url === `file://${process.argv[1]}`) {
    try {
        const options = parseOptions(process.argv.slice(2));
        const result = await correctGameNames({
            inputSql: options["input-sql"],
            candidatePaths: options["candidate-csv"] ? [options["candidate-csv"]] : [],
            candidateDirectory: options["candidate-directory"],
            xmlDirectory: options["xml-directory"],
            xmlManifest: options["xml-manifest"],
            out: options.out,
        });
        console.log(JSON.stringify({
            outputSql: result.outputSql,
            reportPath: result.reportPath,
            corrections: result.report.corrections.rows,
        }, null, 2));
    } catch (error) {
        console.error(error instanceof Error ? error.message : error);
        process.exitCode = 1;
    }
}
