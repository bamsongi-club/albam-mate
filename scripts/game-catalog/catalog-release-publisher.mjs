import { createHash } from 'node:crypto';
import {
    existsSync,
    mkdirSync,
    mkdtempSync,
    renameSync,
    rmSync,
    writeFileSync,
} from 'node:fs';
import { basename, join, resolve } from 'node:path';
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

    mkdirSync(releaseParent, { recursive: true });
    if (existsSync(releaseDirectory)) {
        throw new Error(`release already exists: ${manifest.releaseId}`);
    }

    const stagingDirectory = mkdtempSync(join(releaseParent, `.${manifest.releaseId}-staging-`));
    let publishedDirectory = false;
    let pointerDirectory;
    try {
        const outputs = {};
        for (const [name, artifact] of Object.entries(artifacts)) {
            const contents = artifact.contents;
            writeFile(join(stagingDirectory, name), contents, 'utf8');
            outputs[name] = {
                path: name,
                sha256: sha256(contents),
                rows: artifact.rows,
                bytes: byteLength(contents),
            };
        }

        const outputManifest = {
            ...manifest,
            outputs,
        };
        const manifestContents = `${JSON.stringify(outputManifest, null, 2)}\n`;
        writeFile(join(stagingDirectory, RELEASE_MANIFEST_FILE), manifestContents, 'utf8');

        rename(stagingDirectory, releaseDirectory);
        publishedDirectory = true;

        pointerDirectory = mkdtempSync(join(root, '.current-release-'));
        const pointerContents = `${JSON.stringify({
            schemaVersion: 1,
            releaseId: manifest.releaseId,
            releasePath: `releases/${manifest.releaseId}`,
            manifestPath: `releases/${manifest.releaseId}/${RELEASE_MANIFEST_FILE}`,
            manifestSha256: sha256(manifestContents),
        }, null, 2)}\n`;
        const pointerSource = join(pointerDirectory, CURRENT_RELEASE_FILE);
        writeFile(pointerSource, pointerContents, 'utf8');
        rename(pointerSource, join(root, CURRENT_RELEASE_FILE));

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
        if (pointerDirectory && existsSync(pointerDirectory)) {
            rmSync(pointerDirectory, { recursive: true, force: true });
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
    for (const name of names) {
        if (name === CURRENT_RELEASE_FILE || name === RELEASE_MANIFEST_FILE
            || basename(name) !== name || name === '.' || name === '..') {
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

function sha256(value) {
    const digest = createHash('sha256').update(value).digest('hex');
    if (!SHA256_PATTERN.test(digest)) throw new Error('failed to calculate artifact checksum');
    return digest;
}

function byteLength(value) {
    return Buffer.isBuffer(value) ? value.byteLength : Buffer.byteLength(value, 'utf8');
}
