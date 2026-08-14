# 채팅 전송·전달 AWS 용량 측정 보고서 — #608 반영 후 (2026-08-13 18:50:07~20:13:23 KST)

## 결론

이 문서는 [#608](https://github.com/bamsongi-club/albam-mate/issues/608) WebSocket timeout 분리가 들어간 release에서 채팅 전송·전달 6개 축을 측정한 결과다. 직전 after 캠페인과 release가 달라졌으므로, 단일 성공률·p95의 전후 차이로 개선 폭이나 새 용량 경계를 단정하지 않는다.

- Campaign ID: `chat-delivery-capacity-2026-08-13`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `superseded`
- 문서 인덱스: [Eungi k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/chat-delivery-capacity-2026-08-13.json)
- **before 기준선**: [`chat-delivery-20260813T020049KST`](chat-delivery-capacity-2026-08-13-after-607-repeat.md) — #607 반영, #608 미반영 release
- 대체 관계: 동일 release 반복 [`chat-delivery-capacity-2026-08-13-repeat2`](chat-delivery-capacity-2026-08-13-repeat2.md)가 대체

- 부하 시나리오 6종이 모두 완주했다. 6종 전부 `reportDisposition=included`이며 `dropped_iterations`는 모두 0이다. 초기 fixture 인증 실패 1건은 성능 결과에서 제외하고 근거만 남겼다.
- 동시 연결은 80개까지 연결 성립률·세션 정상률 100%로 끝났고, 이력 조회는 8건/초까지 실패 없이 통과했다.
- 전송 처리량은 전송 요청이 5건/초까지 100%였지만, WebSocket open·healthy가 8/18(44.4%)에 그쳤다. 따라서 5건/초를 통과 용량으로 인용할 수 없다.
- 팬아웃은 전송 성공률 98.97%(1,248/1,261), WebSocket healthy 83.3%(30/36)로 끝났다. 활성 방 수는 세션 정상률 100%(28/28)였지만 구독자 전달 완료가 67.9%(19/28)라 별도 실패가 남았다. 혼합 부하는 WebSocket healthy 86.5%(32/37)로 끝났다.
- 최초 관측 병목은 전송 처리량 setup 중 App 두 대에서 동시에 나온 Hikari 연결 풀 포화다. `total=8`, `active=8`, `idle=0`과 30초 대기 timeout, WebSocket handshake의 `CannotCreateTransactionException`이 이어졌다. 연결 누수·풀 크기·PostgreSQL 수용량 중 원인은 이번 자료만으로 확정하지 않는다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | 2026-08-13 18:50:07~20:13:23 KST (83분 16초) |
| App | `t4g.micro` 2대, Nginx + Spring, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large` 1대, k6 `1.3.0` |
| **release SHA** | **`69438fd3a30150623e5801ff6bff5f4705b6a795`** (develop, #608 반영) |
| **backend image** | **`sha256:9311e1dc14a57fb8314317c0d695b03733bb062fa14826f8505a2884c3933ab4`** |
| **web image** | **`sha256:9ec386df04a54c71ee5dd838067bb18c4fa2fd3e316d22175b52620e847a0200`** |
| PostgreSQL image | `sha256:0826e5f2996099babb925e09fb72bf2c6eb5d187cfcae20aa9291af1612307e4` |
| Redis image | `sha256:78b83aee0bf6781ca973ee5022de73dd16fe93f53593c3a31f079c8c3fa08921` |
| 로그인 제한 | 측정 창 동안 App·k6 모두 300회/10분 (기본 30) |
| 상태 격리 | Run마다 fixture를 새로 만들고 끝나면 cleanup SQL로 지운다. schema 초기화는 하지 않았다 |
| runner | `albam-mate-infra/run.sh loadtest`, k6 `1.3.0` |
| 실행 조건 | 각 Run의 manifest `execution`에 runner와 해석된 scenario 환경 변수를 보존 |
| 원자료 | `albam-mate-infra/.run/results/`; teardown 뒤에도 local-only로 보존 |

직전 after 캠페인은 release `97ba2665`로 #607만 반영했고 #608은 포함하지 않았다. 이번 release에는 WebSocket 전용 timeout 분리가 들어갔으며, 같은 release로 한 번 더 반복 측정했다. fixture 규모·계단·인스턴스 사양은 같지만 #607 after는 1회, #608 after는 2회이므로 수치 차이만으로 성능 개선 폭을 판정하지 않는다.

**용어** — `p95`는 "100건 중 95건이 이 시간 안에 처리됐다"는 뜻이다.

## 무엇이 바뀌었나

이번 release는 일반 HTTP 요청과 WebSocket 연결의 timeout 경로를 분리했다. 이 캠페인은 유휴 연결을 유지하는 `load-connections`를 포함해 의도한 WebSocket 동작을 관측하지만, 다른 WebSocket 실패까지 #608 하나의 효과로 묶지는 않는다.

## 테스트 데이터

Run마다 방 8개·계정 72개·방당 메시지 150건을 [`fixtures/rooms.sql`](../../../../load-tests/k6/eungi/fixtures/rooms.sql)로 만들고, 생성된 ID registry를 기준으로 [`fixtures/cleanup.sql`](../../../../load-tests/k6/eungi/fixtures/cleanup.sql)로 지웠다. Run마다 `run_id`를 달리해 서로의 데이터를 건드리지 않았다.

18:44 KST 초기 `load-throughput` 시도 1건은 임시 bcrypt fixture 생성 래퍼가 hash 문자열을 잘못 처리해 인증에 실패했다. iterations와 WebSocket 세션이 만들어지지 않아 성능 결과로 사용하지 않았고, 수정 뒤 재실행한 결과만 아래 campaign에 넣었다. 제외 시도의 파일명·SHA-256·사유는 manifest에 보존했다.

## 부하 campaign

| # | 시나리오 | 시각 (KST) | 소요 | `dropped_iterations` | exit | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `load-throughput` | 18:50:07~19:04:14 | 14분 07초 | 0 | 2 | `FAIL` |
| 2 | `load-connections` | 19:05:41~19:18:54 | 13분 13초 | 0 | **0** | `PASS` |
| 3 | `load-history` | 19:19:14~19:30:15 | 11분 01초 | 0 | **0** | `PASS` |
| 4 | `load-fanout` | 19:30:33~19:44:36 | 14분 03초 | 0 | 2 | `FAIL` |
| 5 | `load-rooms` | 19:45:21~19:59:07 | 13분 46초 | 0 | 2 | `FAIL` |
| 6 | `load-mixed` | 19:59:31~20:13:23 | 13분 52초 | 0 | 2 | `FAIL` |

`FAIL`은 threshold를 넘었다는 뜻이다. `exit=2`는 관찰형 threshold 위반 뒤 결과를 회수한 상태이며 Run이 중간에 무효가 됐다는 뜻이 아니다. scenario 완주·원자료·오염 여부를 확인해 6개 Run 모두 `included`로 판정했다.

### 전송 처리량 `load-throughput`

| 단계 | 목표 | 응답 p95 | 전송 성공률 | 전달 p95 |
| --- | --- | --- | --- | --- |
| 1 | 1건/초 | 55ms | 100% (120건) | 55ms |
| 2 | 2건/초 | 50ms | 100% (180건) | 51ms |
| 3 | 3건/초 | 43ms | 100% (300건) | 44ms |
| 4 | 4건/초 | 45ms | 100% (420건) | 43ms |
| 5 | 5건/초 | 39ms | 100% (616건) | 41ms |

표의 전송·전달 표본은 연결에 성공한 WebSocket 세션에서 나왔다. setup의 WebSocket open·healthy가 모두 8/18(44.4%)로 threshold를 넘지 못했으므로, 이 축의 5건/초 전건 성공은 서버 용량 근거로 쓰지 않는다.

### 동시 접속 `load-connections`

| 단계 | 동시 연결 | 연결 시간 p95 | 성립률 |
| --- | --- | --- | --- |
| 1 | 5개 | 28ms | 100% |
| 2 | 10개 | 40ms | 100% |
| 3 | 20개 | 41ms | 100% |
| 4 | 40개 | 78ms | 100% |
| 5 | 80개 | 48ms | 100% |

80개까지 연결 성립률과 세션 정상률이 모두 100%였다. 다만 연결만 유지한 조건이므로 전송·구독 처리를 포함한 용량으로 확장해서 읽으면 안 된다.

### 이력 조회 `load-history`

| 단계 | 목표 | 조회 p95 | 성공률 |
| --- | --- | --- | --- |
| 1 | 1건/초 | 29ms | 100% (120건) |
| 2 | 2건/초 | 29ms | 100% (180건) |
| 3 | 4건/초 | 27ms | 100% (360건) |
| 4 | 8건/초 | 25ms | 100% (841건) |

이 축은 threshold 위반 없이 끝났다. 결과는 채팅 이력 조회 단독 조건에 한한다.

### 팬아웃 `load-fanout`

| 단계 | 구독자 | 전달 p95 | 응답 p95 | 전송 성공률 |
| --- | --- | --- | --- | --- |
| 1 | 2명 | **25ms** | 37ms | 99.6% (239/240건) |
| 2 | 4명 | **38ms** | 36ms | 98.3% (236/240건) |
| 3 | 8명 | **50ms** | 37ms | 98.8% (237/240건) |
| 4 | 16명 | **107ms** | 39ms | 99.2% (238/240건) |
| 5 | 24명 | **158ms** | 68ms | 99.2% (239/241건) |

구독자가 2명에서 24명으로 늘 때 전달 p95는 25ms에서 158ms로 약 6.3배가 됐다. 그러나 전체 전송 성공률 98.97%와 WebSocket healthy 83.3%가 threshold를 넘지 못했으므로, 전달 p95만으로 정상 팬아웃 용량을 선언하지 않는다.

### 활성 방 수 `load-rooms`

| 단계 | 활성 방 | 전체 전송 | 응답 p95 | 전송 성공률 | 연결 p95 | 전달 p95 | 구독자 전달 완료 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1개 | 1건/초 | 46ms | 96.7% (116/120건) | 26ms | 31ms | 33.3% (1/3) |
| 2 | 2개 | 2건/초 | 43ms | 100% (180/180건) | 26ms | 31ms | 20.0% (1/5) |
| 3 | 4개 | 4건/초 | 41ms | 100% (360/360건) | 30ms | 28ms | 81.8% (9/11) |
| 4 | 8개 | 8건/초 | 39ms | 100% (840/840건) | 26ms | 50ms | 100% (7/7) |

세션 정상률은 28/28로 100%였지만 전체 구독자 전달 완료는 19/28(67.9%)였다. 단계별 완료율은 setup 시점 태그와 작은 표본의 영향을 받을 수 있으므로, 특정 활성 방 수를 경계라고 선언하지 않는다.

### 혼합 `load-mixed`

| 단계 | 배수 | 응답 p95 | 전송 성공률 | 연결 성립률 | 연결 p95 | 전달 p95 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1배 | 37ms | 100% (360/360건) | 100% | 24ms | 26ms |
| 2 | 2배 | 37ms | 100% (540/540건) | 100% | 27ms | 39ms |
| 3 | 3배 | 60ms | 99.8% (898/900건) | 100% | 57ms | 50ms |
| 4 | 4배 | 42ms | 100% (1,440/1,440건) | 100% | 26ms | 46ms |

전송·연결·전달 표본의 p95는 낮지만 전체 WebSocket healthy가 32/37(86.5%)로 threshold를 넘지 못했다. 따라서 혼합 부하의 통과 또는 경계 미발견 근거로 쓰지 않는다.

## 실행 간 편차 — 이번에는 판정하지 않는다

이번 release는 같은 조건으로 2회 campaign을 남겼지만, 직전 #607 after는 1회이고 #608 포함 여부도 다르다. 따라서 수치를 직접 비교하면 코드 변경 효과와 실행 편차를 분리할 수 없다.

| 비교 항목 | 직전 after | 이번 |
| --- | --- | --- |
| release | `97ba2665` (#607 반영, #608 미반영) | `69438fd3` (#608 반영) |
| campaign 수 | 1회 | 2회 |
| fixture·계단·인스턴스 사양 | 동일 | 동일 |
| 성공률·p95 전후 판정 | 보류 | 보류 |

같은 release·같은 계단으로 최소 3회를 반복한 뒤에만 단계별 성공률과 p95의 범위를 비교한다.

## WebSocket 위반은 세 가지로 나뉜다

| 시나리오 | 연결 성립 | 세션 정상 | 전달 완료 | exit |
| --- | --- | --- | --- | --- |
| `load-throughput` | **44.4%** (8/18) | **44.4%** (8/18) | — | 2 |
| `load-connections` | 100% (170/170) | 100% (97/97) | — | **0** |
| `load-fanout` | 100% (57/57) | **83.3%** (30/36) | 100% (36/36) | 2 |
| `load-rooms` | 100% (50/50) | 100% (28/28) | **67.9%** (19/28) | 2 |
| `load-mixed` | 100% (55/55) | **86.5%** (32/37) | 100% (37/37) | 2 |

`load-connections`의 세션 정상률 100%는 #608이 겨냥한 유휴 연결 조건에서 기대한 동작과 일치한다. 반면 throughput의 연결 수립 실패, fanout·mixed의 세션 정상률 실패, rooms의 전달 완료 실패는 서로 다른 조건에서 나온 관측이다. 이번 Run만으로 같은 원인이나 #608의 미해결 효과라고 단정하지 않는다.

## 최초 병목 — DB 연결 풀 포화가 setup에서 관측됐다

전송 처리량 setup이 진행되던 18:52 KST에 App1·App2에서 동시에 HikariPool `total=8`, `active=8`, `idle=0`과 30초 connection timeout이 관측됐다. 같은 시점에 `ChatWebSocketHandshakeController`의 `CannotCreateTransactionException`이 기록됐고, 해당 Run의 WebSocket open·healthy는 44.4%였다.

이는 연결 풀이 이 Run에서 포화됐다는 관측 근거다. 다만 풀 크기만 올리는 것은 해결책이 아니다. WebSocket handshake의 DB 사용 범위, 커넥션 반환, PostgreSQL 연결 수와 App 두 대 풀 합계를 같은 시간축으로 확인해야 한다.

## CPU

이번 campaign에서는 CPU 크레딧·CPU 사용률·PostgreSQL·Redis 시스템 지표를 이 결론의 근거 수준으로 별도 수집·검증하지 않았다. 따라서 CPU가 병목이 아니었다거나 DB·Redis가 병목이었다는 판정은 하지 않는다.

## 한계

- 이번 release는 동일 조건으로 2회 측정했지만, 실행 간 편차 범위를 판단하기 위한 최소 3회에는 이르지 못했다. 이전 release 대비 개선·악화는 판단하지 않는다.
- 모든 유효 Run의 `dropped_iterations`는 0이지만, p95는 성공하거나 수신된 표본에만 기반한다.
- `load-fanout`, `load-rooms`, `load-mixed`의 구독자 전달 완료율은 setup 시점 태그와 작은 표본의 영향을 받을 수 있다.
- `load-throughput` WebSocket 연결 수립 실패의 원인이 측정 결함인지 서버 동작인지 규명하지 않았다.
- `load-rooms`는 세션이 정상인 28명 중 9명이 전달 완료를 못했다. 전송 성공 요청의 저장·재연결 후 이력 복구는 이번 Run에서 직접 검증하지 않았다.
- Run 사이에 schema를 초기화하지 않았다.
- 원자료는 `local-only`다. raw k6 summary의 `setup_data`에는 fixture 세션 또는 CSRF 정보가 있을 수 있어 문서·manifest에 복사하지 않았다.

## 다음

1. App 두 대 Hikari active·idle·pending과 PostgreSQL 연결 수를 같은 시간축으로 수집하고, WebSocket handshake의 트랜잭션·커넥션 반환 경로를 확인한다.
2. throughput 연결 수립 실패, fanout·mixed 세션 정상률 실패, rooms 전달 완료 실패를 같은 원인으로 합치지 않고 각각 재현 가능한 최소 시나리오로 분리한다.
3. 원인이 확인된 뒤 최소 변경을 적용하고, 같은 release·같은 계단으로 6개 축을 최소 3회 반복한다. 단일 p95가 아니라 단계별 성공률과 p95의 범위를 비교한다.

## 재현

서버와 k6 가드의 로그인 제한을 모두 300으로 맞춘 뒤, 각 시나리오에 고유 `run_id`와 일회성 bcrypt fixture 비밀번호를 사용한다.

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

cleanup SQL은 runner의 EXIT·INT·TERM 경로에서도 실행된다. 실제 fixture credential과 raw `setup_data`는 저장소에 남기지 않는다.

## 증거

이 문서의 수치는 [campaign manifest](evidence/chat-delivery-capacity-2026-08-13.json)에 Run별·단계별로 담겨 있다. k6 summary에서 기계로 뽑았고, raw summary·console·campaign log는 `albam-mate-infra/.run/results/`에 local-only로 보존한다. manifest에는 포함 Run 6개와 제외 시도 1개의 SHA-256도 함께 기록했다.
