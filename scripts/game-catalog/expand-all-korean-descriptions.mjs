import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { translateDescription } from './korean-description-translator.mjs';
import { validateDescription } from './korean-description-validator.mjs';

const DOWNLOAD_DIR = '/Users/han-yejin/Downloads/albam-mate-170k';
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const SUPPLEMENT_DESC_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/05-upsert-korean-descriptions-supplement.sql');

async function processAllDescriptions() {
    console.log('1. zip 내 service-catalog JSON 임시 추출 중...');
    const tmpDir = path.join(process.cwd(), '.tmp');
    fs.mkdirSync(tmpDir, { recursive: true });
    const tmpJsonPath = path.join(tmpDir, 'temp_service_catalog_desc.json');
    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json > "${tmpJsonPath}"`);

    console.log('JSON 파싱 중...');
    const rawContent = fs.readFileSync(tmpJsonPath, 'utf-8');
    const catalogData = JSON.parse(rawContent);
    fs.unlinkSync(tmpJsonPath);

    console.log(`전체 카탈로그 행 수: ${catalogData.length}건`);

    console.log('2. 17만 건 전체 게임 설명 번역 및 검증 처리 중...');
    const sqlStatements = ['BEGIN;'];
    let successCount = 0;

    for (let i = 0; i < catalogData.length; i++) {
        const game = catalogData[i];
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

        if ((i + 1) % 50000 === 0) {
            console.log(`진행률: ${i + 1} / ${catalogData.length} 건 처리 완료...`);
        }
    }

    sqlStatements.push('COMMIT;');

    console.log(`3. 05-upsert-korean-descriptions-supplement.sql 갱신 중 (${successCount}건)...`);
    fs.writeFileSync(SUPPLEMENT_DESC_SQL_PATH, sqlStatements.join('\n') + '\n', 'utf-8');

    console.log('4. zip 파일 내 05-upsert-korean-descriptions-supplement.sql 업데이트 중...');
    const zipTmpDir = path.join(process.cwd(), '.tmp/desc_full_zip_update/06-complete-local-import');
    fs.mkdirSync(zipTmpDir, { recursive: true });
    fs.copyFileSync(SUPPLEMENT_DESC_SQL_PATH, path.join(zipTmpDir, '05-upsert-korean-descriptions-supplement.sql'));
    execSync(`cd "${path.join(process.cwd(), '.tmp/desc_full_zip_update')}" && zip -u "${ZIP_PATH}" 06-complete-local-import/05-upsert-korean-descriptions-supplement.sql`);
    fs.rmSync(path.join(process.cwd(), '.tmp/desc_full_zip_update'), { recursive: true, force: true });

    console.log(`17만 건 전체 게임 설명 한글화 전면 확충 완수! (총 ${successCount}건)`);
}

processAllDescriptions().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
