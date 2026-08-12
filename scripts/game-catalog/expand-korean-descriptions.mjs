import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { translateDescription } from './korean-description-translator.mjs';
import { validateDescription } from './korean-description-validator.mjs';
import { commitZipArtifacts, resolveInputRoot } from './catalog-pipeline-utils.mjs';

const DOWNLOAD_DIR = resolveInputRoot(process.argv.slice(2));
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');
const SUPPLEMENT_DESC_SQL_PATH = path.join(LOCALIZATION_DIR, '05-upsert-korean-descriptions-supplement.sql');

async function processDescriptions() {
    console.log('1. zip 내 service-catalog JSON 임시 추출 중...');
    const tmpJsonPath = '/tmp/temp_service_catalog.json';
    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json > "${tmpJsonPath}"`);

    console.log('JSON 읽는 중...');
    const rawContent = fs.readFileSync(tmpJsonPath, 'utf-8');
    const catalogData = JSON.parse(rawContent);
    fs.unlinkSync(tmpJsonPath);

    console.log(`전체 카탈로그 행 수: ${catalogData.length}건`);

    // 상위 5,000개 게임 대상 설명 한글화 번역 진행
    const targetGames = catalogData.slice(0, 5000);
    console.log(`2. 상위 ${targetGames.length}개 게임 설명 한글화 번역 및 검증 진행 중...`);

    const sqlStatements = ['BEGIN;'];
    let successCount = 0;

    for (const game of targetGames) {
        const bggId = game.bgg_id;
        const origDesc = game.description || '';
        const origDetail = game.detail_description || '';

        const koDesc = translateDescription(origDesc);
        const koDetail = translateDescription(origDetail);

        const validation = validateDescription(bggId, koDesc, koDetail);
        if (validation.valid) {
            successCount++;
            const safeDesc = koDesc.replace(/'/g, "''");
            const safeDetail = koDetail.replace(/'/g, "''");
            sqlStatements.push(`UPDATE games SET description = '${safeDesc}', detail_description = '${safeDetail}' WHERE bgg_id = ${bggId};`);
        }
    }

    sqlStatements.push('COMMIT;');

    console.log(`3. 한글 설명 보완 SQL 작성 중 (${successCount}건)...`);
    console.log('4. 설명 SQL과 ZIP을 함께 검증한 뒤 원자적으로 갱신 중...');
    commitZipArtifacts({
        zipPath: ZIP_PATH,
        zipEntry: '06-complete-local-import/05-upsert-korean-descriptions-supplement.sql',
        zipFileTarget: SUPPLEMENT_DESC_SQL_PATH,
        files: [{ target: SUPPLEMENT_DESC_SQL_PATH, contents: sqlStatements.join('\n') + '\n' }],
    });
    console.log('ZIP 파일 내 05-upsert-korean-descriptions-supplement.sql 반영 완료!');

    console.log('5. README.md 업데이트...');
    const readmePath = path.join(DOWNLOAD_DIR, 'README.md');
    let readmeText = fs.readFileSync(readmePath, 'utf-8');
    if (!readmeText.includes('게임 설명 한국어 번역')) {
        readmeText += `\n- **(추가 업데이트)** BGG 상위 5,000건 게임 설명(\`description\` 및 \`detail_description\`) 보드게임 용어 사전 기반 한국어 번역 보완 완료 (\`05-upsert-korean-descriptions-supplement.sql\` 추가).\n`;
        fs.writeFileSync(readmePath, readmeText, 'utf-8');
    }
    console.log('설명 한글화 작업 완수!');
}

processDescriptions().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
