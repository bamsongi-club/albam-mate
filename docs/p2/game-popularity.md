# P2 게임 인기순 정렬 기능 명세

> **문서 상태: active**
>
> 담당자: `@hanyejin` · 기능 ID: `RANK-02` · 상태 정본: [P2 기능 상태](README.md#기능별-현재-상태)

## 문서 책임

- 이 문서가 소유하는 기능 동작과 완료 기준: 국내·내부·국외 인기 원천을 결합한 점수 계산, 승인 배치의 실패·복구 경계와 게임 목록 기본 정렬.
- 이 문서가 소유하지 않는 API·ERD·아키텍처·기술 결정: HTTP 응답 구조, `games` 스키마와 모듈 책임은 각각 [API](../API.md), [ERD](../ERD.md), [아키텍처](../ARCHITECTURE.md)와 [ADR-0058](../adr/game/0058-external-ranking-and-popularity-sort.md)이 소유한다.

## RANK-02

RANK-02는 게임 찾기에서 이름순 대신 사용자가 자주 찾을 가능성이 높은 게임을 먼저 보여주는 3차 MVP 기능이다. 원천 순위를 요청 중 조회하지 않고 승인된 입력을 배치로 검증·계산해 `games.popularity_score`에 저장한다.

## 핵심 흐름

~~~text
BoardLife·BGG snapshot과 Albam score input 승인
→ manifest·행 수·checksum·grain 검증
→ 원천별 순위 정규화와 6:3:1 가중합 계산
→ 이전 popularity_score snapshot과 생성 산출물 보존
→ SQL 실행 전·후 품질 검증
→ GET /api/games 기본 인기순 제공
~~~

## 기능 규칙

- BoardLife 0.6, Albam 내부 `GAME_FOCUSED` 방 집계 0.3, BGG 0.1을 사용한다.
- 각 원천의 양의 순위가 둘 이상이면 1위를 `1.0`, 최하위 양의 순위를 `0.0`으로 하는 선형 역순을 사용한다. 하나면 `1.0`, 결측·0·음수면 `0.0`이다.
- 같은 외부 ID는 가장 작은 순위를 대표값으로 사용하고, 서비스 카탈로그에 매핑되지 않은 확장판 입력은 반영하지 않는다.
- 기본 정렬은 `popularity_score DESC, name ASC, id ASC`이며 기존 필터·페이지네이션·응답 구조를 유지한다.
- 애플리케이션 요청 중 BoardLife·BGG를 직접 조회하지 않는다. 승인되지 않았거나 checksum이 맞지 않는 배치는 SQL을 실행할 수 없는 상태로 차단한다.

## 실패·복구

- 생성기는 성공한 SQL만 최종 출력 경로로 원자 교체하고, 검증 실패 시 기존 SQL을 제거하며 `quality-report.json`을 `blocked`로 남긴다.
- 적재 전 기존 점수 snapshot, manifest, `quality-report.json`, 생성 SQL과 각 checksum을 배치 증적 위치에 보존한다.
- SQL은 `ON_ERROR_STOP`과 단일 transaction으로 실행하고, 실행 후 전체 행 수·점수 범위·artifact checksum을 확인한다.
- 잘못된 배치는 보존한 snapshot을 이용해 이전 점수를 복원한다. 보존 위치·실행 명령·기본 주기는 [카탈로그 적재 가이드](../guides/GAME_CATALOG_IMPORT.md#rank-02-보존검증복구)에 따른다.

## 완료 기준

- `RANK-02-AC1`: 승인 manifest와 세 원천 입력의 checksum·행 수·grain을 검증하지 못하면 배치가 차단된다.
- `RANK-02-AC2`: 정규화·6:3:1 가중합·중복·결측·미매칭·`GAME_FOCUSED`/`CANCELED` 경계가 고정 테스트로 재현된다.
- `RANK-02-AC3`: `GET /api/games`가 `popularity_score DESC, name ASC, id ASC`와 기존 필터·페이지·응답 계약을 지킨다.
- `RANK-02-AC4`: 성공 후 동일 출력 경로의 검증 실패가 이전 SQL을 남기지 않고, 170,000행 score input도 생성에 성공한다.
- `RANK-02-AC5`: 실행 전 snapshot과 산출물 증적, 실행 후 검증 및 이전 snapshot 복구 절차가 운영 가이드에 재현 가능하게 기록된다.

## 범위와 상태

- 이번 범위에 포함하지 않는 것: 런타임 외부 조회, 개인화·기간별 인기, 사용자 응답에 점수·원천별 순위 노출, 자동 원천 수집.
- 현재 상태: 계약·코드·자동 검증은 이 PR 범위에서 완료한다. 운영 배포와 실제 승인 snapshot 적재·실측은 아직 완료하지 않았다.
