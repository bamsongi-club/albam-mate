# ROOM-10c 시작 경계 낙관 락 기준선 측정

## 범위와 판정

이 문서는 #379이 추가한 시작 경계 기준선의 fixture, 실행 결과와 재현 방법을 기록한다. 대상은 상태 보정과 다음 명령이 같은 ROOM을 대상으로 만나는 경우다.

- 직접 참가
- 대기 등록
- 참가 취소 뒤 자동 승격
- 대기 취소

각 경우는 상태 보정이 먼저 확정되는 순서와 명령이 먼저 확정되는 순서를 모두 실행한다. 새 테스트는 현행 생산 코드의 동작을 PostgreSQL에서 관찰할 뿐, 잠금 전략·재시도 정책·생산 설정을 바꾸지 않는다. 이 결과는 운영 SLO나 성능 합격선이 아니며, 비관적 락을 채택할 근거를 단독으로 만들지 않는다.

테스트는 먼저 <code>build/reports/measurements/room-10c.json</code>을 재생성한다. 이 문서의 수치에 사용한 동일 결과는 [room-10c.json](results/room-10c/room-10c.json)으로 버전 관리한다. 파일의 SHA-256은 Git canonical blob bytes 기준 <code>474614CDED38E3608C1EE606E29C63297E8146F192934177E692D4D9026751CF</code>이다. 작업 트리의 CRLF 파일을 직접 해시한 값은 다를 수 있다. 아래 표는 이 보존 원자료에서 계산했으며, 재실행하면 응답시간과 PostgreSQL 비용은 달라질 수 있다.

## fixture와 측정 방식

공통 원자료 형식과 산식은 [ROOM-10a 측정 계약](room-10-measurement-contract.md)을 따른다.

- 고정 시각은 2026-08-07T00:00:00Z이며, 명령의 Clock과 상태 보정 요청 시각은 모두 시작 시각으로 고정한다.
- 상태 보정은 시작 시각을 명시적으로 전달해 실행한다.
- 두 요청은 test-only read gate에서 같은 초기 ROOM version을 읽은 사실을 확인한 뒤 진행한다.
- test-only commit-order gate가 두 작업의 확정 순서를 정한다. 실제 상태 보정 coordinator와 실제 command service를 호출하며, production Bean·Repository 구현은 바꾸지 않는다.
- 각 시나리오·순서마다 준비 round 1회와 실측 round 3회를 실행한다. fixture 생성과 준비 round는 응답시간·PostgreSQL 비용에서 제외한다.
- 실측마다 <code>pg_stat_statements_reset()</code> 뒤 호출 수, 실행 시간, 행 수, shared buffer hit/read를 수집한다. 배경 scheduler·알림 relay·채팅 retention은 꺼 둔다.

실측 파일의 환경은 다음과 같다.

| 항목 | 값 |
| --- | --- |
| 기준 SHA | <code>2b6a101275f8cbd3298fd5157b5d93b08802d7c5</code> |
| Java | 21.0.11 |
| PostgreSQL | 18.4 (Debian 18.4-1.pgdg13+1) |
| 호스트 | Windows 11, CPU 24개 |
| PostgreSQL image | postgres:18.4 |
| fixture seed | ROOM-10C-20260807 |
| 동시 요청 수준 | 2 |
| 시작 heap / 최대 heap | 147,201,712 B / 536,870,912 B |

## 2026-08-07 실행 결과

T5는 여덟 시나리오·순서 조합을 각각 3회씩, 총 24개 실측 round와 48개 요청으로 기록했다. 아래 응답시간은 한 조합의 6개 요청(3 round × 2 요청)을 합쳐 계산한 ms 단위 p50 / p95 / max다. p95는 nearest-rank 방식으로 계산했다. PostgreSQL 실행 시간은 round별 total execution time의 ms 단위 p50 / p95 / max다. ms 표시는 소수점 다섯째 자리에서 반올림해 소수점 넷째 자리까지 기록한다.

| 시나리오 | 확정 순서 | 요청 결과 (성공 / 업무 / 동시성 / 기술) | 충돌 / 재시도 / 소진 | 응답시간 ms (p50 / p95 / max) | PostgreSQL 비용 |
| --- | --- | ---: | ---: | ---: | --- |
| 직접 참가 | 상태 보정 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 18.3796 / 20.6138 / 20.6138 | calls 7, rows 3, hit 16–43, read 0; exec 0.1993 / 0.2100 / 0.2100 ms |
| 직접 참가 | 명령 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 13.2407 / 21.7614 / 21.7614 | calls 7, rows 3, hit 16, read 0; exec 0.1682 / 0.2293 / 0.2293 ms |
| 대기 등록 | 상태 보정 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 12.6345 / 18.3840 / 18.3840 | calls 7–8, rows 3–5, hit 13–32, read 0; exec 0.2277 / 0.3163 / 0.3163 ms |
| 대기 등록 | 명령 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 15.2492 / 17.3742 / 17.3742 | calls 7, rows 3, hit 13, read 0; exec 0.1940 / 0.2513 / 0.2513 ms |
| 참가 취소·자동 승격 | 상태 보정 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 10.2697 / 15.3684 / 15.3684 | calls 6, rows 4, hit 13, read 0; exec 0.2052 / 0.2464 / 0.2464 ms |
| 참가 취소·자동 승격 | 명령 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 9.7595 / 17.1334 / 17.1334 | calls 6, rows 4, hit 13, read 0; exec 0.2457 / 0.2959 / 0.2959 ms |
| 대기 취소 | 상태 보정 먼저 | 3 / 3 / 0 / 0 | 0 / 0 / 0 | 9.6560 / 14.8777 / 14.8777 | calls 6, rows 4, hit 19, read 0; exec 0.1767 / 0.2654 / 0.2654 ms |
| 대기 취소 | 명령 먼저 | 6 / 0 / 0 / 0 | 0 / 0 / 0 | 11.5473 / 16.7838 / 16.7838 | calls 6, rows 4, hit 19, read 0; exec 0.3063 / 0.3356 / 0.3356 ms |

이 fixture에서는 모든 실측의 낙관 락 충돌·재시도·소진이 0이었다. 이는 정한 확정 순서에서 시작 뒤 잘못된 상태가 남지 않았다는 결과이지, 자연 발생 동시 요청에서도 충돌이 없다는 일반화가 아니다. 특히 이 시나리오는 시작 시각을 넘긴 뒤의 업무 규칙 검증이 결과를 결정할 수 있으므로, 수치만으로 락 전략을 유지하거나 바꾸면 안 된다.

T1~T4는 각 순서의 업무 결과와 최종 상태도 별도로 검증한다.

| 테스트 계약 | 상태 보정 먼저 | 명령 먼저 | 최종 검증 |
| --- | --- | --- | --- |
| 직접 참가 | ROOM_NOT_RECRUITING 업무 실패 | ROOM_NOT_RECRUITING 업무 실패 | 시작 뒤 ACTIVE 참가 없음 |
| 대기 등록 | WAITLIST_NOT_AVAILABLE 업무 실패 | WAITLIST_NOT_AVAILABLE 업무 실패 | WAITING 없음, 기존 대기 EXPIRED |
| 참가 취소·자동 승격 | INVALID_ROOM_STATUS_TRANSITION 업무 실패 | INVALID_ROOM_STATUS_TRANSITION 업무 실패 | 자동 승격 없음, 기존 대기 EXPIRED |
| 대기 취소 | WAITLIST_ENTRY_NOT_FOUND 업무 실패 | 두 요청 성공 | 확정된 CANCELED 또는 EXPIRED를 덮어쓰지 않음 |

T7은 24개 실측 round마다 PostgreSQL에서 ROOM이 CLOSED이고, 저장 인원 수와 ACTIVE 참가 수가 일치하며, 정원 범위·중복 ACTIVE 참가·WAITING·시작 뒤 ACTIVE 및 PROMOTED 대기가 없음을 확인한다. T8은 재시도 소진, 충돌 뒤 업무 실패, 기술 실패를 분리해 현행 오류 우선순위가 유지되는지도 검증한다.

## 보존 원자료와 수준별 비교 입력

T6은 다음 버전 관리 원자료를 직접 읽는다. 두 파일은 모두 동일한 이전 측정 코드 SHA에서 생성됐지만, 이번 시작 경계 실행의 SHA·고정 시각·fixture와 다르다. 따라서 아래 수치는 같은 필드와 산식을 사용하는 비교 입력일 뿐, 서로 다른 시나리오·환경의 절대 성능 비교가 아니다.

| 보고서 | 원자료 SHA | 측정 코드 SHA | 수준 | round | 시나리오 |
| --- | --- | --- | --- | ---: | --- |
| [room-10a.json](results/room-10a/room-10a.json) | E2007E34F3CB30E95078FD6A5BD52E59F83882436429348EFBC3B85BA4B30781 | <code>b75903219c552628a42e071f8e2cd0ba97d8a767</code> | 2, 4, 8 | 9 | 마지막 좌석 동시 참가 |
| [room-10b.json](results/room-10b/room-10b.json) | D60BFA4984D9105B604D1CD748C4DD07DAA445FAF7EDBA8837FE5A125A0F7529 | <code>b75903219c552628a42e071f8e2cd0ba97d8a767</code> | 2, 4, 8 | 21 | 마지막 좌석·대기·자동 승격·대기 취소 |
| [room-10c.json](results/room-10c/room-10c.json) | 474614CDED38E3608C1EE606E29C63297E8146F192934177E692D4D9026751CF | <code>2b6a101275f8cbd3298fd5157b5d93b08802d7c5</code> | 2 | 24 | 시작 경계의 네 명령과 두 확정 순서 |

이전 원자료의 수준별 집계는 다음과 같다. 응답시간은 각 수준의 모든 요청에서 구한 ms p50 / p95 / max다. room-10b의 수준 2는 다섯 시나리오를 포함하지만 수준 4·8은 참가 취소·자동 승격만 포함하므로, room-10b의 세 행을 동일한 시나리오 분포로 해석하지 않는다.

| 보고서·수준 | 요청 | 성공 / 업무 / 동시성 / 기술 | 충돌 / 재시도 / 소진 | 응답시간 ms (p50 / p95 / max) |
| --- | ---: | ---: | ---: | ---: |
| room-10a · 2 | 6 | 3 / 3 / 0 / 0 | 3 / 3 / 0 | 41.7445 / 46.9434 / 46.9434 |
| room-10a · 4 | 12 | 3 / 9 / 0 / 0 | 9 / 9 / 0 | 35.9911 / 43.9970 / 43.9970 |
| room-10a · 8 | 24 | 3 / 21 / 0 / 0 | 21 / 21 / 0 | 45.6804 / 53.0951 / 53.9005 |
| room-10b · 2 | 30 | 24 / 6 / 0 / 0 | 3 / 3 / 0 | 20.9758 / 57.1507 / 58.5063 |
| room-10b · 4 | 12 | 9 / 0 / 3 / 0 | 18 / 15 / 3 | 56.1721 / 98.8761 / 98.8761 |
| room-10b · 8 | 24 | 11 / 0 / 13 / 0 | 51 / 38 / 13 | 98.4441 / 132.2131 / 144.3832 |

## 후속 결정 경계

ROOM-10-AC5와 AC6의 잠금 전략 결정은 이 기준선 결과를 검토한 뒤 별도 DECISION_NEEDED로 제시해야 한다. 그 결정에는 동일 환경·동일 fixture에서 비교할 후보, 수용하지 않을 비용·복구 조건, 그리고 유지 또는 변경 판단을 ADR에 남길 기준이 필요하다. 이 기준선 PR은 그 결정을 대신하거나 비관적 락 비교 구현을 시작하지 않는다.

## 재현 명령

Docker daemon 접근을 먼저 확인한다.

~~~powershell
docker version
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStartBoundaryConcurrencyBaselinePostgresTest.*" --rerun --fail-fast
~~~

명령이 성공하면 <code>build/reports/measurements/room-10c.json</code>을 생성한다. 결과를 갱신할 때는 같은 JSON을 <code>docs/measurements/results/room-10c/room-10c.json</code>으로 보존하고, 문서의 SHA-256·실행 환경·수치·해석 경계를 함께 갱신한다.
