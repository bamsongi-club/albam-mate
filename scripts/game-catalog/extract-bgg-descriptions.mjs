#!/usr/bin/env node

// BGG XML 스냅샷에서 게임별 영문 원문(description)을 그대로 뽑아 JSON으로 만든다.
//
//   node extract-bgg-descriptions.mjs --xml-dir <dir> --out <json> [--ids <txt>] [--limit N]
//
// games 테이블의 description·detail_description은 단어 치환으로 손상돼 있어
// (`Once Upon A Time is a game in which the 플레이어 ...`) 번역 입력으로 쓸 수 없다.
// 적재 전 원본인 이 XML이 유일한 무손상 출처다.

import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ITEM_PATTERN = /<item\b[^>]*\bid="(\d+)"[^>]*>([\s\S]*?)<\/item>/g;
const DESCRIPTION_PATTERN = /<description>([\s\S]*?)<\/description>/;
const PRIMARY_NAME_PATTERN = /<name\b[^>]*\btype="primary"[^>]*\bvalue="([^"]*)"/;

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
    const options = parseOptions(process.argv.slice(2));
    const wanted = options.ids ? new Set(readIds(options.ids)) : null;
    const extracted = [];
    const seen = new Set();

    for (const fileName of readdirSync(options.xmlDir).sort()) {
        if (!fileName.endsWith('.xml')) continue;
        if (options.limit && extracted.length >= options.limit) break;

        const xml = readFileSync(resolve(options.xmlDir, fileName), 'utf8');
        for (const [, id, body] of xml.matchAll(ITEM_PATTERN)) {
            const bggId = Number(id);
            if (wanted && !wanted.has(bggId)) continue;
            if (seen.has(bggId)) continue;

            const description = decodeEntities(body.match(DESCRIPTION_PATTERN)?.[1] ?? '').trim();
            if (description === '') continue;

            seen.add(bggId);
            extracted.push({
                bgg_id: bggId,
                english_name: decodeEntities(body.match(PRIMARY_NAME_PATTERN)?.[1] ?? '').trim(),
                source: description,
            });
            if (options.limit && extracted.length >= options.limit) break;
        }
    }

    extracted.sort((left, right) => left.bgg_id - right.bgg_id);
    writeFileSync(options.out, `${JSON.stringify(extracted, null, 1)}\n`, 'utf8');

    const missing = wanted ? [...wanted].filter((id) => !seen.has(id)) : [];
    process.stderr.write(`${extracted.length}건 추출 -> ${options.out}\n`);
    if (missing.length > 0) {
        process.stderr.write(`XML에 없어 제외한 ${missing.length}건: ${missing.slice(0, 10).join(', ')}\n`);
    }
}

// BGG XML은 본문을 이스케이프해 담는다. 숫자 참조와 이름 참조를 모두 되돌리되,
// 한 번의 순회만 수행해 이중 이스케이프를 다시 디코드하지 않는다.
const ENTITY_PATTERN = /&(?:#(\d+)|#x([0-9a-f]+)|([a-z][a-z0-9]+));/gi;
const NAMED_ENTITIES = Object.freeze({
    amp: '&',
    apos: "'",
    bull: '•',
    copy: '©',
    deg: '°',
    divide: '÷',
    hellip: '…',
    laquo: '«',
    ldquo: '“',
    le: '≤',
    lsquo: '‘',
    mdash: '—',
    micro: 'µ',
    middot: '·',
    nbsp: '\u00a0',
    ndash: '–',
    ne: '≠',
    plusmn: '±',
    quot: '"',
    raquo: '»',
    rdquo: '”',
    reg: '®',
    rsquo: '’',
    times: '×',
    trade: '™',
    gt: '>',
    ge: '≥',
    lt: '<',
});

export function decodeEntities(value) {
    return value.replace(ENTITY_PATTERN, (entity, decimal, hexadecimal, name) => {
        if (decimal !== undefined) {
            return decodeCodePoint(entity, Number(decimal));
        }
        if (hexadecimal !== undefined) {
            return decodeCodePoint(entity, Number.parseInt(hexadecimal, 16));
        }
        return NAMED_ENTITIES[name.toLowerCase()] ?? entity;
    });
}

function decodeCodePoint(entity, codePoint) {
    if (!Number.isInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff) {
        return entity;
    }
    try {
        return String.fromCodePoint(codePoint);
    } catch {
        return entity;
    }
}

function readIds(path) {
    return readFileSync(path, 'utf8')
        .split('\n')
        .map((line) => Number(line.trim()))
        .filter((id) => Number.isSafeInteger(id) && id > 0);
}

function parseOptions(args) {
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        const value = args[index + 1];
        if (!key?.startsWith('--') || !value) failUsage();
        values[key.slice(2)] = value;
    }
    if (!values['xml-dir'] || !values.out) failUsage();
    return {
        xmlDir: resolve(values['xml-dir']),
        out: resolve(values.out),
        ids: values.ids ? resolve(values.ids) : null,
        limit: values.limit ? Number(values.limit) : null,
    };
}

function failUsage() {
    process.stderr.write(
        'usage: node extract-bgg-descriptions.mjs --xml-dir <dir> --out <json> [--ids <txt>] [--limit N]\n',
    );
    process.exit(2);
}
