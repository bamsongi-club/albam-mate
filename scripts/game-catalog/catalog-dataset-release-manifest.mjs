import { isAbsolute } from 'node:path';

export const CATALOG_DATASET_RELEASE_KIND = 'catalog-dataset-release';
export const CATALOG_DATASET_ID = 'bgg-catalog-170k';
export const CATALOG_DATASET_ROWS = 170000;
export const CATALOG_FIELD_VERSION = 'catalog-fields-v1';
export const CATALOG_SOURCE_BATCH_ID = 'bgg-xml-basic-170k-2026-08-10';
export const CATALOG_SOURCE_MANIFEST_SHA256 =
    'b7aa4731c5480a434b915921cb8f7f6d6a616a007b87239bff0452b80764f524';

export const REQUIRED_ARTIFACTS = [
    '01',
    '01b',
    '02',
    '03',
    '04',
    '05',
    '06',
    '07',
];

export const ARTIFACT_BASENAMES = Object.freeze({
    '01': '01-upsert-games-chunked.sql',
    '01b': '01b-restore-boardgameexpansions.sql',
    '02': '02-upsert-game-mechanisms.sql',
    '03': '03-upsert-game-metadata.sql',
    '04': '04-upsert-korean-names-supplement.sql',
    '05': '05-upsert-korean-descriptions-supplement.sql',
    '06': '06-upsert-boardlife-new-games.sql',
    '07': '07-fix-name-mismapping.sql',
});

export const COVERAGE_SERIALIZATIONS = Object.freeze({
    catalogIds: 'sorted-bgg-id-lines-v1',
    mechanismRelations: 'sorted-bgg-id-mechanism-id-csv-v1',
    themeRelations: 'sorted-bgg-id-theme-id-csv-v1',
    playerPreferences: 'sorted-bgg-id-player-count-recommended-best-csv-v1',
});

export const EXPECTED_COVERAGE_ROWS = Object.freeze({
    catalogIds: 170000,
    mechanismRelations: 428488,
    themeRelations: 461973,
    playerPreferences: 263463,
});

const FIELD_PROFILES = Object.freeze({
    bgg_id: 'BGG XML item[@id]',
    name: 'BGG XML alternate name or primary name',
    english_name: 'BGG XML name[@type=primary]@value',
    description: 'BGG XML description derived first sentence',
    detail_description: 'BGG XML description',
    min_players: 'BGG XML minplayers',
    max_players: 'BGG XML maxplayers',
    min_age: 'BGG XML poll suggested_age',
});

const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const SAFE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/u;
const UTC_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;
const WINDOWS_ABSOLUTE_PATH_PATTERN = /^[a-zA-Z]:[\\/]/u;

export function validateCatalogDatasetReleaseManifest(
    manifest,
    { actualDataset, actualArtifacts, actualCoverage } = {},
) {
    assertObject(manifest, 'catalog dataset release manifest');
    assertAllowedKeys(manifest, [
        'schemaVersion',
        'kind',
        'releaseId',
        'datasetId',
        'fieldVersion',
        'approved',
        'testOnly',
        'approval',
        'sourceSnapshot',
        'approvedFields',
        'fieldProvenance',
        'dataset',
        'artifacts',
        'coverage',
    ], 'catalog dataset release manifest');
    assertEqual(manifest.schemaVersion, 1, 'schemaVersion must be 1');
    assertEqual(manifest.kind, CATALOG_DATASET_RELEASE_KIND, `kind must be ${CATALOG_DATASET_RELEASE_KIND}`);
    assertSafeId(manifest.releaseId, 'releaseId');
    assertEqual(manifest.datasetId, CATALOG_DATASET_ID, `datasetId must be ${CATALOG_DATASET_ID}`);
    assertEqual(manifest.fieldVersion, CATALOG_FIELD_VERSION, `fieldVersion must be ${CATALOG_FIELD_VERSION}`);

    if (manifest.approved !== true) {
        throw new Error('catalog dataset release manifest must be approved');
    }
    if (manifest.testOnly !== false) {
        throw new Error('catalog dataset release manifest testOnly must be false');
    }

    validateApproval(manifest.approval);
    validateSourceSnapshot(manifest.sourceSnapshot);
    validateDataset(manifest.dataset, actualDataset);
    validateApprovedFields(manifest.approvedFields, manifest.fieldProvenance);
    validateArtifacts(manifest.artifacts, actualArtifacts);
    validateCoverage(manifest.coverage, actualCoverage);

    return manifest;
}

export function validateCatalogDatasetReleaseReference(
    reference,
    datasetManifest,
    actualManifestSha256,
) {
    assertObject(reference, 'datasetRelease');
    assertAllowedKeys(
        reference,
        ['manifestPath', 'releaseId', 'datasetId', 'manifestSha256'],
        'datasetRelease',
    );
    assertRelativePath(reference.manifestPath, 'datasetRelease.manifestPath');
    assertEqual(reference.releaseId, datasetManifest.releaseId, 'datasetRelease.releaseId does not match dataset manifest');
    assertEqual(reference.datasetId, datasetManifest.datasetId, 'datasetRelease.datasetId does not match dataset manifest');
    assertSha256(reference.manifestSha256, 'datasetRelease.manifestSha256');
    if (actualManifestSha256 !== undefined && reference.manifestSha256 !== actualManifestSha256) {
        throw new Error('datasetRelease.manifestSha256 does not match the referenced manifest');
    }
    return reference;
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

function validateSourceSnapshot(snapshot) {
    assertObject(snapshot, 'sourceSnapshot');
    assertAllowedKeys(
        snapshot,
        ['source', 'batchId', 'rows', 'manifestSha256'],
        'sourceSnapshot',
    );
    assertEqual(snapshot.source, 'BGG XML', 'sourceSnapshot.source must be BGG XML');
    assertEqual(snapshot.batchId, CATALOG_SOURCE_BATCH_ID, `sourceSnapshot.batchId must be ${CATALOG_SOURCE_BATCH_ID}`);
    assertEqual(snapshot.rows, CATALOG_DATASET_ROWS, `sourceSnapshot.rows must be ${CATALOG_DATASET_ROWS}`);
    assertEqual(
        snapshot.manifestSha256,
        CATALOG_SOURCE_MANIFEST_SHA256,
        'sourceSnapshot.manifestSha256 does not match the trusted BGG snapshot manifest',
    );
}

function validateDataset(dataset, actualDataset) {
    assertObject(dataset, 'dataset');
    assertAllowedKeys(dataset, ['rows', 'sha256', 'idSetSha256'], 'dataset');
    assertEqual(dataset.rows, CATALOG_DATASET_ROWS, `dataset.rows must be ${CATALOG_DATASET_ROWS}`);
    assertSha256(dataset.sha256, 'dataset.sha256');
    assertSha256(dataset.idSetSha256, 'dataset.idSetSha256');

    if (actualDataset !== undefined) {
        assertObject(actualDataset, 'actualDataset');
        if (actualDataset.rows !== dataset.rows) {
            throw new Error('dataset.rows does not match actual dataset');
        }
        if (actualDataset.sha256 !== dataset.sha256) {
            throw new Error('dataset.sha256 does not match actual dataset');
        }
        if (actualDataset.idSetSha256 !== dataset.idSetSha256) {
            throw new Error('dataset.idSetSha256 does not match actual dataset');
        }
    }
}

function validateApprovedFields(fields, provenance) {
    if (!Array.isArray(fields) || fields.length === 0) {
        throw new Error('approvedFields must contain at least one field');
    }
    for (const field of fields) {
        assertString(field, 'approvedFields item');
        if (!(field in FIELD_PROFILES)) {
            throw new Error(`approvedFields contains a field outside the trusted profile: ${field}`);
        }
    }
    if (new Set(fields).size !== fields.length) {
        throw new Error('approvedFields must not contain duplicates');
    }

    assertObject(provenance, 'fieldProvenance');
    const fieldsSet = new Set(fields);
    const provenanceKeys = Object.keys(provenance);
    if (provenanceKeys.length !== fields.length || provenanceKeys.some((field) => !fieldsSet.has(field))) {
        throw new Error('fieldProvenance keys must exactly match approvedFields');
    }
    for (const field of fields) {
        const item = provenance[field];
        assertObject(item, `fieldProvenance.${field}`);
        assertAllowedKeys(
            item,
            ['sourceColumn', 'sourceVersion', 'public', 'processingAllowed', 'provenanceSha256'],
            `fieldProvenance.${field}`,
        );
        assertEqual(item.sourceColumn, FIELD_PROFILES[field], `fieldProvenance.${field}.sourceColumn is not trusted`);
        assertEqual(item.sourceVersion, CATALOG_SOURCE_BATCH_ID, `fieldProvenance.${field}.sourceVersion is not trusted`);
        if (item.public !== true) {
            throw new Error(`fieldProvenance.${field}.public must be true`);
        }
        if (item.processingAllowed !== true) {
            throw new Error(`fieldProvenance.${field}.processingAllowed must be true`);
        }
        assertEqual(
            item.provenanceSha256,
            CATALOG_SOURCE_MANIFEST_SHA256,
            `fieldProvenance.${field}.provenanceSha256 does not match the trusted source manifest`,
        );
    }
}

function validateArtifacts(artifacts, actualArtifacts) {
    assertObject(artifacts, 'artifacts');
    assertAllowedKeys(artifacts, REQUIRED_ARTIFACTS, 'artifacts');
    const normalizedPaths = new Set();
    for (const artifactName of REQUIRED_ARTIFACTS) {
        const artifact = artifacts[artifactName];
        assertObject(artifact, `artifacts.${artifactName}`);
        assertAllowedKeys(artifact, ['status', 'path', 'sha256', 'bytes'], `artifacts.${artifactName}`);
        if (artifact.status !== 'approved') {
            throw new Error(`artifacts.${artifactName}.status must be approved`);
        }
        assertRelativePath(artifact.path, `artifacts.${artifactName}.path`);
        const normalizedPath = artifact.path.replaceAll('\\', '/');
        if (normalizedPaths.has(normalizedPath)) {
            throw new Error(`artifacts.${artifactName}.path duplicates another artifact`);
        }
        normalizedPaths.add(normalizedPath);
        const expectedBasename = ARTIFACT_BASENAMES[artifactName];
        const actualBasename = normalizedPath.split('/').at(-1);
        assertEqual(actualBasename, expectedBasename, `artifacts.${artifactName}.path must end with ${expectedBasename}`);
        assertSha256(artifact.sha256, `artifacts.${artifactName}.sha256`);
        if (!Number.isSafeInteger(artifact.bytes) || artifact.bytes <= 0) {
            throw new Error(`artifacts.${artifactName}.bytes must be a positive safe integer`);
        }

        if (actualArtifacts !== undefined) {
            const actual = actualArtifacts[artifactName];
            assertObject(actual, `actualArtifacts.${artifactName}`);
            if (actual.sha256 !== artifact.sha256) {
                throw new Error(`artifacts.${artifactName}.sha256 does not match actual artifact`);
            }
            if (actual.bytes !== artifact.bytes) {
                throw new Error(`artifacts.${artifactName}.bytes does not match actual artifact`);
            }
        }
    }
}

function validateCoverage(coverage, actualCoverage) {
    assertObject(coverage, 'coverage');
    assertAllowedKeys(
        coverage,
        ['catalogIds', 'mechanismRelations', 'themeRelations', 'playerPreferences'],
        'coverage',
    );
    for (const name of Object.keys(COVERAGE_SERIALIZATIONS)) {
        const item = coverage[name];
        assertObject(item, `coverage.${name}`);
        assertAllowedKeys(item, ['rows', 'sha256', 'serialization'], `coverage.${name}`);
        assertEqual(item.rows, EXPECTED_COVERAGE_ROWS[name], `coverage.${name}.rows does not match the release profile`);
        assertSha256(item.sha256, `coverage.${name}.sha256`);
        assertEqual(item.serialization, COVERAGE_SERIALIZATIONS[name], `coverage.${name}.serialization is not supported`);

        if (actualCoverage !== undefined) {
            const actual = actualCoverage[name];
            assertObject(actual, `actualCoverage.${name}`);
            if (actual.rows !== item.rows) {
                throw new Error(`coverage.${name}.rows does not match actual coverage`);
            }
            if (actual.sha256 !== item.sha256) {
                throw new Error(`coverage.${name}.sha256 does not match actual coverage`);
            }
            if (actual.serialization !== item.serialization) {
                throw new Error(`coverage.${name}.serialization does not match actual coverage`);
            }
        }
    }
}

function assertAllowedKeys(value, allowedKeys, field) {
    const allowed = new Set(allowedKeys);
    const unknown = Object.keys(value).filter((key) => !allowed.has(key));
    if (unknown.length > 0) {
        throw new Error(`${field} contains unsupported fields: ${unknown.join(', ')}`);
    }
}

function assertObject(value, field) {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) {
        throw new Error(`${field} must be an object`);
    }
}

function assertEqual(actual, expected, message) {
    if (actual !== expected) throw new Error(message);
}

function assertString(value, field) {
    if (typeof value !== 'string' || value.trim() === '') {
        throw new Error(`${field} must be a non-empty string`);
    }
}

function assertSafeId(value, field) {
    assertString(value, field);
    if (!SAFE_ID_PATTERN.test(value)) {
        throw new Error(`${field} must be a safe identifier`);
    }
}

function assertSha256(value, field) {
    if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) {
        throw new Error(`${field} must be a lowercase SHA-256 hex string`);
    }
}

function assertRelativePath(value, field) {
    assertString(value, field);
    if (isAbsolute(value) || WINDOWS_ABSOLUTE_PATH_PATTERN.test(value)) {
        throw new Error(`${field} must be a relative path`);
    }
    const segments = value.split(/[\\/]/u);
    if (segments.some((segment) => segment === '..' || segment === '')) {
        throw new Error(`${field} must not contain parent traversal or empty path segments`);
    }
}

function assertInstant(value, field) {
    assertString(value, field);
    const parsed = Date.parse(value);
    const canonical = value.includes('.') ? value : value.replace(/Z$/u, '.000Z');
    if (
        !UTC_INSTANT_PATTERN.test(value)
        || Number.isNaN(parsed)
        || new Date(parsed).toISOString() !== canonical
    ) {
        throw new Error(`${field} must be a UTC ISO-8601 instant`);
    }
}
