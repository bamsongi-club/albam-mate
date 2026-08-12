import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { convertTitleToKorean } from './korean-name-collector.mjs';
import { validateKoreanName } from './korean-name-validator.mjs';
import { escapeCsvField, resolveInputRoot } from './catalog-pipeline-utils.mjs';

const DOWNLOAD_DIR = resolveInputRoot(process.argv.slice(2));
const RANKS_CSV_PATH = path.join(DOWNLOAD_DIR, 'reference/04-inputs/boardgames_ranks07-24.csv');
const SUPPLEMENT_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.sql');
const CANDIDATES_OUTPUT_CSV = path.join(DOWNLOAD_DIR, 'reference/02-localization/bgg-game-name-ko-candidates-7001-15000.csv');

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

            }
        }
    }

    console.log(`대상 항목 중 생성 성공: ${validCount} / ${processedCount} 건`);

    // 3. 신규 candidate CSV 작성
    console.log('3. bgg-game-name-ko-candidates-7001-15000.csv 생성 중...');
    const csvHeader = 'bggRank,bggId,nameEn,nameKo,출처,검수완료(Y/N)\n';
    const csvRows = newCandidates.map(c => {
        return `${c.bggRank},${c.bggId},${escapeCsvField(c.nameEn)},${escapeCsvField(c.nameKo)},${escapeCsvField(c.source)},${c.reviewed}`;
    }).join('\n');

    fs.writeFileSync(CANDIDATES_OUTPUT_CSV, csvHeader + csvRows, 'utf-8');
    console.log(`CSV 작성 완료: ${CANDIDATES_OUTPUT_CSV}`);

    console.log('4. 자동 음차는 검수 후보 CSV에만 기록하고 승인 SQL은 변경하지 않습니다.');
}

processNames().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
