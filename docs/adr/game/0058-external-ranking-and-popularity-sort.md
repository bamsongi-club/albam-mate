# ADR-0058: 외부·내부 원천을 결합한 게임 인기 점수와 기본 정렬

- 상태: 승인됨
- 작성일: 2026-08-14
- 결정일: 2026-08-14
- 관련: [RANK-02 구현 이슈](https://github.com/bamsongi-club/albam-mate/issues/723), [게임 목록·검색 API](../../API.md#game-01-게임-목록검색), [게임 조건 검색](../../archive/p1/search.md#search-01-게임-조건-검색)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

게임 찾기의 기본 정렬은 이름순이었지만, 국내 서비스에서 사용자가 자주 찾는 게임을 먼저 발견하려면 국내 커뮤니티 순위와 서비스 내부 이용량, 국외 순위를 함께 반영해야 한다. BoardLife와 BGG 순위는 시점에 따라 변하고 외부 서비스의 가용성·이용 조건에 영향을 받으므로 요청마다 조회할 수 없다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 내부 `GAME_FOCUSED` 방 집계만 사용 | 서비스 사용 맥락과 구현이 단순함 | 국내·국외 외부 인기도를 반영하지 못함 | 제외 |
| 요청 시 BoardLife·BGG를 직접 조회 | 최신 순위를 즉시 반영함 | 지연·장애·이용 조건·페이지 정합성에 런타임이 의존함 | 제외 |
| 원시 순위에 가중치를 바로 적용 | 계산이 짧음 | 원천별 순위 범위가 달라 점수 비교가 성립하지 않음 | 제외 |
| 승인 스냅샷을 검증해 파생 점수를 배치 적재 | 런타임 외부 의존성을 없애고 재현·롤백 가능 | 갱신 배치와 입력 검수가 필요함 | 선택 |

## 결정

- BoardLife 국내 순위, Albam 내부 전체 `GAME_FOCUSED` 방 집계, BGG 순위를 각각 0~1로 정규화한 뒤 `0.6 * BoardLife + 0.3 * Albam + 0.1 * BGG`로 `games.popularity_score`를 계산한다.
- 양의 순위가 둘 이상이면 rank 1을 1, 양의 최댓값을 0으로 하는 선형 역순을 사용한다. 양의 순위가 하나뿐이면 그 순위는 1.0으로 처리하고, 결측·0·음수는 0으로 처리한다.
- 동일한 외부 식별자의 BoardLife 행은 가장 작은 rank를 대표값으로 사용한다. 확장판은 실제 서비스 카탈로그의 BGG ID로 매핑된 행만 반영하며 매핑되지 않은 행은 적재하지 않는다.
- Albam 내부 점수는 모든 상태의 `GAME_FOCUSED` 방을 세되 `CANCELED`만 제외하고, 방 수 내림차순·게임 ID 오름차순으로 결정론적 순위를 만든다. 양의 사용량이 없는 게임의 내부 점수는 0이다.
- `scripts/game-ranking/prepare-game-popularity-ranking.mjs`는 `schemaVersion: 1` 승인 manifest와 source별 checksum·행 수, `1 row per bggId` score input을 검증해 배치 SQL을 만든다. raw source와 manifest는 저장소 밖에서 관리하며 승인되지 않은 입력은 차단한다.
- 애플리케이션은 외부 원천을 실행 중 조회하지 않고 `games.popularity_score DESC, name ASC, id ASC`로 `GET /api/games`를 정렬한다. 점수는 응답 필드로 노출하지 않으며 기존 필터·페이지네이션·응답과 RANK-01 계약을 유지한다.

## 결과

- 얻는 것:
  - 국내·내부·국외 원천을 동일 척도로 결합한 재현 가능한 기본 인기순
  - `games`의 인덱스를 사용하는 단순한 목록 조회와 결측 원천의 안정적인 0점 처리
  - manifest·checksum·생성 SQL을 통한 입력 provenance 확인
- 감수할 비용·위험:
  - 순위 갱신 때마다 원천 스냅샷 검수와 배치 SQL 실행이 필요하다.
  - 원천에 없는 게임은 해당 원천 점수가 0이므로 갱신 직후 순서가 바뀔 수 있다.
- 후속 작업:
  - BoardLife·BGG 스냅샷 갱신 주기와 운영 실행 주체를 적재 가이드에 기록한다.
  - 실제 승인 입력을 확보한 뒤 생성 SQL의 checksum과 적재 결과를 보존한다.
  - 적재 전 점수 snapshot과 산출물 증적, 실행 후 범위 검증, 이전 snapshot 복구 절차를 적재 가이드의 RANK-02 운영 계약으로 따른다.

## 보류 및 재검토

- 지금 하지 않는 것: 런타임 외부 조회, 개인화·기간별 인기 점수, 사용자 응답에 점수·원천별 순위 노출
- 보류 이유: 이번 3차 MVP의 기본 탐색 정렬 범위를 넘고 별도의 제품·운영 계약이 필요하다.
- 다시 검토할 조건: 원천 이용 조건, 갱신 자동화, 품질 기준과 사용자 노출 요구가 별도 이슈와 ADR로 승인될 때

## 참고 자료

- [BoardLife 전체 순위](https://boardlife.co.kr/rank)
- [BGG rank dump 안내](https://boardgamegeek.com/data_dumps/bg_ranks)
- [BGG 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)
- [RANK-02 구현 이슈](https://github.com/bamsongi-club/albam-mate/issues/723)

## 검증

- 상태: 미검증
- 근거:
  - 구현: `V27__add_game_popularity_score.sql`, `GameQueryService`, `prepare-game-popularity-ranking.mjs`
  - 계약: RANK-02 이슈의 승인된 T1~T6 테스트 계약
  - 테스트: `GamePopularityBatchPostgresTest`, `GamePopularitySortIntegrationTest`, 기존 `GameQueryServiceListTest`의 targeted 실행
- 미검증:
  - CI 전체 실행과 실제 운영 승인 스냅샷 적재 결과

> 상태 값과 번호·대체 규칙은 [ADR README](../README.md)를 따른다.
