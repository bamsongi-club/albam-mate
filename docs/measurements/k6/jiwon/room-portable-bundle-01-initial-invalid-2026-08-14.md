# [01/05] ROOM portable bundle 초기 매트릭스 — INVALID (2026-08-14)

## 결론

공식 25개 조합을 모두 실행했고, 각 실행은 fixture 준비 단계에서 `INVALID`가 되었다. 따라서 k6 부하 생성, HTTP 지연시간, RPS, 오류율, DB 사후 진단은 시작되지 않았고 성능 수치를 보고할 수 없다. 25개 Run은 모두 성능 경계 계산에서 제외한다.

- Campaign ID: `room-k6-matrix-2026-08-14`
- 캠페인 종료 상태: [`completed-with-limitations`](README.md)
- 측정 증거 판정: `INVALID`
- 문서 상태: [`superseded`](README.md)
- 기록 분류: `invalid-measurement-campaign` — 성능 기준선·용량 판단에서 제외
- 문서 인덱스: [Jiwon k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/room-portable-bundle-01-initial-invalid-2026-08-14.json)
- 대체 관계: 02 snapshot 재실행 전 오류 이력

공통 원인은 실행 당시 fixture SQL의 `users` INSERT 열 수와 VALUES 표현식 수가 맞지 않아 `prepare=3`으로 종료된 것이다. 이 결과는 준비 단계 결함을 재현한 유효한 오류 기록이지만 성능 측정 결과는 아니다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-14 12:21:08~12:42:27 / KST 2026-08-14 21:21:08~21:42:27 |
| source / 배포 release | `3c21c1e69a214d6033e341c04cc111ef81e90c06` / 동일 revision, 정렬 gate 통과 |
| 실행 범위 | T1 6개·T2 7개·T3 3개·T4 3개·T5 6개, 총 25개를 각각 한 번 실행 |
| runner | ROOM portable bundle → infra `run.sh room-k6`; generic `loadtest` 제외 |
| 실행 환경 | clean app checkout, 전용 P1 stack, Windows/Git Bash 전달 경로 |
| 원자료 | 로컬 `build/k6/room/`만 보존; 비밀값·실환경 URL·실제 fixture/resource 식별자는 Git에 기록하지 않음 |

## 실행 이력과 판정

모든 시각은 원격 실행 artifact 기준이다. `prepare=3`은 PostgreSQL이 fixture SQL을 거절한 exit code다.

| # | 시나리오 | 프로파일 / 조건 | UTC 시작–종료 | KST 시작–종료 | 판정 | 근거 |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | T1 | stress / hot / concurrency 2 | 2026-08-14 12:21:08–12:21:42 | 2026-08-14 21:21:08–21:21:42 | `INVALID` | prepare=3 |
| 2 | T1 | stress / hot / concurrency 4 | 2026-08-14 12:26:06–12:26:33 | 2026-08-14 21:26:06–21:26:33 | `INVALID` | prepare=3 |
| 3 | T1 | stress / hot / concurrency 8 | 2026-08-14 12:26:39–12:27:04 | 2026-08-14 21:26:39–21:27:04 | `INVALID` | prepare=3 |
| 4 | T1 | stress / spread / concurrency 2 | 2026-08-14 12:27:10–12:27:36 | 2026-08-14 21:27:10–21:27:36 | `INVALID` | prepare=3 |
| 5 | T1 | stress / spread / concurrency 4 | 2026-08-14 12:27:41–12:28:07 | 2026-08-14 21:27:41–21:28:07 | `INVALID` | prepare=3 |
| 6 | T1 | stress / spread / concurrency 8 | 2026-08-14 12:28:12–12:28:37 | 2026-08-14 21:28:12–21:28:37 | `INVALID` | prepare=3 |
| 7 | T2 distinct | stress / hot / concurrency 2 | 2026-08-14 12:28:43–12:29:09 | 2026-08-14 21:28:43–21:29:09 | `INVALID` | prepare=3 |
| 8 | T2 distinct | stress / hot / concurrency 4 | 2026-08-14 12:29:16–12:29:47 | 2026-08-14 21:29:16–21:29:47 | `INVALID` | prepare=3 |
| 9 | T2 distinct | stress / hot / concurrency 8 | 2026-08-14 12:29:58–12:30:31 | 2026-08-14 21:29:58–21:30:31 | `INVALID` | prepare=3 |
| 10 | T2 distinct | stress / spread / concurrency 2 | 2026-08-14 12:30:42–12:31:15 | 2026-08-14 21:30:42–21:31:15 | `INVALID` | prepare=3 |
| 11 | T2 distinct | stress / spread / concurrency 4 | 2026-08-14 12:31:27–12:32:03 | 2026-08-14 21:31:27–21:32:03 | `INVALID` | prepare=3 |
| 12 | T2 distinct | stress / spread / concurrency 8 | 2026-08-14 12:32:15–12:32:48 | 2026-08-14 21:32:15–21:32:48 | `INVALID` | prepare=3 |
| 13 | T2 duplicate | spike / hot / concurrency 2 | 2026-08-14 12:33:00–12:33:33 | 2026-08-14 21:33:00–21:33:33 | `INVALID` | prepare=3 |
| 14 | T3 | stress / natural race | 2026-08-14 12:33:45–12:34:18 | 2026-08-14 21:33:45–21:34:18 | `INVALID` | prepare=3 |
| 15 | T3 | spike / wait-first | 2026-08-14 12:34:30–12:35:04 | 2026-08-14 21:34:30–21:35:04 | `INVALID` | prepare=3 |
| 16 | T3 | spike / cancel-first | 2026-08-14 12:35:16–12:35:49 | 2026-08-14 21:35:16–21:35:49 | `INVALID` | prepare=3 |
| 17 | T4 | stress / last seat / concurrency 2 | 2026-08-14 12:36:01–12:36:34 | 2026-08-14 21:36:01–21:36:34 | `INVALID` | prepare=3 |
| 18 | T4 | stress / last seat / concurrency 4 | 2026-08-14 12:36:46–12:37:19 | 2026-08-14 21:36:46–21:37:19 | `INVALID` | prepare=3 |
| 19 | T4 | stress / last seat / concurrency 8 | 2026-08-14 12:37:30–12:38:04 | 2026-08-14 21:37:30–21:38:04 | `INVALID` | prepare=3 |
| 20 | T5 | stress / public / scale 1 | 2026-08-14 12:38:15–12:38:49 | 2026-08-14 21:38:15–21:38:49 | `INVALID` | prepare=3 |
| 21 | T5 | stress / host / scale 1 | 2026-08-14 12:39:00–12:39:33 | 2026-08-14 21:39:00–21:39:33 | `INVALID` | prepare=3 |
| 22 | T5 | stress / participant / scale 1 | 2026-08-14 12:39:45–12:40:17 | 2026-08-14 21:39:45–21:40:17 | `INVALID` | prepare=3 |
| 23 | T5 | stress / public / scale 10 | 2026-08-14 12:40:28–12:41:00 | 2026-08-14 21:40:28–21:41:00 | `INVALID` | prepare=3 |
| 24 | T5 | stress / host / scale 10 | 2026-08-14 12:41:10–12:41:42 | 2026-08-14 21:41:10–21:41:42 | `INVALID` | prepare=3 |
| 25 | T5 | stress / participant / scale 10 | 2026-08-14 12:41:54–12:42:27 | 2026-08-14 21:41:54–21:42:27 | `INVALID` | prepare=3 |

T5 비교 gate도 2026-08-14 12:42:27 UTC (21:42:27 KST)에 실행했다. 유효한 여섯 fixture가 하나도 없어 `FAIL`로 종료했으며, 이는 성능 실패가 아니라 위 준비 단계 오류의 후속 결과다.

## 원인과 후속 수정

실행 당시 fixture SQL의 `users` INSERT 열 수와 VALUES 표현식 수가 맞지 않았다. 생성기는 `email`, `password_hash`, `nickname` 세 값만 만들지만 INSERT 대상에는 `created_at`, `updated_at`도 포함했다. PostgreSQL은 `INSERT has more target columns than expressions`로 거절했고, 25개 Run의 `prepare` phase exit code가 모두 `3`이었다.

- `users` fixture 행마다 `created_at`, `updated_at`에 `clock_timestamp()`를 넣도록 SQL 생성기를 수정했다.
- 생성된 users SQL에 user 수만큼 timestamp 쌍이 있는지 확인하는 회귀 테스트를 추가했다.
- 이 수정은 과거 25개 Run을 유효한 성능 결과로 바꾸지 않는다. 새 release와 clean checkout 정렬, 별도 campaign 재실행이 필요하며 이번 수정 후 AWS 재실행은 하지 않았다.

## 해석과 한계

- 이번 실행은 모든 공식 조합의 동일한 준비 결함을 증명했지만, 어떤 조합의 성능도 측정하지 못했다.
- Windows/Git Bash 전달을 위한 로컬 infra 호환성 보정은 당시 infra working tree에만 있었고 이 보고서의 app release에는 포함되지 않았다.
- manifest의 local-only artifact digest는 이후 로컬 원자료의 변경 여부만 확인하며, 이 Git 저장소만으로 원자료 bundle을 독립 재구성할 수는 없다.

## 다음 측정 조건

- 새 source/release 정렬을 만들고 clean checkout에서 새 bundle을 생성해 25개 매트릭스를 별도 campaign으로 다시 실행한다.
- 재측정 전 최소 gate는 `prepare` 성공, before/after diagnosis 존재, k6 summary 존재, T5 여섯 fixture와 comparison 통과다.

## 재현

현재 실행 절차는 [ROOM k6 실행](../../../../load-tests/k6/jiwon/README.md#실행)과 [Terraform 원격 실행 bundle](../../../../load-tests/k6/jiwon/README.md#terraform-원격-실행-bundle)을 따른다. 이 문서는 수정 전 source의 오류 이력이므로 현재 source에서 동일 실패를 재현하는 명령을 별도로 제공하지 않는다.

## 원자료와 teardown

Run ledger와 artifact 무결성 식별값은 [campaign manifest](evidence/room-portable-bundle-01-initial-invalid-2026-08-14.json)에 있다. 원시 bundle과 실행 산출물은 로컬 `build/k6/room/`에만 보존한다.

P1 teardown은 이 보고서 작성 직후 완료했다. 최종 검증 시각은 2026-08-14 12:56:30 UTC (2026-08-14 21:56:30 KST)다.

| 확인 대상 | 결과 |
| --- | --- |
| `run.sh down` | exit code `0` |
| Terraform P1 state | `0` resources |
| 실행 중/중지 중 EC2 | `0` |
| stack-tagged EBS volume | `0` |
| stack-tagged EIP | `0` |
| P1 CloudWatch alarm / dashboard | `0` / `0` |
| 이번 release backend / web ECR tag | `0` / `0` (두 tag 삭제 완료) |
| P1 SSM parameter / Advanced tier | `0` / `0` |

Resource Groups Tagging API는 stack tag를 가진 21개 mapping(이전 instance 5, volume 7, subnet 1, security-group rule 8)을 계속 반환했다. 그러나 직접 EC2/EBS/EIP 조회와 Terraform state는 모두 0이므로, 이를 실행 중이거나 저장 비용이 발생하는 P1 resource로 해석하지 않았다.

이번 실행이 만든 P1 compute, block storage, public IP, 임시 transfer bucket을 포함한 Terraform-managed resource와 release image tag는 정리됐다. 다만 공유 bootstrap repository/state 저장소와 이미 존재하던 계정 공통 resource는 이 teardown 범위 밖이므로, 이 문서는 계정 전체의 비용이 0이라는 주장까지는 하지 않는다.
