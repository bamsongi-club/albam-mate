# P0 참가·내 모임 구현 명세

이 문서는 참가·재참가·취소·내 모임 기능의 구현 규칙과 완료 기준만 정의한다. 공통 정원·상태·권한·시간 규칙은 [P0 공통 명세](../P0-spec.md#공통-규칙), 요청·응답·오류는 [API 명세](../API.md), 저장 계약은 [ERD](../ERD.md)를 따른다.

ADR 상태는 기술 결정의 상태이며 구현 완료 여부는 각 ADR의 구현 검증 항목에서 별도로 확인한다.

## PART-01 방 참가·재참가

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| API 계약 | [방 참가·재참가](../API.md#part-01-방-참가재참가) |
| 공통 규칙 | [정원](../P0-spec.md#정원capacity), [방 상태](../P0-spec.md#방-상태roomstatus), [권한](../P0-spec.md#권한과-공개-범위), [시간 경계](../P0-spec.md#시간-경계) |
| 데이터 모델 | [ROOMS](../ERD.md#rooms), [PARTICIPATIONS](../ERD.md#participations), [필수 제약](../ERD.md#필수-제약과-계산-규칙) |
| 필수 ADR | [ADR-0005](../adr/participation/0005-room-participation-optimistic-locking.md) — 승인됨, [ADR-0012](../adr/room/0012-room-request-boundary-state-reconciliation.md) — 승인됨 |
| 시간 기준 | [ADR-0009](../adr/platform/0009-utc-time-standard.md) — 승인됨 |
| 검증 환경 | [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md) — 승인됨 |

### 기능 규칙

- 참가 신청은 승인 절차 없이 즉시 확정한다.
- 이전에 취소한 참가 관계가 있으면 새 관계를 만들지 않고 기존 관계를 재활성화한다.
- 시간이 겹치는 다른 방의 참가 여부는 검사하지 않는다.
- 참가 가능 여부와 정확한 오류 우선순위는 연결된 P0 공통 규칙과 API 계약을 따른다.

### 완료 기준

- `PART-01-AC1` 신규 참가와 재참가는 모두 `201 Created`와 `participationStatus = ACTIVE`를 반환한다.
- `PART-01-AC2` 마지막 좌석 참가 시 방이 `CLOSED`가 된다.
- `PART-01-AC3` 방이 정원 충족으로 `CLOSED`인 동안, 주최자도 현재 `ACTIVE` 참가자도 아닌 사용자의 신규 참가 요청은 `CAPACITY_EXCEEDED`를 반환한다.
- `PART-01-AC4` 방이 `CANCELED` 또는 `FINISHED`로 전이된 뒤의 참가 요청은 `ROOM_NOT_RECRUITING`을 반환한다.
- `PART-01-AC5` 동일 사용자의 활성 참가 관계가 중복 생성되지 않는다.
- `PART-01-AC6` 실제 PostgreSQL 동시 요청에서도 모집 정원을 초과하지 않고 충돌 결과가 API·ADR 계약과 일치한다.

### 제외 범위

- 참가 승인과 대기열
- 시간대가 겹치는 방의 참가 제한
- 선착순 처리 순서의 공정성 또는 성능 SLA

## PART-02 참가 취소

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| API 계약 | [참가 취소](../API.md#part-02-참가-취소) |
| 공통 규칙 | [정원](../P0-spec.md#정원capacity), [방 상태](../P0-spec.md#방-상태roomstatus), [권한](../P0-spec.md#권한과-공개-범위), [시간 경계](../P0-spec.md#시간-경계) |
| 데이터 모델 | [ROOMS](../ERD.md#rooms), [PARTICIPATIONS](../ERD.md#participations) |
| 필수 ADR | [ADR-0005](../adr/participation/0005-room-participation-optimistic-locking.md) — 승인됨, [ADR-0012](../adr/room/0012-room-request-boundary-state-reconciliation.md) — 승인됨 |
| 시간 기준 | [ADR-0009](../adr/platform/0009-utc-time-standard.md) — 승인됨 |
| 검증 환경 | [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md) — 승인됨 |

### 기능 규칙

- 일반 참가자는 자신의 현재 활성 참가 관계만 취소한다.
- 취소 권한, 가능 시각과 취소 뒤 방 상태는 연결된 P0 공통 규칙을 따른다.
- 정확한 오류 우선순위는 API 계약을 따른다.

### 완료 기준

- `PART-02-AC1` 성공 시 `200 OK`와 `participationStatus = CANCELED`를 반환한다.
- `PART-02-AC2` 시작 전 취소로 빈자리가 생기면 공통 상태 규칙에 따라 방이 `RECRUITING`으로 복귀한다.
- `PART-02-AC3` 주최자 요청, 활성 참가 관계가 없는 요청과 시작 이후 요청이 각각 API 계약의 오류로 거절된다.
- `PART-02-AC4` 참가와 취소가 동시에 실행돼도 참가 수와 방 상태가 ERD·ADR 불변식을 만족한다.

### 제외 범위

- 시작 이후 사용자 참가 취소
- 참가·취소 알림
- 취소 제재와 노쇼 정책

## PART-03 내 모임 조회

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| API 계약 | [내 모임 조회](../API.md#part-03-내-모임-조회) |
| 공통 규칙 | [방 상태](../P0-spec.md#방-상태roomstatus), [권한과 공개 범위](../P0-spec.md#권한과-공개-범위), [상태 정합성](../P0-spec.md#상태-정합성과-동시-변경) |
| 데이터 모델 | [ROOMS](../ERD.md#rooms), [PARTICIPATIONS](../ERD.md#participations) |
| 필수 ADR | [ADR-0012](../adr/room/0012-room-request-boundary-state-reconciliation.md) — 승인됨 |
| 시간 기준 | [ADR-0009](../adr/platform/0009-utc-time-standard.md) — 승인됨 |
| 검증 환경 | [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md) — 승인됨 |

### 기능 규칙

- `role=joined`는 활성 참가 관계이면서 방이 `CANCELED`가 아닌 본인 참가 방을 조회한다.
- `role=hosted`는 본인이 만든 방을 조회한다.
- `role=all`은 `joined`와 `hosted`를 중복 없이 합쳐 조회한다.
- 정확한 필드, 정렬과 페이지네이션은 API 계약을 따른다.

### 완료 기준

- `PART-03-AC1` `joined`, `hosted`, `all`이 각각 정의된 범위만 반환한다.
- `PART-03-AC2` 참가 취소 관계와 취소된 참가 방은 `joined`에서 제외하고 `FINISHED` 방은 포함한다.
- `PART-03-AC3` 상태 보정이 완료된 결과를 기준으로 필터와 페이지를 계산한다.
- `PART-03-AC4` 내 모임 항목에 정확한 장소와 전체 참가자 목록이 노출되지 않는다.

### 제외 범위

- 다른 사용자의 모임 이력 조회
- 내 모임 목록에서 정확한 장소와 전체 참가자 목록 제공
- 취소한 참가 관계의 이력 조회
