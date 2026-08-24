#!/usr/bin/env node

// PostgreSQL INSERT SQL을 실행하지 않고 games 행과 설명 품질을 측정한다.
// 문자열 안의 comma·괄호·개행은 값의 일부로 보존하고, 여러 INSERT statement를 순서대로 읽는다.

import { createHash } from "node:crypto";
import { createReadStream, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";

import {
    classifyDescription,
    DESCRIPTION_FIELDS,
    DESCRIPTION_STATES,
} from "./description-quality.mjs";

const INSERT_HEADER = /INSERT\s+INTO\s+(?:(?:[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*)?games\s*\(([\s\S]*?)\)\s*VALUES\s*/iu;
const SEARCH_TAIL_LENGTH = 4096;
const NEED_MORE = Symbol("need-more");

export async function analyzeGameCatalogSqlFile(sqlPath) {
    const parser = new GameCatalogSqlParser();
    const digest = createHash("sha256");
    let bytes = 0;

    for await (const chunk of createReadStream(sqlPath, { encoding: "utf8" })) {
        digest.update(chunk);
        bytes += Buffer.byteLength(chunk, "utf8");
        parser.push(chunk);
    }

    return parser.finish({
        fileName: basename(sqlPath),
        sha256: digest.digest("hex"),
        bytes,
    });
}

export async function assertGameCatalogFileBggIdsExactlyOnce(sqlPath, bggIds) {
    const parser = new GameCatalogSqlParser(bggIds);
    for await (const chunk of createReadStream(sqlPath, { encoding: "utf8" })) {
        parser.push(chunk);
    }
    parser.finish({ fileName: basename(sqlPath), sha256: null, bytes: null });
    parser.assertTrackedBggIdsExactlyOnce();
}

export function assertGameCatalogBggIdsExactlyOnce(sql, bggIds) {
    const parser = new GameCatalogSqlParser(bggIds);
    parser.push(sql);
    parser.finish({ fileName: "inline.sql", sha256: null, bytes: Buffer.byteLength(sql) });
    parser.assertTrackedBggIdsExactlyOnce();
}

export function parseGameCatalogSqlText(sql) {
    const parser = new GameCatalogSqlParser();
    parser.push(sql);
    return parser.finish({ fileName: "inline.sql", sha256: null, bytes: Buffer.byteLength(sql) });
}

export function parseGameCatalogSqlChunks(chunks) {
    const parser = new GameCatalogSqlParser();
    for (const chunk of chunks) {
        parser.push(chunk);
    }
    return parser.finish({ fileName: "chunks.sql", sha256: null, bytes: null });
}

class GameCatalogSqlParser {
    constructor(trackedBggIds = []) {
        this.buffer = "";
        this.mode = "search";
        this.columns = null;
        this.afterRow = false;
        this.insertStatements = 0;
        this.allColumns = new Set();
        this.rowCount = 0;
        this.bggIds = new Set();
        this.duplicateBggIds = new Set();
        this.trackedBggIdCounts = new Map(validateTrackedBggIds(trackedBggIds).map((bggId) => [bggId, 0]));
        this.maxBggId = null;
        this.fields = Object.fromEntries(
            DESCRIPTION_FIELDS.map((field) => [field, emptyFieldSummary()]),
        );
        this.affectedRows = {
            mixed: new Set(),
            untranslated: new Set(),
            missing: new Set(),
        };
        this.bothFieldsKorean = 0;
    }

    push(chunk) {
        if (typeof chunk !== "string") {
            throw new TypeError("SQL parser chunk must be a string");
        }
        this.buffer += chunk;
        this.drain(false);
    }

    finish(source) {
        this.drain(true);
        if (this.mode === "values") {
            throw new Error("games INSERT statement가 끝나기 전에 SQL 입력이 끝났다");
        }
        if (this.insertStatements === 0) {
            throw new Error("games INSERT statement를 찾지 못했다");
        }
        return {
            schemaVersion: 1,
            parserVersion: "game-catalog-sql-parser-v1",
            source,
            insertStatements: this.insertStatements,
            rows: this.rowCount,
            uniqueBggIds: this.bggIds.size,
            duplicateBggIdRows: this.duplicateBggIds.size,
            maxBggId: this.maxBggId,
            columns: [...this.allColumns],
            bothFieldsKoreanRows: this.bothFieldsKorean,
            descriptionQuality: {
                classifierVersion: "description-language-v2",
                totalRows: this.rowCount,
                fields: this.fields,
                rowCounts: {
                    mixed: this.affectedRows.mixed.size,
                    untranslated: this.affectedRows.untranslated.size,
                    missing: this.affectedRows.missing.size,
                },
            },
        };
    }

    assertTrackedBggIdsExactlyOnce() {
        for (const [bggId, count] of this.trackedBggIdCounts) {
            if (count !== 1) {
                throw new Error(`bgg_id ${bggId}가 입력 games INSERT에 정확히 한 번 존재하지 않습니다: ${count}건`);
            }
        }
    }

    drain(final) {
        while (true) {
            if (this.mode === "search") {
                const match = INSERT_HEADER.exec(this.buffer);
                if (!match) {
                    if (final) {
                        this.buffer = "";
                        return;
                    }
                    this.buffer = this.buffer.slice(-SEARCH_TAIL_LENGTH);
                    return;
                }
                this.columns = parseColumns(match[1]);
                for (const column of this.columns) {
                    this.allColumns.add(column);
                }
                this.buffer = this.buffer.slice(match.index + match[0].length);
                this.mode = "values";
                this.afterRow = false;
                continue;
            }

            const result = this.consumeValues(final);
            if (result === NEED_MORE) {
                return;
            }
        }
    }

    consumeValues(final) {
        let cursor = 0;
        while (true) {
            cursor = skipWhitespace(this.buffer, cursor);
            if (cursor >= this.buffer.length) {
                this.buffer = this.buffer.slice(cursor);
                return NEED_MORE;
            }

            if (!this.afterRow) {
                if (this.buffer[cursor] !== "(") {
                    throw new Error(`INSERT VALUES 행이 '('로 시작하지 않는다: ${this.buffer.slice(cursor, cursor + 40)}`);
                }
                const rowStart = cursor;
                const parsedRow = parseRow(this.buffer, cursor, this.columns);
                if (parsedRow === NEED_MORE) {
                    this.buffer = this.buffer.slice(rowStart);
                    return NEED_MORE;
                }
                this.addRow(parsedRow.values, this.columns);
                cursor = parsedRow.nextPosition;
                this.afterRow = true;
                continue;
            }

            if (this.buffer[cursor] === ",") {
                cursor += 1;
                this.afterRow = false;
                continue;
            }

            if (this.buffer[cursor] === ";") {
                cursor += 1;
                this.insertStatements += 1;
                this.mode = "search";
                this.afterRow = false;
                this.buffer = this.buffer.slice(cursor);
                return true;
            }

            const tail = this.buffer.slice(cursor);
            if (/^ON\s+CONFLICT\b/iu.test(tail)) {
                const semicolon = tail.indexOf(";");
                if (semicolon < 0) {
                    if (!final) {
                        this.buffer = tail;
                        return NEED_MORE;
                    }
                    this.insertStatements += 1;
                    this.mode = "search";
                    this.afterRow = false;
                    this.buffer = "";
                    return true;
                }
                this.insertStatements += 1;
                this.mode = "search";
                this.afterRow = false;
                this.buffer = tail.slice(semicolon + 1);
                return true;
            }

            if (!final && isPartialOnConflict(tail)) {
                this.buffer = tail;
                return NEED_MORE;
            }

            if (final) {
                this.insertStatements += 1;
                this.mode = "search";
                this.afterRow = false;
                this.buffer = "";
                return true;
            }

            throw new Error(`INSERT VALUES 행 뒤의 구분자를 해석하지 못했다: ${tail.slice(0, 40)}`);
        }
    }

    addRow(values, columns) {
        const row = Object.fromEntries(columns.map((column, index) => [column, values[index]]));
        const rawBggId = row.bgg_id;
        const bggId = rawBggId === null ? null : Number(rawBggId);
        if (!Number.isSafeInteger(bggId) || bggId <= 0) {
            throw new Error(`bgg_id를 양의 정수로 해석하지 못했다: ${String(rawBggId)}`);
        }
        this.rowCount += 1;
        if (this.bggIds.has(bggId)) {
            this.duplicateBggIds.add(bggId);
        }
        this.bggIds.add(bggId);
        if (this.trackedBggIdCounts.has(bggId)) {
            this.trackedBggIdCounts.set(bggId, this.trackedBggIdCounts.get(bggId) + 1);
        }
        this.maxBggId = this.maxBggId === null ? bggId : Math.max(this.maxBggId, bggId);

        const states = {};
        for (const field of DESCRIPTION_FIELDS) {
            const state = classifyDescription(row[field]);
            states[field] = state;
            const summary = this.fields[field];
            summary.counts[state] += 1;
            if (summary.samples[state].length < 10) {
                summary.samples[state].push({
                    bgg_id: bggId,
                    sample: String(row[field] ?? "").slice(0, 300),
                });
            }
            const identifier = bggId;
            if (state === "mixed") {
                this.affectedRows.mixed.add(identifier);
            }
            if (state === "english" || state === "other") {
                this.affectedRows.untranslated.add(identifier);
            }
            if (state === "missing") {
                this.affectedRows.missing.add(identifier);
            }
        }
        if (states.description === "korean" && states.detail_description === "korean") {
            this.bothFieldsKorean += 1;
        }
    }
}

function validateTrackedBggIds(bggIds) {
    if (!Array.isArray(bggIds) && !(bggIds instanceof Set)) {
        throw new TypeError("검증할 bgg_id는 배열 또는 Set이어야 한다");
    }
    const ids = [...bggIds];
    if (new Set(ids).size !== ids.length || ids.some((bggId) => !Number.isSafeInteger(bggId) || bggId <= 0)) {
        throw new Error("검증할 bgg_id가 유효하지 않습니다");
    }
    return ids;
}

function isPartialOnConflict(value) {
    const prefix = value.replace(/\s+/gu, " ").trimEnd().toLocaleUpperCase("en-US");
    return prefix.length > 0 && "ON CONFLICT".startsWith(prefix);
}

function parseColumns(value) {
    const columns = value
        .split(",")
        .map((column) => column.trim().replace(/^(["`])(.+)\1$/u, "$2").toLowerCase());
    if (columns.some((column) => column === "")) {
        throw new Error("games INSERT column list에 빈 열 이름이 있다");
    }
    if (new Set(columns).size !== columns.length) {
        throw new Error("games INSERT column list에 중복 열 이름이 있다");
    }
    for (const required of ["bgg_id", ...DESCRIPTION_FIELDS]) {
        if (!columns.includes(required)) {
            throw new Error(`games INSERT에 ${required} 열이 없다`);
        }
    }
    return columns;
}

function parseRow(sql, start, columns) {
    let cursor = start + 1;
    const values = [];
    while (true) {
        cursor = skipWhitespace(sql, cursor);
        const parsedValue = parseValue(sql, cursor);
        if (parsedValue === NEED_MORE) {
            return NEED_MORE;
        }
        values.push(parsedValue.value);
        cursor = skipWhitespace(sql, parsedValue.nextPosition);
        if (cursor >= sql.length) {
            return NEED_MORE;
        }
        if (sql[cursor] === ",") {
            cursor += 1;
            continue;
        }
        if (sql[cursor] !== ")") {
            throw new Error(`INSERT 행 값 뒤의 구분자를 해석하지 못했다: ${sql.slice(cursor, cursor + 40)}`);
        }
        if (values.length !== columns.length) {
            throw new Error(
                `INSERT 행 열 수가 일치하지 않는다: expected=${columns.length}, actual=${values.length}`,
            );
        }
        return { values, nextPosition: cursor + 1 };
    }
}

function parseValue(sql, start) {
    if (start >= sql.length) {
        return NEED_MORE;
    }
    const isExtendedString = (sql[start] === "E" || sql[start] === "e") && sql[start + 1] === "'";
    const quoteStart = isExtendedString ? start + 1 : start;
    if (sql[quoteStart] === "'") {
        return parseStringValue(sql, quoteStart, isExtendedString);
    }

    let cursor = start;
    let nestedParentheses = 0;
    while (cursor < sql.length) {
        const character = sql[cursor];
        if (character === "(") {
            nestedParentheses += 1;
        } else if (character === ")") {
            if (nestedParentheses === 0) {
                break;
            }
            nestedParentheses -= 1;
        } else if (character === "," && nestedParentheses === 0) {
            break;
        }
        cursor += 1;
    }
    if (cursor >= sql.length) {
        return NEED_MORE;
    }
    const raw = sql.slice(start, cursor).trim();
    return { value: raw.toUpperCase() === "NULL" ? null : raw, nextPosition: cursor };
}

function parseStringValue(sql, quoteStart, extended) {
    if (!extended) {
        return parseStandardStringValue(sql, quoteStart);
    }

    let cursor = quoteStart + 1;
    let value = "";
    while (cursor < sql.length) {
        const character = sql[cursor];
        if (character === "'") {
            if (sql[cursor + 1] === "'") {
                value += "'";
                cursor += 2;
                continue;
            }
            return { value, nextPosition: cursor + 1 };
        }
        if (extended && character === "\\") {
            if (cursor + 1 >= sql.length) {
                return NEED_MORE;
            }
            const escaped = sql[cursor + 1];
            value += {
                n: "\n",
                r: "\r",
                t: "\t",
                b: "\b",
                f: "\f",
                v: "\v",
            }[escaped] ?? escaped;
            cursor += 2;
            continue;
        }
        value += character;
        cursor += 1;
    }
    return NEED_MORE;
}

function parseStandardStringValue(sql, quoteStart) {
    let cursor = quoteStart + 1;
    let segmentStart = cursor;
    const segments = [];
    while (true) {
        const quote = sql.indexOf("'", cursor);
        if (quote < 0) {
            return NEED_MORE;
        }
        segments.push(sql.slice(segmentStart, quote));
        if (sql[quote + 1] === "'") {
            segments.push("'");
            cursor = quote + 2;
            segmentStart = cursor;
            continue;
        }
        return { value: segments.join(""), nextPosition: quote + 1 };
    }
}

function skipWhitespace(value, start) {
    let cursor = start;
    while (cursor < value.length && /\s/u.test(value[cursor])) {
        cursor += 1;
    }
    return cursor;
}

function emptyFieldSummary() {
    return {
        counts: Object.fromEntries(DESCRIPTION_STATES.map((state) => [state, 0])),
        samples: Object.fromEntries(DESCRIPTION_STATES.map((state) => [state, []])),
    };
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
    if (!values.sql || !values.out) {
        failUsage();
    }
    return { sql: resolve(values.sql), out: resolve(values.out) };
}

function failUsage() {
    process.stderr.write(
        "usage: node parse-game-catalog-sql.mjs --sql <path> --out <json>\n",
    );
    process.exit(2);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split("/").pop())) {
    const options = parseOptions(process.argv.slice(2));
    analyzeGameCatalogSqlFile(options.sql)
        .then((report) => {
            writeFileSync(options.out, `${JSON.stringify(report, null, 2)}\n`, "utf8");
            process.stdout.write(`${report.rows}행, ${report.insertStatements}개 INSERT를 측정했습니다.\n`);
        })
        .catch((error) => {
            process.stderr.write(`${error.message}\n`);
            process.exitCode = 1;
        });
}
