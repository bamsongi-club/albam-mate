# ROOM k6 부하테스트

이 디렉터리는 [#578](https://github.com/bamsongi-club/albam-mate/issues/578)의 ROOM 핵심 HTTP 부하 시나리오와 재현 가능한 fixture 생성기를 제공한다. 공식 측정은 고객 데이터가 없는 Terraform 부하테스트 환경에서 `albam-mate-infra`의 runner로 수행한다. 로컬 실행은 스크립트·인증·fixture smoke 확인용이며 운영 성능 수치로 사용하지 않는다.

## 시나리오

| 파일 | 주제 | 기본 profile | 핵심 판정 |
| --- | --- | --- | --- |
| `01-room-cancel-promotion.js` | 참가 취소와 자동 승격의 동시성 | hot/spread, VU 2/4/8, warm-up 1 wave + 실측 10 waves | 성공 취소 수와 승격 수 일치, 정원, FIFO, 중복 ACTIVE 없음 |
| `02-room-waitlist-registration.js` | 최초 대기 등록의 동시성 | hot/spread, VU 2/4/8, warm-up 1 wave + 실측 10 waves | 성공 수와 WAITING 수 일치, 사용자 중복 없음, queue order 중복 없음 |
| `03-room-read-due-backlog.js` | 상태 보정 backlog가 있는 목록 조회 | endpoint별 due 20/2,000, VU 2/4/8 | HTTP 지연·충돌, 최종 ROOM 상태와 WAITING 만료 |
| `04-room-detail-by-role.js` | 공개·주최자·참가자 상세 조회 | 역할별 활성 참가자 1/10, 10 VU, 1분 | 역할별 end-to-end 지연과 응답 계약, 조회 중 저장 상태 불변 |
| `05-room-waitlist-position.js` | 대기열 길이에 따른 순번 조회 | 10/100/1,000명, head/tail, 10 VU, 1분 | 지연 증가 기울기, 정확한 순번, 대기열 불변 |

`hot`은 여러 사용자가 같은 ROOM을 변경하고 `spread`는 같은 요청 수를 서로 다른 ROOM에 분산한다. 두 결과의 차이로 ROOM version 경합과 DB·connection pool 같은 공용 병목을 구분한다.

## 409와 재요청 계약

동시 명령의 `409 ROOM_CONCURRENT_MODIFICATION`은 서버가 내부 낙관 락 재시도 3회를 소진한 관찰 결과다. k6는 같은 요청을 다시 보내지 않는다. 재요청하면 한 사용자의 업무 요청이 여러 번 실행되어 원래 경합 강도와 성공 수를 해석하기 어려워지기 때문이다.

측정 응답은 `2xx`, 위 계약의 `409`, 그 밖의 `4xx`, `5xx`로 분리한다. `409` 비율 자체에는 초기 합격선을 두지 않고 기록한다. `5xx`, 예상하지 않은 `4xx`, 응답 계약 위반, 실행 후 DB 불변식 위반은 테스트 실패다.

## fixture bundle 만들기

Node.js 20 이상에서 저장소 루트를 기준으로 실행한다. 출력은 반드시 Git이 무시하는 `build/k6/room/` 아래에 생성된다.

```sh
node load-tests/k6/room/tools/prepare-fixture.mjs cancel-promotion \
  --seed room-cancel-20260811 \
  --output build/k6/room/cancel-promotion

node load-tests/k6/room/tools/prepare-fixture.mjs waitlist-registration \
  --seed room-waitlist-register-20260811 \
  --output build/k6/room/waitlist-registration
```

위 두 bundle은 기본값으로 hot/spread와 VU 2/4/8 전체를 순차 실행한다. 취소 시나리오는 ROOM 정원 상한 때문에 VU 10을 넘길 수 없다. 대기 등록의 VU 16 탐색은 기본 로그인 제한(부하 발생기 IP당 10분에 30회)을 넘지 않도록 `--levels 16 --modes hot`처럼 한 mode씩 분리한다. VU 32는 로그인 제한 조정이나 세션 준비 계약이 먼저 필요하므로 현재 runner의 지원 profile에서 제외한다.

조회 시나리오는 비교 조건마다 별도 bundle을 만든다.

```sh
node load-tests/k6/room/tools/prepare-fixture.mjs due-backlog-read \
  --endpoint room-list --due-room-count 20 --vus 2 \
  --seed due-room-list-20-vu2 \
  --output build/k6/room/due-room-list-20-vu2

node load-tests/k6/room/tools/prepare-fixture.mjs room-detail \
  --role participant --active-participant-count 10 \
  --seed detail-participant-10 \
  --output build/k6/room/detail-participant-10

node load-tests/k6/room/tools/prepare-fixture.mjs waitlist-position \
  --queue-length 1000 --position tail \
  --seed waitlist-position-1000-tail \
  --output build/k6/room/waitlist-position-1000-tail
```

due backlog의 `/api/rooms`와 `/api/users/me/rooms`는 반드시 각각 새 fixture로 실행한다. 첫 성공 요청이 전역 backlog를 보정하므로 두 endpoint를 한 fixture에서 동시에 실행하면 두 지연을 비교할 수 없다. fixture는 ROOM-09d와 같이 due ROOM을 `RECRUITING`/`CLOSED`로 반씩 만들고, 각 `CLOSED` ROOM에 `WAITING` 10명을 둔다. 따라서 due 20개는 `CLOSED` 10개와 WAITING 총 100건을 포함한다. 준비 단계에서 due·WAITING 수를 확인하고 실행 뒤에는 ROOM이 `CLOSED`/`FINISHED`, 대기가 `EXPIRED`로 전환됐는지 검증한다. 10,000 due ROOM은 기본 측정이 아니라 선택 stress profile이다.

bundle에는 다음 파일이 생긴다.

| 파일 | 역할 |
| --- | --- |
| `scenario.js`, `common.js` | 실행 시점의 k6 소스 복사본 |
| `manifest.json` | ROOM·사용자 대상과 부하 profile |
| `users.json` | 로그인 계정과 실행 전용 임의 비밀번호 |
| `prepare.sql` | 이전 `ROOM-K6:` fixture 정리와 현재 fixture 적재 |
| `verify.sql` | HTTP 성공 수와 DB 결과·도메인 불변식 대조 |
| `k6-vars.json` | runner가 허용한 k6 환경 변수 |
| `source-metadata.json` | source commit·SHA-256·fixture 건수 |

`users.json`과 `prepare.sql`에는 실행 전용 비밀번호가 있으므로 Git에 추가하거나 결과물에 복사하지 않는다. 생성기는 POSIX 환경에서 두 파일을 mode `0600`으로 만들고 runner도 원격 파일을 `0600`으로 강제한다. Windows 로컬 bundle은 현재 사용자만 접근할 수 있는 작업 경로에 둔다. marker가 없는 기존 디렉터리는 삭제하지 않는다. fixture 정리는 `ROOM-K6:` 제목과 `room-k6-*@example.invalid` 계정으로 식별한 데이터만 대상으로 한다. 이 작업은 별도 부하테스트 환경을 독점해서 실행한다.

## Terraform 부하테스트 환경에서 실행

먼저 `albam-mate-infra`에서 Terraform 배포와 inventory 준비를 끝낸다. 그 저장소 루트에서 app 저장소의 bundle 디렉터리를 넘긴다.

```sh
./run.sh loadtest ../albam-mate/build/k6/room/cancel-promotion
```

runner는 다음 순서를 지킨다.

1. PostgreSQL에 `prepare.sql`을 적용한다.
2. 별도 c7g.large load generator에서 `scenario.js`를 실행한다.
3. k6의 실측 성공 수를 PostgreSQL `verify.sql`에 넘겨 불변식을 검사한다.
4. k6 summary·stdout/stderr와 SQL 로그를 로컬 `.run/results/<run-id>/`로 회수한 뒤 원격 임시 파일을 지운다.

각 bundle 실행 전에 fixture가 다시 적재되며 적재 시간은 k6 측정 구간에 포함되지 않는다. 마지막에는 Terraform 환경을 내리면 테스트 계정과 데이터도 함께 폐기된다.

## 결과 읽기

| 지표 | 의미 |
| --- | --- |
| `room_request_duration_ms` | 로그인과 warm-up을 제외한 ROOM 요청의 p50(`med`)·p95·p99·max |
| `room_measured_requests` | 실측 요청 수와 초당 요청 수 |
| `room_success_responses` | 시나리오가 기대한 `200` 또는 `201` |
| `room_conflict_responses` | `409 ROOM_CONCURRENT_MODIFICATION` |
| `room_unexpected_4xx_responses` | 계약에 없는 `4xx` |
| `room_5xx_responses` | 서버 오류 |
| `room_unexpected_response_rate` | 예상하지 않은 응답 비율. 반드시 0이어야 함 |

k6만으로 Hikari connection, JVM heap·GC, DB CPU·lock을 수집할 수는 없다. 같은 실행 시간대의 CloudWatch·애플리케이션 로그와 PostgreSQL 관측 자료를 함께 보존한다. ROOM-09/10의 PostgreSQL 측정값은 fixture와 경합 profile을 설계하는 참고선이며 HTTP RPS나 운영 SLO로 환산하지 않는다.

## 로컬 검증

fixture 생성기와 모든 JavaScript 문법을 확인한다.

```sh
node --test load-tests/k6/room/tools/fixture.test.mjs
node --check load-tests/k6/room/common.js
node --check load-tests/k6/room/01-room-cancel-promotion.js
node --check load-tests/k6/room/02-room-waitlist-registration.js
node --check load-tests/k6/room/03-room-read-due-backlog.js
node --check load-tests/k6/room/04-room-detail-by-role.js
node --check load-tests/k6/room/05-room-waitlist-position.js
```

로컬에 k6가 있다면 생성한 bundle에서 init 단계 계약도 확인한다.

```sh
k6 inspect \
  -e K6_MANIFEST_FILE=build/k6/room/cancel-promotion/manifest.json \
  -e K6_USERS_FILE=build/k6/room/cancel-promotion/users.json \
  build/k6/room/cancel-promotion/scenario.js
```
