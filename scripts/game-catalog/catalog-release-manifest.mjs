import { validateDescriptionProvenance } from "./description-quality.mjs";

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

const REQUIRED_SOURCES = ['games', 'ranks'];
const REQUIRED_OUTPUTS = ['serviceCatalog', 'upsertSql'];
const REQUIRED_RENDERED_DESCRIPTION_FIELDS = ['description', 'detail_description'];
const REQUIRED_PROCESSING_SCOPES = [
    'service-load',
    'search-text-assembly',
    'embedding-generation',
];

const SHA256_PATTERN = /^[a-f0-9]{64}$/u;
const RELEASE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/u;
const WINDOWS_RESERVED_RELEASE_ID_PATTERN = /^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$/u;
const UTC_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/u;
const FIELD_NAME_PATTERN = /^[a-z][A-Za-z0-9_]{0,63}$/u;

export function validateApprovedReleaseManifest(
    manifest,
    {
        actualInputs,
        actualDescriptionInput,
        actualOutputs,
        requiredProcessingScopes = [],
    } = {},
) {
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

    assertSafeIdentifier(manifest.datasetId, 'datasetId');
    validateApproval(manifest.approval);
    const approvedFields = validateStringArray(manifest.approvedFields, 'approvedFields');
    const missingRenderedDescriptionFields = REQUIRED_RENDERED_DESCRIPTION_FIELDS.filter(
        (field) => !approvedFields.includes(field),
    );
    if (missingRenderedDescriptionFields.length > 0) {
        throw new Error(
            `approvedFields must include rendered description fields: ${missingRenderedDescriptionFields.join(', ')}`,
        );
    }
    const descriptionProvenanceErrors = validateDescriptionProvenance(manifest);
    if (descriptionProvenanceErrors.length > 0) {
        throw new Error(descriptionProvenanceErrors[0].message);
    }
    const approvedProcessingScopes = validateProcessingScopes(manifest.approvedProcessingScopes);
    validateRequiredProcessingScopes(approvedProcessingScopes, requiredProcessingScopes);
    validateSearchText(manifest.search_text, approvedFields);
    validateEmbedding(manifest.embedding);

    assertObject(manifest.inputs, 'inputs');
    for (const inputName of REQUIRED_INPUTS) {
        validateArtifact(manifest.inputs[inputName], `inputs.${inputName}`, inputName);
    }

    assertObject(manifest.coverage, 'coverage');
    for (const coverageName of REQUIRED_COVERAGE) {
        validateCoverage(manifest.coverage[coverageName], `coverage.${coverageName}`);
    }

    assertObject(manifest.sources, 'sources');
    for (const sourceName of REQUIRED_SOURCES) {
        validateSourceArtifact(manifest.sources[sourceName], `sources.${sourceName}`);
    }

    assertObject(manifest.outputs, 'outputs');
    for (const outputName of REQUIRED_OUTPUTS) {
        validateOutputArtifact(manifest.outputs[outputName], `outputs.${outputName}`);
    }

    if (actualInputs !== undefined) {
        compareArtifacts(manifest.sources, actualInputs, 'sources');
    }
    if (actualDescriptionInput !== undefined) {
        compareArtifact(
            manifest.inputs.descriptions,
            actualDescriptionInput,
            'inputs.descriptions',
        );
    }
    if (actualOutputs !== undefined) {
        compareArtifacts(manifest.outputs, actualOutputs, 'outputs');
    }
    return manifest;
}

function compareArtifact(declared, actual, field) {
    assertObject(actual, `actual ${field}`);
    if (actual.fileName !== fileNameFromPath(declared.path)) {
        throw new Error(`${field}.fileName does not match actual artifact`);
    }
    if (actual.sha256 !== declared.sha256) {
        throw new Error(`${field}.sha256 does not match actual artifact`);
    }
    if (actual.rows !== declared.rows) {
        throw new Error(`${field}.rows does not match actual artifact`);
    }
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

function validateSourceArtifact(artifact, field) {
    assertObject(artifact, field);
    assertFileName(artifact.fileName, `${field}.fileName`);
    assertSha256(artifact.sha256, `${field}.sha256`);
    assertRows(artifact.rows, `${field}.rows`);
}

function validateOutputArtifact(artifact, field) {
    assertObject(artifact, field);
    assertRelativePath(artifact.path, `${field}.path`);
    assertSha256(artifact.sha256, `${field}.sha256`);
    assertRows(artifact.rows, `${field}.rows`);
}

function validateCoverage(coverage, field) {
    assertObject(coverage, field);
    assertRows(coverage.rows, `${field}.rows`);
    assertSha256(coverage.sha256, `${field}.sha256`);
}

function validateProcessingScopes(scopes) {
    const values = validateStringArray(scopes, 'approvedProcessingScopes');
    const missingScopes = REQUIRED_PROCESSING_SCOPES.filter((scope) => !values.includes(scope));
    if (missingScopes.length > 0) {
        throw new Error(
            `approvedProcessingScopes must include: ${missingScopes.join(', ')}`,
        );
    }
    return values;
}

function validateRequiredProcessingScopes(approvedScopes, requiredScopes) {
    if (!Array.isArray(requiredScopes)) {
        throw new Error('requiredProcessingScopes must be an array');
    }
    const missingScopes = requiredScopes.filter((scope) => !approvedScopes.includes(scope));
    if (missingScopes.length > 0) {
        throw new Error(
            `approvedProcessingScopes must include required scopes: ${missingScopes.join(', ')}`,
        );
    }
}

function validateSearchText(searchText, approvedFields) {
    assertObject(searchText, 'search_text');
    const fields = validateStringArray(searchText.fields, 'search_text.fields');
    assertString(searchText.sourceFieldVersion, 'search_text.sourceFieldVersion');
    assertString(searchText.assemblyRuleVersion, 'search_text.assemblyRuleVersion');
    const approvedFieldSet = new Set(approvedFields);
    const unapprovedFields = fields.filter((field) => !approvedFieldSet.has(field));
    if (unapprovedFields.length > 0) {
        throw new Error(
            `search_text.fields must be included in approvedFields: ${unapprovedFields.join(', ')}`,
        );
    }
}

function validateEmbedding(embedding) {
    assertObject(embedding, 'embedding');
    assertString(embedding.provider, 'embedding.provider');
    assertString(embedding.model, 'embedding.model');
    assertString(embedding.modelVersion, 'embedding.modelVersion');
    assertString(embedding.indexVersion, 'embedding.indexVersion');
    if (!Number.isSafeInteger(embedding.dimensions) || embedding.dimensions <= 0) {
        throw new Error('embedding.dimensions must be a positive safe integer');
    }
    validateOutputArtifact(embedding.output, 'embedding.output');
}

function validateStringArray(value, field) {
    if (!Array.isArray(value) || value.length === 0) {
        throw new Error(`${field} must contain at least one value`);
    }
    const values = value.map((item) => {
        assertString(item, `${field} item`);
        if (!FIELD_NAME_PATTERN.test(item) && field !== 'approvedProcessingScopes') {
            throw new Error(`${field} contains an invalid field name: ${item}`);
        }
        return item;
    });
    if (new Set(values).size !== values.length) {
        throw new Error(`${field} must not contain duplicates`);
    }
    return values;
}

function compareArtifacts(declaredArtifacts, actualArtifacts, field) {
    assertObject(actualArtifacts, `actual ${field}`);
    for (const artifactName of field === 'sources' ? REQUIRED_SOURCES : REQUIRED_OUTPUTS) {
        const declared = declaredArtifacts[artifactName];
        const actual = actualArtifacts[artifactName];
        assertObject(actual, `actual ${field}.${artifactName}`);
        const declaredFileName = field === 'sources'
            ? declared.fileName
            : fileNameFromPath(declared.path);
        if (actual.fileName !== declaredFileName) {
            throw new Error(`${field}.${artifactName}.fileName does not match actual artifact`);
        }
        if (actual.sha256 !== declared.sha256) {
            throw new Error(`${field}.${artifactName}.sha256 does not match actual artifact`);
        }
        if (actual.rows !== declared.rows) {
            throw new Error(`${field}.${artifactName}.rows does not match actual artifact`);
        }
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

function assertSafeIdentifier(value, field) {
    assertString(value, field);
    if (!RELEASE_ID_PATTERN.test(value)) {
        throw new Error(`${field} must be a safe identifier`);
    }
}

function assertFileName(value, field) {
    assertString(value, field);
    if (value.includes('/') || value.includes('\\') || value === '.' || value === '..') {
        throw new Error(`${field} must be a file name`);
    }
}

function assertRelativePath(value, field) {
    assertString(value, field);
    if (
        value.startsWith('/')
        || /^[A-Za-z]:[\\/]/u.test(value)
        || value.split(/[\\/]/u).includes('..')
        || value.includes('\u0000')
    ) {
        throw new Error(`${field} must be a safe relative path`);
    }
}

function fileNameFromPath(path) {
    return path.split(/[\\/]/u).at(-1);
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
