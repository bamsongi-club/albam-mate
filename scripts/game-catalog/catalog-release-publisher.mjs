import { createHash } from 'node:crypto';
import {
    existsSync,
    mkdirSync,
    mkdtempSync,
    readFileSync,
    renameSync,
    rmSync,
    writeFileSync,
} from 'node:fs';
import { join, resolve } from 'node:path';
import { validateApprovedReleaseManifest } from './catalog-release-manifest.mjs';

const CURRENT_RELEASE_FILE = 'current-release.json';
const RELEASE_MANIFEST_FILE = 'release-manifest.json';
const SHA256_PATTERN = /^[a-f0-9]{64}$/u;

export function publishCatalogRelease({
    outputRoot,
    manifest,
    artifacts,
    writeFile = writeFileSync,
    rename = renameSync,
}) {
    validateApprovedReleaseManifest(manifest);
    const root = resolveOutputRoot(outputRoot);
    const releaseParent = join(root, 'releases');
    const releaseDirectory = join(releaseParent, manifest.releaseId);
    validateArtifacts(artifacts);
    const outputManifest = buildOutputManifest(manifest, artifacts);
    const manifestContents = `${JSON.stringify(outputManifest, null, 2)}\n`;

    mkdirSync(releaseParent, { recursive: true });
    if (existsSync(releaseDirectory)) {
        const existingManifestPath = join(releaseDirectory, RELEASE_MANIFEST_FILE);
        if (!existsSync(existingManifestPath)
            || readFileSync(existingManifestPath, 'utf8') !== manifestContents) {
            throw new Error(`release already exists with different contents: ${manifest.releaseId}`);
        }
        verifyPublishedRelease(releaseDirectory, outputManifest);
        publishCurrentRelease({
            root,
            releaseId: manifest.releaseId,
            manifestContents,
            writeFile,
            rename,
        });
        return {
            releaseId: manifest.releaseId,
            releaseDirectory,
            pointerPath: join(root, CURRENT_RELEASE_FILE),
        };
    }

    const stagingDirectory = mkdtempSync(join(releaseParent, `.${manifest.releaseId}-staging-`));
    let publishedDirectory = false;
    try {
        for (const [name, artifact] of Object.entries(artifacts)) {
            const contents = artifact.contents;
            writeFile(join(stagingDirectory, name), contents, 'utf8');
        }
        writeFile(join(stagingDirectory, RELEASE_MANIFEST_FILE), manifestContents, 'utf8');

        rename(stagingDirectory, releaseDirectory);
        publishedDirectory = true;
        publishCurrentRelease({
            root,
            releaseId: manifest.releaseId,
            manifestContents,
            writeFile,
            rename,
        });

        return {
            releaseId: manifest.releaseId,
            releaseDirectory,
            pointerPath: join(root, CURRENT_RELEASE_FILE),
        };
    } catch (error) {
        if (publishedDirectory && existsSync(releaseDirectory)) {
            rmSync(releaseDirectory, { recursive: true, force: true });
        }
        throw error;
    } finally {
        if (existsSync(stagingDirectory)) {
            rmSync(stagingDirectory, { recursive: true, force: true });
        }
    }
}

function resolveOutputRoot(outputRoot) {
    if (typeof outputRoot !== 'string' || outputRoot.trim() === '') {
        throw new Error('outputRoot must be a non-empty path');
    }
    return resolve(outputRoot);
}

function validateArtifacts(artifacts) {
    if (artifacts === null || typeof artifacts !== 'object' || Array.isArray(artifacts)) {
        throw new Error('artifacts must be an object');
    }
    const names = Object.keys(artifacts);
    if (names.length === 0) throw new Error('artifacts must not be empty');
    const normalizedNames = new Map();
    for (const name of names) {
        const normalizedName = normalizeWindowsArtifactName(name);
        const previousName = normalizedNames.get(normalizedName);
        if (previousName) {
            throw new Error(`release artifact names collide on Windows: ${previousName}, ${name}`);
        }
        normalizedNames.set(normalizedName, name);
        if (normalizedName === normalizeWindowsArtifactName(CURRENT_RELEASE_FILE)
            || normalizedName === normalizeWindowsArtifactName(RELEASE_MANIFEST_FILE)) {
            throw new Error(`invalid release artifact name: ${name}`);
        }
        const artifact = artifacts[name];
        if (artifact === null || typeof artifact !== 'object' || Array.isArray(artifact)) {
            throw new Error(`invalid release artifact: ${name}`);
        }
        if (typeof artifact.contents !== 'string' && !Buffer.isBuffer(artifact.contents)) {
            throw new Error(`release artifact contents must be text or bytes: ${name}`);
        }
        if (!Number.isSafeInteger(artifact.rows) || artifact.rows < 0) {
            throw new Error(`release artifact rows must be a non-negative safe integer: ${name}`);
        }
    }
}

function normalizeWindowsArtifactName(name) {
    if (typeof name !== 'string' || name === '.' || name === '..'
        || name.includes('/') || name.includes('\\')) {
        throw new Error(`invalid release artifact name for Windows: ${name}`);
    }
    const withoutTrailingWindowsSpace = name.replace(/[ .]+$/u, '');
    if (withoutTrailingWindowsSpace !== name || withoutTrailingWindowsSpace === '') {
        throw new Error(`release artifact name has Windows-trimmed suffix: ${name}`);
    }
    const normalizedName = withoutTrailingWindowsSpace.normalize('NFC').toLowerCase();
    const deviceName = normalizedName.split('.')[0];
    if (/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])$/u.test(deviceName)) {
        throw new Error(`release artifact name is a Windows reserved device name: ${name}`);
    }
    return normalizedName;
}

function buildOutputManifest(manifest, artifacts) {
    const outputs = Object.create(null);
    for (const [name, artifact] of Object.entries(artifacts)) {
        outputs[name] = {
            path: name,
            sha256: sha256(artifact.contents),
            rows: artifact.rows,
            bytes: byteLength(artifact.contents),
        };
    }
    return { ...manifest, outputs };
}

function verifyPublishedRelease(releaseDirectory, outputManifest) {
    for (const [name, expected] of Object.entries(outputManifest.outputs)) {
        const contents = readFileSync(join(releaseDirectory, name));
        if (sha256(contents) !== expected.sha256 || byteLength(contents) !== expected.bytes) {
            throw new Error(`existing release artifact checksum mismatch: ${name}`);
        }
    }
}

function publishCurrentRelease({
    root,
    releaseId,
    manifestContents,
    writeFile,
    rename,
}) {
    const pointerDirectory = mkdtempSync(join(root, '.current-release-'));
    try {
        const pointerContents = `${JSON.stringify({
            schemaVersion: 1,
            releaseId,
            releasePath: `releases/${releaseId}`,
            manifestPath: `releases/${releaseId}/${RELEASE_MANIFEST_FILE}`,
            manifestSha256: sha256(manifestContents),
        }, null, 2)}\n`;
        const pointerSource = join(pointerDirectory, CURRENT_RELEASE_FILE);
        writeFile(pointerSource, pointerContents, 'utf8');
        rename(pointerSource, join(root, CURRENT_RELEASE_FILE));
    } finally {
        if (existsSync(pointerDirectory)) {
            rmSync(pointerDirectory, { recursive: true, force: true });
        }
    }
}

function sha256(value) {
    const digest = createHash('sha256').update(value).digest('hex');
    if (!SHA256_PATTERN.test(digest)) throw new Error('failed to calculate artifact checksum');
    return digest;
}

function byteLength(value) {
    return Buffer.isBuffer(value) ? value.byteLength : Buffer.byteLength(value, 'utf8');
}
