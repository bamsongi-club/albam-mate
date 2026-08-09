# ADR-0050: 17만 게임 메타데이터를 관계로 관리하고 상세 필터를 제공

- 상태: 승인됨
- 작성일: 2026-08-05
- 결정일: 2026-08-05
- 관련: [Issue #420](https://github.com/bamsongi-club/albam-mate/issues/420), [SEARCH-01](../../p1/search.md#search-01-게임-조건-검색), [게임 목록·검색 API](../../API.md#game-01-게임-목록검색), [ERD](../../ERD.md), [ADR-0019](0019-bgg-full-catalog-staged-enrichment.md), [ADR-0048](0048-full-reviewed-game-mechanism-catalog.md)
- 대체 대상: ADR-0019의 2,000건 한정과 다중 카테고리·테마·추천/베스트 인원 보류 범위
- 후속 ADR: 없음

## 맥락

기존 SEARCH-01은 약 2,000개 게임의 수치 필드와 검수된 메커니즘 관계까지만 다룬다. 게임 탐색 화면에는 BGG 순위 CSV의 상위 카테고리, BGG XML의 다중 테마, 이용자 투표 기반 추천·베스트 인원도 필요하다.

가능 인원은 제작사 규칙 범위이고 추천·베스트 인원은 이용자 투표 결과이므로 같은 문자열이나 같은 필드로 합치면 의미가 섞인다. 카테고리·테마도 하나의 tag 문자열에 넣으면 여러 선택 조건, 안정 code, 한글 표시명, 중복 방지와 출처 추적을 동시에 보장할 수 없다.

프로젝트가 보유한 승인된 BGG API 접근과 출처 표기 범위 안에서 170,000개 BGG ID를 오프라인 batch로 취득할 수 있다. 다만 token·원본 XML·대용량 JSON·한글 사전 원본·생성 SQL은 서비스 소스가 아니라 저장소 밖 승인 경로에 둬야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 기존 GAMES.tag와 추천·베스트 문자열을 재사용 | 저장 구조 변경이 작다. | 다중 값·코드·원본 ID·투표 의미와 중복 방지를 표현하지 못한다. | 제외 |
| 카테고리·테마·인원을 JSON 또는 쉼표 문자열로 저장 | 적재 형식이 단순해 보인다. | 선택지, 안정 code, FK, 중복 관계와 ALL 검색을 안전하게 검증할 수 없다. | 제외 |
| 관계 테이블과 동적 관계 조회를 사용 | 의미가 다른 값을 분리하고 다중 선택을 정확히 표현한다. | migration·적재기·관계 조회·품질 게이트가 필요하다. | 선택 |
| 첫 변경에 cache·검색 엔진·역방향 인덱스를 함께 도입 | 최대 처리량을 미리 확보할 수 있다. | 실제 병목 없이 운영 복잡도와 유지 비용만 늘어난다. | 제외 |

## 결정

### 1. 메타데이터를 다섯 관계 테이블로 분리한다

- GAME_CATEGORIES에는 전략, 추상 전략, 컬렉터블, 가족, 어린이, 테마, 파티, 워게임의 고정 8개 code·한글명·영문명·CSV subdomain·표시 순서를 둔다.
- GAME_CATEGORY_RELATIONS는 순위 CSV의 해당 subdomain rank가 양수인 경우만 game_id와 category_id 복합 키로 연결한다.
- GAME_THEMES에는 BGG boardgamecategory의 원본 ID, 안정적인 내부 code, 검수된 한글명, 원문 영문명을 둔다. code는 영문명을 ASCII UPPER_SNAKE_CASE로 정규화한 뒤 항상 _BGG_<bgg_theme_id>를 붙여 증분 snapshot에서도 바뀌지 않게 한다.
- GAME_THEME_RELATIONS는 game_id와 theme_id 복합 키로 한 번만 연결한다.
- GAME_PLAYER_PREFERENCES는 game_id와 player_count 복합 키와 is_recommended, is_best를 둔다. 데이터베이스 CHECK와 정규화 단계 모두에서 is_best -> is_recommended를 보장한다.

기존 GAMES.recommended_player_count, best_player_count는 표시용 과거 필드로 유지하되 이 기능의 검색·상세 정본으로 쓰지 않는다. Game.tag도 현재 표시 의미를 유지한다.

### 2. BGG 투표와 한글명을 결정적으로 정규화한다

- suggested_numplayers의 추천은 bestVotes + recommendedVotes > notRecommendedVotes, 베스트는 bestVotes > recommendedVotes와 bestVotes > notRecommendedVotes를 모두 만족할 때만 참이다.
- N+ label은 N부터 게임의 검증된 max_players까지 확장한다. max_players가 없거나 N이 더 크면 배치를 실패시킨다.
- 동률·전체 0표·poll 누락은 추정하지 않는다. 베스트는 만들지 않고, poll 자체가 없으면 인원 선호 관계를 만들지 않는다.
- 한글 테마명은 팀의 허용된 매핑을 사용하며 BGG의 공식 번역이라고 주장하지 않는다. 한글명·원문명·code·원본 ID의 유일성이 깨지면 적재 산출물을 만들지 않는다.

수집기는 최대 20개 ID의 XML 요청을 재개 가능한 batch로 보관하고, 요청·응답 ID, HTTP 결과, bytes, SHA-256, 취득 시각을 manifest에 남긴다. token은 실행 시 Keychain에서만 읽고 출력·manifest·Git에 남기지 않는다.

### 3. 공개 검색은 OR·AND·ANY·ALL을 고정한다

- category, recommendedPlayerCount, bestPlayerCount, mechanism의 같은 종류 반복값은 OR다.
- themeMatch의 기본값은 ANY이고 선택한 테마 중 하나라도 관계가 있으면 된다. ALL은 선택한 모든 고유 테마와 관계가 있어야 한다.
- 서로 다른 필터 그룹, 기존 keyword·가능 인원·플레이 시간·복잡도·예정 모임·해 본 게임 조건은 AND다.
- 존재하지 않는 category/theme code, 0 이하 인원, 잘못되거나 중복된 themeMatch는 일부 유효 값이 함께 있어도 400 VALIDATION_ERROR다.
- 목록·전체 건수에는 같은 불변 GameListSearchCriteria를 쓰고, 관계를 직접 조인하지 않고 상관 EXISTS로 판정한다.

카테고리·테마 선택지는 인증 없이 한글명과 안정 code를 반환한다. 상세에는 정렬된 categories, themes, recommendedPlayerCounts, bestPlayerCounts 배열을 반환하고 관계가 없으면 빈 배열을 반환한다. 목록 카드 응답은 확장하지 않는다.

### 4. 성능은 170,000건 측정 뒤에만 최적화한다

`games-170k.performance.json`은 승인 manifest와 함께 게임 기본 정보 170,000행을 운영 적재에 사용할 수 있다. 다만 fixture의 카테고리·테마·인원 선호·메커니즘·최소 연령은 합성값이므로 운영 관계 데이터로 쓰지 않고, 승인된 순위 CSV·BGG XML snapshot·한글 사전에서 별도 산출한다. PostgreSQL 대표 조합의 EXPLAIN ANALYZE와 응답 시간도 같은 기본 게임 집합으로 재현한다. 관계 복합 키와 FK 유지에 필요한 인덱스 외의 역방향 검색 인덱스, cache, Redis, 검색 엔진, pg_trgm은 넣지 않는다.

조건 없음, 카테고리 단일·복수, 테마 ANY 단일·복수, 테마 ALL 복수, 추천·베스트 복수, 메커니즘·테마·인원 복합 조건을 기록한다. 병목이 재현될 때만 별도 ADR 또는 이 ADR의 후속 결정으로 인덱스·cache를 검토한다.

## 결과

- 얻는 것: 170,000개 게임에서 의미가 분리된 다중 메타데이터, 한글 선택지와 정확한 복합 필터를 제공한다.
- 감수할 비용·위험: XML snapshot·한글 사전·manifest 품질을 지속해서 검수하고 대용량 적재·성능 측정을 수행해야 한다.
- 후속 작업: V20, JPA·API, 수집·정규화·UPSERT 도구, PostgreSQL 검증과 170,000행 실행 계획을 구현한다.

## 보류 및 재검토

- 지금 하지 않는 것: 메커니즘 ALL, 사용자 지정 정렬, 패싯 결과 수, 목록 카드 메타데이터 배열, cache·검색 엔진·측정 전 역방향 검색 인덱스.
- 보류 이유: 현재 승인 범위는 정확한 관계 필터와 실측 근거 확보이며, 추가 운영 복잡도는 측정된 병목이 있어야 정당화된다.
- 다시 검토할 조건: 170,000행 측정에서 특정 관계 조건의 병목이 재현되거나, 출처·한글 매핑·공개 이용 범위가 바뀔 때.

## 참고 자료

- [Issue #420](https://github.com/bamsongi-club/albam-mate/issues/420)
- [BGG XML API2](https://boardgamegeek.com/wiki/page/bgg_xml_api2)
- [ADR-0019](0019-bgg-full-catalog-staged-enrichment.md)
- [ADR-0048](0048-full-reviewed-game-mechanism-catalog.md)

## 검증

- 상태: 검증됨
- 근거: V20 관계 제약·renderer 수렴/롤백 PostgreSQL 검증, 선택지·상세·OR·AND·ANY·ALL HTTP·PostgreSQL 검증, 170,000행 PostgreSQL 실행 계획 측정. 상세 수치와 명령은 [성능 측정 기록](../../game-catalog/2026-08-05-game-metadata-filter-performance.md)을 따른다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
