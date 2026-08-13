import fs from 'node:fs';
import path from 'node:path';
import {
    parseDescriptionUpdates,
    parseNameUpdates,
    readZipTextEntry,
    resolveInputRoot,
    sha256,
    validatePositiveUniqueIds,
} from './catalog-pipeline-utils.mjs';
import { validateDescription } from './korean-description-validator.mjs';

const CLI_ARGS = process.argv.slice(2);
const DOWNLOAD_DIR = resolveInputRoot(CLI_ARGS);
const expectedNames = readOptionalCount('--expected-names');
const expectedDescriptions = readOptionalCount('--expected-descriptions');
const NAMES_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.sql');
const DESC_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/05-upsert-korean-descriptions-supplement.sql');
const REPORT_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/validation-full-localization.report.json');
const CATALOG_ZIP_PATH = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
const CATALOG_ZIP_ENTRY = '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json';

async function validateAll() {
    console.log('=== 17만 건 전체 한글화 데이터 전수 검수 시작 ===\n');
    const catalogContents = await readZipTextEntry(CATALOG_ZIP_PATH, CATALOG_ZIP_ENTRY);
    const catalogRows = JSON.parse(catalogContents);
    const catalogIds = new Set(validatePositiveUniqueIds(catalogRows, 'catalog'));

    // 1. 04-upsert-korean-names-supplement.sql 검수
    console.log('1. [게임명] 04-upsert-korean-names-supplement.sql 검수 중...');
    const namesContent = fs.readFileSync(NAMES_SQL_PATH, 'utf-8');
    const nameUpdates = parseNameUpdates(namesContent);

    let totalNames = 0;
    let alphabetMixedCount = 0;
    let knownBugPhoneticCount = 0;
    let duplicateNameCount = 0;
    let blankNameCount = 0;
    const bugSamples = [];

    const KNOWN_BUG_PATTERNS = [
        /스앰어에/, /름아에/, /아크우어에/, /엔크아우엔트어/, /르이엘엠/,
        /디e/, /크qu어e/, /트알 드어/, /로브오알르이/
    ];

    const nameIds = new Set();
    for (const update of nameUpdates) {
            totalNames++;
            const name = update.value;
            if (typeof name !== 'string' || name.trim() === '') {
                blankNameCount++;
                if (bugSamples.length < 5) bugSamples.push({ bggId: update.bggId, name, issue: '빈 게임명' });
            }
            if (nameIds.has(update.bggId)) {
                duplicateNameCount++;
                bugSamples.push({ bggId: update.bggId, name, issue: '중복 bgg_id' });
            }
            nameIds.add(update.bggId);

            // 영어 알파벳 섞임 검사 (1판, 2판, 3D 등 정규 표현 외 무분별한 한영 혼용)
            if (/[a-zA-Z]/.test(name) && !/\((1|2|3|4|5)판\)/.test(name) && !/\b(3D|2D|HD|VR|v\d+)\b/i.test(name)) {
                alphabetMixedCount++;
                if (bugSamples.length < 5) bugSamples.push({ bggId: update.bggId, name, issue: '한영 알파벳 혼재' });
            }

            // 구버전 이상 음차 버그 패턴 검사
            for (const pat of KNOWN_BUG_PATTERNS) {
                if (pat.test(name)) {
                    knownBugPhoneticCount++;
                    if (bugSamples.length < 10) bugSamples.push({ bggId: update.bggId, name, issue: `구버전 음차 버그 (${pat})` });
                    break;
                }
            }
    }

    let nameParseErrors = nameUpdates.length === 0 ? 1 : 0;
    if (totalNames !== catalogRows.length) nameParseErrors++;
    if (expectedNames !== null && totalNames !== expectedNames) nameParseErrors++;
    const nameCoverageErrorCount = exactIdCoverage(nameIds, catalogIds) ? 0 : 1;

    console.log(`- 전체 게임명 레코드 수: ${totalNames}건`);
    console.log(`- 무분별한 한영 알파벳 혼재 건수: ${alphabetMixedCount}건`);
    console.log(`- 구버전 이상 음차 패턴 잔여 건수: ${knownBugPhoneticCount}건`);

    // 2. 05-upsert-korean-descriptions-supplement.sql 검수
    console.log('\n2. [게임 설명] 05-upsert-korean-descriptions-supplement.sql 검수 중...');
    const descContent = fs.readFileSync(DESC_SQL_PATH, 'utf-8');
    const descUpdates = parseDescriptionUpdates(descContent);

    let totalDescs = 0;
    let rawJosaErrorCount = 0; // 은(는), 이(가) 잔여 오류
    let descriptionValidationErrorCount = 0;
    let duplicateDescriptionCount = 0;
    const descIds = new Set();

    for (const update of descUpdates) {
        totalDescs++;
        if (descIds.has(update.bggId)) {
            duplicateDescriptionCount++;
            bugSamples.push({ bggId: update.bggId, issue: '설명 SQL 중복 bgg_id' });
        }
        descIds.add(update.bggId);
        if (update.detailDescription.includes('은(는)') || update.detailDescription.includes('이(가)') || update.detailDescription.includes('을(를)')) {
            rawJosaErrorCount++;
        }
        if (!validateDescription(update.bggId, update.description, update.detailDescription).valid) {
            descriptionValidationErrorCount++;
        }
    }
    let descParseErrors = descUpdates.length === 0 ? 1 : 0;
    if (totalDescs !== catalogRows.length) descParseErrors++;
    if (expectedDescriptions !== null && totalDescs !== expectedDescriptions) descParseErrors++;
    const descriptionCoverageErrorCount = exactIdCoverage(descIds, catalogIds) ? 0 : 1;

    console.log(`- 전체 게임 설명 레코드 수: ${totalDescs}건`);
    console.log(`- 괄호 표기 조사 은(는)/이(가) 미정제 건수: ${rawJosaErrorCount}건`);
    console.log(`- 영어 잔존·번역 검증 실패 건수: ${descriptionValidationErrorCount}건`);

    console.log('\n=== 전수 검수 요약 결과 ===');
    console.log(`- 게임명 통과율: ${(((totalNames - (alphabetMixedCount + knownBugPhoneticCount)) / totalNames) * 100).toFixed(2)}%`);
    console.log(`- 게임 설명 조사 정제 통과율: ${(((totalDescs - rawJosaErrorCount) / totalDescs) * 100).toFixed(2)}%`);
    if (bugSamples.length > 0) {
        console.log('\n[잔여 이슈 샘플]:', JSON.stringify(bugSamples, null, 2));
    }

    const issueCount = alphabetMixedCount + knownBugPhoneticCount + duplicateNameCount + blankNameCount
        + rawJosaErrorCount + descriptionValidationErrorCount + duplicateDescriptionCount
        + nameParseErrors + descParseErrors + nameCoverageErrorCount + descriptionCoverageErrorCount;
    fs.writeFileSync(REPORT_PATH, JSON.stringify({
        schemaVersion: 1,
        datasetKind: 'approved-full-localization',
        grain: '1 row per bgg_id',
        status: issueCount === 0 ? 'ready' : 'blocked',
        inputs: {
            catalog: { sha256: sha256(catalogContents), rows: catalogRows.length },
            namesSql: { sha256: sha256(namesContent), rows: totalNames },
            descriptionsSql: { sha256: sha256(descContent), rows: totalDescs },
        },
        checks: {
            totalNames,
            totalDescs,
            expectedNames,
            expectedDescriptions,
            alphabetMixedCount,
            knownBugPhoneticCount,
            duplicateNameCount,
            blankNameCount,
            nameCoverageErrorCount,
            rawJosaErrorCount,
            descriptionValidationErrorCount,
            duplicateDescriptionCount,
            descriptionCoverageErrorCount,
            nameParseErrors,
            descParseErrors,
        },
        samples: bugSamples.slice(0, 20),
    }, null, 2) + '\n', 'utf-8');
    if (issueCount > 0) {
        console.error(`\n검수 실패: 잔여 이슈 ${issueCount}건`);
        process.exitCode = 1;
    }
}

validateAll().catch((error) => {
    console.error(error);
    fs.mkdirSync(path.dirname(REPORT_PATH), { recursive: true });
    fs.writeFileSync(REPORT_PATH, JSON.stringify({
        schemaVersion: 1,
        datasetKind: 'approved-full-localization',
        grain: '1 row per bgg_id',
        status: 'blocked',
        errors: [{ message: error.message }],
    }, null, 2) + '\n', 'utf-8');
    process.exitCode = 1;
});

function readOptionalCount(flag) {
    const index = CLI_ARGS.indexOf(flag);
    if (index === -1) return null;
    const value = Number(CLI_ARGS[index + 1]);
    if (!Number.isInteger(value) || value < 1) throw new Error(`${flag}는 양의 정수여야 합니다`);
    return value;
}

function exactIdCoverage(actualIds, expectedIds) {
    return actualIds.size === expectedIds.size
        && [...expectedIds].every((bggId) => actualIds.has(bggId));
}
