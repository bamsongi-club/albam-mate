import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { convertTitleToKorean, OFFICIAL_NAME_MAP } from './korean-name-collector.mjs';
import { validateKoreanName } from './korean-name-validator.mjs';

const DOWNLOAD_DIR = '/Users/han-yejin/Downloads/albam-mate-170k';
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const SUPPLEMENT_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.sql');
const NEEDS_REVIEW_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.needs-review.json');

async function processAllNames() {
    console.log('1. zip 내 service-catalog JSON 임시 추출 중...');
    const tmpDir = path.join(process.cwd(), '.tmp');
    fs.mkdirSync(tmpDir, { recursive: true });
    const tmpJsonPath = path.join(tmpDir, 'temp_service_catalog_names.json');
    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json > "${tmpJsonPath}"`);

    console.log('JSON 데이터 파싱 중...');
    const rawContent = fs.readFileSync(tmpJsonPath, 'utf-8');
    const catalogData = JSON.parse(rawContent);
    fs.unlinkSync(tmpJsonPath);

    console.log(`전체 카탈로그 행 수: ${catalogData.length}건`);

    console.log('2. 기존 04-upsert-korean-names-supplement.sql 수집 중...');
    const existingSql = fs.readFileSync(SUPPLEMENT_SQL_PATH, 'utf-8');
    const existingMap = new Map();
    const sqlLines = existingSql.split('\n');
    for (const line of sqlLines) {
        const match = line.match(/UPDATE games SET name = '((?:''|[^'])*)' WHERE bgg_id = (\d+);/);
        if (match) {
            existingMap.set(Number(match[2]), match[1].replace(/''/g, "'"));
        }
    }
    console.log(`기존 한글명 개수: ${existingMap.size}건`);

    console.log('3. 17만 건 전체 게임명 웹 검증 정식 명칭 최우선 반영 및 보완 중...');
    const sqlStatements = ['BEGIN;'];
    const needsReview = [];
    let successCount = 0;

    const BUGGY_PHONETIC_REGEX = /스앰어에|름아에|아크우어에|엔크아우엔트어|르이엘엠|로브오알르이|드어|스에|트어|크아우|프아|르이|그흐/;

    for (const game of catalogData) {
        const bggId = game.bgg_id;
        let nameKo = OFFICIAL_NAME_MAP[bggId];
        let isAutoTransliterated = false;

        if (!nameKo) {
            nameKo = existingMap.get(bggId);
            const isMixedAlphabet = /[a-zA-Z]/.test(nameKo) && !/\((1|2|3|4|5)판\)/.test(nameKo) && !/\b(3D|2D|HD|VR)\b/i.test(nameKo);
            const isBuggyPhonetic = BUGGY_PHONETIC_REGEX.test(nameKo);

            if (!nameKo || nameKo === game.english_name || isMixedAlphabet || isBuggyPhonetic) {
                nameKo = convertTitleToKorean(game.english_name || game.name);
                isAutoTransliterated = true;
            }
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

    console.log(`4. 04-upsert-korean-names-supplement.sql 전면 갱신 중 (${successCount - needsReview.length}건, 검수 대기 ${needsReview.length}건)...`);
    fs.writeFileSync(SUPPLEMENT_SQL_PATH, sqlStatements.join('\n') + '\n', 'utf-8');
    fs.writeFileSync(NEEDS_REVIEW_PATH, JSON.stringify(needsReview, null, 2) + '\n', 'utf-8');

    console.log('5. zip 파일 내 04-upsert-korean-names-supplement.sql 업데이트 중...');
    const zipTmpDir = path.join(process.cwd(), '.tmp/name_zip_update/06-complete-local-import');
    fs.mkdirSync(zipTmpDir, { recursive: true });
    fs.copyFileSync(SUPPLEMENT_SQL_PATH, path.join(zipTmpDir, '04-upsert-korean-names-supplement.sql'));
    execSync(`cd "${path.join(process.cwd(), '.tmp/name_zip_update')}" && zip -u "${ZIP_PATH}" 06-complete-local-import/04-upsert-korean-names-supplement.sql`);
    fs.rmSync(path.join(process.cwd(), '.tmp/name_zip_update'), { recursive: true, force: true });

    console.log(`17만 건 전체 게임명 한글화 전면 확충 완수! (적용 ${successCount - needsReview.length}건, 검수 대기 ${needsReview.length}건)`);
}

processAllNames().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
