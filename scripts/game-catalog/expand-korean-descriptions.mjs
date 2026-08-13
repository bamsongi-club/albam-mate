import fs from 'node:fs';
import path from 'node:path';
import { translateDescription } from './korean-description-translator.mjs';
import { validateDescription } from './korean-description-validator.mjs';
import { readZipJsonEntry, resolveInputRoot } from './catalog-pipeline-utils.mjs';

const DOWNLOAD_DIR = resolveInputRoot(process.argv.slice(2));
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');
const NEEDS_REVIEW_PATH = path.join(LOCALIZATION_DIR, '05-upsert-korean-descriptions-supplement.needs-review.json');
const CATALOG_ZIP_ENTRY = '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json';

async function processDescriptions() {
    console.log('1. zip 내 service-catalog JSON 임시 추출 중...');
    const catalogData = readZipJsonEntry(ZIP_PATH, CATALOG_ZIP_ENTRY);

    console.log(`전체 카탈로그 행 수: ${catalogData.length}건`);

    // 상위 5,000개 게임 대상 설명 한글화 번역 진행
    const targetGames = catalogData.slice(0, 5000);
    console.log(`2. 상위 ${targetGames.length}개 게임 설명 한글화 번역 및 검증 진행 중...`);

    const needsReview = [];

    for (const game of targetGames) {
        const bggId = game.bgg_id;
        const origDesc = game.description || '';
        const origDetail = game.detail_description || '';

        const koDesc = translateDescription(origDesc);
        const koDetail = translateDescription(origDetail);

        const validation = validateDescription(bggId, koDesc, koDetail);
        if (validation.valid) {
            needsReview.push({
                bgg_id: bggId,
                source_description: origDesc,
                source_detail_description: origDetail,
                description_ko: koDesc,
                detail_description_ko: koDetail,
                reviewed: false,
            });
        }
    }

    fs.writeFileSync(NEEDS_REVIEW_PATH, JSON.stringify(needsReview, null, 2) + '\n', 'utf-8');
    console.log(`3. 자동 번역 ${needsReview.length}건을 검수 대기 목록에 기록했습니다.`);
    console.log('사람이 승인하기 전에는 설명 SQL과 ZIP을 변경하지 않습니다.');
}

processDescriptions().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
