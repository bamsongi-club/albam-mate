import { isAbsolute } from 'node:path';

import {
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
        'schemaVersion', 'kind', 'approved', 'testOnly', 'approval', 'datasetRelease', 'approvedFields',
        'approvedProcessingScopes', 'sources', 'outputs', 'searchTextTemplate',
    ], 'poc search text execution manifest');
    assertEqual(manifest.schemaVersion, 1, 'schemaVersion must be 1');
    assertEqual(manifest.kind, POC_SEARCH_TEXT_MANIFEST_KIND, `kind must be ${POC_SEARCH_TEXT_MANIFEST_KIND}`);
    if (manifest.approved !== true) throw new Error('poc search text execution manifest must be approved');
    if (manifest.testOnly !== false) throw new Error('poc search text execution manifest testOnly must be false');
    if (datasetManifest === undefined) throw new Error('datasetManifest is required to validate the datasetRelease reference');

    validateApproval(manifest.approval);
    validateCatalogDatasetReleaseReference(manifest.datasetRelease, datasetManifest, actualManifestSha256);
    validateApprovedFields(manifest.approvedFields, datasetManifest);
    validateScopes(manifest.approvedProcessingScopes);
    validateSources(manifest.sources, actualCorpusSha256);
    validateOutputs(manifest.outputs);
    assertEqual(manifest.searchTextTemplate, SEARCH_TEXT_TEMPLATE, 'searchTextTemplate does not match the approved search_text template');
    return manifest;
}

function validateApproval(approval) {
    assertObject(approval, 'approval');
    assertAllowedKeys(approval, ['reviewedBy', 'reviewedAt', 'references'], 'approval');
    assertString(approval.reviewedBy, 'approval.reviewedBy');
    assertInstant(approval.reviewedAt, 'approval.reviewedAt');
    if (!Array.isArray(approval.references) || approval.references.length === 0) {
        throw new Error('approval.references must contain at least one reference');
    }
    for (const reference of approval.references) {
        assertString(reference, 'approval reference');
        try {
            new URL(reference);
        } catch {
            throw new Error(`approval reference must be a URL: ${reference}`);
        }
    }
}

function validateApprovedFields(fields, datasetManifest) {
    if (!Array.isArray(fields) || fields.length === 0) throw new Error('approvedFields must contain at least one field');
    for (const field of fields) {
        assertString(field, 'approvedFields item');
        const datasetField = DATASET_FIELD_BY_POC_FIELD[field];
        if (!datasetField || !SEARCH_TEXT_FIELD_ORDER.includes(field)) {
            throw new Error(`approvedFields field does not map to an approved release field: ${field}`);
        }
        if (!datasetManifest.approvedFields?.includes(datasetField)) {
            throw new Error(`approvedFields field is not approved by dataset release: ${field}`);
        }
        const provenance = datasetManifest.fieldProvenance?.[datasetField];
        if (provenance?.public !== true) {
            throw new Error(`fieldProvenance.${datasetField}.public must be true`);
        }
        if (provenance.processingAllowed !== true) {
            throw new Error(`fieldProvenance.${datasetField}.processingAllowed must be true`);
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

function validateSources(sources, actualCorpusSha256) {
    assertObject(sources, 'sources');
    assertAllowedKeys(sources, ['corpus'], 'sources');
    validateDescriptor(sources.corpus, 'sources.corpus');
    if (sources.corpus.rows !== 1000) throw new Error('sources.corpus.rows must be 1000');
    if (actualCorpusSha256 !== undefined && sources.corpus.sha256 !== actualCorpusSha256) {
        throw new Error('sources.corpus.sha256 does not match the referenced corpus file');
    }
}

function validateOutputs(outputs) {
    assertObject(outputs, 'outputs');
    assertAllowedKeys(outputs, ['searchText'], 'outputs');
    validateDescriptor(outputs.searchText, 'outputs.searchText');
    if (outputs.searchText.rows !== 1000) throw new Error('outputs.searchText.rows must be 1000');
}

function validateDescriptor(descriptor, field) {
    assertObject(descriptor, field);
    assertAllowedKeys(descriptor, ['path', 'sha256', 'rows'], field);
    assertRelativePath(descriptor.path, `${field}.path`);
    assertSha256(descriptor.sha256, `${field}.sha256`);
    if (!Number.isSafeInteger(descriptor.rows) || descriptor.rows <= 0) {
        throw new Error(`${field}.rows must be a positive safe integer`);
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
function assertInstant(value, field) {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u.test(value ?? '') || Number.isNaN(Date.parse(value))) {
        throw new Error(`${field} must be a UTC instant`);
    }
}

function assertRelativePath(value, field) {
    assertString(value, field);
    if (isAbsolute(value) || WINDOWS_ABSOLUTE_PATH_PATTERN.test(value) || value.split(/[\\/]/u).some((segment) => segment === '..' || segment === '')) {
        throw new Error(`${field} must be a safe relative path`);
    }
}

const DATASET_FIELD_BY_POC_FIELD = Object.freeze({
    name: 'name',
    englishName: 'english_name',
    description: 'description',
    detailDescription: 'detail_description',
});
