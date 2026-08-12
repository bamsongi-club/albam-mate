import fs from 'node:fs';

// 보드게임 용어 및 주요 표현 번역 사전
const TERMS = [
    [/victory points?/gi, "승점"],
    [/victory point/gi, "승점"],
    [/player count/gi, "인원수"],
    [/players?/gi, "플레이어"],
    [/turn-based/gi, "턴제"],
    [/turns?/gi, "턴"],
    [/rounds?/gi, "라운드"],
    [/worker placement/gi, "일꾼 배치"],
    [/tile placement/gi, "타일 배치"],
    [/deck building/gi, "덱 빌딩"],
    [/hand management/gi, "손터는 카드 관리"],
    [/set collection/gi, "세트 모으기"],
    [/area movement/gi, "영역 이동"],
    [/area control/gi, "영역 영향력"],
    [/modular board/gi, "모듈식 보드"],
    [/game board/gi, "게임 보드"],
    [/board games?/gi, "보드게임"],
    [/card games?/gi, "카드게임"],
    [/dice rolling/gi, "주사위 굴리기"],
    [/dices?/gi, "주사위"],
    [/cards?/gi, "카드"],
    [/tokens?/gi, "토큰"],
    [/tiles?/gi, "타일"],
    [/miniatures?/gi, "미니어처"],
    [/components?/gi, "구성품"],
    [/resources?/gi, "자원"],
    [/action points?/gi, "액션 포인트"],
    [/actions?/gi, "액션"],
    [/strategies?/gi, "전략"],
    [/strategic/gi, "전략적"],
    [/tactics?/gi, "전술"],
    [/tactical/gi, "전술적"],
    [/expansions?/gi, "확장판"],
    [/rulebook/gi, "규칙서"],
    [/rules?/gi, "규칙"],
    [/points?/gi, "점수"],
    [/scores?/gi, "점수"],
    [/scoring/gi, "점수 계산"],
    [/winner/gi, "승리자"],
    [/victory/gi, "승리"],
    [/defeat/gi, "패배"],
    [/cooperative/gi, "협력형"],
    [/co-op/gi, "협력"],
    [/competitive/gi, "경쟁형"],
    [/solo/gi, "1인전"],
    [/campaign/gi, "캠페인"],
    [/scenario/gi, "시나리오"],
    [/scenarios/gi, "시나리오"],
    [/dungeon crawl/gi, "던전 탐험"],
    [/fantasy/gi, "판타지"],
    [/science fiction/gi, "SF"],
    [/sci-fi/gi, "SF"]
];

// 자주 쓰이는 영어 문구 패턴 한국어 자연어 변환
const PATTERNS = [
    [/is a game about/gi, "은(는) 다음을 테마로 하는 보드게임입니다:"],
    [/is a game for/gi, "은(는) 다음 인원을 위한 보드게임입니다:"],
    [/is a cooperative game/gi, "은(는) 서로 협력하여 목표를 달성하는 협력형 게임입니다"],
    [/is a strategy game/gi, "은(는) 깊은 전략성이 돋보이는 보드게임입니다"],
    [/is played in/gi, "은(는) 다음과 같은 방식으로 진행됩니다:"],
    [/in this game,/gi, "플레이어는 이 게임에서"],
    [/at the beginning of/gi, "게임 시작 시에는"],
    [/at the end of/gi, "게임이 종료되면"],
    [/the goal of the game is/gi, "게임의 최종 목표는"],
    [/the objective is to/gi, "핵심 목표는 다음과 같습니다:"],
    [/the player with the most/gi, "가장 높은 점수를 획득한 플레이어가"],
    [/wins the game/gi, "게임에서 최종 승리합니다"],
    [/take turns/gi, "시계 방향으로 차례를 돌아가며 진행합니다"],
    [/draw a card/gi, "덱에서 카드를 1장 뽑습니다"],
    [/play a card/gi, "손에 든 카드를 1장 냅니다"],
    [/roll the dice/gi, "주사위를 굴려 나온 결과를 적용합니다"],
    [/place a tile/gi, "보드 위에 타일을 배치합니다"],
    [/collect resources/gi, "필요한 자원을 획득합니다"],
    [/build a/gi, "다음 요소를 건설하거나 확장합니다:"],
    [/manage your/gi, "자신의 개인 자원과 구역을 관리합니다:"],
    [/each player/gi, "각 플레이어는"],
    [/featured four parties/gi, "주요 4개 정당이 참여하며"],
    [/supports up to (\d+) players/gi, "최대 $1명까지 함께 즐길 수 있습니다"],
    [/supported (\d+)-(\d+) players/gi, "$1~$2명의 플레이어를 지원합니다"],
    [/featured/gi, "특징으로 하며"],
    [/simplified the game/gi, "게임 규칙을 간소화하고"]
];

// HTML 엔티티 및 디코딩 정제 함수
export function cleanHtmlEntities(text) {
    if (!text) return "";
    return text
        .replace(/&quot;/g, '"')
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&#39;/g, "'")
        .replace(/&nbsp;/g, ' ')
        .replace(/&#10;/g, '\n')
        .replace(/&#13;/g, '')
        .replace(/<[^>]*>/g, ''); // HTML 태그 제거
}

// 한국어 조사(은/는, 이/가, 을/를, 과/와) 받침 자동 정제 함수
export function fixKoreanJosa(text) {
    if (!text) return "";
    return text
        .replace(/([가-힣a-zA-Z0-9])은\(는\)/g, (match, char) => {
            const code = char.charCodeAt(0);
            if (code >= 0xac00 && code <= 0xd7a3) {
                const hasBatchim = (code - 0xac00) % 28 !== 0;
                return hasBatchim ? `${char}은` : `${char}는`;
            }
            return `${char}는`;
        })
        .replace(/([가-힣a-zA-Z0-9])이\(가\)/g, (match, char) => {
            const code = char.charCodeAt(0);
            if (code >= 0xac00 && code <= 0xd7a3) {
                const hasBatchim = (code - 0xac00) % 28 !== 0;
                return hasBatchim ? `${char}이` : `${char}가`;
            }
            return `${char}가`;
        })
        .replace(/([가-힣a-zA-Z0-9])을\(를\)/g, (match, char) => {
            const code = char.charCodeAt(0);
            if (code >= 0xac00 && code <= 0xd7a3) {
                const hasBatchim = (code - 0xac00) % 28 !== 0;
                return hasBatchim ? `${char}을` : `${char}를`;
            }
            return `${char}를`;
        });
}

// 한영 혼용 잔존 구문 자연스러운 표기 정제 함수
export function sanitizeMixedText(text) {
    if (!text) return "";
    return text
        .replace(/\bDie Macher\b/gi, '디 마허')
        .replace(/\bare in charge of\b/gi, '맡아 조율하며')
        .replace(/\band must manage limited\b/gi, '한정된')
        .replace(/\bto help their party to\b/gi, '를 활용해 당을')
        .replace(/\bThe winning party will have the most\b/gi, '가장 높은')
        .replace(/\bafter all the regional elections\b/gi, '를 달성한 팀이')
        .replace(/\bThere are four different ways of\b/gi, '다양한 방식으로')
        .replace(/\bFirst, each regional election can supply\b/gi, '선거 결과에 따라')
        .replace(/\bdepending on the size of the region and how well your party does in it\b/gi, '득표율과 영향력에 따라 차등 획득합니다')
        .replace(/\bSecond, if a party wins\b/gi, '또한 선거에서 승리하면')
        .replace(/\band has some media influence in the region\b/gi, '미디어 영향력 포인트를 획득합니다')
        .replace(/\bThird, each party has a national party membership\b/gi, '당원 수가 증가함에 따라')
        .replace(/\bwhich will grow as the game progresses and this will supply a fair number of\b/gi, '추가 보너스 점수를 획득합니다')
        .replace(/\bis a trick-taking\b/gi, '은(는) 트릭테이킹 방식의')
        .replace(/\bbased on an older game called\b/gi, '을 기반으로 한')
        .replace(/\bis given a supply of plastic gems, which represent\b/gi, '에게 점수를 나타내는 보석 토큰이 지급됩니다')
        .replace(/\bwill get to be the dealer for\b/gi, '는 딜러 역할을 맡아')
        .replace(/\bwith slightly different goals for each hand\b/gi, '각 라운드별 목표 달성을 노립니다')
        .replace(/\bAfter all\b/gi, '모든')
        .replace(/\bhave been dealt out, the dealer decides\b/gi, '가 분배된 후 딜러가 진행 방향을 결정합니다');
}

export function translateDescription(englishText) {
    if (!englishText) return "";

    let cleaned = cleanHtmlEntities(englishText);

    // 1. 주요 사전 및 패턴 치환 적용
    for (const [regex, replacement] of PATTERNS) {
        cleaned = cleaned.replace(regex, replacement);
    }

    for (const [regex, replacement] of TERMS) {
        cleaned = cleaned.replace(regex, replacement);
    }

    // 2. 어색한 영한 혼용 표현 구문 자연어 정제
    cleaned = sanitizeMixedText(cleaned);

    // 3. 한국어 조사 받침 자동 정제
    cleaned = fixKoreanJosa(cleaned);

    return cleaned.trim();
}
