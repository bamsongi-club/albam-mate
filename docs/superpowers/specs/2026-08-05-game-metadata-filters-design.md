# 17만 게임 메타데이터와 상세 필터 설계

- 상태: 사용자 설계 승인 완료 · 명세 검토 대기
- 작성일: 2026-08-05
- 범위: `SEARCH-01` 게임 카탈로그 확장, 카테고리·테마·추천/베스트 인원 필터

## 1. 목표

알밤메이트의 운영 게임 카탈로그 목표를 BGG ID 170,000건으로 확장한다. 각 게임에 다음 검색·표시용 메타데이터를 정규화해 저장하고, 게임 목록에서 복합 필터로 사용한다.

- 상위 카테고리 8종: 전략, 추상 전략, 컬렉터블, 가족, 어린이, 테마, 파티, 워게임
- BGG XML의 `boardgamecategory`를 서비스 화면의 테마로 표시하는 다중 값
- BGG `suggested_numplayers` 투표에서 파생한 추천 인원과 베스트 인원

검색 화면의 테마 선택은 기본 `ANY`(하나라도 포함)이며, 사용자가 `ALL`(모두 포함)로 바꿀 수 있다. 카테고리·추천 인원·베스트 인원은 같은 필터 안에서 OR, 서로 다른 필터 그룹은 AND로 결합한다.

이 설계는 현재 약 2,000건으로 한정된 `SEARCH-01` 범위와 테마 제외 규칙을 후속 ADR과 정본 문서에서 대체한다. 기존 메커니즘의 공개 정책과 OR 의미는 바꾸지 않는다.

## 2. 제외 범위

- 검색 결과 캐시, Redis 캐시, 검색 엔진, `pg_trgm` 도입
- 메커니즘의 `ALL` 모드, 사용자 지정 정렬, 패싯별 결과 건수
- BGG 원문·토큰·대용량 JSON·XML·생성 SQL의 Git 커밋
- 게임 목록 카드에 카테고리·테마 배열을 추가하는 응답 확장
- 사용자 평가와 BGG 투표의 결합, 추천 인원 직접 수정 기능

목록 응답은 필터 적용만 확장한다. 새 메타데이터는 게임 상세와 선택지 API에서 제공한다.

## 3. 용어와 원천

화면 용어와 BGG 원천 용어가 다르므로 아래처럼 고정한다.

| 화면·API 용어 | 원천 | 의미 |
| --- | --- | --- |
| 카테고리 | 순위 CSV의 BGG 서브도메인 rank 열 | 화면 상단의 고정 8개 그룹 |
| 테마 | BGG XML `<link type="boardgamecategory">` | 게임이 가질 수 있는 다중 주제 |
| 가능 인원 | 게임 규칙의 `min_players`·`max_players` | 실제 플레이 가능한 범위 |
| 추천 인원 | BGG `suggested_numplayers`의 Best 또는 Recommended 우세 | 이용자 투표상 권장 인원 |
| 베스트 인원 | 같은 투표의 Best 단독 우세 | 이용자 투표상 가장 좋은 인원 |

`games-170k.performance.json`은 성능 검증용으로 만든 파일이다. 그 안의 합성 검색 속성은 운영 적재에 사용하지 않는다. 이 파일의 170,000개 `bgg_id` 선택만 대상 집합으로 사용하며, 운영 메타데이터는 승인된 순위 CSV와 새 BGG XML 스냅샷에서 다시 만든다.

한글명은 팀이 보유한 허용된 한글 매핑을 사용한다. BGG의 공식 번역이라고 주장하지 않으며, 자동 번역이나 원문을 외부 AI에 전달하는 단계는 두지 않는다.

## 4. 데이터 모델

기존 `GAMES`의 `recommended_player_count`, `best_player_count` 표시 문자열은 이 기능의 필터 원천으로 사용하지 않는다. 새 관계가 게임 상세 응답의 정본이며, 기존 문자열의 삭제·재해석은 범위 밖이다.

### 4.1 카테고리

`GAME_CATEGORIES`는 고정 8개 행을 가진다.

| 컬럼 | 제약 | 설명 |
| --- | --- | --- |
| `id` | PK | 내부 식별자 |
| `code` | UNIQUE, NOT NULL | API용 변경 불가 `UPPER_SNAKE_CASE` 코드 |
| `bgg_subdomain` | UNIQUE, NOT NULL | CSV의 rank 열에 대응하는 BGG 서브도메인 |
| `name_ko` | NOT NULL | 화면 표시 한글명 |
| `name_en` | NOT NULL | 원천 영문명 |
| `display_order` | UNIQUE, NOT NULL | 화면 고정 노출 순서 1~8 |

| `code` | `bgg_subdomain` | `name_ko` | `name_en` | 순서 |
| --- | --- | --- | --- | ---: |
| `STRATEGY` | `strategygames` | 전략 | Strategy | 1 |
| `ABSTRACT_STRATEGY` | `abstracts` | 추상 전략 | Abstract Strategy | 2 |
| `COLLECTIBLE` | `cgs` | 컬렉터블 | Collectible | 3 |
| `FAMILY` | `familygames` | 가족 | Family | 4 |
| `CHILDREN` | `childrensgames` | 어린이 | Children | 5 |
| `THEMATIC` | `thematic` | 테마 | Thematic | 6 |
| `PARTY` | `partygames` | 파티 | Party | 7 |
| `WARGAME` | `wargames` | 워게임 | Wargame | 8 |

`GAME_CATEGORY_RELATIONS`는 `(game_id, category_id)` 복합 기본 키와 두 외래 키만 가진다. 해당 CSV rank가 양수인 카테고리만 관계를 만든다. rank 누락·0·음수에서 임의 카테고리를 만들지 않는다.

### 4.2 테마

`GAME_THEMES`는 BGG `boardgamecategory` 하나당 한 행을 가진다.

| 컬럼 | 제약 | 설명 |
| --- | --- | --- |
| `id` | PK | 내부 식별자 |
| `bgg_theme_id` | UNIQUE, NOT NULL | XML link의 원본 ID |
| `code` | UNIQUE, NOT NULL | API용 변경 불가 내부 코드 |
| `name_ko` | NOT NULL | 검수된 화면 표시 한글명 |
| `name_en` | NOT NULL | XML `value` 영문명 |

`code`는 한글·영문 표시명과 분리한다. 영문명을 ASCII `UPPER_SNAKE_CASE`로 정규화한 뒤 항상 `_BGG_{bgg_theme_id}`를 붙인다. 그래서 새 테마가 더 낮은 BGG ID로 추가되어도 기존 공개 코드는 바뀌지 않는다.

`GAME_THEME_RELATIONS`는 `(game_id, theme_id)` 복합 기본 키와 두 외래 키만 가진다. XML에 같은 테마 link가 중복되어도 하나의 관계만 만든다. 테마가 없는 게임은 관계가 없으며, 테마 필터를 생략한 조회에서는 계속 결과에 포함된다.

### 4.3 추천·베스트 인원

`GAME_PLAYER_PREFERENCES`는 `(game_id, player_count)` 복합 기본 키를 가진다.

| 컬럼 | 제약 | 설명 |
| --- | --- | --- |
| `game_id` | PK, FK → `GAMES.id` | 내부 게임 식별자 |
| `player_count` | PK, CHECK `> 0` | 정규화된 실제 인원 수 |
| `is_recommended` | NOT NULL | 추천 인원 여부 |
| `is_best` | NOT NULL, `is_best → is_recommended` | 베스트 인원 여부 |

원본 poll의 `numplayers`가 `N`이면 N 한 행을, `N+`이면 N부터 그 게임의 검증된 `max_players`까지 한 행씩 만든다. 예를 들어 가능 인원이 `2~5`이고 2인은 비추천, 3인은 추천, `4+`는 추천, 4인은 베스트이면 다음과 같다.

| 인원 | 추천 | 베스트 |
| ---: | :---: | :---: |
| 2 | false | false |
| 3 | true | false |
| 4 | true | true |
| 5 | true | false |

응답은 추천 `[3, 4, 5]`, 베스트 `[4]`처럼 오름차순 정수 배열로 반환한다. 화면은 연속 배열만 `3~5명`으로 압축하고, `2, 4`처럼 비연속이면 `2명, 4명`으로 표시한다.

## 5. BGG 투표 정규화 규칙

각 `suggested_numplayers` 결과에서 `bestVotes`, `recommendedVotes`, `notRecommendedVotes`를 원본 XML 스냅샷에 보존한다. 데이터베이스에는 다음 판정 결과만 적재한다.

- 추천: `bestVotes + recommendedVotes > notRecommendedVotes`
- 베스트: `bestVotes > recommendedVotes`이고 `bestVotes > notRecommendedVotes`
- 투표 수가 모두 0이거나 동률이면 베스트로 만들지 않는다.
- 베스트는 추천의 부분집합이다. 동일 인원에 여러 원본 label이 겹치면 각 boolean을 OR로 합친다.
- label은 양의 정수 또는 양의 정수 뒤 `+`만 허용한다. `N+`의 N이 `max_players`보다 크거나 `max_players`가 없으면 배치 검증 실패다.
- poll이 없는 게임은 추측하지 않고 `GAME_PLAYER_PREFERENCES` 관계를 만들지 않는다.

이 규칙은 가능 인원과 추천·베스트 인원을 혼동하지 않는다. 가능 인원 검색은 기존 `min_players`·`max_players` 규칙을 그대로 사용한다.

## 6. 공개 API 계약

### 6.1 선택지 API

| Method / Path | 응답 | 정렬 | 인증 |
| --- | --- | --- | --- |
| `GET /api/game-categories` | `GameCategoryOption[]` | `displayOrder ASC` | 불필요 |
| `GET /api/game-themes` | `GameThemeOption[]` | `nameKo ASC, code ASC` | 불필요 |

두 선택지 응답은 `code`, `nameKo`, `nameEn`을 반환한다. 카테고리 응답만 `displayOrder`를 추가한다. 내부 DB ID와 BGG 원본 ID는 응답에 노출하지 않는다.

### 6.2 게임 목록 필터

`GET /api/games`에 아래 반복 파라미터를 추가한다.

| 이름 | 타입 | 기본값 | 의미 |
| --- | --- | --- | --- |
| `category` | string, 반복 가능 | 검색 없음 | 선택한 카테고리 코드 |
| `theme` | string, 반복 가능 | 검색 없음 | 선택한 테마 코드 |
| `themeMatch` | `ANY` 또는 `ALL` | `ANY` | 테마 선택 결합 방식 |
| `recommendedPlayerCount` | positive integer, 반복 가능 | 검색 없음 | 선택한 추천 인원 |
| `bestPlayerCount` | positive integer, 반복 가능 | 검색 없음 | 선택한 베스트 인원 |

아래는 선택지 API에서 받은 실제 테마 코드를 넣는 형식 예시다.

```text
GET /api/games?category=STRATEGY&category=FAMILY&theme=<themeCodeA>&theme=<themeCodeB>&themeMatch=ALL&recommendedPlayerCount=3&recommendedPlayerCount=4&bestPlayerCount=4
```

| 그룹 | 같은 그룹 안 결합 | 다른 그룹과 결합 |
| --- | --- | --- |
| `category` | 선택한 코드 중 하나 이상 | AND |
| `theme` + `themeMatch=ANY` | 선택한 코드 중 하나 이상 | AND |
| `theme` + `themeMatch=ALL` | 선택한 모든 코드와 관계 | AND |
| `recommendedPlayerCount` | 선택한 인원 중 하나 이상이 추천 | AND |
| `bestPlayerCount` | 선택한 인원 중 하나 이상이 베스트 | AND |
| 기존 `mechanism` | 현재 계약대로 OR | AND |

같은 값을 반복 전달해도 한 번과 같은 결과를 낸다. `themeMatch`를 테마 없이 보내는 것은 유효하며 테마 조건을 적용하지 않는다. 이는 선택 전에도 화면이 기본 토글값을 전송할 수 있게 하기 위함이다.

존재하지 않는 카테고리·테마 코드, 잘못되거나 중복된 `themeMatch`, 0 이하 인원은 전체 요청을 `400 VALIDATION_ERROR`로 거절한다. 일부 올바른 값이 함께 있어도 잘못된 값을 무시하지 않는다.

### 6.3 게임 상세

`GET /api/games/{gameId}`의 `GameDetail`에 아래 필드를 추가한다.

```json
{
  "categories": [{"code": "STRATEGY", "nameKo": "전략", "nameEn": "Strategy"}],
  "themes": [{"code": "<NORMALIZED_NAME>_BGG_<bggThemeId>", "nameKo": "경제", "nameEn": "Economic"}],
  "recommendedPlayerCounts": [3, 4, 5],
  "bestPlayerCounts": [4]
}
```

관계가 없으면 배열은 `[]`이다. 카테고리는 `displayOrder`, 테마는 `nameKo ASC, code ASC`, 인원 배열은 오름차순으로 반환한다.

## 7. 조회 구조와 성능 원칙

새 조건은 기존의 불변 `GameListSearchCriteria` 하나에 추가하고, 목록·전체 건수에 같은 동적 조건을 적용한다. 게임 행을 직접 다대다 조인하지 않고 관계별 상관 `EXISTS`로 판정한다.

- 카테고리·추천·베스트·테마 ANY는 `EXISTS`와 `IN`으로 한 번씩 판정한다.
- 테마 ALL은 선택한 고유 테마 코드 수와 게임의 일치한 고유 관계 수가 같은지로 판정한다.
- 기존 메커니즘과 해 본 게임 조건도 같은 방식으로 결합해 행 중복과 잘못된 전체 건수를 막는다.

관계 복합 기본 키와 참조 무결성에 필요한 인덱스는 스키마에 포함한다. 그 밖의 역방향 검색 인덱스와 캐시는 처음 변경에 넣지 않는다. 실제 170,000건 PostgreSQL 데이터에서 대표 조합의 `EXPLAIN ANALYZE`와 응답 시간을 기록한 뒤, 병목이 확인된 관계에만 별도 인덱스를 추가한다.

대표 측정 조합은 조건 없음, 카테고리 단일·복수, 테마 ANY 단일·복수, 테마 ALL 복수, 추천·베스트 복수, 메커니즘·테마·인원 전체 조합이다. 모든 테마 조합을 열거하지 않고, 선택 수와 관계 밀도가 큰 현실적인 최악 조합을 고른다.

## 8. 수집·검수·적재 흐름

1. 승인된 170,000개 BGG ID와 순위 CSV를 읽어 고정 카테고리 관계를 만든다.
2. BGG XML API를 20개 ID 단위의 재개 가능한 오프라인 배치로 수집한다. Bearer 토큰은 macOS Keychain에서 실행 시에만 읽고 출력·manifest·Git에 남기지 않는다.
3. XML에서 테마 관계와 원본 인원 투표를 파싱한다. 대상 ID, 응답 ID, 배치 checksum, HTTP 결과, 원본 XML checksum을 manifest에 기록한다.
4. 팀의 한글 테마 사전과 결합해 안정 코드·한글명·영문명이 모두 있는 정규화 산출물을 만든다.
5. 행 수 170,000, BGG ID 중복 0, 요청·응답 ID 집합 동일, 모든 테마 한글명 존재, 관계 중복 0, 인원 판정 재계산 일치라는 품질 게이트를 통과한 산출물만 적재 대상으로 승격한다.
6. 전진 Flyway는 다섯 새 테이블과 8개 고정 카테고리만 만든다. 대용량 게임·테마·관계 데이터는 승인 산출물의 결정적 UPSERT가 적재한다.
7. 적재기는 `bgg_id`와 `bgg_theme_id`로 내부 ID를 해석해 반복 실행해도 같은 행으로 수렴시킨다. 참조 게임이나 테마를 해석하지 못하면 전체 적재 트랜잭션을 롤백한다. 새 스냅샷에 없다는 이유만으로 기존 게임을 삭제하지 않는다.

수집·정규화·적재 산출물은 저장소 밖의 승인된 경로에 둔다. 현재 출처 표기와 허용 범위 기록은 유지한다.

## 9. 문서·마이그레이션 변경 경계

구현 변경에는 다음 정본을 같은 PR에 반영한다.

- 새 ADR-0050: 170,000건 운영 카탈로그, 테마·카테고리·인원 관계와 공개 필터 결정을 채택하고 ADR-0019의 2,000건·테마 보류 범위를 해당 범위에서 대체한다.
- `docs/P1-spec.md`, `docs/p1/search.md`: `SEARCH-01` 데이터 규모·필터·완료 기준·제외 범위 갱신
- `docs/API.md`: 새 목록 query, 선택지 API, 상세 응답과 오류 계약
- `docs/ERD.md`: 다섯 테이블, 관계, 제약, 실제 채택한 인덱스 근거
- `docs/guides/GAME_CATALOG_IMPORT.md`: 원본 manifest, 한글 사전, 재개 수집, 품질 게이트, UPSERT 순서
- 전진 Flyway, JPA 엔티티·관계, 조회 기준 객체, 선택지·상세 응답, PostgreSQL 검증

마이그레이션 번호는 구현 시작 시 최신 `develop`의 파일과 적용 이력을 확인해 비어 있는 다음 번호를 사용한다. 이 설계 시점에는 V16까지 존재하지만, 번호 자체를 예약하지 않는다.

## 10. 검증 기준

### 계약·정규화

- 8개 카테고리 코드·순서·한글명이 정확하고, rank 기반 관계가 중복 없이 생성된다.
- 테마 원본 ID·영문명·내부 코드·한글명 매핑이 유일하고, 한글명이 빈 테마는 공개·적재하지 않는다.
- `4+` 확장, 비연속 추천 인원, 투표 동률, poll 누락, 잘못된 label을 단위 테스트로 검증한다.
- 전체 170,000 ID 집합과 source manifest·quality report의 checksum·관계 수가 일치한다.

### API·DB

- 카테고리·테마 단일 및 다중 OR, 테마 ANY·ALL, 추천·베스트 단일 및 다중 OR, 모든 그룹 AND를 HTTP와 PostgreSQL 통합 테스트로 검증한다.
- 알 수 없는 코드·잘못된 `themeMatch`·0 이하 인원은 `400 VALIDATION_ERROR`이며, 중복값은 결과를 중복하지 않는다.
- 목록 내용 조회와 전체 건수가 같은 조건·정렬·페이지 규칙을 쓴다.
- 적재를 두 번 실행해도 참조·관계 행 수가 증가하지 않으며, 해석 실패는 트랜잭션 전체를 롤백한다.

### 성능

- 170,000건 PostgreSQL fixture에서 8절의 대표 조합마다 `EXPLAIN ANALYZE`와 응답 시간을 남긴다.
- 캐시 없이 측정하며, 별도 검색 인덱스를 추가했다면 그 전후 실행 계획과 측정값을 같은 기록에 남긴다.

## 11. 완료 조건

- 승인된 170,000건 입력이 원천·한글 매핑·품질 게이트를 통과해 반복 적재된다.
- 카테고리, 테마, 추천 인원, 베스트 인원 데이터가 게임 상세과 선택지 API에서 한글명으로 조회된다.
- 요청 계약의 OR·AND·ANY·ALL 의미가 PostgreSQL에서 중복 없는 목록·전체 건수로 검증된다.
- 대표 170,000건 조합의 실행 계획과 시간 근거가 남고, 캐시나 추가 인덱스는 측정 필요성이 확인된 경우에만 도입된다.
