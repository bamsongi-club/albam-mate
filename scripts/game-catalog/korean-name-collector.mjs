import fs from 'node:fs';
import path from 'node:path';

// 영단어 -> 한국어 매핑 사전 (보드게임 관련 빈출 단어 및 일반 단어)
const WORD_MAP = {
    // 판본 및 게임 형식
    "edition": "판",
    "second": "2판",
    "third": "3판",
    "fourth": "4판",
    "fifth": "5판",
    "deluxe": "디럭스",
    "expansion": "확장",
    "board": "보드",
    "game": "게임",
    "card": "카드",
    "dice": "다이스",
    "miniature": "미니처",
    "miniatures": "미니처",
    "pack": "팩",
    "box": "박스",
    "set": "세트",
    "promo": "프로모",

    // 테마 & 판타지 & SF
    "dragon": "드래곤",
    "dragons": "드래곤",
    "dungeon": "던전",
    "dungeons": "던전",
    "castle": "캐슬",
    "castles": "캐슬",
    "kingdom": "킹덤",
    "kingdoms": "킹덤즈",
    "empire": "엠파이어",
    "empires": "엠파이어스",
    "war": "워",
    "wars": "워즈",
    "battle": "배틀",
    "battles": "배틀스",
    "quest": "퀘스트",
    "quests": "퀘스트",
    "legend": "레전드",
    "legends": "레전드",
    "hero": "히어로",
    "heroes": "히어로즈",
    "shadow": "섀도우",
    "shadows": "섀도우",
    "dark": "다크",
    "night": "나이트",
    "space": "스페이스",
    "star": "스타",
    "stars": "스타즈",
    "galaxy": "갤럭시",
    "cosmic": "코스믹",
    "alien": "에일리언",
    "aliens": "에일리언즈",
    "monster": "몬스터",
    "monsters": "몬스터즈",
    "zombie": "좀비",
    "zombies": "좀비",
    "magic": "매직",
    "spell": "스펠",
    "spells": "스펠스",
    "wizard": "위저드",
    "wizards": "위저드",
    "witch": "위치",
    "vampire": "뱀파이어",
    "vampires": "뱀파이어",
    "pirate": "파이럿",
    "pirates": "파이럿츠",
    "island": "아일랜드",
    "islands": "아일랜드",
    "land": "랜드",
    "lands": "랜즈",
    "world": "월드",
    "worlds": "월즈",
    "city": "시티",
    "cities": "시티즈",
    "town": "타운",
    "village": "빌리지",
    "valley": "밸리",
    "forest": "포레스트",
    "mountain": "마운틴",
    "mountains": "마운틴스",
    "sea": "시",
    "ocean": "오션",
    "river": "리버",
    "lake": "레이크",

    // 액션 & 역할
    "master": "마스터",
    "masters": "마스터즈",
    "lord": "로드",
    "lords": "로드",
    "king": "킹",
    "kings": "킹스",
    "queen": "퀸",
    "prince": "프린스",
    "princess": "프린세스",
    "knight": "나이트",
    "knights": "나이트",
    "guard": "가드",
    "commander": "커맨더",
    "captain": "캡틴",
    "agent": "에이전트",
    "agents": "에이전츠",
    "hunter": "헌터",
    "hunters": "헌터스",
    "runner": "러너",
    "riders": "라이더스",
    "raiders": "레이더스",
    "settlers": "개척자들",
    "builder": "빌더",
    "builders": "빌더스",
    "maker": "메이커",
    "craft": "크래프트",

    // 형용사 및 개념
    "super": "슈퍼",
    "hyper": "하이퍼",
    "mega": "메가",
    "ultra": "울트라",
    "epic": "에픽",
    "ultimate": "얼티밋",
    "supreme": "슈프림",
    "grand": "그랜드",
    "royal": "로얄",
    "golden": "골든",
    "silver": "실버",
    "black": "블랙",
    "white": "화이트",
    "red": "레드",
    "blue": "블루",
    "green": "그린",
    "yellow": "옐로우",
    "iron": "아이언",
    "steel": "스틸",
    "blood": "블러드",
    "fire": "파이어",
    "ice": "아이스",
    "storm": "스톰",
    "time": "타임",
    "chronicles": "크로니클스",
    "chronicle": "크로니클",
    "tales": "테일즈",
    "tale": "테일",
    "story": "스토리",
    "stories": "스토리즈",
    "secret": "시크릿",
    "secrets": "시크릿",
    "mystery": "미스터리",
    "mysteries": "미스터리즈",
    "escape": "이스케이프",
    "fever": "피버",
    "rush": "러시",
    "clash": "클래시",
    "strike": "스트라이크",
    "force": "포스",
    "power": "파워",
    "energy": "에너지",
    "chaos": "카오스",
    "order": "오더",
    "destiny": "데스티니",
    "fate": "페이트",
    "legacy": "레거시",
    "genesis": "제네시스",
    "revolution": "레볼루션",
    "evolution": "에볼루션",
    "invasion": "인베이전",
    "rebellion": "리벨리온",
    "resistance": "레지스탕스",
    "alliance": "얼라이언스",
    "catan": "카탄",
    "carcassonne": "카르카손",
    "ticket": "티켓",
    "ride": "라이드",
    "splendor": "스플렌더",
    "azul": "아줄",
    "wingspan": "윙스팬",
    "terraforming": "테라포밍",
    "mars": "마스",
    "scythe": "사이더",
    "everdell": "에버델",
    "brass": "브라스",
    "birmingham": "버밍엄",
    "lancashire": "랭커셔",
    "gloomhaven": "글룸헤이븐",
    "pandemic": "팬데믹",
    "root": "루트",
    "cascadia": "캐스캐디아",
    "dune": "듄",
    "imperium": "임페리움",
    "concordia": "콘코디아",
    "agricola": "아그리콜라",
    "puerto": "푸에르토",
    "rico": "리코",
    "dominion": "도미니언",
    "wonders": "원더스",
    "duel": "듀얼",
    "clank": "클랭크",
    "arkham": "아컴",
    "horror": "호러",
    "eldritch": "엘드리치",
    "robinson": "로빈슨",
    "crusoe": "크루소",
    "nemesis": "네메시스",
    "spirit": "스피릿",
    "island": "아일랜드",
    "die": "디",
    "macher": "마허",
    "samurai": "사무라이",
    "konige": "쾨니게",
    "koenige": "쾨니게",
    "acquee": "아크에",
    "jacque": "자크",
    "jacques": "자크",
    "mediterranee": "메디테라네",
    "cathedral": "캐시드럴",
    "wacky": "왜키",
    "west": "웨스트",
    "robo": "로보",
    "rally": "래리",
    "roborally": "로보래리",
    "mare": "마레",
    "medici": "메디치",
    "gateway": "게이트웨이",
    "stars": "스타즈",
    "realm": "렘",
    "divine": "디바인",
    "light": "라이트",
    "twilight": "트와일라잇",
    "struggle": "스트러글",
    "great": "그레이트",
    "western": "웨스턴",
    "trail": "트레일",
    "gaia": "가이아",
    "project": "프로젝트",
    "scythe": "사이쓰",
    "nemesis": "네메시스",
    "brass": "브라스",
    "terraforming": "테라포밍",
    "concordia": "콘코디아",
    "barrage": "바라지",
    "maracaibo": "마라카이보",
    "orleans": "오를레앙",
    "lisboa": "리스보아",
    "kanban": "칸반",
    "ev": "EV",
    "vinhos": "비뇨스",
    "gallerist": "갤러리스트",
    "anachrony": "아나크로니",
    "tzolk'in": "촐킨",
    "tzolkin": "촐킨",
    "teotihuacan": "테오티우아칸",
    "cant": "캔트",
    "sophies": "소피스",
    "sophie's": "소피스",
    "curse": "커스",
    "mummy's": "미미스",
    "mummys": "미미스",
    "tomb": "툼",
    "lowenherz": "뢰벤헤르츠",
    "loewenherz": "뢰벤헤르츠",
    "im": "아임",
    "i'm": "아임",
    "boss": "보스",
    "nightmare": "나이트메어",
    "knightmare": "나이트메어",
    "honor": "아너",
    "encounters": "인카운터스",
    "armed": "암드",
    "dangerous": "덴저러스",
    "chess": "체스",
    "productions": "프로덕션스",
    "elm": "엘름",
    "street": "스트리트",
    "arabian": "아라비안",
    "covet": "코벳"
};

// 웹 검색 및 보드라이프/정식 발매 검증 한글 게임명 맵 (최우선 적용)
export const OFFICIAL_NAME_MAP = {
    1: "디 마허",
    2: "드래곤마스터",
    3: "사무라이",
    4: "왕들의 계곡",
    5: "어콰이어",
    6: "마레 메디테라네움",
    7: "카테드랄",
    8: "로드 오브 크리에이션",
    9: "엘 카바예로",
    10: "엘펜랜드",
    11: "보난자",
    12: "라",
    13: "카탄",
    14: "바사리",
    15: "코스믹 인카운터",
    16: "마라케시",
    17: "버튼맨",
    18: "로보랠리",
    19: "웨키 웨스트",
    20: "철갑 행성",
    21: "게이트웨이 투 더 스타즈",
    22: "매직 렐름",
    23: "디바인 라이트",
    24: "여명의 제국 (1판)",
    25: "타치이",
    26: "에이지 오브 르네상스",
    27: "수프리머시",
    28: "일루미나티",
    29: "테랭 바그",
    30: "다크 타워",
    31: "다크 월드",
    32: "버팔로 체스",
    34: "아컴 호러",
    36: "페더레이션 & 엠파이어",
    37: "드래곤 마스터즈",
    38: "룬즈",
    39: "다크오버",
    40: "보더랜드"
};

// 라틴 특수 문자(ö, é, ä, ß 등) 정규화 및 아스키 대치 함수
export function normalizeText(text) {
    if (!text) return "";
    return text
        .replace(/ß/g, 'ss')
        .replace(/Æ/g, 'Ae')
        .replace(/æ/g, 'ae')
        .replace(/Ø/g, 'O')
        .replace(/ø/g, 'o')
        .normalize('NFD').replace(/[\u0300-\u036f]/g, '');
}

// 규칙 기반 한글 음차 변환기
export function transliterateEnglishWord(word) {
    const rawClean = word.toLowerCase().replace(/['"]/g, '');
    const cleanWord = normalizeText(rawClean).replace(/[^a-z]/g, '');
    if (!cleanWord) return word;
    if (WORD_MAP[cleanWord]) {
        return WORD_MAP[cleanWord];
    }
    
    let res = cleanWord;
    const syllables = [
        ["tion", "션"], ["sion", "션"], ["ture", "쳐"], ["ment", "먼트"],
        ["ness", "니스"], ["land", "랜드"], ["ville", "빌"], ["burg", "부르크"],
        ["ford", "포드"], ["field", "필드"], ["wood", "우드"], ["stone", "스톤"],
        ["gate", "게이트"], ["port", "포트"], ["town", "타운"], ["vale", "베일"],
        ["way", "웨이"], ["rai", "라이"], ["rei", "레이"], ["samu", "사무"],
        ["night", "나이트"], ["mare", "메어"], ["curse", "커스"],
        ["th", "스"], ["ch", "치"], ["sh", "쉬"], ["ph", "프"], ["ck", "크"],
        ["ee", "이"], ["oo", "우"], ["ea", "이"], ["ai", "에"], ["oa", "오"],
        ["ou", "아우"], ["ow", "오"], ["ar", "아"], ["er", "어"], ["ir", "어"],
        ["or", "오"], ["ur", "어"], ["al", "알"], ["el", "엘"], ["il", "일"],
        ["ol", "올"], ["ul", "얼"], ["an", "앤"], ["en", "엔"], ["in", "인"],
        ["on", "온"], ["un", "언"], ["am", "암"], ["em", "엠"], ["im", "임"],
        ["om", "옴"], ["um", "엄"], ["ba", "바"], ["be", "베"], ["bi", "비"],
        ["bo", "보"], ["bu", "부"], ["ca", "카"], ["ce", "세"], ["ci", "시"],
        ["co", "코"], ["cu", "쿠"], ["da", "다"], ["de", "데"], ["di", "디"],
        ["do", "도"], ["du", "두"], ["fa", "파"], ["fe", "페"], ["fi", "피"],
        ["fo", "포"], ["fu", "푸"], ["ga", "가"], ["ge", "게"], ["gi", "기"],
        ["go", "고"], ["gu", "구"], ["ha", "하"], ["he", "헤"], ["hi", "히"],
        ["ho", "호"], ["hu", "후"], ["ja", "자"], ["je", "제"], ["ji", "지"],
        ["jo", "조"], ["ju", "주"], ["ka", "카"], ["ke", "케"], ["ki", "키"],
        ["ko", "코"], ["ku", "쿠"], ["la", "라"], ["le", "레"], ["li", "리"],
        ["lo", "로"], ["lu", "루"], ["ma", "마"], ["me", "메"], ["mi", "미"],
        ["mo", "모"], ["mu", "무"], ["na", "나"], ["ne", "네"], ["ni", "니"],
        ["no", "노"], ["nu", "누"], ["pa", "파"], ["pe", "페"], ["pi", "피"],
        ["po", "포"], ["pu", "푸"], ["ra", "라"], ["re", "레"], ["ri", "리"],
        ["ro", "로"], ["ru", "루"], ["sa", "사"], ["se", "세"], ["si", "시"],
        ["so", "소"], ["su", "수"], ["ta", "타"], ["te", "테"], ["ti", "티"],
        ["to", "토"], ["tu", "투"], ["va", "바"], ["ve", "베"], ["vi", "비"],
        ["vo", "보"], ["vu", "부"], ["wa", "와"], ["we", "웨"], ["wi", "위"],
        ["wo", "워"], ["ya", "야"], ["ye", "예"], ["yo", "요"], ["yu", "유"],
        ["za", "자"], ["ze", "제"], ["zi", "지"], ["zo", "조"], ["zu", "주"],
        ["b", "브"], ["c", "크"], ["d", "드"], ["f", "프"], ["g", "그"],
        ["h", "흐"], ["j", "즈"], ["k", "크"], ["l", "르"], ["m", "름"],
        ["n", "느"], ["p", "프"], ["r", "르"], ["s", "스"], ["t", "트"],
        ["v", "브"], ["w", "우"], ["x", "크스"], ["z", "즈"],
        ["a", "아"], ["e", "에"], ["i", "이"], ["o", "오"], ["u", "우"], ["y", "이"]
    ];

    for (const [pattern, ko] of syllables) {
        res = res.replaceAll(pattern, ko);
    }

    if (/[a-zA-Z]/.test(res)) {
        res = res.replace(/[a-zA-Z]/g, '');
    }

    return res || word;
}

export function convertTitleToKorean(englishTitle) {
    if (!englishTitle) return "";
    
    // 특수 구문 및 구명칭 우선 치환
    let formatted = englishTitle;
    formatted = formatted.replace(/\bTal\s+der\s+Könige\b/gi, '왕들의 계곡');
    formatted = formatted.replace(/\bTal\s+der\s+Koenige\b/gi, '왕들의 계곡');
    formatted = formatted.replace(/\bAcquired\b/gi, '어콰이어드');
    formatted = formatted.replace(/\((Second|2nd)\s+Edition\)/i, '(2판)');
    formatted = formatted.replace(/\((Third|3rd)\s+Edition\)/i, '(3판)');
    formatted = formatted.replace(/\((Fourth|4th)\s+Edition\)/i, '(4판)');
    formatted = formatted.replace(/\((Fifth|5th)\s+Edition\)/i, '(5판)');
    formatted = formatted.replace(/\((First|1st)\s+Edition\)/i, '(1판)');
    formatted = formatted.replace(/\(Deluxe\s+Edition\)/i, '(디럭스판)');

    // 단어별 변환 (라틴 악센트 문자 ö, é, ä 등 자동 아스키 정규화 변환 지원)
    const tokens = formatted.split(/(\s+|[:\-\(\)\.,!])/);
    const convertedTokens = tokens.map(token => {
        const normalizedToken = normalizeText(token);
        if (/^[a-zA-Z']{1,}$/.test(normalizedToken)) {
            const lower = normalizedToken.toLowerCase();
            const cleanLower = lower.replace(/'/g, '');
            if (WORD_MAP[lower]) {
                return WORD_MAP[lower];
            }
            if (WORD_MAP[cleanLower]) {
                return WORD_MAP[cleanLower];
            }
            return transliterateEnglishWord(cleanLower);
        }
        return token;
    });

    return convertedTokens.join('');
}
