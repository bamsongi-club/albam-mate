import assert from "node:assert/strict";
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

import {
    buildCorrections,
    correctGameNames,
    extractBggNamesFromXml,
    isUnreviewedAutomaticCandidate,
    parseCandidateCsv,
    selectNameCorrections,
} from "./game-name-correction.mjs";

test("T1: 검수 완료 한글명은 현재 SQL 값과 무관하게 보정 대상으로 선택한다", () => {
    const automatic = parseCandidateCsv(
        "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n1,Automatic,자동,추정번역(자동음차),N\n2,Reviewed,검수명,국내 정발명,Y\n",
        "candidate.csv",
    );
    const selection = selectNameCorrections([automatic]);

    assert.equal(isUnreviewedAutomaticCandidate(automatic.rows[0]), true);
    assert.equal(isUnreviewedAutomaticCandidate(automatic.rows[1]), false);
    assert.deepEqual(selection.corrections.map(({ bggId, nameKo }) => ({ bggId, nameKo })), [
        { bggId: 1, nameKo: "자동" },
        { bggId: 2, nameKo: "검수명" },
    ]);
});

test("T1: 같은 ID의 검수 완료 한글명이 자동 음차 후보보다 우선한다", () => {
    const candidates = parseCandidateCsv(
        "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n1,Game,자동,추정번역(자동음차),N\n1,Game,공식명,국내 정발명,Y\n",
        "duplicate.csv",
    );

    assert.deepEqual(selectNameCorrections([candidates]).corrections.map(({ nameKo }) => nameKo), ["공식명"]);
});

test("T1: 같은 ID의 상충하는 검수 완료 한글명은 후보명을 포함해 fail-closed 한다", () => {
    const candidates = parseCandidateCsv(
        "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n327266,Game,검수명 하나,국내 정발명,Y\n327266,Game,검수명 둘,다른 검수,Y\n",
        "conflicting-reviewed.csv",
    );

    assert.throws(
        () => selectNameCorrections([candidates]),
        /bgg_id 327266.*검수명 하나.*검수명 둘/u,
    );
});

test("T2: BGG XML 한글 alternate를 우선해 보정한다", () => {
    const names = extractBggNamesFromXml(
        '<items><item id="370749"><name type="primary" value="Wendy, Grow Up"/><name type="alternate" value="웬디, 어른이 되렴"/></item><item id="42"><name type="primary" value="Tinners&#039; Trail"/></item></items>',
        new Set([370749, 42]),
    );
    const corrections = buildCorrections(
        [
            { bggId: 370749, nameEn: "Wendy, Grow Up", source: "추정번역(자동음차)", reviewed: "N" },
            { bggId: 42, nameEn: "Tinners' Trail", source: "추정번역(자동음차)", reviewed: "N" },
        ],
        names,
    );

    assert.deepEqual(corrections.map(({ name, source }) => ({ name, source })), [
        { name: "웬디, 어른이 되렴", source: "bgg-xml-korean-alternate" },
        { name: "Tinners' Trail", source: "bgg-xml-primary" },
    ]);
});

test("T3: XML item 또는 primary가 없으면 candidate nameEn으로 대체하지 않고 실패한다", async () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-name-correction-"));
    try {
        const inputSql = join(root, "01-games-full.sql");
        const candidateCsv = join(root, "candidates.csv");
        const xmlDirectory = join(root, "xml");
        const xmlManifest = join(root, "xml-manifest.json");
        mkdirSync(xmlDirectory);
        writeFileSync(inputSql, `BEGIN;
INSERT INTO games (bgg_id, name, english_name, description, detail_description) VALUES
    (42, '옛 음차', 'Candidate CSV Name', 'd', 'dd');
COMMIT;
`);
        writeFileSync(candidateCsv, "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n42,Candidate CSV Name,옛 음차,추정번역(자동음차),N\n");
        writeFileSync(join(xmlDirectory, "batch.xml"), '<items><item id="42"></item></items>');
        writeRawXmlManifest(xmlManifest, xmlDirectory, [{ file: "batch.xml", requestIds: [42], responseIds: [42] }]);

        await assert.rejects(
            correctGameNames({
                inputSql,
                candidatePaths: [candidateCsv],
                xmlDirectory,
                xmlManifest,
                expectedXmlManifestSha256: sha(xmlManifest),
                out: join(root, "out"),
            }),
            /bgg_id 42의 primary 영문 fallback을 찾을 수 없습니다/u,
        );
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test("T4: 보정 SQL은 행 수와 XML manifest provenance를 보존한다", async () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-name-correction-"));
    try {
        const inputSql = join(root, "01-games-full.sql");
        const candidateCsv = join(root, "candidates.csv");
        const xmlDirectory = join(root, "xml");
        const xmlManifest = join(root, "xml-manifest.json");
        const out = join(root, "out");
        mkdirSync(xmlDirectory);
        writeFileSync(inputSql, `BEGIN;
INSERT INTO games (bgg_id, name, english_name, description, detail_description) VALUES
    (42, '옛 음차', 'Tinners'' Trail', 'd', 'dd'),
    (370749, '더블유엔드이', 'Wendy, Grow Up', 'd', 'dd');
COMMIT;
`);
        writeFileSync(candidateCsv, `bggId,nameEn,nameKo,출처,검수완료(Y/N)
42,Tinners' Trail,옛 음차,추정번역(자동음차),N
370749,"Wendy, Grow Up","더블유엔드이",추정번역(자동음차),N
`);
        writeFileSync(join(xmlDirectory, "batch.xml"), '<items><item id="42"><name type="primary" value="Tinners&#039; Trail"/></item><item id="370749"><name type="primary" value="Wendy, Grow Up"/><name type="alternate" value="웬디, 어른이 되렴"/></item></items>');
        writeRawXmlManifest(xmlManifest, xmlDirectory, [{ file: "batch.xml", requestIds: [42, 370749], responseIds: [42, 370749] }]);

        const result = await correctGameNames({
            inputSql,
            candidatePaths: [candidateCsv],
            xmlDirectory,
            xmlManifest,
            expectedXmlManifestSha256: sha(xmlManifest),
            out,
        });
        const sql = readFileSync(result.outputSql, "utf8");
        const report = JSON.parse(readFileSync(result.reportPath, "utf8"));

        assert.equal(report.inputRows, 2);
        assert.equal(report.outputRows, 2);
        assert.equal(report.corrections.rows, 2);
        assert.equal(report.corrections.reviewedKoreanRows, 0);
        assert.equal(report.corrections.koreanAlternateRows, 1);
        assert.equal(report.corrections.primaryEnglishFallbackRows, 1);
        assert.equal(report.provenance.xmlManifestSha256, sha(xmlManifest));
        assert.deepEqual(report.provenance.xmlFiles, [{
            file: "batch.xml",
            requestIds: [42, 370749],
            responseIds: [42, 370749],
            bytes: statSync(join(xmlDirectory, "batch.xml")).size,
            sha256: sha(join(xmlDirectory, "batch.xml")),
        }]);
        assert.ok(sql.indexOf("UPDATE games SET name = '웬디, 어른이 되렴'") < sql.lastIndexOf("COMMIT;"));
        assert.ok(sql.includes("UPDATE games SET name = 'Tinners'' Trail' WHERE bgg_id = 42;"));
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test("T3: production CLI는 승인 raw XML manifest hash와 다르면 SQL을 만들지 않는다", () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-name-correction-"));
    try {
        const inputSql = join(root, "01-games-full.sql");
        const candidateCsv = join(root, "candidates.csv");
        const xmlDirectory = join(root, "xml");
        const xmlManifest = join(root, "xml-manifest.json");
        const out = join(root, "out");
        mkdirSync(xmlDirectory);
        writeFileSync(inputSql, `BEGIN;
INSERT INTO games (bgg_id, name, english_name, description, detail_description) VALUES
    (42, '옛 음차', 'Trusted Game', 'd', 'dd');
COMMIT;
`);
        writeFileSync(candidateCsv, "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n42,Trusted Game,옛 음차,추정번역(자동음차),N\n");
        writeFileSync(join(xmlDirectory, "batch.xml"), '<items><item id="42"><name type="primary" value="Trusted Game"/></item></items>');
        writeRawXmlManifest(xmlManifest, xmlDirectory, [{ file: "batch.xml", requestIds: [42], responseIds: [42] }]);

        const result = spawnSync("node", [
            "scripts/game-catalog/game-name-correction.mjs",
            "--input-sql", inputSql,
            "--candidate-csv", candidateCsv,
            "--xml-directory", xmlDirectory,
            "--xml-manifest", xmlManifest,
            "--out", out,
        ], { cwd: process.cwd(), encoding: "utf8" });

        assert.notEqual(result.status, 0);
        assert.match(result.stderr, /승인 raw XML manifest SHA-256과 일치하지 않습니다/u);
        assert.equal(existsSync(join(out, "01-games-full.sql")), false);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test("T4: candidate-directory의 후보 파일을 읽고 provenance에 기록한다", async () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-name-correction-"));
    try {
        const inputSql = join(root, "01-games-full.sql");
        const candidateDirectory = join(root, "candidates");
        const xmlDirectory = join(root, "xml");
        const xmlManifest = join(root, "xml-manifest.json");
        const out = join(root, "out");
        mkdirSync(candidateDirectory);
        mkdirSync(xmlDirectory);
        writeFileSync(inputSql, `BEGIN;
INSERT INTO games (bgg_id, name, english_name, description, detail_description) VALUES
    (327266, '자동 음차', 'Directory Game', 'd', 'dd');
COMMIT;
`);
        writeFileSync(
            join(candidateDirectory, "bgg-game-name-ko-candidates-reviewed.csv"),
            "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n327266,Directory Game,자동 음차,추정번역(자동음차),N\n",
        );
        writeFileSync(join(candidateDirectory, "ignored.csv"), "bggId,nameEn\n999,Ignored\n");
        writeFileSync(join(xmlDirectory, "batch.xml"), '<items><item id="327266"><name type="primary" value="Directory Game"/></item></items>');
        writeRawXmlManifest(xmlManifest, xmlDirectory, [{ file: "batch.xml", requestIds: [327266], responseIds: [327266] }]);

        const result = await correctGameNames({
            inputSql,
            candidateDirectory,
            xmlDirectory,
            xmlManifest,
            expectedXmlManifestSha256: sha(xmlManifest),
            out,
        });

        assert.equal(result.report.candidates.files.length, 1);
        assert.equal(result.report.candidates.files[0].fileName, "bgg-game-name-ko-candidates-reviewed.csv");
        assert.equal(result.report.provenance.candidateDirectory, "candidates");
        assert.ok(readFileSync(result.outputSql, "utf8").includes("UPDATE games SET name = 'Directory Game' WHERE bgg_id = 327266;"));
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

function writeRawXmlManifest(manifestPath, xmlDirectory, batches) {
    writeFileSync(manifestPath, `${JSON.stringify({
        schemaVersion: 1,
        files: batches.map((batch) => {
            const xmlPath = join(xmlDirectory, batch.file);
            return {
                file: batch.file,
                requestIds: batch.requestIds,
                responseIds: batch.responseIds,
                httpStatus: 200,
                bytes: statSync(xmlPath).size,
                sha256: sha(xmlPath),
                acquiredAt: "2026-08-10T00:00:00.000Z",
            };
        }),
    }, null, 2)}\n`);
}

function sha(path) {
    return createHash("sha256").update(readFileSync(path)).digest("hex");
}
