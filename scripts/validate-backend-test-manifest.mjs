import fs from 'node:fs';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';

import {
    POSTGRES_DECISIONS,
    changedPathsIn,
    classifyPostgresRequirementIn,
} from './classify-postgres-requirement.mjs';
import {
    DEFAULT_SCHEMA_PATH as DEFAULT_PACKET_SCHEMA_PATH,
    validateAgainstSchema,
    validatePacket,
} from './validate-packet.mjs';

export { changedPathsIn } from './classify-postgres-requirement.mjs';

export const DEFAULT_MANIFEST_SCHEMA_PATH = fileURLToPath(
    new URL('../.codex/contracts/backend-test-manifest.schema.json', import.meta.url),
);

const SOURCE_SET_PREFIXES = {
    test: 'src/test/java/',
    postgresTest: 'src/postgresTest/java/',
};
const JAVA_IDENTIFIER = /^[$_\p{ID_Start}][$_\u200c\u200d\p{ID_Continue}]*$/u;

// \uad6c\ud604\uc790\uac00 packet\uc758 allowedPaths\uc5d0 \uc788\uc5b4\ub3c4 \ubc14\uafc0 \uc218 \uc5c6\ub294 \uacbd\ub85c\ub2e4. \uc774 \uc0c1\uc218\uac00 \uc815\ubcf8\uc774\uba70
// \uc5d0\uc774\uc804\ud2b8 \uc9c0\uc2dc\ubb38\uc740 \ubaa9\ub85d\uc744 \ub2e4\uc2dc \uc801\uc9c0 \uc54a\uace0 \uc774 \uac80\uc0ac\ub97c \uac00\ub9ac\ud0a8\ub2e4. \ud558\uc704 \ub514\ub809\ud130\ub9ac\uc758
// AGENTS.md\u00b7CLAUDE.md\ub3c4 \uac19\uc740 \uaddc\uce59\uc774\ubbc0\ub85c \ud30c\uc77c\uba85 \ud328\ud134\uc73c\ub85c \ub454\ub2e4.
export const ALWAYS_READ_ONLY_PATTERNS = [
    '**/AGENTS.md',
    '**/CLAUDE.md',
    'docs/PRD.md',
    'docs/P0-spec.md',
    'docs/CONVENTIONS.md',
    'docs/adr/**',
];

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

// packet이 쓰는 경로 표기만 지원한다. 정확한 경로, `접두어/**`, `접두어/`와 `**/파일명`이며
// 그 밖의 와일드카드는 조용히 통과시키지 않고 패턴 오류로 보고한다. 경계 판정이 불확실한
// 패턴을 넘기면 감사가 통과로 보이기 때문이다.
export function matchesPathPattern(pattern, filePath) {
    if (pattern.startsWith('**/')) {
        const name = pattern.slice(3);
        return filePath === name || filePath.endsWith(`/${name}`);
    }
    if (pattern.endsWith('/**')) {
        return filePath.startsWith(pattern.slice(0, -2));
    }
    if (pattern.endsWith('/')) {
        return filePath.startsWith(pattern);
    }
    return filePath === pattern;
}

function isUnsupportedPattern(pattern) {
    if (pattern.startsWith('**/')) return pattern.slice(3).includes('*');
    if (pattern.endsWith('/**')) return pattern.slice(0, -3).includes('*');
    return pattern.includes('*');
}

// 구현자가 실제로 바꾼 경로가 packet이 고정한 소유 경계 안인지 판정한다. 지금까지 사람이
// 눈으로 확인했던 범위 밖 변경 확인을 대체한다.
export function auditChangedPaths(packet, changedPaths) {
    const errors = [];
    const allowed = Array.isArray(packet?.allowedPaths) ? packet.allowedPaths : [];
    const forbidden = Array.isArray(packet?.forbiddenPaths) ? packet.forbiddenPaths : [];

    for (const [key, patterns] of [
        ['allowedPaths', allowed],
        ['forbiddenPaths', forbidden],
    ]) {
        patterns.forEach((pattern, index) => {
            if (typeof pattern === 'string' && isUnsupportedPattern(pattern)) {
                addError(
                    errors,
                    `$packet.${key}[${index}]`,
                    'pathPattern',
                    `지원하지 않는 경로 패턴입니다. 정확한 경로, '접두어/**', '접두어/' 또는 '**/파일명'만 씁니다: ${pattern}`,
                );
            }
        });
    }
    if (errors.length > 0) {
        return errors;
    }

    for (const changed of changedPaths) {
        const readOnlyPattern = ALWAYS_READ_ONLY_PATTERNS.find((pattern) =>
            matchesPathPattern(pattern, changed),
        );
        if (readOnlyPattern) {
            addError(
                errors,
                `$changedPaths['${changed}']`,
                'alwaysReadOnly',
                `항상 read-only인 경로를 변경했습니다 (${readOnlyPattern}).`,
            );
            continue;
        }

        const forbiddenPattern = forbidden.find((pattern) => matchesPathPattern(pattern, changed));
        if (forbiddenPattern) {
            addError(
                errors,
                `$changedPaths['${changed}']`,
                'forbiddenPath',
                `forbiddenPaths에 해당하는 경로를 변경했습니다 (${forbiddenPattern}).`,
            );
            continue;
        }

        if (!allowed.some((pattern) => matchesPathPattern(pattern, changed))) {
            addError(
                errors,
                `$changedPaths['${changed}']`,
                'allowedPath',
                'allowedPaths 밖의 경로를 변경했습니다.',
            );
        }
    }
    return errors;
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
    return !selectorClass.includes('$') && selectorClass === sourceClass;
}

function normalizeEvidenceSource(source) {
    const portableSource = source.replaceAll('\\', '/');
    const normalizedSource = path.posix.normalize(portableSource);
    const hasParentTraversal = portableSource.split('/').includes('..');
    const isAbsolute = path.posix.isAbsolute(portableSource) || path.win32.isAbsolute(source);
    return { normalizedSource, hasParentTraversal, isAbsolute };
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

            const { normalizedSource, hasParentTraversal, isAbsolute } =
                normalizeEvidenceSource(source);
            if (hasParentTraversal || isAbsolute) {
                addError(
                    errors,
                    `${evidencePath}.source`,
                    'sourcePath',
                    'source는 절대 경로나 .. 구간이 없는 worktree 상대 경로여야 합니다.',
                );
                return;
            }

            const expectedPrefix = SOURCE_SET_PREFIXES[task];
            if (expectedPrefix && !normalizedSource.startsWith(expectedPrefix)) {
                addError(
                    errors,
                    `${evidencePath}.source`,
                    'sourceSet',
                    `${task} evidence source는 ${expectedPrefix} 아래에 있어야 합니다.`,
                );
            }

            const resolvedSource = path.resolve(worktree, normalizedSource);
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

            const methodName = selector.split('.').at(-1);
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

function validatePostgresRequirement(packet, manifest, classification) {
    const errors = [];
    const packetRequired = packet?.postgresRequired;
    const manifestRequired = manifest?.postgresRequired;
    const packetReasons = packet?.postgresRequirementReasons;
    const manifestReasons = manifest?.postgresRequirementReasons;
    const postgresEvidence = (Array.isArray(manifest?.tests) ? manifest.tests : []).flatMap(
        (manifestTest) =>
            (Array.isArray(manifestTest?.evidence) ? manifestTest.evidence : []).filter(
                (evidence) => evidence?.task === 'postgresTest',
            ),
    );

    if (
        typeof packetRequired === 'boolean' &&
        typeof manifestRequired === 'boolean' &&
        packetRequired !== manifestRequired
    ) {
        addError(
            errors,
            '$manifest.postgresRequired',
            'postgresDecisionMismatch',
            'manifest의 postgresRequired가 packet의 결정과 다릅니다.',
        );
    }
    if (
        Array.isArray(packetReasons) &&
        Array.isArray(manifestReasons) &&
        !isDeepStrictEqual(packetReasons, manifestReasons)
    ) {
        addError(
            errors,
            '$manifest.postgresRequirementReasons',
            'postgresReasonMismatch',
            'manifest의 PostgreSQL 판단 근거가 packet과 다릅니다.',
        );
    }

    if (manifestRequired === true && postgresEvidence.length === 0) {
        addError(
            errors,
            '$manifest.tests',
            'postgresEvidence',
            'postgresRequired가 true이면 postgresTest exact selector evidence가 하나 이상 필요합니다.',
        );
    }
    if (manifestRequired === false && postgresEvidence.length > 0) {
        addError(
            errors,
            '$manifest.tests',
            'unexpectedPostgresEvidence',
            'postgresRequired가 false인데 postgresTest evidence가 포함되었습니다.',
        );
    }

    if (classification?.decision === POSTGRES_DECISIONS.REQUIRED && manifestRequired !== true) {
        addError(
            errors,
            '$manifest.postgresRequired',
            'postgresRequired',
            '실제 변경이 PostgreSQL 검증 필수로 분류되어 postgresRequired: true가 필요합니다.',
        );
    }
    if (classification?.decision === POSTGRES_DECISIONS.NEEDS_REVIEW && manifestRequired !== true) {
        addError(
            errors,
            '$manifest.postgresRequired',
            'postgresNeedsReview',
            '변경이 needs-review입니다. false로 생략할 수 없으며 PostgreSQL evidence를 포함해 안전하게 검증해야 합니다.',
        );
    }

    return errors;
}

// changedPaths가 null이면 경로 감사를 건너뛴다. CLI는 항상 실제 변경 경로를 넘기므로
// 전달 게이트에서는 감사가 생략되지 않는다.
export function validateBackendTestManifest(
    packet,
    manifest,
    worktreePath,
    packetSchema,
    manifestSchema,
    changedPaths = null,
    postgresClassification = null,
) {
    const worktree = resolveWorktree(worktreePath);
    const packetErrors = prefixErrors(validatePacket(packet, packetSchema), '$packet');
    const manifestErrors = prefixErrors(validateAgainstSchema(manifestSchema, manifest), '$manifest');
    const pathErrors = changedPaths === null ? [] : auditChangedPaths(packet, changedPaths);
    return [
        ...packetErrors,
        ...manifestErrors,
        ...validateManifestRelations(packet, manifest, worktree),
        ...validatePostgresRequirement(packet, manifest, postgresClassification),
        ...pathErrors,
    ];
}

export function validateBackendTestManifestFiles({
    packetPath,
    manifestPath,
    worktreePath,
    base = null,
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
    const changedPaths = changedPathsIn(path.resolve(worktreePath), base);
    const postgresClassification = classifyPostgresRequirementIn(worktreePath, {
        base,
        changedPaths,
    });
    const errors = validateBackendTestManifest(
        packet,
        manifest,
        worktreePath,
        packetSchema,
        manifestSchema,
        changedPaths,
        postgresClassification,
    );

    return {
        packet,
        manifest,
        packetPath: resolvedPacketPath,
        manifestPath: resolvedManifestPath,
        worktreePath: path.resolve(worktreePath),
        changedPaths,
        postgresClassification,
        errors,
    };
}

function parseArguments(argv) {
    const values = {};
    const allowed = new Set(['--packet', '--manifest', '--worktree', '--base']);
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
            '사용법: node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree> [--base <ref>]',
        );
        process.exitCode = 2;
        return;
    }

    try {
        const result = validateBackendTestManifestFiles({
            packetPath: args.packet,
            manifestPath: args.manifest,
            worktreePath: args.worktree,
            base: args.base ?? null,
        });
        if (result.errors.length > 0) {
            console.error(`backend test manifest 검증 실패: ${result.manifestPath}`);
            console.error(`- PostgreSQL 분류: ${result.postgresClassification.decision}`);
            for (const reason of result.postgresClassification.reasons) {
                console.error(`  - ${reason.code} (${reason.path}): ${reason.message}`);
            }
            for (const error of result.errors) {
                console.error(`- ${error.instancePath}: ${error.message} [${error.keyword}]`);
            }
            process.exitCode = 1;
            return;
        }

        console.log(
            `backend test manifest 검증 통과: ${result.manifestPath} (PostgreSQL ${result.postgresClassification.decision}, T-ID ${result.manifest.tests.length}개, 감사한 변경 경로 ${result.changedPaths.length}개)`,
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
