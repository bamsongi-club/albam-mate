const REQUIRED_ARTIFACTS = [
    '01',
    '01b',
    '02',
    '03',
    '04',
    '05',
    '06',
    '07',
];

const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const SAFE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/u;
const UTC_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;

export function validateCatalogDatasetReleaseManifest(manifest, { actualArtifacts } = {}) {
    assertObject(manifest, 'catalog dataset release manifest');
    assertEqual(manifest.schemaVersion, 1, 'schemaVersion must be 1');
    assertEqual(manifest.kind, 'catalog-dataset-release', 'kind must be catalog-dataset-release');
    assertSafeId(manifest.releaseId, 'releaseId');
    assertSafeId(manifest.datasetId, 'datasetId');
    assertString(manifest.fieldVersion, 'fieldVersion');

    if (manifest.approved !== true) {
        throw new Error('catalog dataset release manifest must be approved');
    }
    if (manifest.testOnly !== false) {
        throw new Error('catalog dataset release manifest testOnly must be false');
    }

    validateApproval(manifest.approval);
    validateDataset(manifest.dataset);
    validateApprovedFields(manifest.approvedFields);
    validateArtifacts(manifest.artifacts, actualArtifacts);
    validateCoverage(manifest.coverage);

    return manifest;
}

function validateApproval(approval) {
    assertObject(approval, 'approval');
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

function validateDataset(dataset) {
    assertObject(dataset, 'dataset');
    if (!Number.isSafeInteger(dataset.rows) || dataset.rows <= 0) {
        throw new Error('dataset.rows must be a positive safe integer');
    }
    assertSha256(dataset.sha256, 'dataset.sha256');
    assertSha256(dataset.idSetSha256, 'dataset.idSetSha256');
}

function validateApprovedFields(fields) {
    if (!Array.isArray(fields) || fields.length === 0) {
        throw new Error('approvedFields must contain at least one field');
    }
    for (const field of fields) assertString(field, 'approvedFields item');
    if (new Set(fields).size !== fields.length) {
        throw new Error('approvedFields must not contain duplicates');
    }
}

function validateArtifacts(artifacts, actualArtifacts) {
    assertObject(artifacts, 'artifacts');
    for (const artifactName of REQUIRED_ARTIFACTS) {
        const artifact = artifacts[artifactName];
        assertObject(artifact, `artifacts.${artifactName}`);
        if (artifact.status !== 'approved') {
            throw new Error(`artifacts.${artifactName}.status must be approved`);
        }
        assertString(artifact.path, `artifacts.${artifactName}.path`);
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

function validateCoverage(coverage) {
    assertObject(coverage, 'coverage');
    for (const name of ['catalogIds', 'mechanismRelations', 'themeRelations', 'playerPreferences']) {
        const item = coverage[name];
        assertObject(item, `coverage.${name}`);
        if (!Number.isSafeInteger(item.rows) || item.rows < 0) {
            throw new Error(`coverage.${name}.rows must be a non-negative safe integer`);
        }
        assertSha256(item.sha256, `coverage.${name}.sha256`);
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

function assertInstant(value, field) {
    assertString(value, field);
    const parsed = Date.parse(value);
    const canonical = value.includes('.') ? value : value.replace(/Z$/u, '.000Z');
    if (!UTC_INSTANT_PATTERN.test(value)
        || Number.isNaN(parsed)
        || new Date(parsed).toISOString() !== canonical) {
        throw new Error(`${field} must be a UTC ISO-8601 instant`);
    }
}
