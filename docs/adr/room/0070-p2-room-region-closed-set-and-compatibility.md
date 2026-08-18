# ADR-0070: P2 Room 지역 닫힌 집합과 기존 요청 호환

- 상태: 승인됨
- 작성일: 2026-08-17
- 결정일: 2026-08-18
- 관련: [#794](https://github.com/bamsongi-club/albam-mate/issues/794), [#796](https://github.com/bamsongi-club/albam-mate/issues/796), [AI-01 명세](../../p2/assistant.md), [ROOM-03 API](../../API.md#room-03-방-생성), [Room ERD](../../ERD.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

현재 Room의 `region`은 `VARCHAR(50)` 문자열이고 생성 요청에는 필드가 없으며, Room 생성 factory가 `홍대`를 하드코딩한다. AI-01은 모델이 제안할 지역의 허용 집합과 서버·DB 검증 경계를 필요로 한다.

자유 문자열을 그대로 허용하면 AI schema와 검색·표시 기준이 흔들리고, 영문 코드로 즉시 전환하면 기존 Room 행과 한국어 wire value의 호환 비용이 커진다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 자유 텍스트 지역 | 입력이 자유로움 | 값 집합·검증·AI schema가 불안정함 | 제외 |
| 영문 code 저장과 한국어 표시 | 장기적인 국제화가 쉬움 | 기존 행 migration과 wire·표시 매핑이 필요함 | 보류 |
| 애플리케이션만 허용값 검증 | migration이 작음 | DB에 잘못된 값이 남을 수 있음 | 제외 |
| 한국어 문자열 + 닫힌 Java enum + DB `CHECK` | 기존 wire·행과 호환하면서 저장 경계를 보장함 | 지역 추가 시 migration과 API 검토가 필요함 | 선택 |

## 결정

- 허용 지역은 `홍대`, `강남`, `건대`, `잠실`의 닫힌 집합으로 고정한다. Java 내부는 enum으로 표현하고 DB에는 현재 wire 관례와 기존 행을 보존하는 한국어 문자열을 저장한다.
- DB에는 `CHECK (region IN ('홍대', '강남', '건대', '잠실'))` 제약을 둔다. 애플리케이션 검증만으로 대체하지 않는다.
- 공개 API wire value는 기존 한국어 문자열을 유지한다. AI는 enum 지역만 제안하며 상세 장소는 별도 `place` 입력으로 분리한다.
- AI-01 확인형 Room 생성 요청에는 `region`을 추가하되 호환 기간에는 optional로 받는다. 기존 직접 `POST /api/rooms` 계약은 region 없이 유지하며, 누락된 AI 요청은 `홍대`로 해석한다.
- 호환 기간 종료 조건은 모든 지원 first-party client가 `region`을 전송하는 release가 배포되고, 이후 30일 동안 누락 요청이 관측되지 않는 것이다. 종료 시 required 전환은 별도 ADR 또는 API 계약 변경으로 승인한다.
- 기존 Room 행은 재작성하지 않는다. migration 전 운영 데이터의 `region` 허용값을 확인한다. 예상 밖 값이 하나라도 있으면 제약 migration을 중단하고 정리·rollback 계획을 별도 승인한다.
- 기존 `PATCH`에서 지역을 수정할 수 없는 규칙과 P2의 지역 목록 검색 필터 제외는 유지한다.

## 결과

- 얻는 것:
  - AI tool schema·서버·DB가 같은 네 지역 집합을 사용한다.
  - 기존 한국어 응답과 기존 `홍대` 행을 보존하면서 신규 요청의 값 범위를 제한한다.
  - 누락된 구형 요청을 호환 처리하면서 신규 client는 지역을 명시할 수 있다.
- 감수할 비용·위험:
  - 지역 추가는 enum·CHECK·API·fixture를 함께 갱신해야 한다.
  - 제약 migration 전에 운영 데이터 검사가 필수다.
  - 호환 기간 동안 누락 요청은 `홍대`로 해석되므로 관측과 종료 조건을 관리해야 한다.
- 후속 작업:
  - `AI-01c`에서 enum·DTO·요청 기본값·migration·기존 데이터 검사를 구현한다.
  - `docs/API.md`, `docs/ERD.md`, `docs/ARCHITECTURE.md`에 region 계약을 반영한다.

## 보류 및 재검토

- 지금 하지 않는 것: 자유 텍스트 지역, 영문 code 저장, 지역 수정 PATCH, 지역 검색 filter, 기존 Room 행 backfill.
- 보류 이유: 현재 기능은 기존 한국어 wire와 홍대 기본값 호환이 우선이며, 다국어 저장 표현은 별도 요구와 migration 근거가 필요하다.
- 다시 검토할 조건: 허용 지역 추가, first-party client 전환 완료, 30일 누락 관측 결과, 국제화 요구 또는 기존 데이터에서 허용 밖 값이 발견되는 경우.

## 참고 자료

- [AI-01 명세](../../p2/assistant.md)
- [AI-D02·D03 결정 이슈 #796](https://github.com/bamsongi-club/albam-mate/issues/796)
- [ROOM-03 방 생성 API](../../API.md#room-03-방-생성)
- [ERD](../../ERD.md)

## 검증

- 상태: 미검증
- 근거: 결정 — 완료된 [#796](https://github.com/bamsongi-club/albam-mate/issues/796)의 지역 결정과 현재 `Room.region`·기존 `홍대` 기본값 경계를 반영함.
- 미검증:
  - 운영 Room region 값 확인과 `CHECK` migration rollback 검증
  - API·DTO·enum·기존 client 호환 및 지역 입력 회귀 테스트
