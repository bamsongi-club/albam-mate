const REQUIRED_INPUTS = [
    'catalog',
    'names',
    'descriptions',
    'mechanismDictionary',
    'themeDictionary',
    'relations',
];

const REQUIRED_COVERAGE = [
    'catalogIds',
    'relationGameIds',
    'mechanismIds',
    'themeIds',
];

const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const RELEASE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/u;
const WINDOWS_RESERVED_RELEASE_ID_PATTERN = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$/u;
const UTC_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;

export function validateApprovedReleaseManifest(manifest) {
    assertObject(manifest, 'release manifest');
    assertEqual(manifest.schemaVersion, 1, 'schemaVersion must be 1');
    assertString(manifest.releaseId, 'releaseId');
    if (!RELEASE_ID_PATTERN.test(manifest.releaseId)) {
        throw new Error('releaseId must be a safe release directory name');
    }
    if (WINDOWS_RESERVED_RELEASE_ID_PATTERN.test(manifest.releaseId)) {
        throw new Error('releaseId must not be a Windows reserved device name');
    }
    if (manifest.approved !== true) {
        throw new Error('release manifest must be approved');
    }
    if (manifest.testOnly !== false) {
        throw new Error('release manifest testOnly must be false');
    }

    validateApproval(manifest.approval);
    assertObject(manifest.inputs, 'inputs');
    for (const inputName of REQUIRED_INPUTS) {
        validateArtifact(manifest.inputs[inputName], `inputs.${inputName}`, inputName);
    }

    assertObject(manifest.coverage, 'coverage');
    for (const coverageName of REQUIRED_COVERAGE) {
        validateCoverage(manifest.coverage[coverageName], `coverage.${coverageName}`);
    }
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

function validateArtifact(artifact, field, name) {
    assertObject(artifact, field);
    if (artifact.status !== 'approved') {
        throw new Error(`${name} artifact status must be approved`);
    }
    assertString(artifact.path, `${field}.path`);
    assertSha256(artifact.sha256, `${field}.sha256`);
    assertRows(artifact.rows, `${field}.rows`);
}

function validateCoverage(coverage, field) {
    assertObject(coverage, field);
    assertRows(coverage.rows, `${field}.rows`);
    assertSha256(coverage.sha256, `${field}.sha256`);
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

function assertInstant(value, field) {
    assertString(value, field);
    const parsedTime = Date.parse(value);
    const canonicalValue = value.includes('.') ? value : value.replace(/Z$/u, '.000Z');
    if (!UTC_INSTANT_PATTERN.test(value)
        || Number.isNaN(parsedTime)
        || new Date(parsedTime).toISOString() !== canonicalValue) {
        throw new Error(`${field} must be a UTC ISO-8601 instant`);
    }
}

function assertSha256(value, field) {
    if (typeof value !== 'string' || !SHA256_PATTERN.test(value)) {
        throw new Error(`${field} must be a lowercase SHA-256 hex digest`);
    }
}

function assertRows(value, field) {
    if (!Number.isSafeInteger(value) || value < 0) {
        throw new Error(`${field} must be a non-negative safe integer`);
    }
}
