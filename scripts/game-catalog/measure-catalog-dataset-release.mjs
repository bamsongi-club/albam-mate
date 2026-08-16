#!/usr/bin/env node

import { readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';

import { validateCatalogDatasetReleaseManifest } from './catalog-dataset-release-manifest.mjs';
import {
    measureCatalogDatasetCoverage,
    resolveArtifactPaths,
    sha256,
} from './catalog-dataset-release-measurement.mjs';

const options = parseOptions(process.argv.slice(2));
const manifest = JSON.parse(readFileSync(options.manifest, 'utf8'));
validateCatalogDatasetReleaseManifest(manifest);
const datasetContents = readFileSync(options.dataset);
const datasetRows = JSON.parse(datasetContents);
if (!Array.isArray(datasetRows)) {
    throw new Error('dataset must be a JSON array');
}
const ids = datasetRows.map((row) => {
    const value = row.bgg_id ?? row.bggId;
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new Error('dataset row must contain a positive integer bgg_id/bggId');
    }
    return value;
});
if (new Set(ids).size !== ids.length) {
    throw new Error('dataset contains duplicate bgg_id values');
}
const canonicalIds = [...ids].sort((left, right) => left - right).join('\n') + '\n';
const actualDataset = {
    rows: datasetRows.length,
    sha256: sha256(datasetContents),
    idSetSha256: sha256(Buffer.from(canonicalIds, 'utf8')),
};
const artifactPaths = resolveArtifactPaths(manifest.artifacts, options.artifactsRoot);
const artifactContents = Object.fromEntries(
    Object.entries(artifactPaths).map(([key, artifactPath]) => [key, readFileSync(artifactPath)]),
);
const actualArtifacts = Object.fromEntries(
    Object.entries(artifactContents).map(([key, contents]) => {
        const artifactPath = artifactPaths[key];
        return [key, {
            sha256: sha256(contents),
            bytes: statSync(artifactPath).size,
        }];
    }),
);
const actualCoverage = measureCatalogDatasetCoverage({
    datasetIds: ids,
    mechanismSql: artifactContents['02'],
    metadataSql: artifactContents['03'],
});

validateCatalogDatasetReleaseManifest(manifest, { actualDataset, actualArtifacts, actualCoverage });
process.stdout.write(`${JSON.stringify({ actualDataset, actualArtifacts, actualCoverage }, null, 2)}\n`);

function parseOptions(args) {
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        const value = args[index + 1];
        if (!key?.startsWith('--') || !value) failUsage();
        values[key.slice(2)] = value;
    }
    if (!values.manifest || !values.dataset || !values['artifacts-root']) failUsage();
    return {
        manifest: resolve(values.manifest),
        dataset: resolve(values.dataset),
        artifactsRoot: resolve(values['artifacts-root']),
    };
}

function failUsage() {
    process.stderr.write(
        'usage: node measure-catalog-dataset-release.mjs --manifest <json> --dataset <json> --artifacts-root <dir>\n',
    );
    process.exit(2);
}
