import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const POSTGRES_DECISIONS = {
    REQUIRED: 'required',
    NOT_REQUIRED: 'not-required',
    NEEDS_REVIEW: 'needs-review',
};

const DATABASE_MIGRATION_PATH = /^src\/main\/resources\/db\/(?:migration|vendor-migration)\//u;
const POSTGRES_TEST_PATH = /^src\/postgresTest\//u;
const PRODUCTION_JAVA_PATH = /^src\/main\/java\/.+\.java$/u;
const PRODUCTION_RESOURCE_PATH = /^src\/main\/resources\//u;
const ENTITY_PATH = /\/(?:entity|model)\//u;
const REPOSITORY_PATH = /\/repository\//u;
const RUNTIME_REVIEW_PATHS = [
    /^\.github\/workflows\//u,
    /^(?:build\.gradle|settings\.gradle|gradle\.properties)$/u,
    /^gradle\//u,
    /^(?:compose[^/]*\.ya?ml|Dockerfile)$/u,
    /\/global\/(?:config|security\/session)\//u,
    /\/infra\/redis\//u,
    /\/chat\/websocket\//u,
];

const REQUIRED_JAVA_SIGNALS = [
    {
        code: 'jpa-mapping',
        pattern:
            /@(?:Entity|Table|Column|JoinColumn|JoinTable|ManyToOne|OneToMany|OneToOne|ManyToMany|Embedded|Embeddable|Enumerated|Convert|GeneratedValue|Version|Index|UniqueConstraint)\b|jakarta\.persistence\./u,
        message: 'JPA 매핑 또는 데이터베이스 제약 변경 신호가 있습니다.',
    },
    {
        code: 'database-query',
        pattern:
            /@Query\b|nativeQuery\s*=|@Modifying\b|EntityManager\b|JdbcTemplate\b|\b(?:select|insert|update|delete)\b[\s\S]*\b(?:from|into|set)\b/iu,
        message: 'JPA/native query 또는 직접 SQL 변경 신호가 있습니다.',
    },
    {
        code: 'ordering-or-case',
        pattern:
            /\bSort\.(?:by|Order|Direction|NullHandling)\b|\b(?:nullsFirst|nullsLast|nullsNative)\s*\(|\b(?:IgnoreCase|OrderBy|Collation)\b|\b(?:lower|upper|collate|ilike)\s*\(/iu,
        message: '정렬 또는 대소문자 비교의 데이터베이스 의미 변경 신호가 있습니다.',
    },
    {
        code: 'transaction-or-concurrency',
        pattern:
            /@Lock\b|LockModeType\b|\b(?:PESSIMISTIC|SERIALIZABLE|REPEATABLE_READ|OPTIMISTIC_FORCE_INCREMENT)\b|Isolation\.|Propagation\.REQUIRES_NEW|@Retryable\b|\b(?:deadlock|CountDownLatch|CyclicBarrier|ExecutorService)\b|\bsynchronized\b/iu,
        message: '트랜잭션 격리, 잠금, 재시도 또는 동시성 변경 신호가 있습니다.',
    },
    {
        code: 'postgres-specific',
        pattern:
            /\b(?:jsonb|clock_timestamp|on\s+conflict|returning|for\s+update|skip\s+locked|at\s+time\s+zone|timestamp\s+(?:with|without)\s+time\s+zone)\b|@JdbcTypeCode\b|SqlTypes\.JSON\b/iu,
        message: 'PostgreSQL 전용 문법, 시간 또는 JSON 의미 변경 신호가 있습니다.',
    },
    {
        code: 'index-or-plan',
        pattern: /\b(?:create\s+(?:unique\s+)?index|explain|analyze|query\s+plan)\b/iu,
        message: '인덱스 또는 실행 계획 변경 신호가 있습니다.',
    },
];

const REVIEW_JAVA_CONTEXT =
    /(?:\.repository\.|JpaRepository\b|CrudRepository\b|EntityManager\b|JdbcTemplate\b|DataSource\b|@Transactional\b|TransactionTemplate\b|org\.springframework\.data\.domain\.(?:PageRequest|Pageable|Sort)\b|org\.springframework\.(?:session|data\.redis)|RedisTemplate\b|WebSocket\b|SimpMessagingTemplate\b)/u;

function normalizePath(filePath) {
    return filePath.trim().replaceAll('\\', '/').replace(/^\.\//u, '');
}

function reason(code, filePath, message) {
    return { code, path: filePath, message };
}

function changedText(patch) {
    return patch
        .split(/\r?\n/u)
        .filter(
            (line) =>
                (line.startsWith('+') || line.startsWith('-')) &&
                !line.startsWith('+++') &&
                !line.startsWith('---'),
        )
        .map((line) => line.slice(1))
        .join('\n');
}

function classifyProductionJava(change) {
    const filePath = change.path;
    if (ENTITY_PATH.test(filePath)) {
        return {
            decision: POSTGRES_DECISIONS.REQUIRED,
            reason: reason('jpa-entity-path', filePath, 'entity/model 변경은 JPA 매핑 의미를 포함합니다.'),
        };
    }
    if (REPOSITORY_PATH.test(filePath)) {
        return {
            decision: POSTGRES_DECISIONS.REQUIRED,
            reason: reason(
                'repository-path',
                filePath,
                'repository 변경은 조회, 정렬 또는 데이터베이스 실행 의미를 포함합니다.',
            ),
        };
    }

    const diffText = changedText(change.patch) || (change.untracked ? change.contents : '');
    for (const signal of REQUIRED_JAVA_SIGNALS) {
        if (signal.pattern.test(diffText)) {
            return {
                decision: POSTGRES_DECISIONS.REQUIRED,
                reason: reason(signal.code, filePath, signal.message),
            };
        }
    }

    if (RUNTIME_REVIEW_PATHS.some((pattern) => pattern.test(filePath))) {
        return {
            decision: POSTGRES_DECISIONS.NEEDS_REVIEW,
            reason: reason(
                'runtime-path-review',
                filePath,
                '세션, Redis, WebSocket 또는 런타임 설정 경로라 Docker 검증 생략을 자동 확정할 수 없습니다.',
            ),
        };
    }

    if (REVIEW_JAVA_CONTEXT.test(`${diffText}\n${change.contents}`)) {
        return {
            decision: POSTGRES_DECISIONS.NEEDS_REVIEW,
            reason: reason(
                'database-context-review',
                filePath,
                '데이터 접근 또는 트랜잭션 문맥이 있으나 PostgreSQL 필요 여부를 자동 확정할 신호가 부족합니다.',
            ),
        };
    }

    return {
        decision: POSTGRES_DECISIONS.NOT_REQUIRED,
        reason: reason(
            'pure-java-change',
            filePath,
            '데이터 접근 신호가 없는 Java 변경은 H2 또는 단위 테스트 경계입니다.',
        ),
    };
}

function classifyChange(change) {
    const filePath = normalizePath(change.path);
    const normalized = { ...change, path: filePath };

    if (POSTGRES_TEST_PATH.test(filePath)) {
        return {
            decision: POSTGRES_DECISIONS.REQUIRED,
            reason: reason(
                'postgres-test-source',
                filePath,
                'PostgreSQL source set 변경은 해당 테스트 실행이 필요합니다.',
            ),
        };
    }
    if (DATABASE_MIGRATION_PATH.test(filePath) || (PRODUCTION_RESOURCE_PATH.test(filePath) && filePath.endsWith('.sql'))) {
        return {
            decision: POSTGRES_DECISIONS.REQUIRED,
            reason: reason(
                'flyway-or-sql',
                filePath,
                'Flyway 또는 운영 SQL 변경은 실제 PostgreSQL 검증이 필요합니다.',
            ),
        };
    }
    if (PRODUCTION_JAVA_PATH.test(filePath)) {
        return classifyProductionJava(normalized);
    }
    if (PRODUCTION_RESOURCE_PATH.test(filePath)) {
        const diffText = changedText(change.patch) || (change.untracked ? change.contents : '');
        if (/\b(?:spring\.(?:datasource|jpa|flyway)|jdbc|hibernate|timezone|time-zone)\b/iu.test(diffText)) {
            return {
                decision: POSTGRES_DECISIONS.REQUIRED,
                reason: reason(
                    'database-configuration',
                    filePath,
                    '데이터소스, JPA, Flyway 또는 시간대 설정 변경 신호가 있습니다.',
                ),
            };
        }
        return {
            decision: POSTGRES_DECISIONS.NEEDS_REVIEW,
            reason: reason(
                'main-resource-review',
                filePath,
                '운영 resource 변경이라 PostgreSQL 또는 런타임 영향 여부를 자동 확정할 수 없습니다.',
            ),
        };
    }
    if (RUNTIME_REVIEW_PATHS.some((pattern) => pattern.test(filePath))) {
        return {
            decision: POSTGRES_DECISIONS.NEEDS_REVIEW,
            reason: reason(
                'build-or-runtime-review',
                filePath,
                '빌드, CI 또는 Docker 런타임 변경은 선택 검증을 자동으로 줄이지 않습니다.',
            ),
        };
    }

    return {
        decision: POSTGRES_DECISIONS.NOT_REQUIRED,
        reason: reason(
            'non-database-path',
            filePath,
            '문서, 일반 테스트 또는 도구 변경으로 데이터베이스 의미를 바꾸지 않습니다.',
        ),
    };
}

export function classifyPostgresChanges(changes) {
    if (!Array.isArray(changes) || changes.length === 0) {
        return {
            decision: POSTGRES_DECISIONS.NEEDS_REVIEW,
            reasons: [
                reason(
                    'empty-change-set',
                    '(none)',
                    '감사할 변경 경로가 없어 PostgreSQL 검증 생략을 확정할 수 없습니다.',
                ),
            ],
        };
    }

    const classified = changes.map(classifyChange);
    const selectedDecision = classified.some(
        (entry) => entry.decision === POSTGRES_DECISIONS.REQUIRED,
    )
        ? POSTGRES_DECISIONS.REQUIRED
        : classified.some((entry) => entry.decision === POSTGRES_DECISIONS.NEEDS_REVIEW)
          ? POSTGRES_DECISIONS.NEEDS_REVIEW
          : POSTGRES_DECISIONS.NOT_REQUIRED;

    return {
        decision: selectedDecision,
        reasons: classified
            .filter((entry) => entry.decision === selectedDecision)
            .map((entry) => entry.reason),
    };
}

function runGit(worktree, args) {
    return execFileSync('git', ['-C', worktree, ...args], {
        encoding: 'utf8',
        maxBuffer: 32 * 1024 * 1024,
        windowsHide: true,
    });
}

export function changedPathsIn(worktree, base = null) {
    const diffPaths = runGit(worktree, [
        'diff',
        '--name-only',
        '--no-renames',
        '-z',
        base ?? 'HEAD',
    ])
        .split('\0')
        .filter(Boolean);
    const untrackedPaths = runGit(worktree, ['ls-files', '--others', '--exclude-standard', '-z'])
        .split('\0')
        .filter(Boolean);

    return [...new Set([...diffPaths, ...untrackedPaths].map(normalizePath))].sort();
}

function readChange(worktree, filePath, base, untrackedPaths) {
    const resolvedPath = path.resolve(worktree, filePath);
    let contents = '';
    if (fs.existsSync(resolvedPath) && fs.statSync(resolvedPath).isFile()) {
        contents = fs.readFileSync(resolvedPath, 'utf8');
    }

    const patch = runGit(worktree, [
        'diff',
        '--no-ext-diff',
        '--unified=0',
        '--no-renames',
        base ?? 'HEAD',
        '--',
        filePath,
    ]);

    return {
        path: filePath,
        patch,
        contents,
        untracked: untrackedPaths.has(filePath),
    };
}

export function classifyPostgresRequirementIn(worktreePath, { base = null, changedPaths = null } = {}) {
    const worktree = fs.realpathSync(path.resolve(worktreePath));
    const selectedPaths = (changedPaths ?? changedPathsIn(worktree, base))
        .map(normalizePath)
        .filter(Boolean);
    const untrackedPaths = new Set(
        runGit(worktree, ['ls-files', '--others', '--exclude-standard', '-z'])
            .split('\0')
            .filter(Boolean)
            .map(normalizePath),
    );
    const changes = selectedPaths.map((filePath) =>
        readChange(worktree, filePath, base, untrackedPaths),
    );

    return {
        ...classifyPostgresChanges(changes),
        changedPaths: [...new Set(selectedPaths)].sort(),
    };
}

function parseArguments(argv) {
    const values = {};
    const allowed = new Set(['--worktree', '--base']);
    for (let index = 0; index < argv.length; index += 2) {
        const option = argv[index];
        const value = argv[index + 1];
        if (!allowed.has(option) || value === undefined || value.startsWith('--')) {
            return null;
        }
        values[option.slice(2)] = value;
    }
    return values.worktree ? values : null;
}

function runCli() {
    const args = parseArguments(process.argv.slice(2));
    if (!args) {
        console.error(
            '사용법: node scripts/classify-postgres-requirement.mjs --worktree <worktree> [--base <ref>]',
        );
        process.exitCode = 2;
        return;
    }

    try {
        const result = classifyPostgresRequirementIn(args.worktree, { base: args.base ?? null });
        process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    } catch (error) {
        console.error(`PostgreSQL 필요 변경 분류 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) {
    runCli();
}
