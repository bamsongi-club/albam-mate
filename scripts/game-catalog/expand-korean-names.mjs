import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { convertTitleToKorean } from './korean-name-collector.mjs';
import { validateKoreanName } from './korean-name-validator.mjs';

const DOWNLOAD_DIR = '/Users/han-yejin/Downloads/albam-mate-170k';
const RANKS_CSV_PATH = path.join(DOWNLOAD_DIR, 'reference/04-inputs/boardgames_ranks07-24.csv');
const SUPPLEMENT_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.sql');
const CANDIDATES_OUTPUT_CSV = path.join(DOWNLOAD_DIR, 'reference/02-localization/bgg-game-name-ko-candidates-7001-15000.csv');
const MAIN_README_PATH = path.join(DOWNLOAD_DIR, 'README.md');

async function processNames() {
    console.log('1. 기존 SQL의 이미 보강된 bgg_id 수집 중...');
    const existingSqlContent = fs.readFileSync(SUPPLEMENT_SQL_PATH, 'utf-8');
    const existingBggIds = new Set();
    const sqlLines = existingSqlContent.split('\n');
    for (const line of sqlLines) {
        const match = line.match(/WHERE bgg_id = (\d+);/);
        if (match) {
            existingBggIds.add(Number(match[1]));
        }
    }
    console.log(`기존 SQL 내 한글화 적용 bgg_id 개수: ${existingBggIds.size}건`);

    console.log('2. ranks CSV 파싱 및 BGG 7001위 ~ 15000위 한글화 후보 생성 중...');
    const fileStream = fs.createReadStream(RANKS_CSV_PATH);
    const rl = readline.createInterface({ input: fileStream, crlfDelay: Infinity });

    let isHeader = true;
    const newCandidates = [];
    const newSqlStatements = [];
    let processedCount = 0;
    let validCount = 0;

    for await (const line of rl) {
        if (isHeader) {
            isHeader = false;
            continue;
        }
        if (!line.trim()) continue;

        // Simple CSV parse for: id,name,...,rank
        // Using regex for CSV split handling quotes
        const match = line.match(/^(\d+),("(?:[^"]|"")*"|[^,]*),(\d*),(\d+)/);
        if (!match) continue;

        const bggId = Number(match[1]);
        let nameEn = match[2];
        if (nameEn.startsWith('"') && nameEn.endsWith('"')) {
            nameEn = nameEn.slice(1, -1).replace(/""/g, '"');
        }
        const rank = Number(match[4]);

        // 7001위 ~ 15000위 및 기존에 등록되지 않은 게임 대상
        if (rank >= 7001 && rank <= 15000 && !existingBggIds.has(bggId)) {
            processedCount++;
            const nameKo = convertTitleToKorean(nameEn);
            const validation = validateKoreanName(bggId, nameKo, nameEn);

            if (validation.valid) {
                validCount++;
                newCandidates.push({
                    bggRank: rank,
                    bggId,
                    nameEn,
                    nameKo,
                    source: '추정번역(자동음차)',
                    reviewed: 'N'
                });

                // SQL UPDATE 구문 작성 (작은 따옴표 escape)
                const safeNameKo = nameKo.replace(/'/g, "''");
                newSqlStatements.push(`UPDATE games SET name = '${safeNameKo}' WHERE bgg_id = ${bggId};`);
                existingBggIds.add(bggId);
            }
        }
    }

    console.log(`대상 항목 중 생성 성공: ${validCount} / ${processedCount} 건`);

    // 3. 신규 candidate CSV 작성
    console.log('3. bgg-game-name-ko-candidates-7001-15000.csv 생성 중...');
    const csvHeader = 'bggRank,bggId,nameEn,nameKo,출처,검수완료(Y/N)\n';
    const csvRows = newCandidates.map(c => {
        const safeEn = c.nameEn.includes(',') ? `"${c.nameEn.replace(/"/g, '""')}"` : c.nameEn;
        const safeKo = c.nameKo.includes(',') ? `"${c.nameKo.replace(/"/g, '""')}"` : c.nameKo;
        return `${c.bggRank},${c.bggId},${safeEn},${safeKo},${c.source},${c.reviewed}`;
    }).join('\n');

    fs.writeFileSync(CANDIDATES_OUTPUT_CSV, csvHeader + csvRows, 'utf-8');
    console.log(`CSV 작성 완료: ${CANDIDATES_OUTPUT_CSV}`);

    // 4. 04-upsert-korean-names-supplement.sql 갱신
    console.log('4. 04-upsert-korean-names-supplement.sql 갱신 중...');
    let updatedSqlContent = existingSqlContent.trim();
    if (updatedSqlContent.endsWith('COMMIT;')) {
        updatedSqlContent = updatedSqlContent.slice(0, -7).trim();
    }
    
    const appendSql = '\n-- 추가 확충분 (bggRank 7001~15000위 음차 보완 ' + newSqlStatements.length + '건)\n' +
        newSqlStatements.join('\n') + '\nCOMMIT;\n';

    fs.writeFileSync(SUPPLEMENT_SQL_PATH, updatedSqlContent + appendSql, 'utf-8');
    console.log(`SQL 갱신 완료: 총 ${existingBggIds.size}건으로 확충됨.`);

    // 5. README.md 업데이트
    console.log('5. README.md 통계업데이트 중...');
    let readmeText = fs.readFileSync(MAIN_README_PATH, 'utf-8');
    const newTotalFormatted = existingBggIds.size.toLocaleString();
    readmeText = readmeText.replace(/게임명 한국어 표시명 \d+(,\d+)*건/, `게임명 한국어 표시명 ${newTotalFormatted}건`);
    fs.writeFileSync(MAIN_README_PATH, readmeText, 'utf-8');
    console.log('README.md 업데이트 완료.');
}

processNames().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
