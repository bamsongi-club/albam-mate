import { isAbsolute } from 'node:path';

import {
    FIELD_PROFILES,
    validateCatalogDatasetReleaseReference,
} from './catalog-dataset-release-manifest.mjs';

export const POC_SEARCH_TEXT_MANIFEST_KIND = 'poc-search-text-execution';
export const PROCESSING_SCOPE_SEARCH_TEXT_ASSEMBLY = 'search-text-assembly';
export const SEARCH_TEXT_TEMPLATE = '게임명: {name}\n영문명: {englishName}\n메커니즘: {mechanisms}\n카테고리: {categories}\n테마: {themes}\n설명: {description}';
export const SEARCH_TEXT_FIELD_ORDER = Object.freeze([
    'name', 'englishName', 'alias', 'mechanism', 'category', 'theme', 'description', 'detailDescription',
]);

const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const WINDOWS_ABSOLUTE_PATH_PATTERN = /^[a-zA-Z]:[\\/]/u;

export function validatePocSearchTextManifest(
    manifest,
    { datasetManifest, actualManifestSha256, actualCorpusSha256 } = {},
) {
    assertObject(manifest, 'poc search text execution manifest');
    assertAllowedKeys(manifest, [
        'schemaVersion', 'kind', 'approved', 'testOnly', 'datasetRelease', 'approvedFields',
        'approvedProcessingScopes', 'corpus', 'searchTextTemplate',
    ], 'poc search text execution manifest');
    assertEqual(manifest.schemaVersion, 1, 'schemaVersion must be 1');
    assertEqual(manifest.kind, POC_SEARCH_TEXT_MANIFEST_KIND, `kind must be ${POC_SEARCH_TEXT_MANIFEST_KIND}`);
    if (manifest.approved !== true) throw new Error('poc search text execution manifest must be approved');
    if (manifest.testOnly !== false) throw new Error('poc search text execution manifest testOnly must be false');
    if (datasetManifest === undefined) throw new Error('datasetManifest is required to validate the datasetRelease reference');

    validateCatalogDatasetReleaseReference(manifest.datasetRelease, datasetManifest, actualManifestSha256);
    validateApprovedFields(manifest.approvedFields);
    validateScopes(manifest.approvedProcessingScopes);
    validateCorpus(manifest.corpus, actualCorpusSha256);
    assertEqual(manifest.searchTextTemplate, SEARCH_TEXT_TEMPLATE, 'searchTextTemplate does not match the approved search_text template');
    return manifest;
}

function validateApprovedFields(fields) {
    if (!Array.isArray(fields) || fields.length === 0) throw new Error('approvedFields must contain at least one field');
    for (const field of fields) {
        assertString(field, 'approvedFields item');
        if (!(field in FIELD_PROFILES) || !SEARCH_TEXT_FIELD_ORDER.includes(field)) {
            throw new Error(`approvedFields contains a field outside the trusted profile: ${field}`);
        }
    }
    if (new Set(fields).size !== fields.length) throw new Error('approvedFields must not contain duplicates');
}

function validateScopes(scopes) {
    if (!Array.isArray(scopes) || scopes.length === 0 || !scopes.includes(PROCESSING_SCOPE_SEARCH_TEXT_ASSEMBLY)) {
        throw new Error(`approvedProcessingScopes must include ${PROCESSING_SCOPE_SEARCH_TEXT_ASSEMBLY}`);
    }
    if (new Set(scopes).size !== scopes.length || scopes.some((scope) => typeof scope !== 'string' || scope.trim() === '')) {
        throw new Error('approvedProcessingScopes must be unique non-empty strings');
    }
}

function validateCorpus(corpus, actualCorpusSha256) {
    assertObject(corpus, 'corpus');
    assertAllowedKeys(corpus, ['path', 'sha256'], 'corpus');
    assertRelativePath(corpus.path, 'corpus.path');
    assertSha256(corpus.sha256, 'corpus.sha256');
    if (actualCorpusSha256 !== undefined && corpus.sha256 !== actualCorpusSha256) {
        throw new Error('corpus.sha256 does not match the referenced corpus file');
    }
}

function assertAllowedKeys(value, allowedKeys, field) {
    const unknown = Object.keys(value).filter((key) => !allowedKeys.includes(key));
    if (unknown.length > 0) throw new Error(`${field} contains unsupported fields: ${unknown.join(', ')}`);
}

function assertObject(value, field) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${field} must be an object`);
}

function assertEqual(actual, expected, message) { if (actual !== expected) throw new Error(message); }
function assertString(value, field) { if (typeof value !== 'string' || value.trim() === '') throw new Error(`${field} must be a non-empty string`); }
function assertSha256(value, field) { if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) throw new Error(`${field} must be a lowercase SHA-256 hex string`); }

function assertRelativePath(value, field) {
    assertString(value, field);
    if (isAbsolute(value) || WINDOWS_ABSOLUTE_PATH_PATTERN.test(value) || value.split(/[\\/]/u).some((segment) => segment === '..' || segment === '')) {
        throw new Error(`${field} must be a safe relative path`);
    }
}
