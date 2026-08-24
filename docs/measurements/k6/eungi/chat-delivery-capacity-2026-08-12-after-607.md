# 채팅 전송·전달 AWS 용량 측정 보고서 — #607 수정 후 (2026-08-12 21:33:24~22:50:58 KST)

## 결론

이 문서는 [#607](https://github.com/bamsongi-club/albam-mate/issues/607) 수정이 들어간 release로 채팅 전송·전달 경계를 측정한 결과다. **이전 캠페인을 대체하지 않는다.** 재는 대상 코드가 달라졌으므로 이전 캠페인은 before 기준선으로 그대로 유효하다.

- Campaign ID: `chat-delivery-20260812T213324KST`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 문서 인덱스: [k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-after-607.json)
- **before 기준선**: [`chat-delivery-20260812T090111KST`](chat-delivery-capacity-2026-08-12-repeat.md) 및 그 이전 2회
- 대체 관계: 없음. release가 달라 이전 캠페인을 대체하지 않는다

- **[#607](https://github.com/bamsongi-club/albam-mate/issues/607)이 해결됐다.** 채팅 경로의 HTTP 500이 **0건**이다. before 3회는 700~760건이었다. `RedisConnectionFailureException`도 앱 두 대 모두 0건이며 before는 2,812~2,928건이었다.
- **Redis 연결 폭주가 사라졌다.** 누적 수신 연결이 **14,211건**으로 before(99,907~125,678건)의 약 1/9다.
- **가장 심하게 무너지던 경계가 없어졌다.** 활성 방 8개 구간의 전송 성공률이 **100%**, 응답 p95가 **36ms**다. before 3회는 57.1~70%·3,119~5,105ms였다.
- **개선이 확인된 축은 이력 조회와 활성 방 8개 구간이다.** 이력 조회 p95는 before 56~60ms에서 after 23~26ms로, 활성 방 8개 응답 p95는 3,119~5,105ms에서 36ms로 내려갔다. 동시 접속 p95는 27~70ms로 before 49~56ms와 겹쳐 개선을 판정할 수 없다.
- 6종 중 **3종이 `exit=0`**이다. before는 `load-history` 하나뿐이었다.
- **남은 WebSocket 위반은 둘로 나뉜다.** `load-throughput`의 연결 성립은 13/18(72.2%)에 그쳤고 `connect_ms` p95는 30,332ms였다. 이는 [#608](https://github.com/bamsongi-club/albam-mate/issues/608) 유휴 종료와 다른 미규명 관측이다. `load-connections`·`load-rooms`의 열린 뒤 세션 유지·전달 위반은 #608이 겨냥한 유휴 종료와 맞는다. 성능 임계는 하나도 걸리지 않았다.
- 이 캠페인은 1회 실행이다. 성공률의 절대값이 아니라 **500이 0이 된 사실**이 판정 근거다. 이 결과는 이후 [`chat-delivery-20260813T020049KST`](chat-delivery-capacity-2026-08-13-after-607-repeat.md)에서 다른 release로 재현됐다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | 2026-08-12 21:33:24~22:50:58 KST (77분 34초) |
| App | `t4g.micro` 2대, Nginx + Spring, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large` 1대, k6 `1.3.0` |
| **release SHA** | **`b6c32e2214dddb8f0368a84751a46f0bd25e647f`** (develop, #607 수정 포함) |
| **backend image** | **`sha256:450438e6f66a3f3718cf5ed57562e370e7846535a347e329deb772d770fb9e4f`** |
| **web image** | **`sha256:407cea553150135402294a378e70b602ac25ee369a29262fd42ca86e4a075bfe`** |
| PostgreSQL image | `sha256:0826e5f2996099babb925e09fb72bf2c6eb5d187cfcae20aa9291af1612307e4` |
| Redis image | `sha256:78b83aee0bf6781ca973ee5022de73dd16fe93f53593c3a31f079c8c3fa08921` |
| 로그인 제한 | 측정 창 동안 300회/10분 (기본 30) |
| 상태 격리 | Run마다 fixture를 새로 만들고 끝나면 지운다 |
| 원자료 | `albam-mate-infra/.run/results/`; teardown 뒤에도 로컬에 보존 |

**before 3회는 모두 release `1db046c0`으로 측정했다.** 계단·fixture 규모·발생기·인스턴스 사양은 같고 **애플리케이션 코드만 다르다.** after의 각 Run 명령·runner/k6 버전·해석된 환경·배포값과 시나리오/fixture blob SHA는 [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-after-607.json)에 보존했다.

측정 당시 release `b6c32e22`의 시나리오·fixture 경로는 `load-tests/k6/chat/`이었다. 이후 `894d74de`에서 내용 변경 없이 `load-tests/k6/eungi/`로 100% rename되었고 blob SHA는 유지됐다. 보고서의 현재 경로 링크와 manifest의 측정 시점 경로를 함께 기록해 이름 변경을 동일 소스로 추적할 수 있게 했다.

배포 전에 실행 중인 컨테이너의 image digest가 새 빌드와 일치하는지, jar에 `RedisSessionFailureFilter` 클래스가 있는지 확인했다. `run.sh up`은 이미지를 빌드하지 않으므로 `run.sh publish`를 먼저 실행해야 한다.

## 무엇이 바뀌었나

[RedisSessionConfiguration](../../../../src/main/java/cloud/bamsongi/albammate/infra/redis/RedisSessionConfiguration.java)이 연결 팩토리를 둘로 나눴다.

| 팩토리 | `shareNativeConnection` | `autoReconnect` | 용도 |
| --- | --- | --- | --- |
| `@Primary` | `false` | `false` | 제한·Pub/Sub 등 |
| `@SpringSessionRedisConnectionFactory` | **`true`** | **`true`** | **세션 저장소** |

500의 발생원이던 세션 경로(`hGetAll`·`pExpireAt`)만 네이티브 연결을 공유하고 자동 재연결한다.

`GlobalExceptionHandler`에 `RedisConnectionFailureException`·`RedisSystemException` 처리가 추가되어 남는 연결 실패는 500이 아니라 503이 된다. 필터 단계에서 발생하는 경우를 위해 `RedisSessionFailureFilter`도 함께 들어갔다.

## 테스트 데이터

Run마다 방 8개·계정 72개·방당 메시지 150건을 측정 당시 release의 `load-tests/k6/chat/fixtures/rooms.sql`로 만들고 `load-tests/k6/chat/fixtures/cleanup.sql`로 지웠다. 현재 저장소에서는 동일 blob이 `load-tests/k6/eungi/fixtures/`로 rename되었으며, 현재 경로는 [fixtures/rooms.sql](../../../../load-tests/k6/eungi/fixtures/rooms.sql)과 [fixtures/cleanup.sql](../../../../load-tests/k6/eungi/fixtures/cleanup.sql)에서 확인할 수 있다. before와 같은 규모다.

## 부하 campaign

| # | 시나리오 | 시각 (KST) | 소요 | `dropped_iterations` | exit | 판정 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `load-throughput` | 21:33:24~21:47:44 | 14분 20초 | 0 | 2 | `FAIL` |
| 2 | `load-connections` | 21:47:44~22:01:15 | 13분 31초 | 0 | 2 | `FAIL` |
| 3 | `load-history` | 22:01:15~22:13:15 | 12분 00초 | 0 | **0** | `PASS` |
| 4 | `load-fanout` | 22:13:15~22:27:10 | 13분 55초 | 0 | **0** | `PASS` |
| 5 | `load-rooms` | 22:27:10~22:39:05 | 11분 55초 | 0 | 2 | `FAIL` |
| 6 | `load-mixed` | 22:39:05~22:50:58 | 11분 53초 | 0 | **0** | `PASS` |

`FAIL`은 WebSocket 계약 threshold를 통과하지 못했다는 뜻이다. 세 Run 모두 원자료와 scenario를 완주했으므로 `reportDisposition=included`이며 `INVALID`는 아니다.

**`dropped_iterations`가 6종 모두 0이다.** before의 `load-rooms` 10건·`load-mixed` 18건은 발생기가 목표 부하를 내지 못했다는 뜻이었다. 이번에는 목표를 전부 냈고 서버가 받아냈다.

### 전송 처리량 `load-throughput`

| 단계 | 목표 | 응답 p95 | 전송 성공률 | 전달 p95 |
| --- | --- | --- | --- | --- |
| 1 | 1건/초 | 60ms | 100% (120건) | 74ms |
| 2 | 2건/초 | 46ms | 100% (180건) | 58ms |
| 3 | 3건/초 | 43ms | 100% (300건) | 53ms |
| 4 | 4건/초 | 40ms | 99.8% (420건) | 51ms |
| 5 | 5건/초 | **38ms** | **100%** (616건) | 49ms |

**계단이 올라갈수록 오히려 빨라진다.** before 3회는 4단계나 5단계에서 반드시 무너졌다. 이 축이 전 구간 정상으로 끝난 것은 처음이다.

### 동시 접속 `load-connections`

| 단계 | 동시 연결 | 연결 시간 p95 | 성립률 |
| --- | --- | --- | --- |
| 1 | 5개 | 27ms | 100% |
| 2 | 10개 | 37ms | 100% |
| 3 | 20개 | 68ms | 100% |
| 4 | 40개 | 70ms | 100% |
| 5 | 80개 | 52ms | 100% |

before(49~56ms)와 27~70ms로 겹친다. **원래 여유가 있던 축이라 개선 폭이 뚜렷하지 않다.**

### 이력 조회 `load-history`

| 단계 | 목표 | 조회 p95 | 성공률 |
| --- | --- | --- | --- |
| 1 | 1건/초 | 26ms | 100% (120건) |
| 2 | 2건/초 | 24ms | 100% (180건) |
| 3 | 4건/초 | 24ms | 100% (360건) |
| 4 | 8건/초 | **23ms** | 100% (841건) |

before 56~60ms에서 **절반 이하로 떨어졌다.** 읽기 경로에서도 500이 369건 나왔던 이유가 세션 조회였음을 뒷받침한다.

### 팬아웃 `load-fanout`

| 단계 | 구독자 | 전달 p95 | 응답 p95 | 전송 성공률 |
| --- | --- | --- | --- | --- |
| 1 | 2명 | **29ms** | 42ms | 98.3% (240건) |
| 2 | 4명 | **35ms** | 39ms | 98.8% (240건) |
| 3 | 8명 | **47ms** | 39ms | 99.2% (240건) |
| 4 | 16명 | **91ms** | 39ms | 99.2% (240건) |
| 5 | 24명 | **121ms** | 40ms | 99.6% (241건) |

before는 5단계에서 응답 p95 202ms·성공률 95.0%였다. 이번에는 40ms·99.6%이며 `exit=0`이다.

전달 지연은 29ms에서 121ms로 여전히 오른다. **팬아웃 비용이 전달 쪽에 쌓인다는 결론은 그대로다** — 이 축은 Redis 결함과 무관한 구조적 특성이다.

### 활성 방 수 `load-rooms`

| 단계 | 활성 방 | 전체 전송 | 응답 p95 | 전송 성공률 | 전달 p95 |
| --- | --- | --- | --- | --- | --- |
| 1 | 1개 | 1건/초 | 42ms | 97.5% (120건) | 38ms |
| 2 | 2개 | 2건/초 | 39ms | 100% (180건) | 39ms |
| 3 | 4개 | 4건/초 | 38ms | 99.7% (360건) | 40ms |
| 4 | **8개** | 8건/초 | **36ms** | **100%** (841건) | 47ms |

**이 캠페인에서 가장 큰 변화다.** before 3회가 모두 무너지던 지점이 전건 성공이다.

| | before 1차 | before 2차 | before 3차 | **after** |
| --- | --- | --- | --- | --- |
| 응답 p95 | 3,119ms | 5,105ms | 4,047ms | **36ms** |
| 전송 성공률 | 70% | 57.1% | 65.9% | **100%** |
| `dropped_iterations` | — | 8 | 10 | **0** |

before 3회 내내 57.1~70% 범위에서 흔들리던 것이 편차가 아니라 결함이었음을 보여준다.

### 혼합 `load-mixed`

| 단계 | 배수 | 응답 p95 | 전송 성공률 | 연결 성립률 | 전달 p95 |
| --- | --- | --- | --- | --- | --- |
| 1 | 1배 | 37ms | 100% (360건) | 100% | 37ms |
| 2 | 2배 | 36ms | 100% (540건) | 100% | 31ms |
| 3 | 3배 | 35ms | **100%** (900건) | 100% | 32ms |
| 4 | 4배 | **35ms** | **100%** (1,442건) | 100% | 34ms |

before 3차는 3배 71.8%·4배 44.9%였고 응답 p95가 4,024~5,131ms였다. **4배까지 전건 성공이고 응답이 35ms로 평평하다.**

"축을 섞으면 각 축을 따로 밀 때보다 일찍 무너진다"는 before의 결론은 **이 release에서는 관측되지 않는다.** 4배가 이 스크립트의 마지막 계단이므로 더 높은 배수의 경계는 이번 측정으로 알 수 없다.

## Redis — 결함이 사라졌다

채팅 경로가 받은 응답을 App1 프록시 접근 로그에서 세면 다음과 같다.

| 경로 | 2xx | **500** | 503 |
| --- | --- | --- | --- |
| `POST /api/rooms/{id}/chat/messages` | 5,504 | **0** | 17 |
| `GET /api/rooms/{id}/chat/messages` | 3,707 | **0** | 0 |

before/after 비교다.

| 지표 | before 1차 | before 2차 | before 3차 | **after** |
| --- | --- | --- | --- | --- |
| Redis 누적 수신 연결 | 99,907 | 120,838 | 125,678 | **14,211** |
| `RedisConnectionFailureException` | — | 2,928 | 2,812 | **0** |
| 채팅 경로 HTTP 500 | 706 | 760 | 700 | **0** |
| 채팅 경로 HTTP 503 | 13+520 | 343+272 | 305+259 | **17** |
| Redis 거부 연결 | 0 | 0 | 0 | **0** |
| Redis 사용 메모리 | 2.06MB | 2.06MB | 2.05MB | **2.08MB** |

**500이 0이고 연결이 1/9로 줄었다.** 남은 503 17건은 결함이 아니라 의도한 동작이다. 연결 실패를 서버 내부 오류가 아니라 일시적 의존성 장애로 응답하는 것이 수정의 일부였다.

before에서 47~52건 나오던 429도 이번에는 0건이다.

## 남은 WebSocket 위반은 둘로 나뉜다

`exit=2`인 3종의 WebSocket 위반 지표다.

| 시나리오 | 위반 지표 | 해석 |
| --- | --- | --- |
| `load-throughput` | `chat_websocket_opened`, `chat_websocket_session_healthy` | 연결 성립 13/18(72.2%), `connect_ms` p95 30,332ms. 원인 미규명이며 #608로 설명하지 않음 |
| `load-connections` | `chat_websocket_session_healthy` | 열린 뒤 유휴 세션 종료. #608 대상 |
| `load-rooms` | `chat_websocket_session_healthy`, `load_subscriber_delivery_complete` | 유휴 세션 종료의 전달 영향. #608 대상 |

`chat_websocket_opened`는 socket이 열린 뒤 유휴로 종료되는 지표가 아니라 연결 수립 지표다. 따라서 이 실패를 [#608](https://github.com/bamsongi-club/albam-mate/issues/608)의 유휴 60초 종료로 귀속하지 않는다. **응답 시간·전송 성공률·`dropped_iterations` 같은 성능 임계는 하나도 걸리지 않았다.**

## CPU

| 역할 | 크레딧 시작 → 끝 | CPU 평균 | CPU 최고 |
| --- | --- | --- | --- |
| App1 | 0.77 → 10.09 | 3.8% | 12.2% |
| App2 | 15.01 → 15.01 | 2.9% | 6.7% |

before와 마찬가지로 CPU는 병목이 아니다. 처리량이 크게 올랐음에도 사용률은 오히려 낮다.

## 한계

- **1회 실행이다.** 성공률·p95의 절대값은 실행 간 편차를 포함한다. 판정 근거는 500이 0이 된 사실이며, 이 지표는 before 3회가 700~760으로 좁아 1회로도 판정할 수 있다. 이 한계는 [후속 반복 측정](chat-delivery-capacity-2026-08-13-after-607-repeat.md)이 다른 release에서 같은 결과를 얻어 해소됐다.
- **`load-mixed` 4배가 마지막 계단이라 새 경계를 찾지 못했다.** 전건 성공으로 끝났으므로 이 release의 혼합 부하 한계는 4배보다 위에 있다.
- `load-throughput`의 발신자 회전 결함은 아직 고치지 않았다. 이번에는 429가 0건이라 영향이 관측되지 않았을 뿐이다.
- 측정값은 `t4g.micro` 노드와 DB 커넥션 풀 8개 구성에 묶인다.
- Run 사이에 schema를 초기화하지 않았다.
- 원자료는 `local-only`다.

## 다음

1. **[#608](https://github.com/bamsongi-club/albam-mate/issues/608) WebSocket 전용 `location` 분리** — 열린 뒤 유휴 세션 위반과 그 전달 영향을 분리한다.
2. **`load-throughput` 연결 수립 실패 재현** — #608과 별도로 5/18 실패와 30초 지연의 원인을 분리한다.
3. **`load-throughput` 발신자 회전 수정** — 이 축의 편차 원인이다.
4. **계단 상향 후 재측정** — `load-mixed`가 4배에서 전건 성공했으므로 새 경계를 찾으려면 계단을 올려야 한다.
5. **#608 수정 후 반복 측정** — 그때는 같은 조건 3회로 범위를 잡는다.

## 재현

```sh
bash run.sh publish              # 이미지 빌드가 먼저다. up 은 빌드하지 않는다
bash run.sh up -auto-approve
ALBAM_MATE_LOGIN_LIMIT=300 bash run.sh deploy
```

시나리오마다 아래를 실행한다. 6종 전체는 약 80분 걸린다.

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

이 문서의 수치는 [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-after-607.json)에 Run별·단계별로 담겨 있다. k6 summary에서 기계로 뽑았으며 손으로 옮긴 값이 아니다.
