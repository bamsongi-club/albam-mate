# ROOM 핵심 HTTP k6 실행 결과 보고서 (2026-08-11 23:24~2026-08-12 02:47 KST)

## 결론

- Campaign ID: `room-k6-20260811T142439Z`
- 상태: `completed-with-limitations`
- 근거 식별자: [campaign manifest](evidence/room-core-scenarios-partial-2026-08-11.json)
- 논리 시나리오 5개를 모두 시도했다. 유효 실측 지표가 있는 PASS Run은 6개이고, 대기 순번 조회의 6개 조건은 k6 correctness threshold 실패로 FAIL이다.
- PASS Run은 참가 취소·자동 승격, 최초 대기 등록, due backlog `room-list / due 20 / VU 2·4·8`, ROOM 상세 `public / active 1 / VU 10`이다. 각 Run은 k6 종료 코드 `0`, summary check 실패 `0`, 예상하지 않은 응답 비율 `0`, SQL 불변식 검증 통과로 끝났다.
- 대기 순번 조회는 대기열 10·100·1,000과 head·tail을 모두 실행했다. 여섯 Run 모두 `phase:measure` check 표본이 0건이라 `rate==1` threshold를 통과하지 못했고 k6 종료 코드 `108`로 끝났다. DB `verify.sql`은 통과했지만 실측 요청 수와 지연 지표가 발행되지 않아 성능 결과로 사용할 수 없다.
- 이 보고서는 고객 데이터가 없는 전용 Terraform 부하테스트 환경의 실행 기록이다. 운영 트래픽, 운영 SLO, 용량 한계, hot/spread·VU별 병목 차이는 이 수치로 결론 내리지 않는다.

## 측정 조건

| 항목 | 값 |
| --- | --- |
| 앱 소스 | `1d549673e30723999892f737783c53a27b3e70c3` |
| ROOM load-test tree | Git object `8583a57eab18c0c16e2344d7a8274de0d911ea52` |
| 실행 환경 | 고객 데이터가 없는 전용 Terraform 부하테스트 환경 (`perf-jiwon`) |
| 측정 구간 | 2026-08-11 23:24:39~2026-08-12 02:47:03 KST (첫 포함 Run ID 시작~마지막 `verify.log` 기록 시각) |
| 실행 순서 | fixture `prepare.sql` 적용 → 별도 load generator의 k6 → `verify.sql` 불변식 검증 |
| 원자료 | sibling `albam-mate-infra/.run/results/<run-id>/`, `local-only` |

원시 bundle의 `users.json`·`prepare.sql`, 실제 ROOM·사용자 식별자, URL·AWS 식별자와 원시 로그는 비밀번호 또는 실제 환경 식별자를 포함할 수 있어 이 저장소에 복사하지 않았다. 포함 Run별 원자료 파일의 SHA-256과 안전한 요약은 campaign manifest에 기록했다.

## 포함 범위와 결과

`room_request_duration_ms`는 로그인과 warm-up을 제외한 ROOM 요청 지연이다. 첫 두 bundle은 여섯 profile을 순차 실행하지만 k6 summary가 bundle 전체로 합산되므로 profile별 수치가 아니다. `N/A`는 k6가 `phase:measure` 표본을 발행하지 않아 계산할 수 없다는 뜻이다.

| 시나리오·조건 | Run ID | 실측 요청 | 기대 2xx | 허용 409 | p50 / p95 / p99 | 판정 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 취소·자동 승격, hot/spread·VU 2/4/8 | `20260811T142439Z-1095` | 280 | 221 | 59 | 108.224 / 193.008 / 222.066 ms | PASS |
| 최초 대기 등록, hot/spread·VU 2/4/8 | `20260811T143655Z-14` | 280 | 221 | 59 | 70.512 / 102.146 / 116.232 ms | PASS |
| due backlog, `room-list / due 20 / VU 2` | `20260811T144707Z-398` | 118 | 118 | 0 | 20.965 / 30.523 / 51.603 ms | PASS |
| due backlog, `room-list / due 20 / VU 4` | `20260811T145132Z-1442` | 237 | 237 | 0 | 17.252 / 20.584 / 25.068 ms | PASS |
| due backlog, `room-list / due 20 / VU 8` | `20260811T145435Z-1521` | 479 | 479 | 0 | 15.444 / 20.592 / 38.393 ms | PASS |
| ROOM 상세, `public / active 1 / VU 10` | `20260811T164413Z-1699` | 600 | 600 | 0 | 10.296 / 13.702 / 25.104 ms | PASS |
| 대기 순번, `queue 10 / head / VU 10` | `20260811T173000Z-1805` | N/A | N/A | N/A | N/A | FAIL |
| 대기 순번, `queue 10 / tail / VU 10` | `20260811T173425Z-1441` | N/A | N/A | N/A | N/A | FAIL |
| 대기 순번, `queue 100 / head / VU 10` | `20260811T173733Z-1060` | N/A | N/A | N/A | N/A | FAIL |
| 대기 순번, `queue 100 / tail / VU 10` | `20260811T174009Z-322` | N/A | N/A | N/A | N/A | FAIL |
| 대기 순번, `queue 1,000 / head / VU 10` | `20260811T174245Z-2` | N/A | N/A | N/A | N/A | FAIL |
| 대기 순번, `queue 1,000 / tail / VU 10` | `20260811T174517Z-681` | N/A | N/A | N/A | N/A | FAIL |

## SQL 불변식 대조

| Run ID | 검증 결과 |
| --- | --- |
| `20260811T142439Z-1095` | HTTP 성공 221건, 취소 221건, 자동 승격 221건 |
| `20260811T143655Z-14` | HTTP 성공 221건, DB WAITING 221건 |
| `20260811T144707Z-398` | due ROOM 20개·RECRUITING 10개·CLOSED 10개·WAITING 100건 보존, scheduler lock 유지 |
| `20260811T145132Z-1442` | due ROOM 20개·RECRUITING 10개·CLOSED 10개·WAITING 100건 보존, scheduler lock 유지 |
| `20260811T145435Z-1521` | due ROOM 20개·RECRUITING 10개·CLOSED 10개·WAITING 100건 보존, scheduler lock 유지 |
| `20260811T164413Z-1699` | 상세 조회 fixture의 저장 상태 불변식 검증 통과 |
| 대기 순번 6개 Run | 각 대기열 길이와 대상 사용자의 기대 순번 보존 검증 통과 |

## 대기 순번 조회 실패 기록

여섯 Run 모두 fixture 준비과 `verify.sql`은 통과했지만, `checks{phase:measure}`가 `0 pass / 0 fail / value 0`이었다. 따라서 `rate==1` correctness threshold가 실패했고, `room_measured_requests`와 `room_request_duration_ms`가 summary에 발행되지 않았다. 예상하지 않은 응답 비율은 모두 `0`이지만, 측정 요청이 없으므로 성공이나 성능 지표로 해석하지 않는다.

## 범위와 해석 한계

- 논리 시나리오 5개는 모두 시도했지만, 전체 profile 매트릭스를 완료한 것은 아니다. due backlog는 `room-list / due 20 / VU 2·4·8`만, ROOM 상세는 `public / active 1 / VU 10`만 유효 결과가 있다.
- due backlog의 `room-list / due 2,000` fixture 준비 실패 두 건은 k6·검증 결과가 없어서 위 표와 manifest의 포함 Run에 넣지 않았다.
- 대기 순번 6개 조건은 모두 실패 기록으로 보존했다. 원인을 고치거나 재시도하려면 별도 변경·배포 승인과 새 Campaign ID가 필요하다.
- 취소·대기 등록 bundle은 hot/spread·VU 2/4/8을 모두 실행했지만, 현재 summary는 bundle 합산값뿐이다. mode·VU별 지연이나 경합 차이를 주장할 수 없다.
- CloudWatch, JVM heap·GC, Hikari connection, PostgreSQL CPU·lock, 앱 이미지·infra revision과 반복 실행 증거가 이 campaign에 함께 기록되지 않았다. 따라서 병목 원인, 용량 경계, 운영 SLO를 이 보고서에서 판단하지 않는다.

후속 재측정은 새 Campaign ID와 새 manifest로 기록한다. 현재 실패 결과를 성공 수치로 대체하거나, 원시 결과만으로 운영 수치를 계산하지 않는다.
