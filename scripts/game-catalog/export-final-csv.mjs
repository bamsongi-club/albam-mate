import fs from 'node:fs';
import path from 'node:path';

import {
    escapeCsvField,
    parseApprovedRelationTuples,
    parseDescriptionUpdates,
    parseNameUpdates,
    readZipTextEntry,
    resolveInputRoot,
    validateApprovedLocalizationReport,
} from './catalog-pipeline-utils.mjs';

const DOWNLOAD_DIR = resolveInputRoot(process.argv.slice(2));
const ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');
const NAMES_SQL_PATH = path.join(LOCALIZATION_DIR, '04-upsert-korean-names-supplement.sql');
const DESC_SQL_PATH = path.join(LOCALIZATION_DIR, '05-upsert-korean-descriptions-supplement.sql');
const VALIDATION_REPORT_PATH = path.join(LOCALIZATION_DIR, 'validation-full-localization.report.json');
const MECHANISM_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-mechanism-ko-map.review-draft.json');
const THEME_MAP_JSON = path.join(LOCALIZATION_DIR, 'bgg-theme-ko-map.review-draft.json');
const OUTPUT_CSV_PATH = path.join(DOWNLOAD_DIR, 'albam-mate-games-170k-final.csv');

async function exportCleanCsv() {
    const mechanismDictionary = readDictionary(MECHANISM_MAP_JSON, {
        id: (entry) => entry.bgg_id ?? entry.bggId ?? entry.id,
        value: (entry) => entry.name_ko ?? entry.nameKo,
        role: 'mechanism',
    });
    const themeDictionary = readDictionary(THEME_MAP_JSON, {
        id: (entry) => entry.bggThemeId ?? entry.bgg_id ?? entry.id,
        value: (entry) => entry.nameKo ?? entry.name_ko,
        role: 'theme',
    });

    const catalogEntry = '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json';
    const mechanismEntry = '06-complete-local-import/02-upsert-game-mechanisms.sql';
    const themeEntry = '06-complete-local-import/03-upsert-game-metadata.sql';
    const catalogContents = await readZipTextEntry(ZIP_PATH, catalogEntry);
    const rawCatalog = JSON.parse(catalogContents);
    const mechanismSql = await readZipTextEntry(ZIP_PATH, mechanismEntry);
    const themeSql = await readZipTextEntry(ZIP_PATH, themeEntry);
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

    const namesSql = fs.readFileSync(NAMES_SQL_PATH, 'utf8');
    const descriptionsSql = fs.readFileSync(DESC_SQL_PATH, 'utf8');
    const nameUpdates = parseNameUpdates(namesSql);
    const descriptionUpdates = parseDescriptionUpdates(descriptionsSql);
    const validationReport = JSON.parse(fs.readFileSync(VALIDATION_REPORT_PATH, 'utf8'));
    validateApprovedLocalizationReport({
        report: validationReport,
        namesSql,
        descriptionsSql,
        catalogContents,
        catalogRows: rawCatalog,
        nameUpdates,
        descriptionUpdates,
    });
    const nameMap = new Map(nameUpdates.map(({ bggId, value }) => [bggId, value]));
    const descriptionMap = new Map(descriptionUpdates.map((update) => [update.bggId, update]));

    const csvRows = [[
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
        'themes_ko',
    ].join(',')];

    for (const game of rawCatalog) {
        const bggId = Number(game.bgg_id);
        const nameKo = nameMap.get(bggId);
        const descriptions = descriptionMap.get(bggId);
        const englishName = game.english_name ?? game.name ?? '';
        const row = [
            bggId,
            escapeCsvField(nameKo),
            escapeCsvField(englishName),
            escapeCsvField(game.alias ?? `${nameKo}, ${englishName}`),
            escapeCsvField(game.image_url ?? ''),
            escapeCsvField(game.supported_player_count ?? ''),
            escapeCsvField(game.tag ?? ''),
            escapeCsvField(game.estimated_play_time ?? ''),
            game.min_players ?? '',
            game.max_players ?? '',
            game.min_play_time_minutes ?? '',
            game.max_play_time_minutes ?? '',
            game.complexity ?? '',
            game.release_year ?? '',
            escapeCsvField(descriptions.description),
            escapeCsvField(descriptions.detailDescription),
            escapeCsvField((gameMechanisms.get(bggId) ?? []).join('; ')),
            escapeCsvField((gameThemes.get(bggId) ?? []).join('; ')),
        ];
        csvRows.push(row.join(','));
    }

    fs.writeFileSync(OUTPUT_CSV_PATH, csvRows.join('\n'), 'utf8');
    console.log(`승인된 한글화 ${rawCatalog.length}행을 ${OUTPUT_CSV_PATH}에 생성했습니다.`);
}

function readDictionary(filePath, { id, value, role }) {
    const parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
    const dictionary = new Map();
    for (const entry of parsed.entries ?? []) {
        const bggId = Number(id(entry));
        const localizedName = value(entry);
        if (!Number.isSafeInteger(bggId) || bggId <= 0 || typeof localizedName !== 'string' || localizedName.trim() === '') {
            throw new Error(`invalid ${role} dictionary entry`);
        }
        if (dictionary.has(bggId)) throw new Error(`duplicate ${role} dictionary ID: ${bggId}`);
        dictionary.set(bggId, localizedName);
    }
    return dictionary;
}

function relationNames(relations, dictionary) {
    const result = new Map();
    for (const { gameBggId, relatedBggId } of relations) {
        const values = result.get(gameBggId) ?? [];
        values.push(dictionary.get(relatedBggId));
        result.set(gameBggId, values);
    }
    return result;
}

exportCleanCsv().catch((error) => {
    fs.rmSync(OUTPUT_CSV_PATH, { force: true });
    console.error(error);
    process.exitCode = 1;
});
