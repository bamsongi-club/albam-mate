import assert from 'node:assert/strict';
import {
    mkdirSync,
    mkdtempSync,
    rmSync,
    symlinkSync,
    writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
    measureCatalogDatasetCoverage,
    resolveArtifactPaths,
} from './catalog-dataset-release-measurement.mjs';

test('SQL coverage는 정렬·중복 제거·bool_or 규칙으로 결정적으로 계산된다', () => {
    const coverage = measureCatalogDatasetCoverage({
        datasetIds: [2, 1],
        mechanismSql: `INSERT INTO game_mechanism_relation_source (bgg_id, bgg_mechanism_id) VALUES
            (2, 20), (1, 10), (1, 10);`,
        metadataSql: `with desired(bgg_id,bgg_theme_id) as (values
            (2, 4), (1, 3), (1, 3)) insert into game_theme_relations;
            with desired(bgg_id,player_count,is_recommended,is_best) as (values
            (1, 2, true, false), (1, 2, false, true), (2, 1, false, false))
            insert into game_player_preferences;`,
    });

    assert.equal(coverage.catalogIds.rows, 2);
    assert.equal(coverage.mechanismRelations.rows, 2);
    assert.equal(coverage.themeRelations.rows, 2);
    assert.equal(coverage.playerPreferences.rows, 2);
    assert.equal(coverage.playerPreferences.serialization, 'sorted-bgg-id-player-count-recommended-best-csv-v1');
});

test('artifact realpath가 root 밖으로 나가거나 같은 파일을 가리키면 차단한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'catalog-release-artifacts-'));
    const outside = mkdtempSync(join(tmpdir(), 'catalog-release-outside-'));
    try {
        writeFileSync(join(root, '01-upsert-games-chunked.sql'), '01');
        writeFileSync(join(outside, '01-upsert-games-chunked.sql'), 'outside');

        const validArtifacts = artifactMap('artifacts');
        mkdirSync(join(root, 'artifacts'));
        for (const { path } of Object.values(validArtifacts)) {
            writeFileSync(join(root, path), path);
        }
        assert.doesNotThrow(() => resolveArtifactPaths(validArtifacts, root));

        const escaped = { ...validArtifacts };
        symlinkSync(join(outside, '01-upsert-games-chunked.sql'), join(root, 'artifacts', 'escaped.sql'));
        escaped['01'] = { path: 'artifacts/escaped.sql' };
        assert.throws(() => resolveArtifactPaths(escaped, root), /unexpected file name|outside artifacts root/u);

    } finally {
        rmSync(root, { recursive: true, force: true });
        rmSync(outside, { recursive: true, force: true });
    }
});

function artifactMap(directory) {
    const path = (fileName) => ({ path: `${directory}/${fileName}` });
    return {
        '01': path('01-upsert-games-chunked.sql'),
        '01b': path('01b-restore-boardgameexpansions.sql'),
        '02': path('02-upsert-game-mechanisms.sql'),
        '03': path('03-upsert-game-metadata.sql'),
        '04': path('04-upsert-korean-names-supplement.sql'),
        '05': path('05-upsert-korean-descriptions-supplement.sql'),
        '06': path('06-upsert-boardlife-new-games.sql'),
        '07': path('07-fix-name-mismapping.sql'),
    };
}
