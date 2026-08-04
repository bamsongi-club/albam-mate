import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import {
    DEFAULT_SCHEMA_PATH as DEFAULT_PACKET_SCHEMA_PATH,
    validateAgainstSchema,
    validatePacket,
} from './validate-packet.mjs';

export const DEFAULT_MANIFEST_SCHEMA_PATH = fileURLToPath(
    new URL('../.codex/contracts/backend-test-manifest.schema.json', import.meta.url),
);

const SOURCE_SET_PREFIXES = {
    test: 'src/test/java/',
    postgresTest: 'src/postgresTest/java/',
};
const JAVA_IDENTIFIER = /^[$_\p{ID_Start}][$_\u200c\u200d\p{ID_Continue}]*$/u;

function addError(errors, instancePath, keyword, message) {
    errors.push({ instancePath, schemaPath: '#/relations', keyword, message });
}

function prefixErrors(errors, root) {
    return errors.map((error) => ({
        ...error,
        instancePath: error.instancePath === '$' ? root : `${root}${error.instancePath.slice(1)}`,
    }));
}

function readJson(filePath, label) {
    let contents;
    try {
        contents = fs.readFileSync(filePath, 'utf8');
    } catch (error) {
        throw new Error(`${label} 파일을 읽을 수 없습니다 (${filePath}): ${error.message}`);
    }

    try {
        return JSON.parse(contents);
    } catch (error) {
        throw new Error(`${label} JSON이 올바르지 않습니다 (${filePath}): ${error.message}`);
    }
}

function resolveWorktree(worktreePath) {
    const resolved = path.resolve(worktreePath);
    let canonical;
    try {
        canonical = fs.realpathSync(resolved);
    } catch (error) {
        throw new Error(`worktree를 찾을 수 없습니다 (${resolved}): ${error.message}`);
    }

    if (!fs.statSync(canonical).isDirectory()) {
        throw new Error(`worktree가 디렉터리가 아닙니다: ${canonical}`);
    }
    return canonical;
}

function isInsideWorktree(worktree, candidate) {
    const relative = path.relative(worktree, candidate);
    return relative !== '' && relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
}

function isExactSelector(selector, source) {
    if (selector.includes('*') || selector.includes('?') || /\s/u.test(selector)) {
        return false;
    }

    const segments = selector.split('.');
    if (segments.length < 3 || !segments.every((segment) => JAVA_IDENTIFIER.test(segment))) {
        return false;
    }

    if (typeof source !== 'string') {
        return true;
    }
    const sourceClass = path.posix.basename(source.replaceAll('\\', '/'), '.java');
    const selectorClass = segments.at(-2);
    return selectorClass === sourceClass || selectorClass.startsWith(`${sourceClass}$`);
}

// selector가 가리키는 메서드가 source에 실제로 선언됐는지 본다. review-fast는 구현자의 targeted
// 실행을 다시 하지 않으므로, 여기서 거르지 못한 selector 오타는 PR 본문의 T-ID 매핑에 그대로 남는다.
// JUnit 5 테스트 메서드는 void이며 이 저장소에는 중첩 클래스 테스트가 없다.
function hasTestMethodDeclaration(contents, methodName) {
    const escaped = methodName.replaceAll(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    return new RegExp(`\\bvoid\\s+${escaped}\\s*\\(`, 'u').test(contents);
}

function validateManifestRelations(packet, manifest, worktree) {
    const errors = [];
    const packetTests = Array.isArray(packet?.requiredTests) ? packet.requiredTests : [];
    const manifestTests = Array.isArray(manifest?.tests) ? manifest.tests : [];

    if (packetTests.length !== manifestTests.length) {
        addError(
            errors,
            '$manifest.tests',
            'tIdCount',
            `manifest T-ID 수(${manifestTests.length})가 packet T-ID 수(${packetTests.length})와 다릅니다.`,
        );
    }

    const seenIds = new Set();
    manifestTests.forEach((manifestTest, testIndex) => {
        const expectedId = packetTests[testIndex]?.id;
        const actualId = manifestTest?.id;
        if (typeof actualId === 'string') {
            if (seenIds.has(actualId)) {
                addError(errors, `$manifest.tests[${testIndex}].id`, 'uniqueTId', 'manifest T-ID가 중복되었습니다.');
            }
            seenIds.add(actualId);
        }
        if (typeof expectedId === 'string' && actualId !== expectedId) {
            addError(
                errors,
                `$manifest.tests[${testIndex}].id`,
                'tIdOrder',
                `packet 순서의 ${expectedId}가 필요하지만 ${JSON.stringify(actualId)}입니다.`,
            );
        }

        const seenEvidence = new Set();
        const evidenceList = Array.isArray(manifestTest?.evidence) ? manifestTest.evidence : [];
        evidenceList.forEach((evidence, evidenceIndex) => {
            const evidencePath = `$manifest.tests[${testIndex}].evidence[${evidenceIndex}]`;
            const { task, source, selector } = evidence ?? {};

            if (typeof task === 'string' && typeof selector === 'string') {
                const evidenceKey = `${task}\u0000${selector}`;
                if (seenEvidence.has(evidenceKey)) {
                    addError(
                        errors,
                        evidencePath,
                        'duplicateEvidence',
                        '같은 T-ID 안에서 task와 selector가 중복되었습니다.',
                    );
                }
                seenEvidence.add(evidenceKey);
            }

            const selectorIsExact = typeof selector === 'string' && isExactSelector(selector, source);
            if (typeof selector === 'string' && !selectorIsExact) {
                addError(
                    errors,
                    `${evidencePath}.selector`,
                    'exactSelector',
                    'selector는 wildcard와 공백이 없는 package-qualified 클래스·메서드 exact selector여야 합니다.',
                );
            }

            if (typeof source !== 'string') {
                return;
            }

            const normalizedSource = source.replaceAll('\\', '/');
            const expectedPrefix = SOURCE_SET_PREFIXES[task];
            if (expectedPrefix && !normalizedSource.startsWith(expectedPrefix)) {
                addError(
                    errors,
                    `${evidencePath}.source`,
                    'sourceSet',
                    `${task} evidence source는 ${expectedPrefix} 아래에 있어야 합니다.`,
                );
            }

            const resolvedSource = path.resolve(worktree, source);
            if (!isInsideWorktree(worktree, resolvedSource)) {
                addError(
                    errors,
                    `${evidencePath}.source`,
                    'worktreePath',
                    'source는 worktree 안의 상대 경로여야 합니다.',
                );
                return;
            }

            if (!fs.existsSync(resolvedSource)) {
                addError(errors, `${evidencePath}.source`, 'sourceExists', 'source 파일이 존재하지 않습니다.');
                return;
            }

            const canonicalSource = fs.realpathSync(resolvedSource);
            if (!isInsideWorktree(worktree, canonicalSource)) {
                addError(
                    errors,
                    `${evidencePath}.source`,
                    'worktreePath',
                    'source가 symlink를 통해 worktree 밖을 가리킵니다.',
                );
                return;
            }
            if (!fs.statSync(canonicalSource).isFile()) {
                addError(errors, `${evidencePath}.source`, 'sourceFile', 'source는 파일이어야 합니다.');
                return;
            }

            if (!selectorIsExact) {
                return;
            }

            const segments = selector.split('.');
            // 중첩 클래스 selector는 바깥 클래스 파일만으로 선언 위치를 특정할 수 없어 클래스 일치까지만 본다.
            if (segments.at(-2).includes('$')) {
                return;
            }

            let contents;
            try {
                contents = fs.readFileSync(canonicalSource, 'utf8');
            } catch (error) {
                addError(
                    errors,
                    `${evidencePath}.source`,
                    'sourceReadable',
                    `source를 읽을 수 없습니다: ${error.message}`,
                );
                return;
            }

            const methodName = segments.at(-1);
            if (!hasTestMethodDeclaration(contents, methodName)) {
                addError(
                    errors,
                    `${evidencePath}.selector`,
                    'selectorMethod',
                    `source에서 void ${methodName}(...) 선언을 찾을 수 없습니다.`,
                );
            }
        });
    });

    return errors;
}

export function validateBackendTestManifest(packet, manifest, worktreePath, packetSchema, manifestSchema) {
    const worktree = resolveWorktree(worktreePath);
    const packetErrors = prefixErrors(validatePacket(packet, packetSchema), '$packet');
    const manifestErrors = prefixErrors(validateAgainstSchema(manifestSchema, manifest), '$manifest');
    return [...packetErrors, ...manifestErrors, ...validateManifestRelations(packet, manifest, worktree)];
}

export function validateBackendTestManifestFiles({
    packetPath,
    manifestPath,
    worktreePath,
    packetSchemaPath = DEFAULT_PACKET_SCHEMA_PATH,
    manifestSchemaPath = DEFAULT_MANIFEST_SCHEMA_PATH,
}) {
    const resolvedPacketPath = path.resolve(packetPath);
    const resolvedManifestPath = path.resolve(manifestPath);
    const resolvedPacketSchemaPath = path.resolve(packetSchemaPath);
    const resolvedManifestSchemaPath = path.resolve(manifestSchemaPath);
    const packet = readJson(resolvedPacketPath, '패킷');
    const manifest = readJson(resolvedManifestPath, 'manifest');
    const packetSchema = readJson(resolvedPacketSchemaPath, '패킷 스키마');
    const manifestSchema = readJson(resolvedManifestSchemaPath, 'manifest 스키마');
    const errors = validateBackendTestManifest(packet, manifest, worktreePath, packetSchema, manifestSchema);

    return {
        packet,
        manifest,
        packetPath: resolvedPacketPath,
        manifestPath: resolvedManifestPath,
        worktreePath: path.resolve(worktreePath),
        errors,
    };
}

function parseArguments(argv) {
    const values = {};
    const allowed = new Set(['--packet', '--manifest', '--worktree']);
    for (let index = 0; index < argv.length; index += 2) {
        const option = argv[index];
        const value = argv[index + 1];
        if (!allowed.has(option) || value === undefined || value.startsWith('--')) {
            return null;
        }
        values[option.slice(2)] = value;
    }
    return values.packet && values.manifest && values.worktree ? values : null;
}

function runCli() {
    const args = parseArguments(process.argv.slice(2));
    if (!args) {
        console.error(
            '사용법: node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree>',
        );
        process.exitCode = 2;
        return;
    }

    try {
        const result = validateBackendTestManifestFiles({
            packetPath: args.packet,
            manifestPath: args.manifest,
            worktreePath: args.worktree,
        });
        if (result.errors.length > 0) {
            console.error(`backend test manifest 검증 실패: ${result.manifestPath}`);
            for (const error of result.errors) {
                console.error(`- ${error.instancePath}: ${error.message} [${error.keyword}]`);
            }
            process.exitCode = 1;
            return;
        }

        console.log(
            `backend test manifest 검증 통과: ${result.manifestPath} (T-ID ${result.manifest.tests.length}개)`,
        );
    } catch (error) {
        console.error(`backend test manifest 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) {
    runCli();
}
