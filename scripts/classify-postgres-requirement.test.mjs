import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
    POSTGRES_DECISIONS,
    changedPathsIn,
    classifyPostgresChanges,
    classifyPostgresRequirementIn,
} from './classify-postgres-requirement.mjs';

const scriptPath = fileURLToPath(new URL('./classify-postgres-requirement.mjs', import.meta.url));
const myRoomQuerySourcePath = fileURLToPath(
    new URL(
        '../src/main/java/cloud/bamsongi/albammate/room/service/query/MyRoomQueryService.java',
        import.meta.url,
    ),
);
const myRoomQueryRepositoryPath =
    'src/main/java/cloud/bamsongi/albammate/room/service/query/MyRoomQueryService.java';

function change(filePath, changedLine = '', contents = '') {
    return {
        path: filePath,
        patch: changedLine ? `@@ -1 +1 @@\n-${changedLine}\n+${changedLine}\n` : '',
        contents,
        untracked: false,
    };
}

function decisionFor(changes) {
    return classifyPostgresChanges(changes).decision;
}

test('Flyway, 운영 SQL과 postgresTest 변경을 required로 분류한다', () => {
    assert.equal(
        decisionFor([
            change('src/main/resources/db/migration/V42__add_room_constraint.sql', 'alter table rooms'),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
    assert.equal(
        decisionFor([change('src/main/resources/report.sql', 'select * from rooms')]),
        POSTGRES_DECISIONS.REQUIRED,
    );
    assert.equal(
        decisionFor([
            change(
                'src/postgresTest/java/cloud/bamsongi/albammate/room/RoomSchemaPostgresTest.java',
                'void 제약조건을_검증한다() {}',
            ),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
});

test('JPA 매핑, repository와 제약 변경을 required로 분류한다', () => {
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/room/entity/Room.java',
                'private String title;',
            ),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/room/repository/RoomRepository.java',
                'List<Room> findAll();',
            ),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/game/Game.java',
                '@Table(uniqueConstraints = @UniqueConstraint(columnNames = "external_id"))',
            ),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
});

test('native query, 정렬과 대소문자 변경을 required로 분류한다', () => {
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/search/SearchQuery.java',
                '@Query(value = "select * from games where title ilike :title", nativeQuery = true)',
            ),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/search/SearchOrder.java',
                'return Sort.by("title").ignoreCase();',
            ),
        ]),
        POSTGRES_DECISIONS.REQUIRED,
    );
    for (const changedLine of [
        'return Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id"));',
        'return order.nullsLast();',
        'return order.with(Sort.NullHandling.NULLS_FIRST);',
    ]) {
        assert.equal(
            decisionFor([
                change(
                    'src/main/java/cloud/bamsongi/albammate/room/service/query/MyRoomQueryService.java',
                    changedLine,
                ),
            ]),
            POSTGRES_DECISIONS.REQUIRED,
            changedLine,
        );
    }
});

test('격리, 잠금, 재시도와 동시성 변경을 required로 분류한다', () => {
    for (const changedLine of [
        '@Transactional(isolation = Isolation.REPEATABLE_READ)',
        '@Lock(LockModeType.PESSIMISTIC_WRITE)',
        '@Retryable(OptimisticLockException.class)',
        'CountDownLatch start = new CountDownLatch(1);',
    ]) {
        assert.equal(
            decisionFor([
                change(
                    'src/main/java/cloud/bamsongi/albammate/room/service/RoomCommandService.java',
                    changedLine,
                ),
            ]),
            POSTGRES_DECISIONS.REQUIRED,
            changedLine,
        );
    }
});

test('PostgreSQL 문법, 인덱스, 실행 계획, 시간과 JSONB 변경을 required로 분류한다', () => {
    for (const changedLine of [
        'String sql = "select payload::jsonb from outbox for update skip locked";',
        'String sql = "select clock_timestamp() at time zone \'UTC\'";',
        'String sql = "create index idx_room_open on rooms(id)";',
        'String sql = "explain analyze select * from rooms";',
    ]) {
        assert.equal(
            decisionFor([
                change(
                    'src/main/java/cloud/bamsongi/albammate/notification/OutboxQuery.java',
                    changedLine,
                ),
            ]),
            POSTGRES_DECISIONS.REQUIRED,
            changedLine,
        );
    }
});

test('DTO와 데이터 접근이 없는 순수 계산 변경은 not-required로 분류한다', () => {
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/room/dto/RoomSummary.java',
                'public record RoomSummary(long id, String title) {}',
            ),
            change(
                'src/main/java/cloud/bamsongi/albammate/game/service/PlayerCountCalculator.java',
                'return minimum + maximum;',
                'final class PlayerCountCalculator { int calculate(int minimum, int maximum) { return minimum + maximum; } }',
            ),
            change('src/test/java/cloud/bamsongi/albammate/room/RoomPolicyTest.java', 'void 계산한다() {}'),
        ]),
        POSTGRES_DECISIONS.NOT_REQUIRED,
    );
});

test('데이터 접근 문맥과 런타임 경로가 애매하면 needs-review로 분류한다', () => {
    assert.equal(
        decisionFor([
            change(
                'src/main/java/cloud/bamsongi/albammate/room/service/RoomService.java',
                'return room.title();',
                'import cloud.bamsongi.albammate.room.repository.RoomRepository;\nclass RoomService {}',
            ),
        ]),
        POSTGRES_DECISIONS.NEEDS_REVIEW,
    );
    for (const springDataContext of [
        'import org.springframework.data.domain.PageRequest;\nclass RoomService {}',
        'import org.springframework.data.domain.Pageable;\nclass RoomService {}',
    ]) {
        assert.equal(
            decisionFor([
                change(
                    'src/main/java/cloud/bamsongi/albammate/room/service/RoomService.java',
                    'return room.title();',
                    springDataContext,
                ),
            ]),
            POSTGRES_DECISIONS.NEEDS_REVIEW,
            springDataContext,
        );
    }
    assert.equal(
        decisionFor([change('.github/workflows/ci.yml', 'jobs:')]),
        POSTGRES_DECISIONS.NEEDS_REVIEW,
    );
    assert.equal(decisionFor([]), POSTGRES_DECISIONS.NEEDS_REVIEW);
});

test('required 변경이 하나라도 있으면 안전한 변경과 섞여도 required가 우선한다', () => {
    const result = classifyPostgresChanges([
        change('src/main/java/cloud/bamsongi/albammate/room/dto/RoomSummary.java', 'record RoomSummary() {}'),
        change('src/main/resources/db/migration/V42__room.sql', 'alter table rooms add column memo text;'),
    ]);

    assert.equal(result.decision, POSTGRES_DECISIONS.REQUIRED);
    assert.deepEqual(result.reasons.map((entry) => entry.code), ['flyway-or-sql']);
});

function createGitWorktree(t) {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), 'postgres-classifier-'));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    const git = (...args) =>
        spawnSync('git', ['-C', worktree, ...args], { encoding: 'utf8', windowsHide: true });
    git('init', '--quiet');
    fs.writeFileSync(path.join(worktree, 'README.md'), '# baseline\n', 'utf8');
    git('add', '--all');
    git(
        '-c',
        'user.name=test',
        '-c',
        'user.email=test@example.com',
        'commit',
        '--quiet',
        '--message=baseline',
    );
    return { worktree, git };
}

test('실제 MyRoomQueryService의 Spring Data 정렬 방향 변경을 required로 분류한다', (t) => {
    const { worktree, git } = createGitWorktree(t);
    const sourceContents = fs.readFileSync(myRoomQuerySourcePath, 'utf8');
    const descendingOrder = 'Sort.Order.desc("startAt")';
    const ascendingOrder = 'Sort.Order.asc("startAt")';
    assert.match(sourceContents, /Sort\.Order\.desc\("startAt"\)/u);

    const temporarySourcePath = path.join(worktree, myRoomQueryRepositoryPath);
    fs.mkdirSync(path.dirname(temporarySourcePath), { recursive: true });
    fs.writeFileSync(temporarySourcePath, sourceContents, 'utf8');
    git('add', '--all');
    const baseline = git(
        '-c',
        'user.name=test',
        '-c',
        'user.email=test@example.com',
        'commit',
        '--quiet',
        '--message=add-my-room-query-service',
    );
    assert.equal(baseline.status, 0, baseline.stderr);

    fs.writeFileSync(
        temporarySourcePath,
        sourceContents.replace(descendingOrder, ascendingOrder),
        'utf8',
    );

    const result = classifyPostgresRequirementIn(worktree);
    assert.equal(result.decision, POSTGRES_DECISIONS.REQUIRED);
    assert.deepEqual(result.reasons.map((entry) => entry.code), ['ordering-or-case']);
    assert.deepEqual(result.changedPaths, [myRoomQueryRepositoryPath]);
});

test('실제 worktree의 추적·미추적 변경을 모아 분류한다', (t) => {
    const { worktree } = createGitWorktree(t);
    const dtoPath = 'src/main/java/cloud/bamsongi/albammate/room/dto/RoomSummary.java';
    fs.mkdirSync(path.dirname(path.join(worktree, dtoPath)), { recursive: true });
    fs.writeFileSync(path.join(worktree, dtoPath), 'public record RoomSummary(long id) {}\n', 'utf8');

    assert.deepEqual(changedPathsIn(worktree), [dtoPath]);
    assert.equal(
        classifyPostgresRequirementIn(worktree).decision,
        POSTGRES_DECISIONS.NOT_REQUIRED,
    );

    const migrationPath = 'src/main/resources/db/migration/V42__room.sql';
    fs.mkdirSync(path.dirname(path.join(worktree, migrationPath)), { recursive: true });
    fs.writeFileSync(path.join(worktree, migrationPath), 'alter table rooms add column memo text;\n', 'utf8');

    const result = classifyPostgresRequirementIn(worktree);
    assert.equal(result.decision, POSTGRES_DECISIONS.REQUIRED);
    assert.ok(result.changedPaths.includes(dtoPath));
    assert.ok(result.changedPaths.includes(migrationPath));
});

test('CLI는 분류 결정, 근거와 변경 경로를 JSON으로 출력한다', (t) => {
    const { worktree } = createGitWorktree(t);
    const dtoPath = 'src/main/java/cloud/bamsongi/albammate/room/dto/RoomSummary.java';
    fs.mkdirSync(path.dirname(path.join(worktree, dtoPath)), { recursive: true });
    fs.writeFileSync(path.join(worktree, dtoPath), 'public record RoomSummary(long id) {}\n', 'utf8');

    const result = spawnSync(process.execPath, [scriptPath, '--worktree', worktree], {
        encoding: 'utf8',
    });

    assert.equal(result.status, 0, result.stderr);
    const output = JSON.parse(result.stdout);
    assert.equal(output.decision, POSTGRES_DECISIONS.NOT_REQUIRED);
    assert.deepEqual(output.changedPaths, [dtoPath]);
    assert.equal(output.reasons[0].code, 'pure-java-change');
});
