import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
    mkdirSync,
    mkdtempSync,
    readFileSync,
    rmSync,
    symlinkSync,
    writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = resolve(
    dirname(fileURLToPath(import.meta.url)),
    "prepare-game-catalog.mjs",
);

test("manifest가 없으면 검수 보고서만 만들고 적재 산출물은 만들지 않는다", () => {
    withCase([game(10, "10", "첫 번째 게임", "First Game")], ({ games, ranks, out }) => {
        const result = runCli(games, ranks, out);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.equal(report.status, "blocked");
        assert.ok(report.errors.some(({ code }) => code === "MISSING_MANIFEST"));
        assert.equal(report.inputs.games.rows, 1);
        assert.equal(report.inputs.ranks.rows, 1);
        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("승인된 입력은 내부 id를 제외한 결정적 카탈로그와 UPSERT SQL을 만든다", () => {
    const rows = [
        game(2, "20", "두 번째 게임", "Second Game"),
        game(1, "10", "첫 번째 게임", "First Game"),
    ];

    withCase(rows, ({ root, games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);
        const first = runCli(games, ranks, out, manifest);

        assert.equal(first.status, 0, first.stderr);
        const catalogText = readFileSync(join(out, "service-catalog.json"), "utf8");
        const sqlText = readFileSync(join(out, "upsert-games.sql"), "utf8");
        const reportText = readFileSync(join(out, "quality-report.json"), "utf8");
        const catalog = JSON.parse(catalogText);
        const report = JSON.parse(reportText);

        assert.deepEqual(catalog.map(({ bgg_id }) => bgg_id), [10, 20]);
        assert.ok(catalog.every((row) => !("id" in row)));
        assert.equal(report.checks.matchedRows, 2);
        assert.equal(report.checks.baselineNameMismatchRows, 0);
        assert.equal(report.checks.expansionRows, 0);
        assert.equal(report.toolCommit, "0123456789abcdef0123456789abcdef01234567");
        assert.match(sqlText, /^BEGIN;/);
        assert.match(sqlText, /ON CONFLICT \(bgg_id\) DO UPDATE/);
        assert.match(sqlText, /COMMIT;\n$/);
        assert.doesNotMatch(sqlText, /DELETE FROM games/i);

        const secondOut = join(root, "second-output");
        const second = runCli(games, ranks, secondOut, manifest);
        assert.equal(second.status, 0, second.stderr);
        assert.equal(
            readFileSync(join(secondOut, "service-catalog.json"), "utf8"),
            catalogText,
        );
        assert.equal(
            readFileSync(join(secondOut, "upsert-games.sql"), "utf8"),
            sqlText,
        );
        assert.equal(
            readFileSync(join(secondOut, "quality-report.json"), "utf8"),
            reportText,
        );
    });
});

test("UPSERT SQL은 표준 문자열 모드를 먼저 설정하고 역슬래시와 따옴표를 보존한다", () => {
    const row = game(1, "10", "경로 \\ ' 게임", "Path \\ ' Game");

    withCase([row], ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);
        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const sql = readFileSync(join(out, "upsert-games.sql"), "utf8");
        const standardStringsSetting = "SET LOCAL standard_conforming_strings = on;";
        assert.ok(sql.includes(standardStringsSetting));
        assert.ok(sql.indexOf(standardStringsSetting) < sql.indexOf("INSERT INTO games"));
        assert.ok(sql.includes(`'${row.name.replaceAll("'", "''")}'`));
    });
});

test("같은 bgg_id가 둘 이상이면 데이터베이스 쓰기 전에 실패한다", () => {
    const rows = [
        game(1, "10", "첫 번째 게임", "First Game"),
        game(2, "10.0", "중복 게임", "Duplicate Game"),
    ];

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);
        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(report.errors.some(({ code }) => code === "DUPLICATE_BGG_ID"));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("차단된 재실행은 같은 출력 경로의 이전 적재 산출물을 제거한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeManifest(manifest, games, ranks, []);
        assert.equal(runCli(games, ranks, out, manifest).status, 0);
        readFileSync(join(out, "service-catalog.json"));
        readFileSync(join(out, "upsert-games.sql"));

        const value = readJson(manifest);
        value.review.status = "pending";
        writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);
        assert.equal(runCli(games, ranks, out, manifest).status, 1);

        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("손상된 JSON·CSV·manifest도 차단 보고서를 남긴다", async (context) => {
    await context.test("games JSON", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            assert.equal(runCli(games, ranks, out, manifest).status, 0);
            writeFileSync(games, "{\n");
            assertParseFailure(
                runCli(games, ranks, out, manifest),
                out,
                "INVALID_GAMES_JSON",
            );
        });
    });

    await context.test("ranks CSV", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({ games, ranks, out }) => {
            writeFileSync(ranks, 'id,name\n10,"닫히지 않은 이름\n');
            assertParseFailure(runCli(games, ranks, out), out, "INVALID_RANKS_CSV");
        });
    });

    await context.test("manifest JSON", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeFileSync(manifest, "{\n");
            assertParseFailure(
                runCli(games, ranks, out, manifest),
                out,
                "INVALID_MANIFEST_JSON",
            );
        });
    });
});

test("입력과 출력 파일 경로가 같으면 원본을 보존하고 실행 전에 거절한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({ games, ranks, out }) => {
        mkdirSync(out, { recursive: true });
        const conflictingInput = join(out, "quality-report.json");
        const original = readFileSync(games, "utf8");
        writeFileSync(conflictingInput, original);

        const result = runCli(conflictingInput, ranks, out);

        assert.equal(result.status, 2);
        assert.match(result.stderr, /입력 파일과 출력 파일 경로가 같습니다/);
        assert.equal(readFileSync(conflictingInput, "utf8"), original);
    });
});

test("출력 디렉터리 symlink alias가 입력 산출물과 충돌하면 원본을 보존하고 거절한다", () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-catalog-alias-"));
    try {
        const dataDirectory = join(root, "data");
        const aliasDirectory = join(root, "alias");
        mkdirSync(dataDirectory, { recursive: true });
        symlinkSync(dataDirectory, aliasDirectory, "dir");

        const games = join(dataDirectory, "service-catalog.json");
        const ranks = join(root, "ranks.csv");
        const manifest = join(root, "manifest.json");
        const original = "기존 산출물";
        writeFileSync(games, original);
        const row = game(1, "10", "첫 번째 게임", "First Game");
        writeFileSync(ranks, ranksCsv([row]));
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, aliasDirectory, manifest);

        assert.equal(result.status, 2);
        assert.match(result.stderr, /입력 파일과 출력 파일 경로가 같습니다/);
        assert.equal(readFileSync(games, "utf8"), original);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test("반복 문구 경고는 검수자가 명시적으로 승인하기 전까지 적재를 막는다", () => {
    const rows = Array.from({ length: 20 }, (_, index) =>
        game(
            index + 1,
            String(index + 100),
            `반복 게임 ${index + 1}`,
            `Repeated Game ${index + 1}`,
        ),
    );

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);
        const blocked = runCli(games, ranks, out, manifest);
        assert.equal(blocked.status, 1);

        const blockedReport = readJson(join(out, "quality-report.json"));
        const warningCodes = blockedReport.warnings.map(({ code }) => code);
        assert.ok(warningCodes.includes("LOW_DETAIL_DESCRIPTION_DIVERSITY"));
        const lowDiversityWarnings = blockedReport.warnings.filter(({ code }) =>
            code.startsWith("LOW_"),
        );
        assert.ok(lowDiversityWarnings.length > 0);
        assert.ok(lowDiversityWarnings.every(({ message }) => message.includes(" 값이 ")));
        assert.ok(
            blockedReport.errors.some(
                ({ code }) => code === "UNACKNOWLEDGED_WARNINGS",
            ),
        );

        writeManifest(manifest, games, ranks, warningCodes);
        const approvedOut = join(dirname(out), "approved-output");
        const approved = runCli(games, ranks, approvedOut, manifest);
        assert.equal(approved.status, 0, approved.stderr);
        assert.equal(readJson(join(approvedOut, "quality-report.json")).status, "ready");
    });
});

test("필수값 누락과 BGG 기준 이름 불일치는 함께 보고하고 적재를 막는다", () => {
    const invalid = game(1, "10", "검수 필요 게임", "Unverified Name");
    invalid.description = "";

    withCase([invalid], ({ games, ranks, manifest, out }) => {
        writeFileSync(
            ranks,
            ranksCsv([game(1, "10", "검수 필요 게임", "Baseline Name")]),
        );
        writeManifest(manifest, games, ranks, []);
        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        const errorCodes = report.errors.map(({ code }) => code);
        assert.ok(errorCodes.includes("MISSING_REQUIRED_VALUE"));
        assert.ok(errorCodes.includes("BASELINE_NAME_MISMATCH"));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("같은 표시 이름의 서로 다른 BGG 게임은 버전 충돌 경고로 남긴다", () => {
    const rows = [
        game(1, "10", "같은 게임", "Same Game"),
        game(2, "20", "같은 게임", "Same Game: Second Edition"),
    ];

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);
        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(
            report.warnings.some(({ code }) => code === "POSSIBLE_VERSION_COLLISION"),
        );
    });
});

test("TODO 출처 정보는 검수 승인으로 바꿔도 적재를 허용하지 않는다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeManifest(manifest, games, ranks, []);
        const value = readJson(manifest);
        value.sources.games.sourceReference = "TODO: 실제 출처";
        value.sources.games.acquiredAt = "TODO";
        writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        assert.ok(
            readJson(join(out, "quality-report.json")).errors.some(
                ({ code }) => code === "INVALID_SOURCE_METADATA",
            ),
        );
    });
});

test("acceptedWarnings가 배열이 아니면 검수 오류 보고서만 남긴다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeManifest(manifest, games, ranks, []);
        const value = readJson(manifest);
        value.review.acceptedWarnings = { LOW_DESCRIPTION_DIVERSITY: true };
        writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.equal(report.status, "blocked");
        assert.ok(report.errors.some(({ code }) => code === "INVALID_REVIEW"));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("games 배열의 비객체 행은 구조화된 차단 오류로 보고한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeFileSync(games, "[null]\n");
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(report.errors.some(({ code }) => code === "INVALID_GAME_ROW"));
        assert.equal(report.status, "blocked");
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("CSV 행의 열 수가 헤더와 다르면 검수 보고서를 남긴다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeFileSync(ranks, `${readFileSync(ranks, "utf8").replace(/\n$/, ",추가 열\n")}`);
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(report.errors.some(({ code }) => code === "INVALID_RANKS_CSV"));
        assert.equal(report.status, "blocked");
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("빈 games 입력은 실행할 수 없는 빈 UPSERT를 만들지 않는다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeFileSync(games, "[]\n");
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(report.errors.some(({ code }) => code === "EMPTY_GAMES_INPUT"));
        assert.equal(report.status, "blocked");
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

function withCase(rows, operation) {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-catalog-"));
    try {
        const games = join(root, "games.json");
        const ranks = join(root, "ranks.csv");
        const manifest = join(root, "manifest.json");
        const out = join(root, "output");
        writeFileSync(games, `${JSON.stringify(rows, null, 2)}\n`);
        writeFileSync(ranks, ranksCsv(rows));
        operation({ root, games, ranks, manifest, out });
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

function runCli(games, ranks, out, manifest) {
    const args = [SCRIPT, "--games", games, "--ranks", ranks, "--out", out];
    if (manifest) {
        args.push("--manifest", manifest);
    }
    return spawnSync(process.execPath, args, { encoding: "utf8" });
}

function writeManifest(path, gamesPath, ranksPath, acceptedWarnings) {
    const manifest = {
        schemaVersion: 1,
        batchId: "2026-07-24-test-catalog",
        toolCommit: "0123456789abcdef0123456789abcdef01234567",
        sources: {
            games: sourceMetadata(gamesPath, "팀 검수 자료"),
            ranks: sourceMetadata(ranksPath, "BGG 기준 스냅샷"),
        },
        fieldSources: {
            bgg_id: "ranks.id",
            name: "games.name",
            english_name: "games.english_name",
            alias: "games.alias",
            image_url: "games.image_url",
            recommended_player_count: "games.recommended_player_count",
            tag: "games.tag",
            estimated_play_time: "games.estimated_play_time",
            complexity: "games.complexity",
            description: "games.description",
            detail_description: "games.detail_description",
        },
        review: {
            status: "approved",
            reviewedAt: "2026-07-27T10:00:00Z",
            reviewers: ["test-reviewer"],
            acceptedWarnings,
        },
    };
    writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`);
}

function sourceMetadata(path, source) {
    const contents = readFileSync(path);
    return {
        fileName: path.split("/").at(-1),
        sha256: createHash("sha256").update(contents).digest("hex"),
        sourceReference: source,
        acquiredAt: "2026-07-24T00:00:00Z",
        usageTerms: "테스트 전용 자료",
    };
}

function game(id, bggId, name, englishName) {
    return {
        id,
        bgg_id: bggId,
        name,
        english_name: englishName,
        alias: `${name}, ${englishName}`,
        image_url: `https://example.com/${bggId}.jpg`,
        recommended_player_count: "2~4명",
        tag: "전략",
        estimated_play_time: "60~120분",
        complexity: 3.25,
        description: `${name}(${englishName})은 2020년에 출시된 전략 게임입니다.`,
        detail_description: `[승리 조건] ${name}에서 가장 많은 점수를 얻으면 승리합니다.`,
    };
}

function ranksCsv(rows) {
    const header = [
        "id",
        "name",
        "yearpublished",
        "rank",
        "bayesaverage",
        "average",
        "usersrated",
        "is_expansion",
        "abstracts_rank",
        "cgs_rank",
        "childrensgames_rank",
        "familygames_rank",
        "partygames_rank",
        "strategygames_rank",
        "thematic_rank",
        "wargames_rank",
    ].join(",");
    const body = rows.map((row) =>
        [
            row.bgg_id,
            csvCell(row.english_name),
            "2020",
            row.id,
            "8.0",
            "8.0",
            "100",
            "0",
            "",
            "",
            "",
            "",
            "",
            "1",
            "",
            "",
        ].join(","),
    );
    return `${header}\n${body.join("\n")}\n`;
}

function csvCell(value) {
    return `"${String(value).replaceAll('"', '""')}"`;
}

function readJson(path) {
    return JSON.parse(readFileSync(path, "utf8"));
}

function assertParseFailure(result, out, expectedCode) {
    assert.equal(result.status, 1);
    const report = readJson(join(out, "quality-report.json"));
    assert.equal(report.status, "blocked");
    assert.ok(report.errors.some(({ code }) => code === expectedCode));
    assert.throws(() => readFileSync(join(out, "service-catalog.json")));
    assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
}
