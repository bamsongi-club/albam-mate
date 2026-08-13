import fs from 'node:fs';
import path from 'node:path';
import { convertTitleToKorean, OFFICIAL_NAME_MAP } from './korean-name-collector.mjs';
import { validateKoreanName } from './korean-name-validator.mjs';
import {
    commitZipArtifacts,
    parseNameUpdates,
    readZipJsonEntry,
    resolveInputRoot,
} from './catalog-pipeline-utils.mjs';

const DOWNLOAD_DIR = resolveInputRoot(process.argv.slice(2));
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const SUPPLEMENT_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.sql');
const NEEDS_REVIEW_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.needs-review.json');

async function processAllNames() {
    console.log('1. zip 내 service-catalog JSON 임시 추출 중...');
    console.log('JSON 데이터 파싱 중...');
    const catalogData = await readZipJsonEntry(
        ZIP_PATH,
        '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json',
    );

    console.log(`전체 카탈로그 행 수: ${catalogData.length}건`);

    console.log('2. 기존 04-upsert-korean-names-supplement.sql 수집 중...');
    const existingSql = fs.readFileSync(SUPPLEMENT_SQL_PATH, 'utf-8');
    const existingMap = new Map(parseNameUpdates(existingSql).map(({ bggId, value }) => [bggId, value]));
    console.log(`기존 한글명 개수: ${existingMap.size}건`);

    console.log('3. 17만 건 전체 게임명 웹 검증 정식 명칭 최우선 반영 및 보완 중...');
    const sqlStatements = ['BEGIN;'];
    const needsReview = [];
    let successCount = 0;

    for (const game of catalogData) {
        const bggId = game.bgg_id;
        let nameKo = OFFICIAL_NAME_MAP[bggId] ?? existingMap.get(bggId);
        let isAutoTransliterated = false;

        if (!nameKo) {
            nameKo = convertTitleToKorean(game.english_name || game.name);
            isAutoTransliterated = true;
        }

        const validation = validateKoreanName(bggId, nameKo, game.english_name);
        if (validation.valid) {
            successCount++;
            if (isAutoTransliterated) {
                // 사람이 검수한 OFFICIAL_NAME_MAP·기존 승인본이 아닌 자동 음차 결과이므로
                // 정식 게임명으로 바로 승인하지 않고 별도 검수 대기 목록으로만 남긴다.
                needsReview.push({ bgg_id: bggId, english_name: game.english_name, name_ko: nameKo });
                continue;
            }
            const safeName = nameKo.replace(/'/g, "''");
            sqlStatements.push(`UPDATE games SET name = '${safeName}' WHERE bgg_id = ${bggId};`);
        }
    }

    sqlStatements.push('COMMIT;');
    const nextSql = sqlStatements.join('\n') + '\n';
    const nextApprovedIds = new Set(parseNameUpdates(nextSql).map(({ bggId }) => bggId));
    const missingApprovedIds = [...existingMap.keys()].filter((bggId) => !nextApprovedIds.has(bggId));
    if (missingApprovedIds.length > 0) {
        throw new Error(`기존 승인 이름이 새 SQL에서 누락됐습니다: ${missingApprovedIds.slice(0, 10).join(', ')}`);
    }

    console.log(`4. 04-upsert-korean-names-supplement.sql 전면 갱신 중 (${successCount - needsReview.length}건, 검수 대기 ${needsReview.length}건)...`);
    console.log('5. SQL·검수 대기 목록·ZIP을 함께 검증한 뒤 원자적으로 갱신 중...');
    await commitZipArtifacts({
        zipPath: ZIP_PATH,
        zipEntry: '06-complete-local-import/04-upsert-korean-names-supplement.sql',
        zipFileTarget: SUPPLEMENT_SQL_PATH,
        files: [
            { target: SUPPLEMENT_SQL_PATH, contents: nextSql },
            { target: NEEDS_REVIEW_PATH, contents: JSON.stringify(needsReview, null, 2) + '\n' },
        ],
    });

    console.log(`17만 건 전체 게임명 한글화 전면 확충 완수! (적용 ${successCount - needsReview.length}건, 검수 대기 ${needsReview.length}건)`);
}

processAllNames().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
