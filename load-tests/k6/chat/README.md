# 채팅 계약 검증과 용량 측정

이 디렉터리는 애플리케이션 릴리스 SHA와 함께 고정되는 k6 시나리오와 fixture의 정본이다. 실행과 AWS 로그 수집은 `albam-mate-infra`의 `run.sh loadtest`가 담당한다.

시나리오는 파일 하나에 하나씩 두고, 공유 상수·지표·헬퍼는 `lib/chat.js`에 모은다. 실행기가 도메인 폴더째 옮기므로 시나리오는 같은 폴더의 lib 를 import 한다.

계약 시나리오 일부는 한 파일이 여러 case 를 담고 `K6_CHAT_CASE`로 고른다.

## 무엇에 답하려는 측정인가

채팅은 메시지를 HTTP로 받아 PostgreSQL에 커밋한 뒤, Redis Pub/Sub 신호를 받은 각 인스턴스가 PostgreSQL을 다시 읽어 WebSocket으로 전달한다. 이 구조는 신호가 유실돼도 메시지를 잃지 않는 대신 **전달 한 건마다 데이터베이스 읽기가 붙는다.**

이 측정은 그 비용이 어디서 한계에 닿는지를 축별로 찾는다.

- 전송·조회·연결을 각각 밀었을 때 **어느 축이 먼저 무너지는가**
- 방 하나의 구독자가 늘 때 **전달 지연이 어떻게 변하는가**
- 활성 방이 늘 때 **Pub/Sub 채널 수가 비용이 되는가**

산출물은 합격·불합격이 아니라 **먼저 무너지는 축과 그 시점의 조건**이다.

[ADR-0051](../../../docs/adr/platform/0051-p1-self-managed-aws-infrastructure.md)은 네 역할을 모두 `t4g.micro`로 두고 어느 역할이 먼저 한계에 닿는지 보기로 했고, 성공 기준에 "세션을 유지한 채 WebSocket을 열고 채팅을 보내는 부하"를 조회 부하와 구분해 언급한다. 이 측정이 그 항목을 채운다.

### 이 측정이 답하지 않는 것

**인스턴스 간 전달을 재지 않는다.** 시나리오가 App1·App2를 고정 route로 나누지 않으므로, 발신자와 구독자가 서로 다른 인스턴스에 붙었을 때의 Redis Pub/Sub 경유 지연은 이 결과에 분리되어 있지 않다. `cross-instance` mode가 있으나 route 고정 설정이 필요해 실행 대상이 아니다.

**Redis 장애 중 동작을 재지 않는다.** `redis-recovery` mode는 실행 중 Redis stop/start 수동 개입이 필요하다.

**절대 처리량이 아니다.** 모든 수치는 `t4g.micro`와 DB 커넥션 풀 8개 구성에 묶인다.

## 부하 기준선

계단의 시작점은 목표 규모가 아니라 **이미 통과가 확인된 수준**에서 잡는다. 첫 단계부터 한계를 넘으면 계단이 의미를 잃는다.

| 축 | 시작점 | 근거 |
| --- | --- | --- |
| 전송 | 1건/초 | 이전 측정에서 동시 7건이 p95 727ms로 SLO 750ms 직전이었다 |
| 동시 연결 | 5개 | 같은 측정에서 연결 7개가 안정적이었다 |
| 이력 조회 | 1건/초 | 읽기 경로는 여유가 예상되므로 낮게 시작해 배로 올린다 |
| 방 하나의 구독자 | 2명 | 팬아웃 비용을 최소 조건부터 본다 |
| 활성 방 | 1개 | 방 수만 변수로 남긴다 |

이 표는 측정 결과가 아니라 **계단 설계의 출발점**이며, 실측이 쌓이면 갱신한다.

## 테스트 종류

부하 시나리오는 계단마다 목표를 올리고 `stage` 태그로 단계별 지표를 따로 담는다.

| 종류 | 파일 | 올리는 축 | 계단 |
| --- | --- | --- | --- |
| **전송 처리량** | `load-throughput.js` | 초당 메시지 전송 건수 | 1 → 2 → 3 → 4 → 5건/초 |
| 동시 접속 | `load-connections.js` | 동시 WebSocket 연결 수 | 5 → 10 → 20 → 40 → 80개 |
| 이력 조회 | `load-history.js` | 초당 이력 조회 요청 수 | 1 → 2 → 4 → 8건/초 |
| 팬아웃 | `load-fanout.js` | 방 하나의 구독자 수 | 2 → 4 → 8 → 16 → 24명 |
| 활성 방 수 | `load-rooms.js` | 동시에 활성인 방 수 | 1 → 2 → 4 → 8개 |
| 혼합 | `load-mixed.js` | 전송·조회·연결을 같은 배수로 | 1 → 2 → 3 → 4배 |

**각 축은 하나만 변수로 남긴다.** 팬아웃이 전송률을 방 제한 아래로 고정하는 것이 그 예다. 전송률이 제한을 넘으면 429가 섞여 느려진 이유가 구독자 수인지 제한인지 구분할 수 없다.

`load-throughput.js`는 fixture 방이 늘어도 쓰는 방 수를 `K6_LOAD_THROUGHPUT_ROOMS`로 고정한다. 수신자가 방 수를 따라 늘면 팬아웃 부하가 전송 처리량 축에 섞인다.

계약 검증은 부하를 올리지 않고 동작만 확인한다. 부하 시나리오 전에 배포가 정상인지 보는 용도다.

| 파일 (case) | 확인 대상 |
| --- | --- |
| `send-contract.js` (`send`) | 메시지 전송 |
| `send-contract.js` (`idempotent-retry`) | 같은 식별자 재전송 시 동일 messageId |
| `history-contract.js` | 커서 페이징 정합 |
| `websocket-contract.js` (`fanout`) | 구독자 전원 수신 |
| `rate-limit-contract.js` (`user`) | 사용자 전송 제한 |
| `rate-limit-contract.js` (`room`) | 방 전송 제한 |
| `websocket-contract.js` (`reconnect`) | 재연결 후 누락 복구 |
| `websocket-contract.js` (`idle`) | 유휴 WebSocket 유지 |
| `cross-instance-contract.js` | 인스턴스 간 전달 (route 고정 설정 필요) |
| `redis-recovery.js` | Redis 재기동 후 복구 (수동 개입 필요) |

계약 검증과 용량 측정을 한 결과로 합치지 않는다. 계약 검증은 작은 입력의 정확성을 판정하고, 용량 측정은 입력 조건과 결과 곡선을 기록한다.

## fixture와 격리

`fixtures/rooms.sql`이 방·계정·참가 관계·채팅 이력을 만들고 `fixtures/cleanup.sql`이 지운다. 두 SQL은 psql 변수를 받는다.

| 변수 | 쓰는 곳 | 뜻 |
| --- | --- | --- |
| `run_id` | 둘 다 | 실행 격리 키. 소문자·숫자·`._-`만 쓴다 |
| `room_count` | rooms | 만들 방 수 (1~100) |
| `accounts_per_room` | rooms | 방마다 만들 계정 수 (7~11). 1명은 호스트, 나머지는 참가자 |
| `messages_per_room` | rooms | 방마다 넣을 채팅 메시지 수 (0~5000) |
| `password_hash` | rooms | 계정 비밀번호의 bcrypt 해시. `{bcrypt}` 접두사가 필요하다 |
| `password` | rooms | 위 해시의 평문. 마지막 `SELECT`가 내보내는 fixture에만 쓴다 |

값은 임시 파라미터 테이블의 `CHECK` 제약으로 실행 전에 검사한다. 범위를 벗어나면 데이터를 만들지 않고 중단한다.

`run_id`가 방 제목과 계정 이메일에 들어가므로 같은 데이터베이스에서 여러 실행이 겹쳐도 서로의 데이터를 지우지 않는다. 정리할 때도 그 실행의 것만 지운다.

방 `start_at`은 30일 뒤로 잡는다. **지난 시각이면 상태 보정이 방을 FINISHED로 바꿔 채팅이 닫히고 전송이 403이 된다.**

호스트는 `participations` 행을 갖지 않고 `active_participant_count`에도 들어가지 않는다. `Room.create`가 0에서 시작해 참가자마다 증가시키는 규칙과 같다.

`rooms.sql`의 마지막 `SELECT`가 k6 credential fixture(JSON)를 내보낸다. **실제 비밀번호가 담기므로 저장소에 커밋하지 않는다.** 실행기는 이 출력을 파일로 받아 `K6_CHAT_FIXTURE`에 넘긴다.

| Run | fixture 규모 |
| --- | --- |
| 계약 검증 | 방 2개·계정 18개면 충분하다 |
| `load-rooms.js` | 마지막 계단만큼 방이 필요하다. 계단이 8이면 방 8개 |
| 그 외 부하 | 방 8개·계정 72개를 공식 규모로 쓴다 |

`load-rooms.js`는 fixture 방이 마지막 계단보다 적으면 실행 전에 중단한다. 방이 모자라면 계단이 겹쳐 무의미해진다.

인증 IP 제한은 `run_id`와 무관하게 부하 발생기 IP의 10분 창을 공유한다. setup이 계정 수만큼 로그인하므로 서버 로그인 한도를 그보다 크게 올려야 한다.

## 로컬 예행

운영 실행 전에 로컬에서 스크립트가 도는지 먼저 확인한다. 로컬 실행 명령은 [docs/COMMANDS.md](../../../docs/COMMANDS.md)를 따른다.

**로컬은 동작 확인용이며 용량 근거가 아니다.** 로컬에는 CPU credit도 인스턴스 간 네트워크도 없다. 여기서 나온 지연·처리량은 `t4g.micro` 결과를 대신하지 못한다.

### fixture 적용

```bash
docker compose --env-file .env -f compose.local.yml cp load-tests/k6/chat/fixtures/rooms.sql postgres:/tmp/rooms.sql
```

```bash
docker compose --env-file .env -f compose.local.yml exec -T postgres \
  bash -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v run_id=local-smoke -v room_count=3 -v accounts_per_room=9 -v messages_per_room=150 -v password_hash="{bcrypt}<해시>" -v password="<평문>" -f /tmp/rooms.sql'
```

마지막 `SELECT` 출력을 파일로 받아 `K6_CHAT_FIXTURE`에 넘긴다. 끝나면 같은 `run_id`로 `cleanup.sql`을 적용한다.

`rooms.sql`은 멱등하다. 같은 `run_id`로 다시 적용하면 계정 비밀번호만 갱신하고 방·참가 관계·메시지는 중복 생성하지 않는다.

### 무엇을 어떤 조건으로 예행하는가

| 대상 | 로컬 실행 조건 |
| --- | --- |
| 계약 3종 (`send-contract.js` 두 case, `history-contract.js`) | 그대로 실행한다. `K6_CHAT_VUS`를 계정 수보다 작게 고정한다 |
| `rate-limit-contract.js` | 제한을 일부러 건드리므로 Run 사이에 쿨다운을 기다리거나 Redis를 비운다 |
| 부하 6종 | `K6_LOAD_STEP_DURATION=20s`로 줄여 계단이 도는지만 본다. 이 값의 결과는 용량 근거가 아니다 |

`websocket-contract.js`의 `idle` case 는 유휴 연결을 10분 유지하므로 로컬 예행에서 제외한다.

## 필수 환경 변수

| 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `K6_CHAT_CASE` | 파일마다 다름 | 한 파일이 여러 case 를 담을 때 고른다 |
| `K6_BASE_URL` | — | 대상 서버 주소 |
| `K6_ORIGIN` | `K6_BASE_URL` | CSRF·WebSocket Origin 헤더 |
| `K6_CHAT_FIXTURE` | — | `rooms.sql`이 내보낸 credential fixture 경로. **필수** |
| `K6_LOGIN_LIMIT` | `30` | 스크립트 쪽 로그인 횟수 가드. 서버 한도와 같게 준다 |

계단 조정용 변수다. 기본값이 공식 계단이며 바꾸면 결과를 다른 Run과 비교할 수 없다.

| 변수 | 기본값 |
| --- | --- |
| `K6_LOAD_STEP_DURATION` | `2m` |
| `K6_LOAD_WARMUP_DURATION` | `30s` |
| `K6_LOAD_SEND_RATES` | `1,2,3,4,5` |
| `K6_LOAD_CONNECTION_STEPS` | `5,10,20,40,80` |
| `K6_LOAD_HISTORY_RATES` | `1,2,4,8` |
| `K6_LOAD_FANOUT_SUBSCRIBER_STEPS` | `2,4,8,16,24` |
| `K6_LOAD_FANOUT_SEND_RATE` | `2` |
| `K6_LOAD_ROOM_STEPS` | `1,2,4,8` |
| `K6_LOAD_ROOM_SUBSCRIBERS` | `3` |
| `K6_LOAD_ROOM_SEND_RATE` | `1` |
| `K6_LOAD_MIXED_SCALES` | `1,2,3,4` |
| `K6_LOAD_SUBSCRIBERS_PER_ROOM` | `6` |
| `K6_LOAD_THROUGHPUT_ROOMS` | `3` |

## 용량 측정 공통 가드

setup이 fixture 계정 수만큼 로그인하므로 **서버 로그인 한도를 그보다 크게 올린 뒤에만** 용량 Run을 시작한다. 방 8개·계정 72개면 기본 30회/10분에 setup 중간에 막힌다.

```bash
ALBAM_MATE_LOGIN_LIMIT=300 bash run.sh deploy
```

값이 실제 컨테이너에 들어갔는지 확인한다. compose의 `environment`에 없으면 `--env-file`만으로는 전달되지 않는다.

`K6_LOGIN_LIMIT`은 스크립트 쪽 가드이며 서버 한도와 같은 값을 준다. 두 값이 어긋나면 스크립트가 먼저 막거나, 막지 못한 채 서버에서 실패한다.

## 실행

인프라 저장소에서 실행한다. 시드·측정·정리가 한 사이클로 돈다.

```bash
K6_LOGIN_LIMIT=300 \
  bash run.sh loadtest <경로>/load-tests/k6/chat/load-throughput.js \
  --seed-sql <경로>/load-tests/k6/chat/fixtures/rooms.sql \
  --cleanup-sql <경로>/load-tests/k6/chat/fixtures/cleanup.sql \
  --sql-var run_id=<실행 키> --sql-var room_count=8 --sql-var accounts_per_room=9 \
  --sql-var messages_per_room=150 \
  --sql-var password_hash='{bcrypt}<해시>' --sql-var password='<평문>'
```

`--cleanup-sql`은 중간에 끊겨도 실행된다. 실행기가 `EXIT`·`INT`·`TERM`에서 정리를 건다.

## 결과 확인

원시 결과는 `build/k6/chat/` 아래에 남긴다. `--summary-export`로 뽑은 JSON과 콘솔 로그가 여기에 들어간다. `build/`는 `.gitignore` 대상이라 추적되지 않는다.

부하 시나리오는 요약의 계단별 지표부터 읽는다.

| 지표 | 뜻 |
| --- | --- |
| `load_stage_http_ms{stage:N}` | N단계의 전송·조회 응답 시간 |
| `load_stage_send_ok{stage:N}` | N단계의 전송 성공률 |
| `load_stage_delivery_ms{stage:N}` | N단계의 메시지 전달 지연 |
| `load_stage_connect_ms{stage:N}` | N단계의 WebSocket 연결 시간 |
| `load_stage_opened{stage:N}` | N단계의 연결 성립률 |

k6는 threshold를 선언한 태그 조합만 요약에 남기므로, 항상 통과하는 조건을 붙여 값만 확보한다. 표본이 없는 단계는 0으로 나오므로 **0과 "측정 안 됨"을 구분해서 읽는다.**

## 결과 판정

- **계약 Run**: 계약 불일치 0건이어야 한다. 첫 실패에 즉시 중단한다.
- **Run 무효**: fixture 준비 실패, 로그인 한도 초과로 setup 미완, 필수 지표 누락, 부하 발생기 지속 포화 중 하나다. 이런 Run은 경계 계산에 쓰지 않는다.
- **부하 Run의 판정 기준은 SLO 통과가 아니다.** 부하 mode는 관찰형 게이트라 임계를 넘어도 끝까지 돈다. 계단별 지표로 **한계점을 유효하게 관측했으면 `PASS`**로 본다.
- **한계점**은 성공률이 100%에서 떨어지거나 응답 p95가 직전 단계 대비 크게 뛰는 첫 단계다. 두 신호가 같은 단계에서 나오면 그 단계를 경계로 기록한다.
- `dropped_iterations`가 크면 부하 발생기가 목표 부하를 못 냈다는 뜻이라 **그 구간의 지연 값은 신뢰할 수 없다.** 발생기 CPU·VU 포화와 함께 본다.
- 전달 지연은 유효한 서버 표본이 충분할 때만 비교한다. 표본이 수십 건 수준이면 p95를 근거로 쓰지 않는다.
- 축별로 따로 잰 상한을 합쳐 "견딘다"를 판정하지 않는다. `load-mixed`가 각 축의 상한보다 일찍 무너지는 것이 그 근거다.
- 모든 Run은 release SHA, 이미지 digest, fixture 규모와 시나리오 환경 변수를 결과와 함께 보관한다.

## 측정 문서

비밀값과 실제 환경의 리소스 식별자를 지우고 검증을 마친 결과만 [k6 부하테스트 결과 문서](../../../docs/measurements/k6/README.md) 아래에 보존한다. 탐색·반복 실행의 원시 산출물은 `build/k6/chat/`에 둔다.

| 캠페인 | 문서 |
| --- | --- |
| `chat-delivery-20260811T172123KST` | [채팅 전송·전달 AWS 용량 측정](../../../docs/measurements/k6/chat-delivery-capacity-2026-08-11.md) · [campaign manifest](../../../docs/measurements/k6/evidence/chat-delivery-capacity-2026-08-11.json) |
