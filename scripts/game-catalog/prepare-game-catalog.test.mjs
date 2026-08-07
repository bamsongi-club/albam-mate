import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
    linkSync,
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
        assert.ok(catalog.every((row) => row.supported_player_count === "2~4명"));
        assert.ok(catalog.every((row) => !("recommended_player_count" in row)));
        assert.equal(report.checks.matchedRows, 2);
        assert.equal(report.checks.baselineNameMismatchRows, 0);
        assert.equal(report.checks.expansionRows, 0);
        assert.deepEqual(report.selection, {
            candidateRows: 2,
            includedRows: 2,
            excludedRows: 0,
            exclusions: [],
        });
        assert.ok(report.selectionRules.include);
        assert.ok(report.versionRules.baseGame);
        assert.equal(report.toolCommit, "0123456789abcdef0123456789abcdef01234567");
        assert.match(sqlText, /^BEGIN;/);
        assert.match(sqlText, /supported_player_count/);
        assert.doesNotMatch(sqlText, /recommended_player_count/);
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

test("입력 내부 id가 BGG 순위와 달라도 bgg_id 기준으로 적재한다", () => {
    const row = game(99999, "10", "첫 번째 게임", "First Game");

    withCase([row], ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const catalog = readJson(join(out, "service-catalog.json"));
        assert.equal(catalog[0].bgg_id, 10);
        assert.equal(readJson(join(out, "quality-report.json")).checks.matchedRows, 1);
    });
});

test("BGG 기준 CSV의 yearpublished만 출시 연도로 적재 산출물에 매핑한다", () => {
    const rows = [
        game(1, "10", "기준 연도 게임", "Baseline Year Game"),
        game(2, "20", "연도 미상 게임", "Unknown Year Game"),
    ];
    rows[0].yearpublished = "1995";
    rows[1].yearpublished = "";
    rows[0].description = "2011년에 나온 것처럼 보이는 설명입니다.";
    rows[1].description = "2001년이라는 숫자가 있어도 추정하지 않습니다.";

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const catalog = readJson(join(out, "service-catalog.json"));
        assert.deepEqual(
            catalog.map(({ bgg_id, release_year }) => [bgg_id, release_year]),
            [[10, 1995], [20, null]],
        );
        assert.match(readFileSync(join(out, "upsert-games.sql"), "utf8"), /release_year/);
        assert.equal(readJson(manifest).fieldSources.release_year, "ranks.yearpublished");
    });
});

test("비어 있지 않은 0분과 음수 시간은 품질 오류로 전체 적재를 차단한다", () => {
    const rows = [
        game(1, "10", "범위 게임", "Range Game"),
        game(2, "20", "단일 게임", "Single Game"),
        game(3, "30", "0분 게임", "Zero Time Game"),
        game(4, "40", "음수 게임", "Negative Time Game"),
    ];
    rows[0].supported_player_count = "2~4명";
    rows[0].estimated_play_time = "10~20분";
    rows[1].supported_player_count = "1명";
    rows[1].estimated_play_time = "60분";
    rows[1].complexity = 0;
    rows[2].estimated_play_time = "0분";
    rows[3].estimated_play_time = "-5분";

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.deepEqual(report.searchNumericFields.players, {
            label: "가능 인원",
            total: 4,
            valid: 4,
            missing: 0,
            excluded: 0,
            normalizedToNull: 0,
            exclusionReasons: [],
        });
        assert.deepEqual(report.searchNumericFields.playTimeMinutes.exclusionReasons, [
            { code: "NON_POSITIVE_VALUE", count: 2 },
        ]);
        assert.equal(report.searchNumericFields.playTimeMinutes.excluded, 2);
        const invalidValues = report.errors.find(
            ({ code }) => code === "INVALID_SEARCH_NUMERIC_DISPLAY_VALUE",
        );
        assert.equal(invalidValues.count, 2);
        assert.deepEqual(
            invalidValues.sample.map(({ value, reason }) => ({ value, reason })),
            [
                { value: "0분", reason: "NON_POSITIVE_VALUE" },
                { value: "-5분", reason: "NON_POSITIVE_VALUE" },
            ],
        );
        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("정보 없음 표시값은 검색 수치를 NULL로 두고 표시 문자열을 유지한다", () => {
    const rows = [
        game(1, "10", "시간 미제공 게임", "No Play Time Game"),
        game(2, "20", "단일 시간 게임", "Single Play Time Game"),
    ];
    rows[0].estimated_play_time = "정보 없음";
    rows[1].estimated_play_time = "90분";

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const catalog = readJson(join(out, "service-catalog.json"));
        assert.deepEqual(
            catalog.map(
                ({ estimated_play_time, min_play_time_minutes, max_play_time_minutes }) => [
                    estimated_play_time,
                    min_play_time_minutes,
                    max_play_time_minutes,
                ],
            ),
            [
                ["정보 없음", null, null],
                ["90분", 90, 90],
            ],
        );
        const report = readJson(join(out, "quality-report.json"));
        assert.deepEqual(report.searchNumericFields.playTimeMinutes, {
            label: "예상 플레이 시간",
            total: 2,
            valid: 1,
            missing: 1,
            excluded: 0,
            normalizedToNull: 1,
            exclusionReasons: [],
        });
        assert.equal(
            report.errors.some(({ code }) => code === "INVALID_SEARCH_NUMERIC_DISPLAY_VALUE"),
            false,
        );
    });
});

test("해석 불가 또는 PostgreSQL INTEGER 범위 밖 표시값은 적재 전에 차단한다", async (context) => {
    const cases = [
        {
            name: "해석 불가 인원",
            field: "supported_player_count",
            value: "두 명",
            reason: "UNPARSABLE_DISPLAY_VALUE",
        },
        {
            name: "PostgreSQL INTEGER 최대값 초과 시간",
            field: "estimated_play_time",
            value: "2147483648분",
            reason: "OUT_OF_POSTGRES_INTEGER_RANGE",
        },
        {
            name: "JavaScript safe integer 초과 인원",
            field: "supported_player_count",
            value: "9007199254740992명",
            reason: "OUT_OF_POSTGRES_INTEGER_RANGE",
        },
    ];

    for (const { name, field, value, reason } of cases) {
        await context.test(name, () => {
            withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
                games,
                ranks,
                manifest,
                out,
            }) => {
                const rows = readJson(games);
                rows[0][field] = value;
                writeFileSync(games, `${JSON.stringify(rows, null, 2)}\n`);
                writeManifest(manifest, games, ranks, []);

                const result = runCli(games, ranks, out, manifest);

                assert.equal(result.status, 1);
                const report = readJson(join(out, "quality-report.json"));
                const invalidValues = report.errors.find(
                    ({ code }) => code === "INVALID_SEARCH_NUMERIC_DISPLAY_VALUE",
                );
                assert.equal(invalidValues.count, 1);
                assert.deepEqual(invalidValues.sample, [
                    {
                        row: 1,
                        bgg_id: "10",
                        field,
                        value,
                        reason,
                    },
                ]);
                assert.throws(() => readFileSync(join(out, "service-catalog.json")));
                assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
            });
        });
    }

    await context.test("출시 연도 빈 값과 PostgreSQL INTEGER 경계값은 허용한다", () => {
        const maximumYear = game(1, "10", "최대 출시 연도", "Maximum Release Year");
        maximumYear.yearpublished = "2147483647";
        const unknownYear = game(2, "20", "미상 출시 연도", "Unknown Release Year");
        unknownYear.yearpublished = "";
        const minimumYear = game(3, "30", "최소 출시 연도", "Minimum Release Year");
        minimumYear.yearpublished = "-2147483648";

        withCase([maximumYear, unknownYear, minimumYear], ({ games, ranks, manifest, out }) => {
            writeManifest(manifest, games, ranks, []);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 0, result.stderr);
            assert.deepEqual(
                readJson(join(out, "service-catalog.json"))
                    .map(({ bgg_id, release_year }) => [bgg_id, release_year]),
                [[10, 2147483647], [20, null], [30, -2147483648]],
            );
        });
    });

    for (const { name, value, reason } of [
        {
            name: "PostgreSQL INTEGER 최대값을 초과한 출시 연도",
            value: "2147483648",
            reason: "OUT_OF_POSTGRES_INTEGER_RANGE",
        },
        {
            name: "해석할 수 없는 출시 연도",
            value: "unknown",
            reason: "UNPARSABLE_RELEASE_YEAR",
        },
    ]) {
        await context.test(name, () => {
            const row = game(1, "10", "출시 연도 검증", "Release Year Validation");
            row.yearpublished = value;

            withCase([row], ({ games, ranks, manifest, out }) => {
                writeManifest(manifest, games, ranks, []);

                const result = runCli(games, ranks, out, manifest);

                assert.equal(result.status, 1);
                const report = readJson(join(out, "quality-report.json"));
                const invalidReleaseYears = report.errors.find(
                    ({ code }) => code === "INVALID_RELEASE_YEAR",
                );
                assert.deepEqual(invalidReleaseYears, {
                    code: "INVALID_RELEASE_YEAR",
                    message: "BGG 기준 CSV yearpublished는 비어 있거나 PostgreSQL INTEGER 범위의 정수여야 합니다.",
                    count: 1,
                    sample: [{ bgg_id: "10", value, reason }],
                });
                assert.throws(() => readFileSync(join(out, "service-catalog.json")));
                assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
            });
        });
    }
});

test("complexity는 0을 NULL로 정규화하고 1.00~5.00 경계만 유지한다", () => {
    const rows = [
        game(1, "10", "복잡도 없음", "No Complexity"),
        game(2, "20", "최소 복잡도", "Minimum Complexity"),
        game(3, "30", "최대 복잡도", "Maximum Complexity"),
        game(4, "40", "낮은 범위 밖", "Below Range"),
        game(5, "50", "높은 범위 밖", "Above Range"),
    ];
    rows[0].complexity = 0;
    rows[1].complexity = 1;
    rows[2].complexity = 5;
    rows[3].complexity = 0.99;
    rows[4].complexity = 5.01;

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const catalog = readJson(join(out, "service-catalog.json"));
        assert.deepEqual(catalog.map(({ complexity }) => complexity), [null, 1, 5, null, null]);
        const report = readJson(join(out, "quality-report.json"));
        assert.deepEqual(report.searchNumericFields.complexity, {
            label: "복잡도",
            total: 5,
            valid: 2,
            missing: 1,
            excluded: 2,
            normalizedToNull: 1,
            exclusionReasons: [
                { code: "OUT_OF_RANGE", count: 2 },
                { code: "ZERO_NORMALIZED_TO_NULL", count: 1 },
            ],
        });
    });
});

test("문자열과 boolean complexity는 INVALID_COMPLEXITY와 같은 행으로 제외한다", () => {
    const rows = [
        game(1, "10", "문자열 복잡도", "String Complexity"),
        game(2, "20", "boolean 복잡도", "Boolean Complexity"),
        game(3, "30", "유효 복잡도", "Valid Complexity"),
    ];
    rows[0].complexity = "3.25";
    rows[1].complexity = true;
    rows[2].complexity = 3.25;

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.deepEqual(report.searchNumericFields.complexity, {
            label: "복잡도",
            total: 3,
            valid: 1,
            missing: 0,
            excluded: 2,
            normalizedToNull: 0,
            exclusionReasons: [{ code: "INVALID_COMPLEXITY", count: 2 }],
        });
        assert.equal(report.checks.invalidComplexityRows, 2);
        const invalidComplexityError = report.errors.find(
            ({ code }) => code === "INVALID_COMPLEXITY",
        );
        assert.equal(invalidComplexityError.count, 2);
        assert.deepEqual(
            invalidComplexityError.sample,
            [
                { row: 1, value: "3.25" },
                { row: 2, value: true },
            ],
        );
        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("같은 입력을 두 번 실행하면 검색 수치를 포함한 카탈로그와 품질 보고서가 같다", () => {
    const rows = [
        game(2, "20", "두 번째 검색 게임", "Second Search Game"),
        game(1, "10", "첫 번째 검색 게임", "First Search Game"),
    ];
    rows[0].supported_player_count = "1명";
    rows[0].estimated_play_time = "30분";
    rows[1].supported_player_count = "2~4명";
    rows[1].estimated_play_time = "10~20분";

    withCase(rows, ({ root, games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);
        assert.equal(runCli(games, ranks, out, manifest).status, 0);
        const catalog = readFileSync(join(out, "service-catalog.json"), "utf8");
        const report = readFileSync(join(out, "quality-report.json"), "utf8");

        const secondOut = join(root, "second-search-output");
        assert.equal(runCli(games, ranks, secondOut, manifest).status, 0);
        assert.equal(readFileSync(join(secondOut, "service-catalog.json"), "utf8"), catalog);
        assert.equal(readFileSync(join(secondOut, "quality-report.json"), "utf8"), report);
        assert.deepEqual(
            readJson(join(secondOut, "service-catalog.json")).map((row) => [
                row.min_players,
                row.max_players,
                row.min_play_time_minutes,
                row.max_play_time_minutes,
            ]),
            [
                [2, 4, 10, 20],
                [1, 1, 30, 30],
            ],
        );
    });
});

test("구 recommended_player_count만 있는 입력은 supported_player_count 필수 오류로 차단한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        const rows = readJson(games);
        rows[0].recommended_player_count = rows[0].supported_player_count;
        delete rows[0].supported_player_count;
        writeFileSync(games, `${JSON.stringify(rows, null, 2)}\n`);
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        const missingRequired = report.errors.find(
            ({ code }) => code === "MISSING_REQUIRED_VALUE",
        );
        assert.ok(missingRequired);
        assert.ok(
            missingRequired.sample.some(({ fields }) => fields.includes("supported_player_count")),
        );
        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("서비스 카탈로그 텍스트 필드의 U+0000은 적재 전에 차단한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        const rows = readJson(games);
        rows[0].description = `설명${String.fromCharCode(0)}오염`;
        writeFileSync(games, `${JSON.stringify(rows, null, 2)}\n`);
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.equal(report.status, "blocked");
        assert.ok(report.errors.some(({ code }) => code === "NUL_CHARACTER_IN_TEXT"));
        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("batchId가 TODO면 INVALID_MANIFEST로 적재를 차단한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        manifest,
        out,
    }) => {
        writeManifest(manifest, games, ranks, []);
        const value = readJson(manifest);
        value.batchId = "TODO";
        writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.equal(report.status, "blocked");
        assert.ok(report.errors.some(({ code }) => code === "INVALID_MANIFEST"));
        assert.throws(() => readFileSync(join(out, "service-catalog.json")));
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
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
        game(2, "10", "중복 게임", "Duplicate Game"),
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

    await context.test("games JSON 문자열 내부의 잘못된 UTF-8", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const contents = readFileSync(games);
            const marker = Buffer.from("첫 번째 게임");
            const markerOffset = contents.indexOf(marker);
            assert.notEqual(markerOffset, -1);
            contents[markerOffset] = 0xff;
            writeFileSync(games, contents);

            assertParseFailure(runCli(games, ranks, out, manifest), out, "INVALID_UTF8");
            const report = readJson(join(out, "quality-report.json"));
            assert.equal(report.errors[0].input, "games");
        });
    });

    await context.test("ranks CSV 필드 내부의 잘못된 UTF-8", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const contents = readFileSync(ranks);
            const marker = Buffer.from("First Game");
            const markerOffset = contents.indexOf(marker);
            assert.notEqual(markerOffset, -1);
            contents[markerOffset] = 0xff;
            writeFileSync(ranks, contents);

            assertParseFailure(runCli(games, ranks, out, manifest), out, "INVALID_UTF8");
            const report = readJson(join(out, "quality-report.json"));
            assert.equal(report.errors[0].input, "ranks");
        });
    });

    await context.test("manifest JSON 문자열 내부의 잘못된 UTF-8", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const contents = readFileSync(manifest);
            const marker = Buffer.from("BGG 기준 스냅샷");
            const markerOffset = contents.indexOf(marker);
            assert.notEqual(markerOffset, -1);
            contents[markerOffset] = 0xff;
            writeFileSync(manifest, contents);

            assertParseFailure(runCli(games, ranks, out, manifest), out, "INVALID_UTF8");
            const report = readJson(join(out, "quality-report.json"));
            assert.equal(report.errors[0].input, "manifest");
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

test("입력과 출력 파일이 하드 링크면 원본을 보존하고 실행 전에 거절한다", () => {
    withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
        games,
        ranks,
        out,
    }) => {
        mkdirSync(out, { recursive: true });
        const conflictingInput = join(out, "quality-report.json");
        const original = readFileSync(games);
        linkSync(games, conflictingInput);

        const result = runCli(games, ranks, out);

        assert.equal(result.status, 2);
        assert.match(result.stderr, /입력 파일과 출력 파일 경로가 같습니다/);
        assert.deepEqual(readFileSync(games), original);
        assert.deepEqual(readFileSync(conflictingInput), original);
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

test("complexity와 BGG rank가 합성적으로 결합되면 품질 경고로 차단한다", () => {
    const rows = Array.from({ length: 20 }, (_, index) => {
        const row = game(
            index + 1,
            String(index + 100),
            `단조 게임 ${index + 1}`,
            `Monotonic Game ${index + 1}`,
        );
        row.complexity = Number((4.2 - index * 0.05).toFixed(2));
        return row;
    });

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        const warning = report.warnings.find(
            ({ code }) => code === "SUSPICIOUS_COMPLEXITY_RANK_CORRELATION",
        );
        assert.ok(warning);
        assert.equal(warning.sampleSize, 20);
        assert.ok(Math.abs(warning.correlation) >= 0.9);
        assert.ok(
            report.errors.some(({ code }) => code === "UNACKNOWLEDGED_WARNINGS"),
        );
        assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
    });
});

test("complexity와 BGG rank가 섞인 표본에는 상관 경고를 추가하지 않는다", () => {
    const complexityValues = [1, 4, 2, 3];
    const words = [
        "봄",
        "여름",
        "가을",
        "겨울",
        "새벽",
        "아침",
        "낮",
        "저녁",
        "밤",
        "별",
        "달",
        "구름",
        "바람",
        "비",
        "눈",
        "안개",
        "숲",
        "바다",
        "강",
        "들",
    ];
    const rows = Array.from({ length: 20 }, (_, index) => {
        const row = game(
            index + 1,
            String(index + 100),
            `혼합 게임 ${index + 1}`,
            `Mixed Game ${index + 1}`,
        );
        row.complexity = complexityValues[index % complexityValues.length];
        row.supported_player_count = `${2 + (index % 4)}~${4 + (index % 4)}명`;
        row.estimated_play_time = `${30 + (index % 4) * 15}~${60 + (index % 4) * 15}분`;
        row.description = `${row.name} 설명 ${words[index]}`;
        row.detail_description = `${row.name} 상세 설명 ${words[index]}`;
        return row;
    });

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const report = readJson(join(out, "quality-report.json"));
        assert.equal(report.status, "ready");
        assert.deepEqual(report.warnings, []);
        assert.ok(readFileSync(join(out, "service-catalog.json")));
        assert.ok(readFileSync(join(out, "upsert-games.sql")));
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

test("품질 보고서는 같은 표시명의 BGG 게임별 출시 연도와 채움·누락을 남긴다", () => {
    const rows = Array.from({ length: 25 }, (_, index) => [
        game(index * 2 + 1, String(index * 2 + 10), `같은 게임 ${index}`, `Same Game ${index} A`),
        game(index * 2 + 2, String(index * 2 + 11), `같은 게임 ${index}`, `Same Game ${index} B`),
    ]).flat();
    rows.forEach((row, index) => {
        row.yearpublished = String(1995 + index);
    });

    withCase(rows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, [
            "POSSIBLE_VERSION_COLLISION",
            "LOW_SUPPORTED_PLAYER_COUNT_DIVERSITY",
            "LOW_ESTIMATED_PLAY_TIME_DIVERSITY",
            "LOW_DESCRIPTION_DIVERSITY",
            "LOW_DETAIL_DESCRIPTION_DIVERSITY",
        ]);
        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(
            report.warnings.some(({ code }) => code === "POSSIBLE_VERSION_COLLISION"),
        );
        assert.deepEqual(report.releaseYears, { filledRows: 50, missingRows: 0 });
        const collisionWarning = report.warnings.find(
            ({ code }) => code === "POSSIBLE_VERSION_COLLISION",
        );
        assert.equal(collisionWarning.sample.length, 10);
        assert.equal(collisionWarning.allCollisions.length, 25);
        assert.deepEqual(
            collisionWarning.allCollisions
                .flatMap(({ games: collisionGames }) => collisionGames)
                .map(({ bgg_id, release_year }) => [bgg_id, release_year])
                .sort((left, right) => left[0] - right[0]),
            readJson(join(out, "service-catalog.json"))
                .map(({ bgg_id, release_year }) => [bgg_id, release_year])
                .sort((left, right) => left[0] - right[0]),
        );
    });
});

test("판본 충돌 비교는 제목 의미 기호를 보존한다", () => {
    const symbolRows = [
        game(1, "10", "Bullet♥︎", "Bullet Heart"),
        game(2, "20", "Bullet★", "Bullet Star"),
    ];
    withCase(symbolRows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 0, result.stderr);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(!report.warnings.some(({ code }) => code === "POSSIBLE_VERSION_COLLISION"));
    });

    const equivalentRows = [
        game(1, "10", "Bullet♥︎", "Bullet Heart"),
        game(2, "20", "bullet ♥", "Bullet Heart Two"),
    ];
    withCase(equivalentRows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(report.warnings.some(({ code }) => code === "POSSIBLE_VERSION_COLLISION"));
    });

    const punctuationRows = [
        game(1, "10", "Bullet! Game", "Bullet Game One"),
        game(2, "20", "bullet game", "Bullet Game Two"),
    ];
    withCase(punctuationRows, ({ games, ranks, manifest, out }) => {
        writeManifest(manifest, games, ranks, []);

        const result = runCli(games, ranks, out, manifest);

        assert.equal(result.status, 1);
        const report = readJson(join(out, "quality-report.json"));
        assert.ok(report.warnings.some(({ code }) => code === "POSSIBLE_VERSION_COLLISION"));
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

test("manifest 선택·판본 규칙과 제외 결과의 구조·건수를 검증한다", async (context) => {
    await context.test("선택 규칙이 없으면 차단한다", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const value = readJson(manifest);
            delete value.selectionRules;
            writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 1);
            assert.ok(
                readJson(join(out, "quality-report.json")).errors.some(
                    ({ code }) => code === "INVALID_SELECTION_RULES",
                ),
            );
        });
    });

    await context.test("본판·확장·변형 규칙이 없으면 차단한다", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const value = readJson(manifest);
            delete value.versionRules;
            writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 1);
            assert.ok(
                readJson(join(out, "quality-report.json")).errors.some(
                    ({ code }) => code === "INVALID_VERSION_RULES",
                ),
            );
        });
    });

    await context.test("선택 행 수 합과 실제 카탈로그 행 수가 다르면 차단한다", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const value = readJson(manifest);
            value.selection.candidateRows = 3;
            value.selection.includedRows = 2;
            value.selection.excludedRows = 0;
            writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 1);
            const report = readJson(join(out, "quality-report.json"));
            assert.ok(
                report.errors.some(({ code }) => code === "SELECTION_COUNT_MISMATCH"),
            );
            assert.ok(
                report.errors.some(
                    ({ code }) => code === "SELECTION_INCLUDED_ROWS_MISMATCH",
                ),
            );
        });
    });

    await context.test("제외 건수와 식별자·사유를 함께 검증한다", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const value = readJson(manifest);
            value.selection.candidateRows = 3;
            value.selection.excludedRows = 2;
            value.selection.exclusions = [{ identifier: "bgg_id:20" }];
            writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 1);
            const report = readJson(join(out, "quality-report.json"));
            assert.ok(
                report.errors.some(
                    ({ code }) => code === "INVALID_SELECTION_EXCLUSION",
                ),
            );
            assert.ok(
                report.errors.some(
                    ({ code }) => code === "SELECTION_EXCLUSION_COUNT_MISMATCH",
                ),
            );
        });
    });

    await context.test("실제 카탈로그에 포함된 bgg_id를 제외 목록에 다시 기록하면 차단한다", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const value = readJson(manifest);
            value.selection.candidateRows = 2;
            value.selection.includedRows = 1;
            value.selection.excludedRows = 1;
            value.selection.exclusions = [{ bgg_id: 10, reason: "확장 제외" }];
            writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 1);
            assert.ok(
                readJson(join(out, "quality-report.json")).errors.some(
                    ({ code }) => code === "SELECTION_EXCLUSION_OVERLAPS_CATALOG",
                ),
            );
        });
    });

    await context.test("동일한 제외 식별자는 표현이 달라도 중복으로 차단한다", () => {
        withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
            games,
            ranks,
            manifest,
            out,
        }) => {
            writeManifest(manifest, games, ranks, []);
            const value = readJson(manifest);
            value.selection.candidateRows = 3;
            value.selection.includedRows = 1;
            value.selection.excludedRows = 2;
            value.selection.exclusions = [
                { identifier: "20", reason: "확장 제외" },
                { id: 20, reason: "변형 제외" },
            ];
            writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

            const result = runCli(games, ranks, out, manifest);

            assert.equal(result.status, 1);
            assert.ok(
                readJson(join(out, "quality-report.json")).errors.some(
                    ({ code }) => code === "SELECTION_EXCLUSION_DUPLICATE",
                ),
            );
        });
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

test("games JSON 필드 타입은 정규화 전에 차단한다", async (context) => {
    const cases = [
        {
            name: "bgg_id 불리언",
            mutate: (row) => {
                row.bgg_id = true;
            },
            code: "INVALID_BGG_ID",
        },
        {
            name: "bgg_id 지수 표기 문자열",
            mutate: (row) => {
                row.bgg_id = "1e3";
            },
            code: "INVALID_BGG_ID",
        },
        {
            name: "bgg_id 소수 표기 문자열",
            mutate: (row) => {
                row.bgg_id = "10.0";
            },
            code: "INVALID_BGG_ID",
        },
        {
            name: "bgg_id 공백 문자열",
            mutate: (row) => {
                row.bgg_id = " 10";
            },
            code: "INVALID_BGG_ID",
        },
        {
            name: "필수 텍스트 배열",
            mutate: (row) => {
                row.name = [];
            },
            code: "INVALID_FIELD_TYPE",
        },
        {
            name: "필수 텍스트 객체",
            mutate: (row) => {
                row.description = {};
            },
            code: "INVALID_FIELD_TYPE",
        },
        {
            name: "complexity 불리언",
            mutate: (row) => {
                row.complexity = false;
            },
            code: "INVALID_COMPLEXITY",
        },
    ];

    for (const { name, mutate, code } of cases) {
        await context.test(name, () => {
            withCase([game(1, "10", "첫 번째 게임", "First Game")], ({
                games,
                ranks,
                manifest,
                out,
            }) => {
                const rows = readJson(games);
                mutate(rows[0]);
                writeFileSync(games, `${JSON.stringify(rows, null, 2)}\n`);
                writeManifest(manifest, games, ranks, []);

                const result = runCli(games, ranks, out, manifest);

                assert.equal(result.status, 1);
                const report = readJson(join(out, "quality-report.json"));
                assert.ok(report.errors.some(({ code: errorCode }) => errorCode === code));
                assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
            });
        });
    }
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

test("승인된 메커니즘 manifest는 결정적인 목록과 중복 없는 관계 UPSERT를 만든다", () => {
	const first = game(1, "10", "첫 번째 게임", "First Game");
	const second = game(2, "20", "두 번째 게임", "Second Game");
	first.mechanisms = [
		{ bgg_id: "2040", name: "Hand Management", name_ko: "핸드 관리", description_ko: "카드를 관리해요." },
		{ bgg_id: "2072", name: "Dice Rolling", name_ko: "주사위 굴림", description_ko: "주사위를 굴려요." },
	];
	second.mechanisms = [{ bgg_id: "2072", name: "Dice Rolling", name_ko: "주사위 굴림", description_ko: "주사위를 굴려요." }];

	withCase([first, second], ({ root, games, ranks, manifest, out }) => {
		writeManifest(manifest, games, ranks, []);
		writeMechanismManifest(manifest, 2, 3, {
			2040: "HAND_MANAGEMENT",
			2072: "DICE_ROLLING",
		});

		const firstResult = runCli(games, ranks, out, manifest);
		assert.equal(firstResult.status, 0, firstResult.stderr);
		const catalog = readJson(join(out, "service-mechanism-catalog.json"));
		assert.deepEqual(catalog.map(({ code }) => code), ["HAND_MANAGEMENT", "DICE_ROLLING"]);
		assert.deepEqual(catalog.map(({ featured_order }) => featured_order), [1, 2]);
		const sql = readFileSync(join(out, "upsert-game-mechanisms.sql"), "utf8");
		assert.match(sql, /ON CONFLICT \(game_id, mechanism_id\) DO NOTHING/);

		const secondOut = join(root, "second-output");
		const secondResult = runCli(games, ranks, secondOut, manifest);
		assert.equal(secondResult.status, 0, secondResult.stderr);
		assert.equal(
			readFileSync(join(secondOut, "upsert-game-mechanisms.sql"), "utf8"),
			sql,
		);
	});
});

test("메커니즘 한글 설명이 누락되거나 공백이면 서비스 카탈로그와 운영 UPSERT를 만들지 않는다", async (context) => {
	for (const descriptionKo of [undefined, "   "]) {
		await context.test(String(descriptionKo), () => {
			const row = game(1, "10", "첫 번째 게임", "First Game");
			row.mechanisms = [{
				bgg_id: "2040",
				name: "Hand Management",
				name_ko: "핸드 관리",
				...(descriptionKo === undefined ? {} : { description_ko: descriptionKo }),
			}];
			withCase([row], ({ games, ranks, manifest, out }) => {
				writeManifest(manifest, games, ranks, []);
				writeMechanismManifest(manifest, 1, 1, { 2040: "HAND_MANAGEMENT" });

				const result = runCli(games, ranks, out, manifest);

				assert.equal(result.status, 1);
				assert.throws(() => readFileSync(join(out, "service-catalog.json")));
				assert.throws(() => readFileSync(join(out, "upsert-games.sql")));
				assert.throws(() => readFileSync(join(out, "service-mechanism-catalog.json")));
				assert.throws(() => readFileSync(join(out, "upsert-game-mechanisms.sql")));
			});
		});
	}
});

test("메커니즘 승인 건수가 입력과 다르면 적재 산출물을 차단한다", () => {
	const row = game(1, "10", "첫 번째 게임", "First Game");
	row.mechanisms = [{ bgg_id: "2040", name: "Hand Management", name_ko: "핸드 관리" }];
	withCase([row], ({ games, ranks, manifest, out }) => {
		writeManifest(manifest, games, ranks, []);
		writeMechanismManifest(manifest, 189, 13263, { 2040: "HAND_MANAGEMENT" });

		const result = runCli(games, ranks, out, manifest);
		assert.equal(result.status, 1);
		const report = readJson(join(out, "quality-report.json"));
		assert.ok(report.errors.some(({ code }) => code === "MECHANISM_COUNT_MISMATCH"));
		assert.throws(() => readFileSync(join(out, "service-mechanism-catalog.json")));
		assert.throws(() => readFileSync(join(out, "upsert-game-mechanisms.sql")));
	});
});

test("승인 code 매핑은 영문 표시명이 바뀌어도 공개 code를 바꾸지 않는다", () => {
	const row = game(1, "10", "첫 번째 게임", "First Game");
	row.mechanisms = [{ bgg_id: "2040", name: "Hand Management Revised", name_ko: "핸드 관리", description_ko: "카드를 관리해요." }];
	withCase([row], ({ games, ranks, manifest, out }) => {
		writeManifest(manifest, games, ranks, []);
		writeMechanismManifest(manifest, 1, 1, { 2040: "HAND_MANAGEMENT" });

		const result = runCli(games, ranks, out, manifest);
		assert.equal(result.status, 0, result.stderr);
		assert.equal(readJson(join(out, "service-mechanism-catalog.json"))[0].code, "HAND_MANAGEMENT");
	});
});

test("승인 code 매핑 누락 또는 checksum 불일치는 적재 산출물을 차단한다", async (context) => {
	const row = game(1, "10", "첫 번째 게임", "First Game");
	row.mechanisms = [{ bgg_id: "2040", name: "Hand Management", name_ko: "핸드 관리", description_ko: "카드를 관리해요." }];
	await context.test("매핑 누락", () => {
		withCase([row], ({ games, ranks, manifest, out }) => {
			writeManifest(manifest, games, ranks, []);
			writeMechanismManifest(manifest, 1, 1, {});

			const result = runCli(games, ranks, out, manifest);
			assert.equal(result.status, 1);
			assert.ok(readJson(join(out, "quality-report.json")).errors.some(
				({ code }) => code === "MISSING_APPROVED_MECHANISM_CODE"));
		});
	});
	await context.test("매핑 checksum 불일치", () => {
		withCase([row], ({ games, ranks, manifest, out }) => {
			writeManifest(manifest, games, ranks, []);
			writeMechanismManifest(manifest, 1, 1, { 2040: "HAND_MANAGEMENT" });
			const value = readJson(manifest);
			value.mechanismCatalog.approvedCodes[2040] = "RENAMED_CODE";
			writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

			const result = runCli(games, ranks, out, manifest);
			assert.equal(result.status, 1);
			assert.ok(readJson(join(out, "quality-report.json")).errors.some(
				({ code }) => code === "APPROVED_CODE_MAPPING_MISMATCH"));
		});
	});
});

test("메커니즘 reviewedAt은 실제 ISO-8601 instant가 아니면 적재 산출물을 차단한다", async (context) => {
	const row = game(1, "10", "첫 번째 게임", "First Game");
	row.mechanisms = [{ bgg_id: "2040", name: "Hand Management", name_ko: "핸드 관리" }];
	for (const reviewedAt of ["2026-08-04T00:00:00", "2026-02-30T00:00:00Z"]) {
		await context.test(reviewedAt, () => {
			withCase([row], ({ games, ranks, manifest, out }) => {
				writeManifest(manifest, games, ranks, []);
				writeMechanismManifest(manifest, 1, 1, { 2040: "HAND_MANAGEMENT" });
				const value = readJson(manifest);
				value.mechanismCatalog.reviewedAt = reviewedAt;
				writeFileSync(manifest, `${JSON.stringify(value, null, 2)}\n`);

				const result = runCli(games, ranks, out, manifest);

				assert.equal(result.status, 1);
				assert.ok(readJson(join(out, "quality-report.json")).errors.some(
					({ code }) => code === "INVALID_MECHANISM_MANIFEST"));
				assert.throws(() => readFileSync(join(out, "upsert-game-mechanisms.sql")));
			});
		});
	}
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
    let candidateRows = 1;
    try {
        candidateRows = readJson(gamesPath).length;
    } catch {
        // 경로 충돌 테스트처럼 games 경로가 의도적으로 JSON이 아닐 수 있다.
    }
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
            supported_player_count: "games.supported_player_count",
            tag: "games.tag",
            estimated_play_time: "games.estimated_play_time",
            min_players: "games.supported_player_count를 검증해 정규화",
            max_players: "games.supported_player_count를 검증해 정규화",
            min_play_time_minutes: "games.estimated_play_time를 검증해 정규화",
            max_play_time_minutes: "games.estimated_play_time를 검증해 정규화",
            complexity: "games.complexity",
            release_year: "ranks.yearpublished",
            description: "games.description",
            detail_description: "games.detail_description",
        },
        selectionRules: {
            include: "BGG 기준 스냅샷과 bgg_id가 일치하고 필수 검수를 통과한 후보만 포함",
            exclude: "매핑·필수값·판본 근거가 부족한 후보는 식별자와 사유를 남기고 제외",
        },
        versionRules: {
            baseGame: "BGG 본판으로 확인된 항목만 본판으로 분류",
            expansion: "BGG 확장은 본판과 구분하고 서비스 목록 반영 여부를 검수",
            variant: "변형 여부를 확인할 수 없으면 임의로 병합하지 않고 제외",
        },
        selection: {
            candidateRows,
            includedRows: candidateRows,
            excludedRows: 0,
            exclusions: [],
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

function writeMechanismManifest(path, publishedCount, relationCount, approvedCodes) {
	const manifest = readJson(path);
	manifest.mechanismCatalog = {
		publishedCount,
		relationCount,
		sourceReference: "승인된 BGG 메커니즘 입력",
		reviewedBy: "test-reviewer",
		reviewedAt: "2026-08-04T00:00:00Z",
		approvedCodes,
		approvedCodesSha256: codeMappingSha256(approvedCodes),
	};
	writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`);
}

function codeMappingSha256(codes) {
	const canonical = JSON.stringify(
		Object.fromEntries(Object.entries(codes).sort(([left], [right]) => left.localeCompare(right))),
	);
	return createHash("sha256").update(canonical).digest("hex");
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
        supported_player_count: "2~4명",
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
            row.yearpublished ?? "2020",
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
