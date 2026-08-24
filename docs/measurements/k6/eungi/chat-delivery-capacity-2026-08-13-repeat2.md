# 채팅 전송·전달 AWS 용량 반복 측정 보고서 (2026-08-13 23:47:59~2026-08-14 01:03:43 KST)

## 결론

이 문서는 #608 WebSocket 유휴 timeout 분리가 포함된 동일 release에서 채팅 전송·전달 6개 축을 다시 완주하고, 연결 성립·세션 정상·전달 완료 관측을 분리해 기록한다.

- Campaign ID: `chat-delivery-capacity-2026-08-13-repeat2`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 문서 인덱스: [Eungi k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/chat-delivery-capacity-2026-08-13-repeat2.json)
- before 기준선: [chat-delivery-capacity-2026-08-13](chat-delivery-capacity-2026-08-13.md)
- 대체 관계: 동일 release 반복으로 `chat-delivery-capacity-2026-08-13`을 대체

- 부하 시나리오 6종이 모두 원자료와 함께 완주했다. 모든 포함 Run은 `reportDisposition=included`이다.
- threshold 관측 위반 Run은 3개다(`load-throughput`, `load-fanout`, `load-rooms`). `runner_exit=2`는 runner가 결과 회수 뒤 반환한 상태이며, Run 무효와 같은 뜻으로 쓰지 않는다.
- `dropped_iterations`는 포함 Run 모두 0이다.
- 6개 Run 모두 원자료와 fixture cleanup을 갖춘 유효 관측이다. load-throughput의 동시 handshake 병목은 같은 release에서 다시 재현됐고, load-connections의 단계적 80개 연결과 load-history는 threshold를 통과했다.
- 전달 완료 관측 실패와 fanout HTTP 실패는 throughput Hikari burst와 다른 조건에서 나왔다. 이 반복만으로 공통 원인이나 #608의 미해결 효과로 단정하지 않는다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | 2026-08-13 23:47:59~2026-08-14 01:03:43 KST (75분 44초) |
| App | t4g.micro 2대, Nginx + Spring, Hikari max 8 |
| PostgreSQL / Redis | PostgreSQL t4g.micro 1대 / Redis t4g.micro 1대 |
| 발생기 | c7g.large 1대, k6 1.3.0 |
| release SHA | `69438fd3a30150623e5801ff6bff5f4705b6a795` (fix: 유휴 WebSocket timeout 분리) |
| backend image | `sha256:9311e1dc14a57fb8314317c0d695b03733bb062fa14826f8505a2884c3933ab4` |
| web image | `sha256:9ec386df04a54c71ee5dd838067bb18c4fa2fd3e316d22175b52620e847a0200` |
| PostgreSQL image | `postgres@sha256:0826e5f2996099babb925e09fb72bf2c6eb5d187cfcae20aa9291af1612307e4` |
| Redis image | `redis@sha256:78b83aee0bf6781ca973ee5022de73dd16fe93f53593c3a31f079c8c3fa08921` |
| 로그인 제한 | App·k6 모두 300회/10분 |
| 상태 격리 | Run마다 fixture를 만들고 cleanup SQL로 삭제했다. schema 초기화는 하지 않았다. |
| runner | albam-mate-infra/run.sh loadtest |
| 실행 조건 | 각 Run의 manifest execution에 runner, k6 version, resolved scenario environment를 보존했다. |
| 원자료 | albam-mate-infra/.run/results/; teardown 뒤에도 local-only로 보존 |

**용어** — `p95`는 "100건 중 95건이 이 시간 안에 처리됐다"는 뜻이다.

## 무엇이 바뀌었나

이 문서는 chat-delivery-capacity-2026-08-13의 동일 release 반복이다. App 2대, PostgreSQL, Redis, c7g.large 발생기, Hikari max 8, 로그인 제한 300, fixture 8방 72계정 방당 150개 메시지와 계단을 유지했다.
기존 campaign에 없던 안전한 런타임 집계 snapshot을 시나리오 중후에 추가했다. 비밀값, 세션, 원문 로그를 보존하지 않고 Hikari, 채팅 counter, 컨테이너 lifecycle, Redis 구독 수만 담는다.

## 테스트 데이터

Run마다 방 8개·계정 72개·방당 메시지 150건을 [`fixtures/rooms.sql`](../../../../load-tests/k6/eungi/fixtures/rooms.sql)로 만들고, 생성된 ID registry를 기준으로 [`fixtures/cleanup.sql`](../../../../load-tests/k6/eungi/fixtures/cleanup.sql)로 지웠다. Run마다 `run_id`를 달리해 서로의 데이터를 건드리지 않았다.

## 부하 campaign

| # | 시나리오 | 시각 (KST) | 소요 | `dropped_iterations` | exit | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `load-throughput` | 2026-08-13 23:47:59~2026-08-14 00:02:07 | 14분 8초 | 0 | 2 | `FAIL` |
| 2 | `load-connections` | 2026-08-14 00:02:07~2026-08-14 00:15:26 | 13분 19초 | 0 | 0 | `PASS` |
| 3 | `load-history` | 2026-08-14 00:15:26~2026-08-14 00:26:28 | 11분 2초 | 0 | 0 | `PASS` |
| 4 | `load-fanout` | 2026-08-14 00:26:28~2026-08-14 00:40:12 | 13분 44초 | 0 | 2 | `FAIL` |
| 5 | `load-rooms` | 2026-08-14 00:40:12~2026-08-14 00:51:58 | 11분 46초 | 0 | 2 | `FAIL` |
| 6 | `load-mixed` | 2026-08-14 00:51:58~2026-08-14 01:03:43 | 11분 45초 | 0 | 0 | `PASS` |

`FAIL`은 threshold를 넘었다는 뜻이다. manifest의 `COMPLETED` 계열 실행 상태와 `reportDisposition=included`은 scenario 완주·원자료 사용 가능 여부를 뜻하며 Run 판정을 바꾸지 않는다.

### 전송 처리량 `load-throughput`

| 단계 | 목표 | 응답 p95 | 전송 성공률 | 전달 p95 |
| --- | --- | --- | --- | --- |
| 1 | 1건/초 | 58ms | 100.0% (120/120) | 56ms |
| 2 | 2건/초 | 51ms | 100.0% (180/180) | 51ms |
| 3 | 3건/초 | 47ms | **99.7% (299/300)** | 44ms |
| 4 | 4건/초 | 42ms | **99.8% (419/420)** | 41ms |
| 5 | 5건/초 | 44ms | 100.0% (615/615) | 41ms |

전송 성공률은 단계별 99.7~100%였지만 setup에서 동시에 만든 18개 WebSocket 중 8개만 열렸다. HTTP 전송 성공률만으로 이 축의 통과를 선언할 수 없다.
연결 시간 p95 약 30.34초와 Run 후 양 App의 Hikari timeout 및 CannotCreateTransaction 집계는 WebSocket handshake DB 접근 경로의 burst 병목과 일치한다.

### 동시 접속 `load-connections`

| 단계 | 동시 연결 | 연결 시간 p95 | 성립률 |
| --- | --- | --- | --- |
| 1 | 5개 | 37ms | 100.0% (5/5) |
| 2 | 10개 | 27ms | 100.0% (9/9) |
| 3 | 20개 | 29ms | 100.0% (19/19) |
| 4 | 40개 | 30ms | 100.0% (39/39) |
| 5 | 80개 | 31ms | 100.0% (21/21) |

5→80개로 단계적으로 올린 연결은 170/170이 성립하고 callback 완료 표본 97/97이 정상이다. 이는 동시 18개 burst와 다른 조건의 대조군이다.
opened 170과 정상 표본 97의 차이는 ramp-down에서 2분 timer가 중단된 73개이며 실패율로 합산하지 않는다.

### 이력 조회 `load-history`

| 단계 | 목표 | 조회 p95 | 성공률 |
| --- | --- | --- | --- |
| 1 | 1건/초 | 33ms | 100.0% (120/120) |
| 2 | 2건/초 | 29ms | 100.0% (180/180) |
| 3 | 4건/초 | 30ms | 100.0% (360/360) |
| 4 | 8건/초 | 26ms | 100.0% (841/841) |

이력 조회 단독 조건은 HTTP 실패 0건, 4개 단계 전부 100%로 끝났다. 혼합 부하나 WebSocket 전달 경로의 성공을 대신 증명하지 않는다.

### 팬아웃 `load-fanout`

| 단계 | 구독자 | 전달 p95 | 응답 p95 | 전송 성공률 |
| --- | --- | --- | --- | --- |
| 1 | 2명 | 30ms | 41ms | 100.0% (240/240) |
| 2 | 4명 | 30ms | 40ms | **98.8% (237/240)** |
| 3 | 8명 | 60ms | 40ms | **98.3% (236/240)** |
| 4 | 16명 | 109ms | 39ms | **98.8% (237/240)** |
| 5 | 24명 | 162ms | 41ms | **98.8% (238/241)** |

연결 성립 57/57, callback 완료 세션 정상 36/36, 전달 완료 36/36은 충족했다. 다만 HTTP 전송 실패 13건으로 threshold가 위반됐다.
세션과 전달 분모는 2분 callback을 완료한 36개이며 ramp-down에 중단된 나머지 연결과 구분한다.

### 활성 방 수 `load-rooms`

| 단계 | 활성 방 | 전체 전송 | 응답 p95 | 전송 성공률 | 연결 p95 | 전달 p95 | 구독자 전달 완료 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1개 | 1건/초 | 43ms | 100.0% (120/120) | 32ms | 28ms | **33.3% (1/3)** |
| 2 | 2개 | 2건/초 | 42ms | 100.0% (180/180) | 26ms | 27ms | **20.0% (1/5)** |
| 3 | 4개 | 4건/초 | 39ms | 100.0% (360/360) | 28ms | 31ms | **81.8% (9/11)** |
| 4 | 8개 | 8건/초 | 38ms | **99.9% (840/841)** | 27ms | 39ms | 100.0% (7/7) |

연결 성립 50/50과 callback 완료 세션 정상 28/28은 충족했지만 전달 완료는 19/28이다. 이는 연결 종료가 아니라 120초 관찰창에서 기대한 고유 메시지를 한 건도 받지 못한 표본이다.
Run 중 snapshot에서 Redis 채널 구독자 2와 App restart 0을 확인했지만 전달 조회 예외, 일시적 Pub/Sub 공백, 측정 시점 편향 중 어느 하나를 이번 Run만으로 확정하지 않는다.

### 혼합 `load-mixed`

| 단계 | 배수 | 응답 p95 | 전송 성공률 | 연결 성립률 | 연결 p95 | 전달 p95 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1배 | 38ms | 100.0% (360/360) | 100.0% (5/5) | 31ms | 29ms |
| 2 | 2배 | 37ms | 100.0% (540/540) | 100.0% (9/9) | 26ms | 39ms |
| 3 | 3배 | 36ms | 100.0% (900/900) | 100.0% (14/14) | 24ms | 40ms |
| 4 | 4배 | 36ms | 100.0% (1442/1442) | 100.0% (5/5) | 33ms | 40ms |

혼합 조건은 threshold를 통과했다. 연결 성립 55/55, callback 완료 세션 정상 37/37, 전달 완료 37/37이며 HTTP 실패는 0건이다.
이 결과는 혼합 부하의 이 한 반복에 한정되며 다른 Run의 전달 관측 실패를 반증하지 않는다.

## 실행 간 편차 — 이번에는 판정하지 않는다

동일 release의 8월 13일 본 campaign와 이 반복은 모두 throughput burst의 8/18 WebSocket 성립 실패와 약 30초 연결 지연을 보였다. fanout, rooms, mixed의 장기 구독 관측은 ramp-down callback 표본에 의존하므로 성공률을 직접 평균내지 않는다.
동일 release는 이번 본 campaign과 반복을 합쳐 2회지만, 개선·악화·절대 용량 경계를 판정하기 위한 최소 3회에는 이르지 못했다. 수정 후 같은 계단을 한 번 더 반복해 범위끼리 비교해야 한다.

## WebSocket 위반은 세 가지로 나뉜다

| 시나리오 | 연결 성립 | 세션 정상 | 전달 완료 | exit |
| --- | --- | --- | --- | --- |
| `load-throughput` | **44.4% (8/18)** | **44.4% (8/18)** | — | 2 |
| `load-connections` | 100.0% (170/170) | 100.0% (97/97) | — | 0 |
| `load-fanout` | 100.0% (57/57) | 100.0% (36/36) | 100.0% (36/36) | 2 |
| `load-rooms` | 100.0% (50/50) | 100.0% (28/28) | **67.9% (19/28)** | 2 |
| `load-mixed` | 100.0% (55/55) | 100.0% (37/37) | 100.0% (37/37) | 0 |

연결 성립·세션 정상·전달 완료는 같은 지표가 아니다. 각 Rate의 분모는 k6가 실제로 callback을 기록한 표본이며, ramp-down 때 중단된 timer callback은 그 표본에 포함되지 않을 수 있다. 따라서 이 표만으로 공통 원인이나 재연결·이력 복구를 단정하지 않는다.

## 최초 병목 — 동시 WebSocket handshake의 Hikari connection-pool self-starvation 재현

load-throughput setup은 18개 구독자를 동시에 시작했고 이번 반복에서도 WebSocket 성립이 8/18, ws_connecting p95가 약 30.34초였다. Run 후 20분 window 안전 집계에서 App1과 App2 각각 Hikari acquisition timeout 5건과 CannotCreateTransactionException 10건을 확인했다.
배포는 노드별 Hikari max 8이며 handshake access guard outer transaction 안에서 room status correction이 REQUIRES_NEW transaction을 연다. outer connection이 suspend 중에도 pool slot을 점유한 채 inner connection을 요구하므로 동시 burst에서 self-starvation이 가능하다. 이 관측은 30초 Hikari timeout과 Nginx 이전의 handshake 실패 경로를 직접 지지한다.
PostgreSQL 느린 쿼리나 락이 최초 촉발점인지와 rooms 전달 관측 실패의 직접 원인은 이번 snapshot만으로 확정하지 않는다.

### 안전한 런타임 관측

| 관측 artifact | 시각 (UTC) | App 상태 / Hikari |
| --- | --- | --- |
| `01-throughput-during.json` | 2026-08-13T14:52:07Z | running; Hikari active/idle/pending/max=0/8/0/8<br>running; Hikari active/idle/pending/max=0/8/0/8 |
| `01-throughput-late.json` | 2026-08-13T14:55:33Z | running; Hikari active/idle/pending/max=1/7/0/8<br>running; Hikari active/idle/pending/max=0/8/0/8 |
| `01-throughput-post.json` | 2026-08-13T15:04:34Z | running; Hikari active/idle/pending/max=0/8/0/8<br>running; Hikari active/idle/pending/max=0/8/0/8 |
| `02-connections-during.json` | 2026-08-13T15:10:24Z | running; Hikari active/idle/pending/max=0/8/0/8<br>running; Hikari active/idle/pending/max=0/5/0/8 |
| `03-history-during.json` | 2026-08-13T15:23:39Z | running; Hikari active/idle/pending/max=0/3/0/8<br>running; Hikari active/idle/pending/max=0/3/0/8 |
| `04-fanout-during.json` | 2026-08-13T15:32:05Z | running; Hikari active/idle/pending/max=0/4/0/8<br>running; Hikari active/idle/pending/max=1/2/0/8 |
| `05-rooms-during.json` | 2026-08-13T15:43:15Z | running; Hikari active/idle/pending/max=0/4/0/8<br>running; Hikari active/idle/pending/max=0/6/0/8 |
| `06-mixed-during.json` | 2026-08-13T15:55:53Z | running; Hikari active/idle/pending/max=0/4/0/8<br>running; Hikari active/idle/pending/max=0/4/0/8 |
| `06-mixed-post.json` | 2026-08-13T16:04:10Z | running; Hikari active/idle/pending/max=0/4/0/8<br>running; Hikari active/idle/pending/max=0/5/0/8 |

위 값은 snapshot 시점의 집계값이다. rolling log window count는 같은 컨테이너가 유지됐을 때에도 정확한 구간 delta와 같지 않을 수 있다.

## CPU

이번 campaign에서는 CloudWatch CPU 시계열을 보고서 수치로 검증·보존하지 않았다. 따라서 CPU가 병목이 아니었다고 판정하지 않는다.

## 한계

- 동일 release 2회 측정만으로 이전 release 대비 개선·악화나 절대 용량 경계를 단정하지 않는다.
- p95는 성공하거나 수신된 표본에 기반한다. `dropped_iterations`가 0이 아니면 해당 단계의 지연은 하한이다.
- WebSocket healthy·전달 완료의 분모는 setup 시점 태그와 VU ramp-down의 영향을 받을 수 있다.
- 원시 k6 summary의 `setup_data`에는 fixture 세션 또는 CSRF 정보가 있을 수 있다. 원문은 local-only로 유지하고, 이 문서·manifest에는 `.metrics`에서 파생한 값과 artifact hash만 기록했다.
- Run 사이에 schema를 초기화하지 않았다.
- 동일 release 2회 측정이지만 최소 3회 반복 전에는 이전 campaign과의 차이만으로 개선·악화를 판정하지 않는다.
- App runtime snapshot의 rolling log count는 시점 집계이며 정확한 per-stage delta가 아니다.
- fanout, rooms, mixed의 세션 정상과 전달 완료는 2분 callback을 끝낸 표본만 분모로 하며 ramp-down에 중단된 VU를 포함하지 않는다.

## 다음

1. handshake access guard outer transaction과 status correction REQUIRES_NEW 중첩을 분리하거나 동일 요청에서 두 pool slot이 겹치지 않도록 최소 변경을 설계한다. 단순 pool 상향만으로 원인을 가리지 않는다.
2. WebSocket client metric에 open, error, close timestamp와 close code, reason을 남기고 App에서 access revalidation과 delivery failure의 reason counter를 추가한다.
3. 활성 방 전달은 route 고정 cross-instance 실험과 all-open barrier를 분리해 Redis Pub/Sub 구독 공백, delivery 조회 예외, stage time bias를 구분한다.
4. 수정 후 동일 fixture와 계단으로 최소 3회 반복해 범위 기준으로 재측정한다.

## 재현

서버와 k6 가드의 로그인 제한을 모두 300으로 맞춘 뒤, 각 시나리오에 고유 `run_id`와 일회성 bcrypt fixture 비밀번호를 사용한다. 실제 fixture credential과 raw `setup_data`는 저장소에 남기지 않는다.

```sh
ALBAM_MATE_LOGIN_LIMIT=300 bash run.sh deploy

K6_LOGIN_LIMIT=300 \
  bash run.sh loadtest load-tests/k6/eungi/load-throughput.js \
  --seed-sql load-tests/k6/eungi/fixtures/rooms.sql \
  --cleanup-sql load-tests/k6/eungi/fixtures/cleanup.sql \
  --sql-var run_id=<unique-run-id> \
  --sql-var room_count=8 --sql-var accounts_per_room=9 \
  --sql-var messages_per_room=150 \
  --sql-var password_hash='{bcrypt}<valid-hash>' --sql-var password='<plaintext>'
```

## 증거

이 문서의 수치는 [campaign manifest](evidence/chat-delivery-capacity-2026-08-13-repeat2.json)에 Run별·단계별로 담겨 있다. summary는 기계적으로 `.metrics`만 추출했으며, raw summary·console·campaign log는 `albam-mate-infra/.run/results/`에 local-only로 보존한다.
