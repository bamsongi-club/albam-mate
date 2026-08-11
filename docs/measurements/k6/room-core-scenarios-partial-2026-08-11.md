# ROOM 핵심 HTTP k6 부분 측정 보고서 (2026-08-11 23:24~23:57 KST)

## 결론

- Campaign ID: `room-k6-20260811T142439Z`
- 상태: `partial`
- 근거 식별자: [campaign manifest](evidence/room-core-scenarios-partial-2026-08-11.json)
- 포함한 원시 실행은 5개다. 참가 취소·자동 승격과 최초 대기 등록의 기본 hot/spread·VU 2/4/8 bundle, 그리고 due backlog 목록 조회의 `room-list / due 20 / VU 2·4·8`만 유효하게 끝났다.
- 포함 Run은 모두 k6 종료 코드 `0`, summary의 check 실패 `0`, 예상하지 않은 응답 비율 `0`, SQL 불변식 검증 통과로 끝났다. 동시 명령 시나리오의 `409 ROOM_CONCURRENT_MODIFICATION`은 계약상 허용한 응답으로 별도 기록했다.
- 이 보고서는 전용 Terraform 부하테스트 환경의 부분 결과다. 운영 트래픽, 운영 SLO, 용량 한계, hot/spread·VU별 병목 차이는 이 수치로 결론 내리지 않는다.

## 측정 조건

| 항목 | 값 |
| --- | --- |
| 앱 소스 | `1d549673e30723999892f737783c53a27b3e70c3` |
| ROOM load-test tree | Git object `8583a57eab18c0c16e2344d7a8274de0d911ea52` |
| 실행 환경 | 고객 데이터가 없는 전용 Terraform 부하테스트 환경 |
| 측정 구간 | 2026-08-11 23:24:39~23:57:31 KST (첫 포함 Run ID 시작~마지막 `verify.log` 기록 시각) |
| 실행 순서 | fixture `prepare.sql` 적용 → 별도 load generator의 k6 → `verify.sql` 불변식 검증 |
| 원자료 | sibling `albam-mate-infra/.run/results/<run-id>/`, `local-only` |

원시 bundle의 `users.json`·`prepare.sql`, 실제 ROOM·사용자 식별자, URL·AWS 식별자와 원시 로그는 비밀번호 또는 실제 환경 식별자를 포함할 수 있어 이 저장소에 복사하지 않았다. 포함 Run별 원자료 파일의 SHA-256과 안전한 요약은 campaign manifest에 기록했다.

## 포함 범위와 결과

`room_request_duration_ms`는 로그인과 warm-up을 제외한 ROOM 요청 지연이다. 첫 두 bundle은 여섯 profile을 순차 실행하지만 k6 summary가 bundle 전체로 합산되므로 profile별 수치가 아니다.

| 시나리오·조건 | Run ID | 실측 요청 | 기대 2xx | 허용 409 | p50 / p95 / p99 | 판정 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 취소·자동 승격, hot/spread·VU 2/4/8 | `20260811T142439Z-1095` | 280 | 221 | 59 | 108.224 / 193.008 / 222.066 ms | PASS |
| 최초 대기 등록, hot/spread·VU 2/4/8 | `20260811T143655Z-14` | 280 | 221 | 59 | 70.512 / 102.146 / 116.232 ms | PASS |
| due backlog, `room-list / due 20 / VU 2` | `20260811T144707Z-398` | 118 | 118 | 0 | 20.965 / 30.523 / 51.603 ms | PASS |
| due backlog, `room-list / due 20 / VU 4` | `20260811T145132Z-1442` | 237 | 237 | 0 | 17.252 / 20.584 / 25.068 ms | PASS |
| due backlog, `room-list / due 20 / VU 8` | `20260811T145435Z-1521` | 479 | 479 | 0 | 15.444 / 20.592 / 38.393 ms | PASS |

## SQL 불변식 대조

| Run ID | 검증 결과 |
| --- | --- |
| `20260811T142439Z-1095` | HTTP 성공 221건, 취소 221건, 자동 승격 221건 |
| `20260811T143655Z-14` | HTTP 성공 221건, DB WAITING 221건 |
| `20260811T144707Z-398` | due ROOM 20개·RECRUITING 10개·CLOSED 10개·WAITING 100건 보존, scheduler lock 유지 |
| `20260811T145132Z-1442` | due ROOM 20개·RECRUITING 10개·CLOSED 10개·WAITING 100건 보존, scheduler lock 유지 |
| `20260811T145435Z-1521` | due ROOM 20개·RECRUITING 10개·CLOSED 10개·WAITING 100건 보존, scheduler lock 유지 |

## 포함하지 않은 범위와 해석 한계

- due backlog는 전체 기본 12개 조합 중 `room-list / due 20 / VU 2·4·8` 세 조건만 포함한다. `room-list / due 2,000`과 `my-rooms`의 due 20·2,000 조건은 포함하지 않았다.
- 상세 조회 역할별 시나리오와 대기 순번 시나리오는 유효 실행 결과가 없다.
- 취소·대기 등록 bundle은 hot/spread·VU 2/4/8을 모두 실행했지만, 현재 summary는 bundle 합산값뿐이다. mode·VU별 지연이나 경합 차이를 주장할 수 없다.
- CloudWatch, JVM heap·GC, Hikari connection, PostgreSQL CPU·lock, 앱 이미지·infra revision과 반복 실행 증거가 이 campaign에 함께 기록되지 않았다. 따라서 병목 원인, 용량 경계, 운영 SLO를 이 보고서에서 판단하지 않는다.
- 이 문서에 포함하지 않은 후속 재실행 산출물은 이 campaign의 근거가 아니며, 위 표와 manifest의 계산에 사용하지 않는다.

후속 측정은 새 Campaign ID와 새 manifest로 기록한다. 현재 부분 결과를 전체 매트릭스 결과로 덮어쓰거나, 원시 결과만으로 운영 수치를 계산하지 않는다.
