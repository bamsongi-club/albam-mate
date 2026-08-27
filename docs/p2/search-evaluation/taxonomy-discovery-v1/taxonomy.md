# SEARCH-04 taxonomy discovery v1

> 상태: `draft-discovery`
>
> 이 문서는 150개 query corpus를 먼저 수집한 뒤 information need와 retrieval behavior를 기준으로 bottom-up clustering한 결과다. production taxonomy, human qrels, 최종 routing 승인 문서가 아니다.

## Discovery 원칙

- `queries.csv`에는 의도 label을 넣지 않고 query와 provenance만 보관한다.
- 기존 fixture·regression query와 synthetic query를 source로 구분한다.
- query 표면 형태(한국어·영어·오타·짧은 표현)는 intent와 분리한다.
- 여러 정보 요구는 `primary_intent`와 `secondary_intents`로 표현하고 `MIXED` 하나로 합치지 않는다.
- catalog field가 존재한다는 이유만으로 사용자 taxonomy를 결정하지 않는다.

## Initial clustering

| 임시 cluster | 공통 information need | 대표 query | 경계·겹침 | 분리 실익 |
| --- | --- | --- | --- | --- |
| I1 이름 찾기 | 제목을 직접 찾거나 불완전한 기억을 복원 | Q004, Q058, Q067, Q071, Q076 | exact·오타·부분 기억·유사 게임 요청이 섞임. Q060·Q073은 경계 | exact lexical, fuzzy, alias, dense reformulation이 달라짐 |
| I2 플레이 방식 찾기 | 작동 방식과 선택 구조에 따른 탐색 | Q003, Q024, Q078, Q083, Q089 | 전문용어와 일반어 설명이 공존 | sparse relation과 dense paraphrase를 분리 가능 |
| I3 세계관·내용 찾기 | 배경·이야기·소재·테마에 따른 탐색 | Q033, Q117, Q124, Q125, Q126 | 테마와 mechanism이 함께 나타남 | theme relation·description·dense를 분리 가능 |
| I4 관계적 상호작용 | 협력·배신·협상·정보 공유·눈치 | Q001, Q019, Q027, Q097, Q100 | interaction과 분위기·mechanism이 겹침 | 관계 의미와 단순 lexical match를 분리 가능 |
| I5 플레이 경험 | 가벼움·긴장감·전략성·반복성·운 체감 | Q016, Q028, Q037, Q107, Q110 | 주관성이 높고 field로 직접 확정하기 어려움 | dense와 graded human qrels 필요성을 확인 |
| I6 진입 장벽·접근성 | 초보·설명 난이도·아이·실력 차이 | Q018, Q021, Q096, Q112, Q115 | minAge·complexity와 teachability·skill gap이 섞임 | structured signal과 human preference를 분리 가능 |
| I7 수치·catalog 적합성 | 인원·시간·연령 조건 | Q002, Q044, Q062, Q127, Q145 | hard constraint와 “짧게”가 섞임 | parser·hard filter·constraint metric을 분리 가능 |
| I8 사용 상황·대상 | 커플·친구·가족·혼자·모임·테이블 상황 | Q047, Q137, Q139, Q143, Q148 | catalog에 없는 context가 많음 | context를 hard filter로 잘못 처리하는 위험 확인 |
| I9 짧은·불완전 표현 | 너무 짧거나 전문용어·오타·혼합 언어 | Q053, Q054, Q067, Q086, Q149 | expression form은 intent 자체가 아님 | clarification·normalization·routing 실패를 분리 가능 |

`multi-intent`는 별도 cluster가 아니라 위 cluster의 조합으로 남긴다.

## Merge / split 결정

### Split

- I1 → `TITLE_EXACT`, `TITLE_RECOVERY`, `REFERENCE_SIMILARITY`
- I6과 I7 분리: 접근성은 human preference가 섞이고 수치 조건은 hard filter 후보이기 때문이다.
- I2와 I3 분리: mechanism relation과 theme·description의 데이터 경로가 다르다.
- I4와 I5 분리: 관계 구조와 주관적 체감은 relevance 판단 기준이 다르다.

### Merge

- 커플·친구·가족·혼자·모임은 `OCCASION_CONTEXT`로 통합한다.
- 언어·오타·짧은 표현은 `query_form`으로 관리한다.
- `MIXED` taxonomy는 만들지 않고 multi-label 조합으로 표현한다.
- Q053~Q056은 삭제하지 않고 `OPEN_DISCOVERY`와 `CLARIFY_FIRST`로 유지한다.

## Final taxonomy

| ID | 한국어 이름 | 정의 | 포함 기준 | 제외 기준 | 대표 query | 중첩 | retrieval 의미 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `TITLE_EXACT` | 정확한 게임명 찾기 | 제목·별칭을 직접 입력해 찾음 | 제목이 명시됨 | “비슷한 게임”, 기억 불명 | Q004, Q057, Q063 | `STRUCTURED_FIT` | name/alias lexical, normalization, MRR |
| `TITLE_RECOVERY` | 불완전한 게임명 복원 | 오타·번역·부분 기억으로 제목을 복원 | 제목 확신이 낮음 | 정확한 제목 직접 조회 | Q058, Q067, Q071 | `THEME_CONTENT`, `MECHANIC_RULE` | fuzzy lexical + dense + clarification |
| `REFERENCE_SIMILARITY` | 기준 게임 유사 탐색 | 특정 게임과 비슷한 게임을 찾음 | “비슷한”, “같은” | 기준 게임 자체 조회 | Q060, Q073 | `TITLE_EXACT`, `MECHANIC_RULE` | dense·metadata·reranking, graded qrels |
| `MECHANIC_RULE` | 메커니즘·플레이 규칙 탐색 | 작동 방식과 선택 구조를 기준으로 찾음 | 드래프팅·worker placement 등 | 배경만 설명 | Q077, Q083, Q089 | `INTERACTION_MODE`, `THEME_CONTENT` | sparse relation + dense paraphrase |
| `THEME_CONTENT` | 테마·내용 탐색 | 세계관·배경·소재를 기준으로 찾음 | 우주·중세·추리 등 | mechanism만 설명 | Q117, Q124, Q125 | `MECHANIC_RULE` | theme relation + description + dense |
| `INTERACTION_MODE` | 상호작용 방식 | 협력·배신·협상·정보 공유·눈치 | 사람 간 관계가 핵심 | 단순한 분위기 표현 | Q019, Q097, Q100 | `MECHANIC_RULE`, `PLAY_EXPERIENCE` | dense + relation/lexical signal |
| `PLAY_EXPERIENCE` | 플레이 경험·분위기 | 가벼움·긴장감·전략성·반복성 등 | 주관적 체감 | 명시적 수치 조건 | Q016, Q107, Q110 | `INTERACTION_MODE`, `ACCESSIBILITY` | dense + graded qrels |
| `ACCESSIBILITY` | 입문성·접근성 | 초보·아이·설명 난이도·실력 차이 | 배우기 쉬움·teachability | 숫자 나이만 있음 | Q018, Q112, Q115 | `STRUCTURED_FIT`, `OCCASION_CONTEXT` | complexity/age + human judgment |
| `STRUCTURED_FIT` | 구조화 조건 적합성 | 인원·시간·연령 등 명시 조건 | “4인”, “30분 이하” | “짧게”만 있음 | Q127, Q128, Q132 | `ACCESSIBILITY`, `OCCASION_CONTEXT` | parser → hard filter → feasible recall |
| `OCCASION_CONTEXT` | 사용 상황·대상 | 커플·가족·친구·모임·혼자 등 | 이용 맥락 | 숫자 조건만 있음 | Q137, Q140, Q143 | `STRUCTURED_FIT`, `INTERACTION_MODE` | soft hint + dense/qrels |
| `OPEN_DISCOVERY` | 정보 부족·광범위 탐색 | 의도를 특정하기 어려운 짧은 query | `게임`, `game` 등 | 구체 조건이 있는 query | Q053~Q056 | 없음 | clarification·broad retrieval |

## Annotation vocabulary

- `HARD_PLAYERS`, `HARD_TIME`, `HARD_AGE`: 충족하지 않으면 제외 후보가 되는 명시 조건
- `STRUCTURED_PREFERENCE`: metadata signal이지만 hard exclusion으로 확정하지 않은 표현
- `SEMANTIC_PREFERENCE`: 분위기·경험·전략성 등 의미 선호
- `CONTEXT_HINT`: 결과의 soft hint
- `AMBIGUOUS_SUBJECTIVE`: 서비스가 임의 해석하면 위험한 표현
- `query_form`: 자연어·제목·오타·혼합 언어·구조화 fragment 등의 표현 형태
- `answerability`: 현재 catalog로 직접 확인 가능한지, human qrels나 clarification이 필요한지

## 제한사항

- 94개 synthetic query가 포함되어 실제 사용자 분포가 아니다.
- mechanism·interaction query가 기존 evidence에 편중되어 있다.
- `REFERENCE_SIMILARITY`와 `OPEN_DISCOVERY`는 4개뿐이라 품질 평가 표본으로 부족하다.
- taxonomy label은 relevance qrels가 아니다.
- 현재 catalog release와 search-text execution allowlist의 연결은 별도 승인 대상이다.
