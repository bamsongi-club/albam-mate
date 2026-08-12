# 채팅 전송·전달 AWS 용량 측정 보고서 — #607 수정 후 반복 (2026-08-13 02:00:49~03:16:26 KST)

## 결론

이 문서는 [#607](https://github.com/bamsongi-club/albam-mate/issues/607) 수정이 들어간 상태를 **더 나중의 develop release로 한 번 더** 측정한 결과다. 목적은 새 경계를 찾는 것이 아니라 [직전 after 캠페인](chat-delivery-capacity-2026-08-12-after-607.md)이 1회 실행이었다는 한계를 메우는 것이다.

- Campaign ID: `chat-delivery-20260813T020049KST`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 문서 인덱스: [Eungi k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/chat-delivery-capacity-2026-08-13-after-607-repeat.json)
- **before 기준선**: [`chat-delivery-20260812T090111KST`](chat-delivery-capacity-2026-08-12-repeat.md) 및 그 이전 2회 (release `1db046c0`)
- **직전 after**: [`chat-delivery-20260812T213324KST`](chat-delivery-capacity-2026-08-12-after-607.md) (release `b6c32e22`)
- 대체 관계: 없음. release가 달라 이전 캠페인을 대체하지 않는다

- **#607 수정 효과가 재현됐다.** 채팅 경로 HTTP 500이 **0건**, `RedisConnectionFailureException`이 **0건**, Redis 누적 수신 연결이 **14,149건**이다. 직전 after는 각각 0·0·14,211건이었다.
- **직전 after가 1회 실행이라는 한계가 해소됐다.** 서로 다른 두 release에서 같은 결과가 나왔으므로 500 소멸은 우연이나 실행 편차가 아니다.
- 활성 방 8개 구간은 성공률 **99.9%**, 응답 p95 **40ms**다. before 3회는 57.1~70%·3,119~5,105ms였다.
- 혼합 4배까지 전건 성공이며 응답 p95가 38~39ms로 평평하다. **이 계단 구성으로는 여전히 경계를 찾지 못한다.**
- `dropped_iterations`가 6종 모두 0이다.
- **성능 임계는 하나도 걸리지 않았다.** `exit=2`인 3종의 위반은 전부 WebSocket 지표다. 다만 그 안에 서로 다른 세 가지가 섞여 있어 아래 절에서 나눠 본다. `load-throughput`의 연결 수립 실패는 [#608](https://github.com/bamsongi-club/albam-mate/issues/608)로 설명되지 않으며 원인을 규명하지 못했다.
- **`load-rooms`에서 구독자 28명 중 13명이 받아야 할 메시지를 하나도 받지 못했다.** 세션 정상률과 같은 15/28이며 직전 after와 수치까지 같다.
- `load-rooms` 1단계에서 WebSocket 연결 p95가 337ms로 튀었다. 같은 시나리오의 다른 단계(26~83ms)와 직전 after의 같은 단계(22ms)에 견주면 단발 이상치다. 아래 한계에 남긴다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | 2026-08-13 02:00:49~03:16:26 KST (75분 37초) |
| App | `t4g.micro` 2대, Nginx + Spring, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large` 1대, k6 `1.3.0` |
| **release SHA** | **`97ba266580754fc317c06a1c752fcc8bb8826b91`** (develop, #607 수정 포함) |
| **backend image** | **`sha256:df034213e0d6b93e99691757a5b0aa271981066abed2740390dc92d6bde113a7`** |
| PostgreSQL image | `sha256:0826e5f2996099babb925e09fb72bf2c6eb5d187cfcae20aa9291af1612307e4` |
| Redis image | `sha256:78b83aee0bf6781ca973ee5022de73dd16fe93f53593c3a31f079c8c3fa08921` |
| 로그인 제한 | 측정 창 동안 300회/10분 (기본 30) |
| 상태 격리 | Run마다 fixture를 새로 만들고 끝나면 지운다 |
| 원자료 | `albam-mate-infra/.run/results/`; teardown 뒤에도 로컬에 보존 |

배포 후 실행 중인 컨테이너의 image digest가 새 빌드와 일치하는지, jar에 `RedisSessionFailureFilter` 클래스가 있는지 확인했다. `run.sh up`은 이미지를 빌드하지 않으므로 `run.sh publish`를 먼저 실행해야 한다.

**이 캠페인의 시나리오·fixture 경로는 측정 시점에 이미 `load-tests/k6/eungi/`다.** 직전 after는 측정 당시 `load-tests/k6/chat/`이었고 이후 rename됐다. 두 캠페인이 쓴 blob은 같다.

## 직전 after와 무엇이 다른가

| | 직전 after | 이번 |
| --- | --- | --- |
| release | `b6c32e22` | `97ba2665` |
| 사이에 들어간 변경 | — | k6 소유자별 구조 정리, 인기 게임 랭킹 API·화면, PostgreSQL 검증 게이트, 문서 정리 |

**두 release 모두 #607 수정을 포함하고 [#608](https://github.com/bamsongi-club/albam-mate/issues/608)은 포함하지 않는다.** 채팅 전송·전달 경로와 Redis 세션 설정은 동일하다. 계단·fixture 규모·발생기·인스턴스 사양도 같다.

## 테스트 데이터

Run마다 방 8개·계정 72개·방당 메시지 150건을 [`fixtures/rooms.sql`](../../../../load-tests/k6/eungi/fixtures/rooms.sql)로 만들고 [`fixtures/cleanup.sql`](../../../../load-tests/k6/eungi/fixtures/cleanup.sql)로 지웠다. before·직전 after와 같은 규모다.

## 부하 campaign

| # | 시나리오 | 시각 (KST) | 소요 | `dropped_iterations` | exit | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `load-throughput` | 02:00:49~02:14:59 | 14분 10초 | 0 | 2 | `PASS` |
| 2 | `load-connections` | 02:14:59~02:28:15 | 13분 16초 | 0 | 2 | `PASS` |
| 3 | `load-history` | 02:28:15~02:39:13 | 10분 58초 | 0 | **0** | `PASS` |
| 4 | `load-fanout` | 02:39:13~02:52:58 | 13분 45초 | 0 | **0** | `PASS` |
| 5 | `load-rooms` | 02:52:58~03:04:41 | 11분 43초 | 0 | 2 | `PASS` |
| 6 | `load-mixed` | 03:04:42~03:16:26 | 11분 44초 | 0 | **0** | `PASS` |

### 전송 처리량 `load-throughput`

| 단계 | 목표 | 응답 p95 | 전송 성공률 | 전달 p95 |
| --- | --- | --- | --- | --- |
| 1 | 1건/초 | 59ms | 100% (120건) | 62ms |
| 2 | 2건/초 | 55ms | 100% (180건) | 55ms |
| 3 | 3건/초 | 51ms | 100% (300건) | 48ms |
| 4 | 4건/초 | 45ms | 99.8% (420건) | 45ms |
| 5 | 5건/초 | **44ms** | **99.8%** (616건) | 43ms |

계단이 올라갈수록 오히려 빨라진다. 직전 after(60→38ms)와 같은 형태다.

### 동시 접속 `load-connections`

| 단계 | 동시 연결 | 연결 시간 p95 | 성립률 |
| --- | --- | --- | --- |
| 1 | 5개 | 28ms | 100% |
| 2 | 10개 | 29ms | 100% |
| 3 | 20개 | 30ms | 100% |
| 4 | 40개 | 33ms | 100% |
| 5 | 80개 | 37ms | 100% |

80개까지 28~37ms로 평평하다. 이 축은 5회 캠페인 내내 성립률 100%다.

### 이력 조회 `load-history`

| 단계 | 목표 | 조회 p95 | 성공률 |
| --- | --- | --- | --- |
| 1 | 1건/초 | 33ms | 100% (120건) |
| 2 | 2건/초 | 30ms | 100% (180건) |
| 3 | 4건/초 | 29ms | 100% (360건) |
| 4 | 8건/초 | **26ms** | 100% (841건) |

before 56~60ms에서 절반 이하다. 직전 after(23ms)와 같은 수준이다.

### 팬아웃 `load-fanout`

| 단계 | 구독자 | 전달 p95 | 응답 p95 | 전송 성공률 |
| --- | --- | --- | --- | --- |
| 1 | 2명 | **50ms** | 49ms | 99.2% (240건) |
| 2 | 4명 | **49ms** | 48ms | 99.6% (240건) |
| 3 | 8명 | **67ms** | 45ms | 99.2% (240건) |
| 4 | 16명 | **112ms** | 49ms | 98.8% (240건) |
| 5 | 24명 | **170ms** | 47ms | 99.6% (241건) |

전달 지연이 구독자 수에 따라 50ms에서 170ms로 오르는 동안 전송 응답은 45~49ms로 변하지 않는다. **팬아웃 비용은 전달 쪽에 쌓인다** — Redis 결함과 무관한 구조적 특성이며 5회 모두 같은 결론이다.

### 활성 방 수 `load-rooms`

| 단계 | 활성 방 | 전체 전송 | 응답 p95 | 전송 성공률 | 연결 p95 | 전달 p95 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1개 | 1건/초 | 43ms | 97.5% (120건) | **337ms** | 28ms |
| 2 | 2개 | 2건/초 | 43ms | 100% (180건) | 26ms | 23ms |
| 3 | 4개 | 4건/초 | 45ms | 100% (360건) | 83ms | 26ms |
| 4 | **8개** | 8건/초 | **40ms** | **99.9%** (841건) | 27ms | 48ms |

before 3회가 모두 무너지던 방 8개 구간이 99.9%다. 직전 after는 100%였다.

1단계 연결 p95 337ms는 같은 Run의 다른 단계(26~83ms)와 직전 after의 같은 단계(22ms)에 견주면 이상치다. 아래 한계에 남긴다.

### 혼합 `load-mixed`

| 단계 | 배수 | 응답 p95 | 전송 성공률 | 연결 성립률 | 전달 p95 |
| --- | --- | --- | --- | --- | --- |
| 1 | 1배 | 39ms | 100% (360건) | 100% | 29ms |
| 2 | 2배 | 39ms | 100% (540건) | 100% | 28ms |
| 3 | 3배 | 39ms | **100%** (900건) | 100% | 31ms |
| 4 | 4배 | **38ms** | **100%** (1,442건) | 100% | 40ms |

**4배까지 전건 성공이고 응답이 38~39ms로 평평하다.** 직전 after와 같다. before 3차는 3배 71.8%·4배 44.9%였다.

4배가 이 스크립트의 마지막 계단이므로 **두 after 캠페인 모두 혼합 부하의 경계를 찾지 못했다.**

## Redis — 두 release에서 같은 결과

채팅 경로가 받은 응답을 App1 프록시 접근 로그에서 세면 다음과 같다.

| 경로 | 2xx | **500** | 503 |
| --- | --- | --- | --- |
| `POST /api/rooms/{id}/chat/messages` | 5,506 | **0** | 15 |
| `GET /api/rooms/{id}/chat/messages` | 3,705 | **0** | 0 |

before 3회와 after 2회를 비교하면 다음과 같다.

| 지표 | before 3회 (`1db046c0`) | after 1회차 (`b6c32e22`) | **after 2회차 (`97ba2665`)** |
| --- | --- | --- | --- |
| 채팅 경로 HTTP 500 | 706 / 760 / 700 | 0 | **0** |
| `RedisConnectionFailureException` | — / 2,928 / 2,812 | 0 | **0** |
| Redis 누적 수신 연결 | 99,907 / 120,838 / 125,678 | 14,211 | **14,149** |
| 채팅 경로 HTTP 503 | 533 / 615 / 564 | 17 | **15** |
| Redis 거부 연결 | 0 / 0 / 0 | 0 | **0** |
| Redis 사용 메모리 | 2.06MB / 2.06MB / 2.05MB | 2.08MB | **2.08MB** |

**after 두 회차가 좁은 범위로 일치한다.** 누적 연결 14,211과 14,149, 503 17건과 15건이다. before 3회의 성공률이 57.1~70%로 흔들렸던 것과 대비된다.

남은 503은 결함이 아니라 의도한 동작이다. 연결 실패를 서버 내부 오류가 아니라 일시적 의존성 장애로 응답하는 것이 [#638](https://github.com/bamsongi-club/albam-mate/pull/638) 수정의 일부였다.

## WebSocket 위반은 세 가지가 섞여 있다

`exit=2`인 3종의 위반 지표다. **직전 after와 위반 지표·수치가 모두 같다.**

| 시나리오 | 연결 성립 | 세션 정상 | 전달 완료 | 직전 after |
| --- | --- | --- | --- | --- |
| `load-throughput` | **44.4%** (8/18) | 44.4% (8/18) | — | 72.2% (13/18) |
| `load-connections` | 100% (170/170) | **0%** (0/97) | — | 0% (0/97) |
| `load-rooms` | 100% (50/50) | **53.6%** (15/28) | **53.6%** (15/28) | 53.6% (15/28) |
| `load-fanout` | 100% | 100% | 100% | 동일 |
| `load-mixed` | 100% | 100% | 100% | 동일 |

성능 임계(응답 시간·전송 성공률·`dropped_iterations`)는 하나도 걸리지 않았다. 아래 셋은 원인이 서로 다르다.

### 유휴 종료 — #608

`load-connections`는 연결이 100% 열리고 세션 정상률만 0%다. 이 시나리오는 연결을 열고 트래픽 없이 유지하므로 [#608](https://github.com/bamsongi-club/albam-mate/issues/608)의 유휴 60초 종료와 일치한다. 측정 시점 develop에는 WebSocket 전용 `location`이 아직 없다.

### 전달 누락 — #608의 사용자 영향

`load-rooms`는 세션 정상률과 전달 완료율이 **같은 15/28**이다. 세션이 끊긴 구독자 13명이 그 방의 메시지를 **하나도 받지 못했다.**

같은 Run에서 전송은 99.9% 성공했고 응답 p95는 40ms였다. **보내는 쪽은 멀쩡한데 받는 쪽만 비어 있다.** #608이 게이트 위반에 그치지 않고 실제 메시지 누락으로 나타난다는 근거다.

메시지 자체는 PostgreSQL에 남으므로 유실은 아니다. 재연결하면 이력으로 복구된다.

### 연결 수립 실패 — 원인 미규명

`load-throughput`만 연결이 열리지 않는다. 18개 중 10개 실패이며 직전 after는 5개 실패였다.

이 시나리오의 `chat_websocket_connect_ms`는 다음과 같다.

| | min | med | p(95) | max |
| --- | --- | --- | --- | --- |
| 직전 after | 536ms | 557ms | 30,332ms | 30,333ms |
| 이번 | **30,330ms** | 30,356ms | 30,367ms | 30,367ms |

**성공한 8개조차 30.3초가 걸렸다.** 직전 after는 절반가량이 0.5초대였다.

30.3초는 이 시나리오 구독자의 시작 지연(`K6_ROOM_WS_READY_DELAY` 30초)과 거의 같다. `createWebSocket`은 소켓 생성 직후를 기준으로 `open` 이벤트까지의 시간을 재므로, VU의 이벤트 루프가 30초 동안 열리지 않았을 때도 같은 값이 나올 수 있다. **측정 결함일 가능성과 서버가 실제로 연결을 못 받은 가능성을 이번 자료로는 가르지 못한다.**

다만 같은 Run에서 다음이 관측됐다.

- 전달 표본 4,396건, 전달 p95 51ms
- 수신 이벤트 정확도 100% (4,396/4,396)
- 전송 성공률 99.9%

**연결에 성공한 구독자들은 정상적으로 메시지를 받았다.** 서버가 WebSocket 자체를 처리하지 못하는 상태는 아니다.

`load-connections`가 동시 연결 80개까지 100% 성립한 것도 대비된다. **연결 수립 능력 자체의 문제로 보기 어렵다.**

원인 규명은 다음 측정으로 넘긴다.

## CPU

| 역할 | 크레딧 시작 → 끝 | CPU 평균 | CPU 최고 |
| --- | --- | --- | --- |
| App1 | 0.27 → 9.51 | 3.8% | 9.4% |
| App2 | 0.45 → 10.00 | 3.5% | 8.5% |

크레딧이 오르고 사용률은 4% 안팎이다. CPU는 5회 캠페인 모두 병목이 아니다.

## 한계

- **혼합 부하의 경계를 찾지 못했다.** 마지막 계단인 4배에서 전건 성공으로 끝났다. 새 경계를 찾으려면 계단을 올려야 한다.
- **`load-rooms` 1단계 연결 p95 337ms를 규명하지 않았다.** 같은 Run의 다른 단계와 직전 after의 같은 단계 대비 한 자릿수 크다. 단발 이상치로 보이나 원인을 확인하지 않았으므로 다음 측정에서 같은 자리가 다시 튀는지 본다.
- **`load-throughput`의 WebSocket 연결 수립 실패를 규명하지 않았다.** 측정 결함인지 서버 동작인지 이번 자료로 가르지 못했다. 위 절에 관측 사실만 남겼다.
- **WebSocket 관련 결론은 게이트 위반 수준까지만 유효하다.** 세 갈래 중 유휴 종료만 원인이 확인됐다.
- 두 after 캠페인의 release가 다르다. 채팅·Redis 경로는 동일하지만 그 사이 다른 변경이 들어갔다.
- `load-throughput`의 발신자 회전 결함은 아직 고치지 않았다. 이번에도 429가 관측되지 않아 영향이 드러나지 않았을 뿐이다.
- 측정값은 `t4g.micro` 노드와 DB 커넥션 풀 8개 구성에 묶인다.
- Run 사이에 schema를 초기화하지 않았다.
- 원자료는 `local-only`다.

## 다음

1. **[#608](https://github.com/bamsongi-club/albam-mate/issues/608) WebSocket 전용 `location` 분리** — 유휴 종료와 그로 인한 전달 누락을 없앤다.
2. **`load-throughput` 연결 수립 실패 원인 규명** — 측정 결함인지 서버 동작인지 가른다. 스택을 띄운 상태에서 서버 로그와 k6 이벤트 루프를 함께 본다.
3. **혼합·활성 방 계단 상향** — 현재 계단으로는 이 release의 경계를 못 찾는다.
4. **`load-throughput` 발신자 회전 수정**
5. **#608 수정 후 측정** — 이번 after 2회를 before 범위로 삼는다.

## 재현

```sh
bash run.sh publish              # 이미지 빌드가 먼저다. up 은 빌드하지 않는다
bash run.sh up -auto-approve
ALBAM_MATE_LOGIN_LIMIT=300 bash run.sh deploy
```

시나리오마다 아래를 실행한다. 6종 전체는 약 76분 걸린다.

```sh
K6_LOGIN_LIMIT=300 \
  bash run.sh loadtest load-tests/k6/eungi/load-throughput.js \
  --seed-sql load-tests/k6/eungi/fixtures/rooms.sql \
  --cleanup-sql load-tests/k6/eungi/fixtures/cleanup.sql \
  --sql-var run_id=<실행 키> --sql-var room_count=8 --sql-var accounts_per_room=9 \
  --sql-var messages_per_room=150 \
  --sql-var password_hash='{bcrypt}<해시>' --sql-var password='<평문>'
```

## 증거

이 문서의 수치는 [campaign manifest](evidence/chat-delivery-capacity-2026-08-13-after-607-repeat.json)에 Run별·단계별로 담겨 있다. k6 summary에서 기계로 뽑았으며 손으로 옮긴 값이 아니다.
