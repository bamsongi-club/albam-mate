import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const DOWNLOAD_DIR = '/Users/han-yejin/Downloads/albam-mate-170k';
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');

const NAMES_SQL_PATH = path.join(LOCALIZATION_DIR, '04-upsert-korean-names-supplement.sql');
const DESC_SQL_PATH = path.join(LOCALIZATION_DIR, '05-upsert-korean-descriptions-supplement.sql');
const MECHANISM_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-mechanism-ko-map.review-draft.json');
const THEME_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-theme-ko-map.review-draft.json');

const OUTPUT_CSV_PATH = path.join(DOWNLOAD_DIR, 'albam-mate-games-170k-final.csv');

async function exportCleanCsv() {
    console.log('1. 한글 메커니즘(카테고리) 및 테마 사전 파싱 중...');
    const mechDict = new Map();
    const mechJson = JSON.parse(fs.readFileSync(MECHANISM_MAP_JSON, 'utf-8'));
    for (const entry of mechJson.entries) {
        const id = Number(entry.bgg_id || entry.bggId || entry.id);
        mechDict.set(id, entry.name_ko || entry.nameKo);
    }

    const themeDict = new Map();
    const themeJson = JSON.parse(fs.readFileSync(THEME_MAP_JSON, 'utf-8'));
    for (const entry of themeJson.entries) {
        const id = Number(entry.bggThemeId || entry.bgg_id || entry.id);
        themeDict.set(id, entry.nameKo || entry.name_ko);
    }

    console.log('2. zip 내 catalog JSON, 02 메커니즘 SQL, 03 테마 SQL 추출 중...');
    const tmpDir = path.join(process.cwd(), '.tmp');
    fs.mkdirSync(tmpDir, { recursive: true });

    const tmpJsonPath = path.join(tmpDir, 'temp_catalog_clean.json');
    const mechSqlTmp = path.join(tmpDir, '02-upsert-game-mechanisms.sql');
    const themeSqlTmp = path.join(tmpDir, '03-upsert-game-metadata.sql');

    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json > "${tmpJsonPath}"`);
    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/02-upsert-game-mechanisms.sql > "${mechSqlTmp}"`);
    execSync(`unzip -p "${ZIP_PATH}" 06-complete-local-import/03-upsert-game-metadata.sql > "${themeSqlTmp}"`);

    const rawCatalog = JSON.parse(fs.readFileSync(tmpJsonPath, 'utf-8'));
    fs.unlinkSync(tmpJsonPath);

    // 게임 ID -> 메커니즘 한글명 배열 매핑
    const gameMechMap = new Map();
    const mechSqlText = fs.readFileSync(mechSqlTmp, 'utf-8');
    fs.unlinkSync(mechSqlTmp);
    const tupleRegex = /\(\s*(\d+)\s*,\s*(\d+)\s*\)/g;
    let match;
    while ((match = tupleRegex.exec(mechSqlText)) !== null) {
        const gameId = Number(match[1]);
        const mechId = Number(match[2]);
        const mechKo = mechDict.get(mechId);
        if (mechKo) {
            if (!gameMechMap.has(gameId)) gameMechMap.set(gameId, new Set());
            gameMechMap.get(gameId).add(mechKo);
        }
    }

    // 게임 ID -> 테마 한글명 배열 매핑
    const gameThemeMap = new Map();
    const themeSqlText = fs.readFileSync(themeSqlTmp, 'utf-8');
    fs.unlinkSync(themeSqlTmp);
    tupleRegex.lastIndex = 0;
    while ((match = tupleRegex.exec(themeSqlText)) !== null) {
        const gameId = Number(match[1]);
        const themeId = Number(match[2]);
        const themeKo = themeDict.get(themeId);
        if (themeKo) {
            if (!gameThemeMap.has(gameId)) gameThemeMap.set(gameId, new Set());
            gameThemeMap.get(gameId).add(themeKo);
        }
    }

    console.log('3. 04 한글명 SQL 수집 중...');
    const nameMap = new Map();
    const namesContent = fs.readFileSync(NAMES_SQL_PATH, 'utf-8');
    for (const line of namesContent.split('\n')) {
        const m = line.match(/UPDATE games SET name = '((?:''|[^'])*)' WHERE bgg_id = (\d+);/);
        if (m) {
            nameMap.set(Number(m[2]), m[1].replace(/''/g, "'"));
        }
    }

    console.log('4. 05 한글 설명 SQL 수집 중...');
    const descMap = new Map();
    const descContent = fs.readFileSync(DESC_SQL_PATH, 'utf-8');
    for (const line of descContent.split('\n')) {
        const m = line.match(/UPDATE games SET description = '((?:''|[^'])*)', detail_description = '((?:''|[^'])*)' WHERE bgg_id = (\d+);/);
        if (m) {
            descMap.set(Number(m[3]), {
                description: m[1].replace(/''/g, "'"),
                detail_description: m[2].replace(/''/g, "'")
            });
        }
    }

    console.log('5. 17만 건 전체 18종 완벽 메타데이터 CSV 이스케이프 처리 생성 중...');
    const csvHeader = [
        'bgg_id',
        'name_ko',
        'english_name',
        'alias',
        'image_url',
        'supported_player_count',
        'tag',
        'estimated_play_time',
        'min_players',
        'max_players',
        'min_play_time_minutes',
        'max_play_time_minutes',
        'complexity',
        'release_year',
        'description_ko',
        'detail_description_ko',
        'mechanisms_ko',
        'themes_ko'
    ].join(',');

    const csvRows = [csvHeader];

    // CSV 안전 이스케이프 (개행문자 \\n \\r 제거하여 1행=1레코드 완벽 유지)
    const escapeCsv = (val) => {
        if (val === null || val === undefined) return '""';
        const str = String(val).replace(/[\r\n]+/g, ' ').replace(/"/g, '""');
        return `"${str}"`;
    };

    for (const game of rawCatalog) {
        const bggId = game.bgg_id;
        const nameKo = nameMap.get(bggId) || game.name || game.english_name;
        const englishName = game.english_name || game.name || '';
        const alias = game.alias || `${nameKo}, ${englishName}`;
        const imageUrl = game.image_url || '';
        const supportedPlayerCount = game.supported_player_count || (game.min_players ? `${game.min_players}~${game.max_players}명` : '');
        const tag = game.tag || '전략';
        const estimatedPlayTime = game.estimated_play_time || (game.max_play_time_minutes ? `${game.max_play_time_minutes}분` : '');
        const minPlayers = game.min_players || '';
        const maxPlayers = game.max_players || '';
        const minPlayTime = game.min_play_time_minutes || '';
        const maxPlayTime = game.max_play_time_minutes || '';
        const complexity = game.complexity || '';
        const releaseYear = game.release_year || '';

        const descObj = descMap.get(bggId) || {};
        const descKo = descObj.description || game.description || '';
        const detailKo = descObj.detail_description || game.detail_description || '';

        const mechs = gameMechMap.has(bggId) ? Array.from(gameMechMap.get(bggId)).join('; ') : '';
        const themes = gameThemeMap.has(bggId) ? Array.from(gameThemeMap.get(bggId)).join('; ') : '';

        const row = [
            bggId,
            escapeCsv(nameKo),
            escapeCsv(englishName),
            escapeCsv(alias),
            escapeCsv(imageUrl),
            escapeCsv(supportedPlayerCount),
            escapeCsv(tag),
            escapeCsv(estimatedPlayTime),
            minPlayers,
            maxPlayers,
            minPlayTime,
            maxPlayTime,
            complexity,
            releaseYear,
            escapeCsv(descKo),
            escapeCsv(detailKo),
            escapeCsv(mechs),
            escapeCsv(themes)
        ].join(',');

        csvRows.push(row);
    }

    console.log(`6. albam-mate-games-170k-final.csv 파일 출력 중 (${csvRows.length - 1}행)...`);
    fs.writeFileSync(OUTPUT_CSV_PATH, csvRows.join('\n'), 'utf-8');
    console.log(`🎉 개행문자 및 18종 전 컬럼 완벽 통합 CSV 생성 완수! ${OUTPUT_CSV_PATH}`);
}

exportCleanCsv().catch(console.error);
