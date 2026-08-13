import fs from 'node:fs';
import path from 'node:path';
import {
    commitZipArtifacts,
    resolveInputRoot,
    sha256,
    validatePositiveUniqueIds,
} from './catalog-pipeline-utils.mjs';

const CLI_ARGS = process.argv.slice(2);
const DOWNLOAD_DIR = resolveInputRoot(CLI_ARGS);
const MANIFEST_INDEX = CLI_ARGS.indexOf('--input-manifest');
const INPUT_MANIFEST_PATH = MANIFEST_INDEX >= 0 ? CLI_ARGS[MANIFEST_INDEX + 1] : null;
const LOCALIZATION_DIR = path.join(DOWNLOAD_DIR, 'reference/02-localization');
const NEW_GAMES_SQL_PATH = path.join(LOCALIZATION_DIR, '06-upsert-boardlife-new-games.sql');

// 보드라이프 한국 독자/인기 신규 게임 데이터셋 (BGG 미등록 또는 BGG 17만 외부 보드라이프 데이터)
const BOARDLIFE_NEW_GAMES = [
    {
        bgg_id: 990001,
        name: "부루마블",
        english_name: "Blue Marble",
        supported_player_count: "2~4명",
        tag: "가족",
        estimated_play_time: "60분",
        min_players: 2,
        max_players: 4,
        min_play_time_minutes: 60,
        max_play_time_minutes: 60,
        complexity: 1.5,
        release_year: 1982,
        description: "전 세계 도시를 다니며 부동산을 매수하고 통행료를 받는 대한민국 대표 고전 보드게임입니다.",
        detail_description: "부루마블은 1982년 출시된 대한민국 대표 보드게임입니다. 주사위를 굴려 씨앗은행을 지나며 세계 주요 도시의 땅을 구입하고 빌딩과 호텔을 지어 상대방에게 통행료를 받는 방식으로 진행됩니다."
    },
    {
        bgg_id: 990002,
        name: "모두의마블 보드게임",
        english_name: "Modoo Marble Board Game",
        supported_player_count: "2~4명",
        tag: "캐주얼",
        estimated_play_time: "45분",
        min_players: 2,
        max_players: 4,
        min_play_time_minutes: 45,
        max_play_time_minutes: 45,
        complexity: 1.8,
        release_year: 2013,
        description: "인기 모바일 게임 모두의마블의 룰과 캐릭터를 보드게임 판으로 실물 구현한 게임입니다.",
        detail_description: "모두의마블 보드게임은 다양한 캐릭터 카드와 랜드마크 건설 요소, 찬스 카드를 활용하여 상대를 파산시키거나 칼라 독점을 달성하는 캐주얼 보드게임입니다."
    },
    {
        bgg_id: 990003,
        name: "할리갈리 컵스 딜럭스",
        english_name: "Halli Galli Cups Deluxe",
        supported_player_count: "2~4명",
        tag: "파티",
        estimated_play_time: "15분",
        min_players: 2,
        max_players: 4,
        min_play_time_minutes: 15,
        max_play_time_minutes: 15,
        complexity: 1.1,
        release_year: 2014,
        description: "그림 카드의 색상 순서대로 컵을 빠르게 겹치거나 배열하고 종을 치는 순발력 보드게임입니다.",
        detail_description: "카드에 나온 5가지 색상 컵의 배치(가로, 세로, 층)를 보고 누구보다 빠르게 컵을 배치하여 먼저 종을 치는 사람이 카드를 가져가는 게임입니다."
    },
    {
        bgg_id: 990004,
        name: "루미큐브 클래식 한글판",
        english_name: "Rummikub Classic Korean",
        supported_player_count: "2~4명",
        tag: "전략",
        estimated_play_time: "20분",
        min_players: 2,
        max_players: 4,
        min_play_time_minutes: 20,
        max_play_time_minutes: 20,
        complexity: 1.7,
        release_year: 1977,
        description: "숫자와 색상 조합 타일을 등록하고 조합을 교체하여 손의 타일을 먼저 털어내는 세계적인 수 타일 게임입니다.",
        detail_description: "연속된 숫자(런) 또는 같은 숫자 다른 색상(그룹)으로 타일을 3개 이상 조합하여 밭에 내놓거나 기존 조합을 재구성하여 가장 먼저 타일을 없애는 게임입니다."
    },
    {
        bgg_id: 990005,
        name: "다빈치 코드 (한글판)",
        english_name: "Da Vinci Code Korean",
        supported_player_count: "2~4명",
        tag: "추리",
        estimated_play_time: "15분",
        min_players: 2,
        max_players: 4,
        min_play_time_minutes: 15,
        max_play_time_minutes: 15,
        complexity: 1.3,
        release_year: 2004,
        description: "상대방이 숨긴 숫자 타일의 위치와 값을 추리하여 맞춰내는 숫자 추리 보드게임입니다.",
        detail_description: "흑색과 백색 숫자 타일을 크기순으로 정렬한 뒤 상대방 타일을 하나씩 맞추어 쓰러뜨리는 흥미진진한 숫자 추리 게임입니다."
    }
];

async function collectBoardlifeData() {
    if (!INPUT_MANIFEST_PATH) {
        throw new Error('BoardLife 승인 manifest가 필요합니다: --input-manifest <path>');
    }
    const manifest = JSON.parse(fs.readFileSync(INPUT_MANIFEST_PATH, 'utf-8'));
    const bggIds = validatePositiveUniqueIds(BOARDLIFE_NEW_GAMES, 'boardlife');
    validateBoardlifeManifest(manifest, bggIds);

    console.log('1. 보드라이프 수집 정보 파이프라인 분석 중...');
    
    // candidate CSV들에서 boardlife 출처 항목 파악
    const files = fs.readdirSync(LOCALIZATION_DIR).filter(f => f.startsWith('bgg-game-name-ko-candidates') && f.endsWith('.csv'));
    let boardlifeMatches = 0;

    for (const file of files) {
        const content = fs.readFileSync(path.join(LOCALIZATION_DIR, file), 'utf-8');
        const lines = content.split('\n');
        for (const line of lines) {
            if (line.includes('boardlife')) {
                boardlifeMatches++;
            }
        }
    }
    console.log(`보드라이프 정식 매칭 게임 수: ${boardlifeMatches}건`);

    console.log('2. 보드라이프 신규/독자 게임 적재 SQL 생성 중...');
    const sqlStatements = [
        '-- BoardLife 수집 신규 게임 적재 SQL',
        'BEGIN;'
    ];

    for (const game of BOARDLIFE_NEW_GAMES) {
        const safeName = game.name.replace(/'/g, "''");
        const safeEn = game.english_name.replace(/'/g, "''");
        const safeDesc = game.description.replace(/'/g, "''");
        const safeDetail = game.detail_description.replace(/'/g, "''");
        const safeTag = game.tag.replace(/'/g, "''");

        sqlStatements.push(`INSERT INTO games (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, min_players, max_players, min_play_time_minutes, max_play_time_minutes, complexity, release_year, description, detail_description, created_at, updated_at) VALUES (${game.bgg_id}, '${safeName}', '${safeEn}', '${game.supported_player_count}', '${safeTag}', '${game.estimated_play_time}', ${game.min_players}, ${game.max_players}, ${game.min_play_time_minutes}, ${game.max_play_time_minutes}, ${game.complexity}, ${game.release_year}, '${safeDesc}', '${safeDetail}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) ON CONFLICT (bgg_id) DO UPDATE SET
            name = EXCLUDED.name,
            english_name = EXCLUDED.english_name,
            supported_player_count = EXCLUDED.supported_player_count,
            tag = EXCLUDED.tag,
            estimated_play_time = EXCLUDED.estimated_play_time,
            min_players = EXCLUDED.min_players,
            max_players = EXCLUDED.max_players,
            min_play_time_minutes = EXCLUDED.min_play_time_minutes,
            max_play_time_minutes = EXCLUDED.max_play_time_minutes,
            complexity = EXCLUDED.complexity,
            release_year = EXCLUDED.release_year,
            description = EXCLUDED.description,
            detail_description = EXCLUDED.detail_description,
            updated_at = CURRENT_TIMESTAMP;`);
    }

    sqlStatements.push('COMMIT;');

    const zipPath = path.join(DOWNLOAD_DIR, '01-team-handoff-local.zip');
    console.log('3. 승인 manifest 검증 후 SQL과 ZIP을 원자적으로 갱신 중...');
    commitZipArtifacts({
        zipPath,
        zipEntry: '06-complete-local-import/06-upsert-boardlife-new-games.sql',
        zipFileTarget: NEW_GAMES_SQL_PATH,
        files: [{ target: NEW_GAMES_SQL_PATH, contents: sqlStatements.join('\n') + '\n' }],
    });
    console.log('ZIP 파일에 06-upsert-boardlife-new-games.sql 반영 완료!');
}

function validateBoardlifeManifest(manifest, bggIds) {
    const requiredFields = [
        'name', 'english_name', 'supported_player_count', 'tag', 'estimated_play_time',
        'description', 'detail_description',
    ];
    for (const game of BOARDLIFE_NEW_GAMES) {
        for (const field of requiredFields) {
            if (typeof game[field] !== 'string' || game[field].trim().length === 0) {
                throw new Error(`BoardLife 필수 필드 누락: ${field} (${game.bgg_id})`);
            }
        }
        if (!Number.isSafeInteger(game.bgg_id) || game.bgg_id <= 0) {
            throw new Error(`BoardLife bgg_id가 유효하지 않습니다: ${game.bgg_id}`);
        }
    }
    const expectedHash = sha256(Buffer.from(JSON.stringify(BOARDLIFE_NEW_GAMES)));
    const expectedIds = [...bggIds].sort((left, right) => left - right);
    if (manifest?.approved !== true
        || manifest.datasetKind !== 'boardlife-new-games'
        || manifest.grain !== '1 row per bgg_id'
        || manifest.rows !== BOARDLIFE_NEW_GAMES.length
        || manifest.sourceSha256 !== expectedHash
        || JSON.stringify(manifest.bggIds ?? []) !== JSON.stringify(expectedIds)) {
        throw new Error('BoardLife 승인 manifest가 입력 데이터와 일치하지 않습니다');
    }
}

collectBoardlifeData().catch(err => {
    console.error('오류 발생:', err);
    process.exit(1);
});
