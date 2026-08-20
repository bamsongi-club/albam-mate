import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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

test("검수되지 않은 추정번역만 보정 대상으로 선택하고 검수된 후보는 유지한다", () => {
    const automatic = parseCandidateCsv(
        "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n1,Automatic,자동,추정번역(자동음차),N\n2,Reviewed,검수명,국내 정발명,Y\n",
        "candidate.csv",
    );
    const selection = selectNameCorrections([automatic]);

    assert.equal(isUnreviewedAutomaticCandidate(automatic.rows[0]), true);
    assert.equal(isUnreviewedAutomaticCandidate(automatic.rows[1]), false);
    assert.deepEqual(selection.corrections.map(({ bggId }) => bggId), [1]);
});

test("같은 ID에 검수된 후보가 있으면 자동 음차 후보를 보정하지 않는다", () => {
    const candidates = parseCandidateCsv(
        "bggId,nameEn,nameKo,출처,검수완료(Y/N)\n1,Game,자동,추정번역(자동음차),N\n1,Game,공식명,국내 정발명,Y\n",
        "duplicate.csv",
    );

    assert.deepEqual(selectNameCorrections([candidates]).corrections, []);
});

test("BGG XML은 한글 alternate를 우선하고 없으면 primary를 제공한다", () => {
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

test("보정 SQL은 행 수를 보존한 채 최종 COMMIT 전에 이름만 수정한다", async () => {
    const root = mkdtempSync(join(tmpdir(), "albam-mate-game-name-correction-"));
    try {
        const inputSql = join(root, "01-games-full.sql");
        const candidateCsv = join(root, "candidates.csv");
        const xmlDirectory = join(root, "xml");
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

        const result = await correctGameNames({
            inputSql,
            candidatePaths: [candidateCsv],
            xmlDirectory,
            out,
        });
        const sql = readFileSync(result.outputSql, "utf8");
        const report = JSON.parse(readFileSync(result.reportPath, "utf8"));

        assert.equal(report.inputRows, 2);
        assert.equal(report.outputRows, 2);
        assert.equal(report.corrections.rows, 2);
        assert.equal(report.corrections.koreanAlternateRows, 1);
        assert.equal(report.corrections.primaryEnglishFallbackRows, 1);
        assert.ok(sql.indexOf("UPDATE games SET name = '웬디, 어른이 되렴'") < sql.lastIndexOf("COMMIT;"));
        assert.ok(sql.includes("UPDATE games SET name = 'Tinners'' Trail' WHERE bgg_id = 42;"));
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});
