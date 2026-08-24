#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

import {
    CATALOG_DATASET_ROWS,
    CATALOG_SOURCE_MANIFEST_SHA256,
} from './catalog-dataset-release-manifest.mjs';

export function sha256(contents) {
    return createHash('sha256').update(contents).digest('hex');
}

export function assertManifestSha256(manifestContents, expectedSha256) {
    const actual = sha256(manifestContents);
    if (actual !== expectedSha256) {
        throw new Error(`source manifest sha256 mismatch: expected ${expectedSha256}, got ${actual}`);
    }
    return actual;
}

export function collectDatasetIds(files) {
    if (!Array.isArray(files) || files.length === 0) {
        throw new Error('source manifest must contain a non-empty files array');
    }
    const uniqueIds = new Set();
    files.forEach((batch, index) => {
        const label = `batch ${index} (${batch && typeof batch === 'object' ? batch.file ?? 'unknown' : 'unknown'})`;
        if (!batch || typeof batch !== 'object') {
            throw new Error(`${label} must be an object`);
        }
        if (batch.httpStatus !== 200) {
            throw new Error(`${label} httpStatus is not 200`);
        }
        if (!Array.isArray(batch.requestIds) || !Array.isArray(batch.responseIds)) {
            throw new Error(`${label} requestIds/responseIds must be arrays`);
        }
        const requestSorted = [...batch.requestIds].sort(compareNumbers);
        const responseSorted = [...batch.responseIds].sort(compareNumbers);
        if (
            requestSorted.length !== responseSorted.length
            || requestSorted.some((id, position) => id !== responseSorted[position])
        ) {
            throw new Error(`${label} requestIds/responseIds elements do not match`);
        }
        for (const id of batch.responseIds) {
            if (!Number.isSafeInteger(id) || id <= 0) {
                throw new Error(`${label} contains an invalid bgg id: ${id}`);
            }
            uniqueIds.add(id);
        }
    });
    return uniqueIds;
}

export function buildCanonicalDataset(uniqueIds, expectedRows) {
    if (uniqueIds.size !== expectedRows) {
        throw new Error(`unique response id count must be ${expectedRows}, got ${uniqueIds.size}`);
    }
    const sortedIds = [...uniqueIds].sort(compareNumbers);
    const rows = sortedIds.map((bggId) => ({ bgg_id: bggId }));
    const datasetText = `${JSON.stringify(rows, null, 2)}\n`;
    const idSetText = `${sortedIds.join('\n')}\n`;
    return {
        rows: rows.length,
        datasetText,
        sha256: sha256(Buffer.from(datasetText, 'utf8')),
        idSetSha256: sha256(Buffer.from(idSetText, 'utf8')),
    };
}

export function extractCatalogDatasetIds(manifestContents, {
    expectedManifestSha256 = CATALOG_SOURCE_MANIFEST_SHA256,
    expectedRows = CATALOG_DATASET_ROWS,
} = {}) {
    assertManifestSha256(manifestContents, expectedManifestSha256);
    const manifest = JSON.parse(manifestContents.toString('utf8'));
    if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
        throw new Error('source manifest must be a JSON object');
    }
    const uniqueIds = collectDatasetIds(manifest.files);
    return buildCanonicalDataset(uniqueIds, expectedRows);
}

function compareNumbers(left, right) {
    return left - right;
}

function parseOptions(args) {
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        const value = args[index + 1];
        if (!key?.startsWith('--') || !value) failUsage();
        values[key.slice(2)] = value;
    }
    if (!values.manifest || !values.out) failUsage();
    return {
        manifest: resolve(values.manifest),
        out: resolve(values.out),
    };
}

function failUsage() {
    process.stderr.write(
        'usage: node extract-catalog-dataset-ids.mjs --manifest <raw-xml-manifest.json> --out <canonical.json>\n',
    );
    process.exit(2);
}

function main() {
    let options;
    try {
        options = parseOptions(process.argv.slice(2));
        const manifestContents = readFileSync(options.manifest);
        const result = extractCatalogDatasetIds(manifestContents);
        writeFileSync(options.out, result.datasetText, 'utf8');
        process.stdout.write(
            `${JSON.stringify({ rows: result.rows, sha256: result.sha256, idSetSha256: result.idSetSha256 }, null, 2)}\n`,
        );
    } catch (error) {
        process.stderr.write(`${error.message}\n`);
        process.exit(1);
    }
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
    main();
}
