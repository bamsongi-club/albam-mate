import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const DOWNLOAD_DIR = '/Users/han-yejin/Downloads/albam-mate-170k';
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');

const MECHANISM_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-mechanism-ko-map.review-draft.json');
const THEME_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-theme-ko-map.review-draft.json');

const FINAL_CSV_PATH = path.join(DOWNLOAD_DIR, 'albam-mate-games-170k-final.csv');

async function processCategoriesAndThemes() {
    console.log('1. 한글 메커니즘(카테고리) 및 테마 사전 파싱 중...');
    const mechDict = new Map();
    const mechJson = JSON.parse(fs.readFileSync(MECHANISM_MAP_JSON, 'utf-8'));
    for (const entry of mechJson.entries) {
        const id = Number(entry.bgg_id || entry.bggId || entry.id);
        mechDict.set(id, { nameEn: entry.name || entry.nameEn, nameKo: entry.name_ko || entry.nameKo });
    }

    const themeDict = new Map();
    const themeJson = JSON.parse(fs.readFileSync(THEME_MAP_JSON, 'utf-8'));
    for (const entry of themeJson.entries) {
        const id = Number(entry.bggThemeId || entry.bgg_id || entry.id);
        themeDict.set(id, { nameEn: entry.nameEn || entry.name, nameKo: entry.nameKo || entry.name_ko });
    }

    console.log(`- 한글 메커니즘 수: ${mechDict.size}개`);
    console.log(`- 한글 테마 수: ${themeDict.size}개`);

    console.log('2. zip 내 02-upsert-game-mechanisms.sql 및 03-upsert-game-metadata.sql 추출 중...');
    const tmpDir = path.join(process.cwd(), '.tmp');
    fs.mkdirSync(tmpDir, { recursive: true });

    const mechSqlTmp = path.join(tmpDir, '02-upsert-game-mechanisms.sql');
    const themeSqlTmp = path.join(tmpDir, '03-upsert-game-metadata.sql');

    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/02-upsert-game-mechanisms.sql > "${mechSqlTmp}"`);
    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/03-upsert-game-metadata.sql > "${themeSqlTmp}"`);

    // 게임 ID -> 메커니즘 한글명 배열 파싱
    const gameMechMap = new Map();
    const mechSqlText = fs.readFileSync(mechSqlTmp, 'utf-8');
    fs.unlinkSync(mechSqlTmp);

    const tupleRegex = /\(\s*(\d+)\s*,\s*(\d+)\s*\)/g;
    let match;

    while ((match = tupleRegex.exec(mechSqlText)) !== null) {
        const gameId = Number(match[1]);
        const mechId = Number(match[2]);
        const mechInfo = mechDict.get(mechId);
        if (mechInfo && mechInfo.nameKo) {
            if (!gameMechMap.has(gameId)) gameMechMap.set(gameId, []);
            gameMechMap.get(gameId).push(mechInfo.nameKo);
        }
    }

    // 게임 ID -> 테마 한글명 배열 파싱
    const gameThemeMap = new Map();
    const themeSqlText = fs.readFileSync(themeSqlTmp, 'utf-8');
    fs.unlinkSync(themeSqlTmp);

    tupleRegex.lastIndex = 0;
    while ((match = tupleRegex.exec(themeSqlText)) !== null) {
        const gameId = Number(match[1]);
        const themeId = Number(match[2]);
        const themeInfo = themeDict.get(themeId);
        if (themeInfo && themeInfo.nameKo) {
            if (!gameThemeMap.has(gameId)) gameThemeMap.set(gameId, []);
            gameThemeMap.get(gameId).push(themeInfo.nameKo);
        }
    }

    console.log(`- 카테고리/메커니즘 매핑된 게임 수: ${gameMechMap.size}건`);
    console.log(`- 테마 매핑된 게임 수: ${gameThemeMap.size}건`);

    console.log('3. albam-mate-games-170k-final.csv에 categories_ko 및 themes_ko 컬럼 통합 갱신 중...');
    const originalCsvLines = fs.readFileSync(FINAL_CSV_PATH, 'utf-8').split('\n');
    if (originalCsvLines.length === 0) return;

    const oldHeader = originalCsvLines[0];
    const newHeader = `${oldHeader},categories_ko,themes_ko`;
    const newCsvLines = [newHeader];

    const escapeCsv = (val) => {
        if (!val) return '""';
        const str = String(val).replace(/"/g, '""');
        return `"${str}"`;
    };

    for (let i = 1; i < originalCsvLines.length; i++) {
        const line = originalCsvLines[i].trim();
        if (!line) continue;

        // bgg_id는 첫 번째 컬럼 (comma split전 첫 번째 토큰)
        const firstCommaIndex = line.indexOf(',');
        const gameIdStr = firstCommaIndex !== -1 ? line.substring(0, firstCommaIndex) : line;
        const gameId = Number(gameIdStr);

        const mechs = gameMechMap.get(gameId) ? gameMechMap.get(gameId).join('; ') : '';
        const themes = gameThemeMap.get(gameId) ? gameThemeMap.get(gameId).join('; ') : '';

        newCsvLines.push(`${line},${escapeCsv(mechs)},${escapeCsv(themes)}`);
    }

    fs.writeFileSync(FINAL_CSV_PATH, newCsvLines.join('\n'), 'utf-8');
    console.log(`통합 CSV 갱신 완료 (새 헤더 포함): ${FINAL_CSV_PATH}`);

    // 4. 별도 독립 릴레이션 CSV 4종 생성
    console.log('4. 별도 카테고리/테마 릴레이션 CSV 4종 생성 중...');

    // 4-1. mechanisms_ko.csv
    const mechRows = ['bgg_id,name_en,name_ko'];
    for (const [id, info] of mechDict.entries()) {
        mechRows.push(`${id},${escapeCsv(info.nameEn)},${escapeCsv(info.nameKo)}`);
    }
    fs.writeFileSync(path.join(DOWNLOAD_DIR, 'mechanisms_ko.csv'), mechRows.join('\n'), 'utf-8');

    // 4-2. themes_ko.csv
    const themeRows = ['bgg_id,name_en,name_ko'];
    for (const [id, info] of themeDict.entries()) {
        themeRows.push(`${id},${escapeCsv(info.nameEn)},${escapeCsv(info.nameKo)}`);
    }
    fs.writeFileSync(path.join(DOWNLOAD_DIR, 'themes_ko.csv'), themeRows.join('\n'), 'utf-8');

    // 4-3. game_mechanism_mappings.csv
    const gameMechRows = ['game_bgg_id,mechanism_bgg_id'];
    tupleRegex.lastIndex = 0;
    while ((match = tupleRegex.exec(mechSqlText)) !== null) {
        gameMechRows.push(`${match[1]},${match[2]}`);
    }
    fs.writeFileSync(path.join(DOWNLOAD_DIR, 'game_mechanism_mappings.csv'), gameMechRows.join('\n'), 'utf-8');

    // 4-4. game_theme_mappings.csv
    const gameThemeRows = ['game_bgg_id,theme_bgg_id'];
    tupleRegex.lastIndex = 0;
    while ((match = tupleRegex.exec(themeSqlText)) !== null) {
        gameThemeRows.push(`${match[1]},${match[2]}`);
    }
    fs.writeFileSync(path.join(DOWNLOAD_DIR, 'game_theme_mappings.csv'), gameThemeRows.join('\n'), 'utf-8');

    console.log('모든 메커니즘/테마 한글 데이터 추출 및 CSV 생성이 완료되었습니다!');
}

processCategoriesAndThemes().catch(console.error);
