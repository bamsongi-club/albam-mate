import fs from 'node:fs';
import path from 'node:path';

import {
    escapeCsvField,
    parseApprovedRelationTuples,
    parseCsvLine,
    readZipTextEntry,
    resolveInputRoot,
} from './catalog-pipeline-utils.mjs';

const DOWNLOAD_DIR = resolveInputRoot(process.argv.slice(2));
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');
const MECHANISM_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-mechanism-ko-map.review-draft.json');
const THEME_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-theme-ko-map.review-draft.json');
const FINAL_CSV_PATH = path.join(DOWNLOAD_DIR, 'albam-mate-games-170k-final.csv');

async function processCategoriesAndThemes() {
    const mechanismDictionary = readDictionary(MECHANISM_MAP_JSON, {
        id: (entry) => entry.bgg_id ?? entry.bggId ?? entry.id,
        nameEn: (entry) => entry.name ?? entry.nameEn,
        nameKo: (entry) => entry.name_ko ?? entry.nameKo,
        role: 'mechanism',
    });
    const themeDictionary = readDictionary(THEME_MAP_JSON, {
        id: (entry) => entry.bggThemeId ?? entry.bgg_id ?? entry.id,
        nameEn: (entry) => entry.nameEn ?? entry.name,
        nameKo: (entry) => entry.nameKo ?? entry.name_ko,
        role: 'theme',
    });
    const [mechanismSql, themeSql] = await Promise.all([
        readZipTextEntry(ZIP_PATH, '06-complete-local-import/02-upsert-game-mechanisms.sql'),
        readZipTextEntry(ZIP_PATH, '06-complete-local-import/03-upsert-game-metadata.sql'),
    ]);
    const mechanismRelations = parseApprovedRelationTuples(
        mechanismSql,
        'mechanism',
        new Set(mechanismDictionary.keys()),
    );
    const themeRelations = parseApprovedRelationTuples(
        themeSql,
        'theme',
        new Set(themeDictionary.keys()),
    );
    const gameMechanisms = relationNames(mechanismRelations, mechanismDictionary);
    const gameThemes = relationNames(themeRelations, themeDictionary);

    updateFinalCsv(gameMechanisms, gameThemes);
    writeDictionaryCsv(path.join(DOWNLOAD_DIR, 'mechanisms_ko.csv'), mechanismDictionary);
    writeDictionaryCsv(path.join(DOWNLOAD_DIR, 'themes_ko.csv'), themeDictionary);
    writeRelationCsv(
        path.join(DOWNLOAD_DIR, 'game_mechanism_mappings.csv'),
        'game_bgg_id,mechanism_bgg_id',
        mechanismRelations,
    );
    writeRelationCsv(
        path.join(DOWNLOAD_DIR, 'game_theme_mappings.csv'),
        'game_bgg_id,theme_bgg_id',
        themeRelations,
    );
    console.log('승인 relation source만 사용해 카테고리·테마 CSV를 생성했습니다.');
}

function updateFinalCsv(gameMechanisms, gameThemes) {
    const lines = fs.readFileSync(FINAL_CSV_PATH, 'utf8').split('\n').filter(Boolean);
    if (lines.length === 0) throw new Error('final CSV is empty');
    const columns = parseCsvLine(lines[0]);
    let categoriesIndex = columns.indexOf('categories_ko');
    let themesIndex = columns.indexOf('themes_ko');
    if (categoriesIndex === -1) {
        categoriesIndex = columns.length;
        columns.push('categories_ko');
    }
    if (themesIndex === -1) {
        themesIndex = columns.length;
        columns.push('themes_ko');
    }
    const output = [columns.join(',')];
    for (const line of lines.slice(1)) {
        const values = parseCsvLine(line);
        const gameBggId = Number(values[0]);
        while (values.length < columns.length) values.push('');
        values[categoriesIndex] = (gameMechanisms.get(gameBggId) ?? []).join('; ');
        values[themesIndex] = (gameThemes.get(gameBggId) ?? []).join('; ');
        output.push(values.slice(0, columns.length).map(escapeCsvField).join(','));
    }
    fs.writeFileSync(FINAL_CSV_PATH, output.join('\n'), 'utf8');
}

function readDictionary(filePath, { id, nameEn, nameKo, role }) {
    const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    const dictionary = new Map();
    for (const entry of parsed.entries ?? []) {
        const bggId = Number(id(entry));
        const englishName = nameEn(entry);
        const koreanName = nameKo(entry);
        if (!Number.isSafeInteger(bggId) || bggId <= 0
            || typeof englishName !== 'string' || englishName.trim() === ''
            || typeof koreanName !== 'string' || koreanName.trim() === '') {
            throw new Error(`invalid ${role} dictionary entry`);
        }
        if (dictionary.has(bggId)) throw new Error(`duplicate ${role} dictionary ID: ${bggId}`);
        dictionary.set(bggId, { nameEn: englishName, nameKo: koreanName });
    }
    return dictionary;
}

function relationNames(relations, dictionary) {
    const result = new Map();
    for (const { gameBggId, relatedBggId } of relations) {
        const values = result.get(gameBggId) ?? [];
        values.push(dictionary.get(relatedBggId).nameKo);
        result.set(gameBggId, values);
    }
    return result;
}

function writeDictionaryCsv(filePath, dictionary) {
    const rows = ['bgg_id,name_en,name_ko'];
    for (const [bggId, value] of dictionary) {
        rows.push(`${bggId},${escapeCsvField(value.nameEn)},${escapeCsvField(value.nameKo)}`);
    }
    fs.writeFileSync(filePath, rows.join('\n'), 'utf8');
}

function writeRelationCsv(filePath, header, relations) {
    const rows = [header, ...relations.map(({ gameBggId, relatedBggId }) => `${gameBggId},${relatedBggId}`)];
    fs.writeFileSync(filePath, rows.join('\n'), 'utf8');
}

processCategoriesAndThemes().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
