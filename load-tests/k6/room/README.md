# ROOM k6 부하테스트

이 디렉터리는 ROOM 핵심 HTTP 시나리오의 fixture bundle과 k6 소스를 제공한다. 실제 공식 측정은 고객 데이터가 없는 Terraform 부하테스트 환경에서만 수행한다. 여기의 로컬 검증은 fixture, manifest, 인증, 문법 계약 확인용이며 성능 수치나 운영 SLO 증거가 아니다.

## 최종 분류와 적용 부하

### Scenario 01

`01-room-cancel-promotion.js`의 최종 분류는 `write-contention`이다. Stress는 필수이고 Spike는 권장하며 Soak은 제외한다. hot/spread와 VU 2/4/8 wave를 적용한다.

### Scenario 02

`02-room-waitlist-registration.js`의 최종 분류는 `write-contention`이다. Stress는 필수이고 Spike는 권장하며 Soak은 제외한다. hot/spread와 VU 2/4/8 wave를 적용한다.

### Scenario 03

`03-room-read-due-backlog.js`의 최종 분류는 `read-write-contention`이다. Stress는 필수이고 Spike는 선택이며 Soak은 제외한다. endpoint별 지속 VU 또는 급격 ramp를 적용한다.

### Scenario 04

`04-room-detail-by-role.js`의 최종 분류는 `read-load`다. Stress는 필수이고 Spike는 선택이며 Soak은 추후 권장한다. role별 지속 VU 또는 급격 ramp를 적용한다.

### Scenario 05

`05-room-waitlist-position.js`의 최종 분류는 `data-scale-low-contention-comparison`(데이터 증가·저경합 비교)이다. Stress는 선택이고 Spike는 불필요하며 Soak은 후순위다. Stress를 실행할 때는 constant VU 1만 사용한다.

01~05의 manifest에는 `classification.category`, `classification.loadProfiles`, 실제 `configuration.loadProfile`이 함께 기록된다. k6 scenario와 custom metric에는 `test_classification`, `load_profile` 태그가 붙는다. 05는 `stress`를 기록하되 `data-scale-low-contention-comparison`과 `constant-vus-1`을 유지하므로 동시성 부하 결과처럼 해석하지 않는다.

### 이번 campaign 적용 범위

이번 campaign은 01/02의 Stress+Spike, 03의 Stress+Spike, 04의 Stress+Spike만 실행한다. 04의 Soak은 `future-recommended` 분류를 보존하되 이번 campaign에서는 실행하지 않는다. 05는 Stress만 VU 1로 실행하며 Spike는 불필요하고 Soak은 `low-priority` 분류를 보존한 채 이번 campaign에서 제외한다.

01/02의 Spike는 warm-up 0회와 measure 1회의 단일 동시 burst이고, 03/04의 Spike는 1초 ramp-up과 1초 ramp-down을 명시한다. 05는 profile에 따라 VU를 늘리지 않으며 언제나 constant VU 1로 데이터 규모만 비교한다.

## fixture bundle 만들기

Node.js 20 이상에서 저장소 루트를 기준으로 실행한다. bundle 출력은 Git이 무시하는 `build/k6/room/` 아래만 허용한다. bundle에는 `scenario.js`, `common.js`, `manifest.json`, `users.json`, `prepare.sql`, `verify.sql`, `k6-vars.json`, `source-metadata.json`이 생긴다.

공식 정본은 01/02의 mode × VU × load profile마다 **별도 bundle**을 만드는 것이다. 한 bundle에 여러 mode/VU를 넣는 기능은 개발 편의용이며 공식 비교 결과로 합산하지 않는다.

```sh
node load-tests/k6/room/tools/prepare-fixture.mjs cancel-promotion \
  --seed room-cancel-stress-hot-vu2 \
  --load-profile stress --modes hot --levels 2 \
  --output build/k6/room/cancel-stress-hot-vu2

node load-tests/k6/room/tools/prepare-fixture.mjs cancel-promotion \
  --seed room-cancel-spike-hot-vu2 \
  --load-profile spike --modes hot --levels 2 \
  --output build/k6/room/cancel-spike-hot-vu2
```

`--load-profile stress`의 01/02 기본값은 warm-up 1 wave와 measure 10 waves다. `--load-profile spike`는 warm-up 0 wave와 measure 1 wave의 단일 burst로 고정된다. hot은 같은 ROOM에 요청을 모으고, spread는 같은 요청 수를 서로 다른 ROOM에 분산한다.

03은 endpoint마다 새 fixture를 만든다. `room-list`와 `my-rooms`는 같은 bundle이나 실행 결과를 공유하지 않는다.

```sh
node load-tests/k6/room/tools/prepare-fixture.mjs due-backlog-read \
  --seed due-room-list-clean-vu2 \
  --endpoint room-list --due-room-count 0 --vus 2 \
  --load-profile stress --duration 1m --think-time-seconds 1 \
  --output build/k6/room/due-room-list-clean-vu2

node load-tests/k6/room/tools/prepare-fixture.mjs due-backlog-read \
  --seed due-my-rooms-10000-vu8 \
  --endpoint my-rooms --due-room-count 10000 --vus 8 \
  --load-profile spike --duration 1m --think-time-seconds 1 \
  --output build/k6/room/due-my-rooms-10000-vu8
```

03의 공식 matrix는 endpoint × VU 2/4/8 × due ROOM 0(clean)/20/2,000/10,000이다. target endpoint의 사전 warm-up과 pre-measure probe는 due backlog를 먼저 보정할 수 있으므로 금지한다. my-rooms는 VU-local 첫 loop에서 로그인 session만 준비하고, public은 HTTP 없이 warm-up marker만 처리한다. 유효 상태 확인은 post-run `verify.sql`의 effective-status 검증에 맡긴다.

04는 role public/host/participant와 active participant 1/10을 각각 따로 만든다.

```sh
node load-tests/k6/room/tools/prepare-fixture.mjs room-detail \
  --seed detail-host-active10-stress \
  --role host --active-participant-count 10 \
  --load-profile stress --duration 1m \
  --output build/k6/room/detail-host-active10-stress

node load-tests/k6/room/tools/prepare-fixture.mjs room-detail \
  --seed detail-participant-active10-spike \
  --role participant --active-participant-count 10 \
  --load-profile spike --duration 1m \
  --output build/k6/room/detail-participant-active10-spike
```

상세 checker는 공개 응답에 `myRole`, `place`, `host`, `participants`가 없는지 확인한다. 관계자 응답은 host=`HOST`, participant=`JOINED`, `participantCount=active+1`, `remainingRecruitmentSeats=10-active`, `participants.length=active+1`을 확인한다.

05는 큐 길이만 비교하기 위해 `vus`를 1로 고정한다.

```sh
node load-tests/k6/room/tools/prepare-fixture.mjs waitlist-position \
  --seed queue-10000-middle-stress \
  --queue-length 10000 --position middle \
  --load-profile stress \
  --output build/k6/room/queue-10000-middle-stress
```

05의 표준 큐 길이는 10/100/1,000/10,000이다. position은 `head`, `middle`, `tail`을 지원한다. middle의 기대 순번은 `ceil(N/2)`이므로 N=10이면 5다. 이 시나리오는 stress profile이라도 VU 1을 고정하며, profile별 동시 VU 증가는 만들지 않는다. 각 05 `verify.log`의 같은 순번 조회 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`에서 planning time, execution time, actual rows, shared hit blocks, shared read blocks를 추출해 데이터 규모별로 비교한다.

## Terraform 부하테스트 환경에서 실행

`albam-mate-infra`의 ROOM bundle runner가 반영된 상태에서 bundle 하나를 실행한다.

```sh
./run.sh loadtest ../albam-mate/build/k6/room/cancel-stress-hot-vu2
```

runner는 fixture 적재, k6 실행, `verify.sql` 불변식 대조, artifact 회수 순서로 동작한다. fixture 적재 시간은 k6 측정 구간에 포함하지 않는다. `users.json`과 `prepare.sql`에는 실행 전용 비밀번호가 있으므로 Git이나 결과 artifact에 복사하지 않는다.

각 실행 artifact는 `run-metadata.json`의 `runId`·Campaign ID `room-k6-YYYYMMDDTHHmmssKST`·k6 실행 구간과 `run-result.json`의 `measurementWindow`·Run 상태 `PASS`/`FAIL`/`INVALID`·`reportDisposition` `included`/`excluded`으로 서로 연결한다. `run-result.json`은 runner가 회수한 prepare/k6/verify exit code만으로 최종 판정을 기록하며, 원시 로그나 비밀값을 복사하지 않는다. 인프라 관측물은 같은 결과 디렉터리의 `cloudwatch-capacity.json`, `database-lock-samples.ndjson`, `observation-status.json`으로 보관한다.

## 결과 지표와 gate

`room_request_duration_ms`는 measure ROOM 요청의 지연 분포이며 관찰 지표다. `room_measured_requests`는 measure 요청 수와 처리량이며 관찰 지표다. `room_success_responses`와 `room_conflict_responses`는 기대 성공과 계약된 동시 수정 409의 관찰 지표다. `room_unexpected_4xx_responses`와 `room_5xx_responses`는 예상 밖 오류 분포의 관찰 지표다.

`room_unexpected_response_rate`는 예상하지 않은 HTTP 응답 비율이며 `rate==0`이어야 한다. `room_measurement_check_rate`는 measure 응답 payload 계약 충족률이며 `rate==1`이어야 한다.

builtin `checks{phase:measure}` threshold는 사용하지 않는다. 이전 실제 실행에서 해당 tag 표본이 0인 상태로 threshold가 실패한 이력이 있으므로 모든 measure-response checker는 명시적으로 `room_measurement_check_rate`에 기록한다. 03~05는 VU별 첫 iteration을 warm-up으로 처리해 `sessionFor` 로그인과 첫 요청이 measure 표본에 섞이지 않게 한다.

## 보고서와 증거 경계

실제 실행 뒤에는 [REPORT_TEMPLATE.md](REPORT_TEMPLATE.md)를 채워 artifact와 관측 시각을 묶은 뒤 승인된 결과만 [k6 결과 문서 정본](../../../docs/measurements/k6/README.md)으로 승격한다. 이 README는 과거의 원시 실행 로그나 미승인 성능 숫자를 재사용하지 않는다.

k6 결과만으로 Hikari pending, JVM GC, DB CPU, DB lock을 추정하지 않는다. 같은 campaign 시간대의 `cloudwatch-capacity.json`, `database-lock-samples.ndjson`, `observation-status.json`을 함께 보관한다. 승인된 애플리케이션 관측 endpoint가 없으면 `observation-status.json`에 Hikari pending을 `unavailable`과 그 사유로 남긴다.

## 로컬 정적 검증

```sh
node --test load-tests/k6/room/tools/fixture.test.mjs
node --check load-tests/k6/room/common.js
node --check load-tests/k6/room/01-room-cancel-promotion.js
node --check load-tests/k6/room/02-room-waitlist-registration.js
node --check load-tests/k6/room/03-room-read-due-backlog.js
node --check load-tests/k6/room/04-room-detail-by-role.js
node --check load-tests/k6/room/05-room-waitlist-position.js
```
