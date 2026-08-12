import fs from 'node:fs';
import path from 'node:path';

const DOWNLOAD_DIR = process.argv[2] ?? process.env.ALBAM_MATE_170K_DIR;
if (!DOWNLOAD_DIR) {
    console.error('사용법: node validate-full-localization.mjs <170k 인계 디렉터리> (또는 ALBAM_MATE_170K_DIR 환경변수)');
    process.exit(2);
}
const NAMES_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/04-upsert-korean-names-supplement.sql');
const DESC_SQL_PATH = path.join(DOWNLOAD_DIR, 'reference/02-localization/05-upsert-korean-descriptions-supplement.sql');

async function validateAll() {
    console.log('=== 17만 건 전체 한글화 데이터 전수 검수 시작 ===\n');

    // 1. 04-upsert-korean-names-supplement.sql 검수
    console.log('1. [게임명] 04-upsert-korean-names-supplement.sql 검수 중...');
    const namesContent = fs.readFileSync(NAMES_SQL_PATH, 'utf-8');
    const nameLines = namesContent.split('\n');

    let totalNames = 0;
    let alphabetMixedCount = 0;
    let knownBugPhoneticCount = 0;
    const bugSamples = [];

    const KNOWN_BUG_PATTERNS = [
        /스앰어에/, /름아에/, /아크우어에/, /엔크아우엔트어/, /르이엘엠/,
        /디e/, /크qu어e/, /트알 드어/, /로브오알르이/
    ];

    for (const line of nameLines) {
        const match = line.match(/UPDATE games SET name = '((?:''|[^'])*)' WHERE bgg_id = (\d+);/);
        if (match) {
            totalNames++;
            const name = match[1].replace(/''/g, "'");

            // 영어 알파벳 섞임 검사 (1판, 2판, 3D 등 정규 표현 외 무분별한 한영 혼용)
            if (/[a-zA-Z]/.test(name) && !/\((1|2|3|4|5)판\)/.test(name) && !/\b(3D|2D|HD|VR|v\d+)\b/i.test(name)) {
                alphabetMixedCount++;
                if (bugSamples.length < 5) bugSamples.push({ bggId: match[2], name, issue: '한영 알파벳 혼재' });
            }

            // 구버전 이상 음차 버그 패턴 검사
            for (const pat of KNOWN_BUG_PATTERNS) {
                if (pat.test(name)) {
                    knownBugPhoneticCount++;
                    if (bugSamples.length < 10) bugSamples.push({ bggId: match[2], name, issue: `구버전 음차 버그 (${pat})` });
                    break;
                }
            }
        }
    }

    console.log(`- 전체 게임명 레코드 수: ${totalNames}건`);
    console.log(`- 무분별한 한영 알파벳 혼재 건수: ${alphabetMixedCount}건`);
    console.log(`- 구버전 이상 음차 패턴 잔여 건수: ${knownBugPhoneticCount}건`);

    // 2. 05-upsert-korean-descriptions-supplement.sql 검수
    console.log('\n2. [게임 설명] 05-upsert-korean-descriptions-supplement.sql 검수 중...');
    const descContent = fs.readFileSync(DESC_SQL_PATH, 'utf-8');
    const descLines = descContent.split('\n');

    let totalDescs = 0;
    let rawJosaErrorCount = 0; // 은(는), 이(가) 잔여 오류

    for (const line of descLines) {
        const match = line.match(/UPDATE games SET description = '.*', detail_description = '(.*)' WHERE bgg_id = \d+;/);
        if (match) {
            totalDescs++;
            const detail = match[1];
            if (detail.includes('은(는)') || detail.includes('이(가)') || detail.includes('을(를)')) {
                rawJosaErrorCount++;
            }
        }
    }

    console.log(`- 전체 게임 설명 레코드 수: ${totalDescs}건`);
    console.log(`- 괄호 표기 조사 은(는)/이(가) 미정제 건수: ${rawJosaErrorCount}건`);

    console.log('\n=== 전수 검수 요약 결과 ===');
    console.log(`- 게임명 통과율: ${(((totalNames - (alphabetMixedCount + knownBugPhoneticCount)) / totalNames) * 100).toFixed(2)}%`);
    console.log(`- 게임 설명 조사 정제 통과율: ${(((totalDescs - rawJosaErrorCount) / totalDescs) * 100).toFixed(2)}%`);
    if (bugSamples.length > 0) {
        console.log('\n[잔여 이슈 샘플]:', JSON.stringify(bugSamples, null, 2));
    }

    const issueCount = alphabetMixedCount + knownBugPhoneticCount + rawJosaErrorCount;
    if (issueCount > 0) {
        console.error(`\n검수 실패: 잔여 이슈 ${issueCount}건`);
        process.exitCode = 1;
    }
}

validateAll().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
