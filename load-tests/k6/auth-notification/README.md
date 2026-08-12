# 인증·알림 계약 검증과 용량 측정

이 디렉터리는 애플리케이션 릴리스 SHA와 함께 고정되는 k6 시나리오와 fixture의 정본이다. 실행과 AWS 로그 수집은 `albam-mate-infra`의 `run.sh loadtest`가 담당한다.

소셜 OAuth 제공자에는 부하를 주지 않는다. 소셜 로그인은 공급자별 callback smoke test로 별도 확인한다.

## 무엇에 답하려는 측정인가

이 측정은 승인된 두 ADR이 비워둔 입력을 채운다.

- [ADR-0051](../../../docs/adr/platform/0051-p1-self-managed-aws-infrastructure.md)은 네 역할을 모두 `t4g.micro`로 두고 **어느 역할이 먼저 한계에 닿는지** 보기로 했다. 성공 기준도 단일 처리량 숫자가 아니라 실제 사용 흐름에서 먼저 실패하는 역할과 그 시점의 지표다.
- [ADR-0030](../../../docs/adr/notification/0030-postgresql-notification-relay-processing-recovery.md)은 외부 broker consumer 대안을 "선택을 뒷받침하는 부하 측정이 없다"는 이유로 제외했다. 이 측정이 그 근거를 만든다.

따라서 산출물은 합격·불합격이 아니라 **먼저 무너지는 역할과 그 시점의 조건**이다. "현재 규모에서 broker가 필요 없다"도 ADR-0030 재검토의 유효한 결론이다.

### 이 측정이 답하지 않는 것

ADR-0051은 성공 기준에서 "세션을 유지한 채 WebSocket을 열고 채팅을 보내는 부하"를 조회 부하와 구분해 언급한다. **현재 시나리오에는 채팅 WebSocket 부하가 없다.** 연결을 오래 붙드는 부하는 `-Xmx256m` 환경에서 메모리를 가장 많이 쓸 후보이므로, 여기서 나온 "먼저 무너지는 역할"은 **조회·알림 경로에 한정된 결론**이며 ADR-0051의 성공 기준을 전부 충족하지 않는다. 채팅 부하를 합친 판정은 별도 범위다.

## 부하 기준선 (1×)

용량 측정은 아래 1× 가정에서 출발해 배수만 올린다. 이 표는 측정 결과가 아니라 **팀이 정한 가정**이며, 실사용 관측이 생기면 갱신한다.

| 항목 | 1× 값 | 근거 |
| --- | --- | --- |
| 피크 활성 방 | 100개 | 팀이 정한 목표 규모 |
| 방당 참가자 | 5명 | `recruitmentCapacity` 상한 10의 절반 |
| 동시 온라인 세션 | 300명 | 활성 방 관련 사용자 중 화면을 켜둔 비율 |
| 방당 피크 1시간 이벤트 | 15건 | 참가·취소·대기열 승격 합계 |
| 파생 — 알림 이벤트 유입 | 약 25건/분 | 100개 × 15건 ÷ 60분 |
| 파생 — `unread-count` 조회 | 약 30건/초 | 300명 ÷ 10초 polling |

읽기 부하가 쓰기 부하보다 두 자릿수 크다. 알림 이벤트 유입 25건/분은 현재 relay 설정 상한(`poll-interval 5s` × `max-events-per-run 50` × 2 인스턴스 = 약 20건/초)의 2%에 불과하다. 즉 1× 조건에서 먼저 한계에 닿을 후보는 relay가 아니라 읽기 경로와 인스턴스 자원이다.

## 테스트 종류

| 종류 | 스크립트 | 답하는 질문 | 성능 임계값 |
| --- | --- | --- | --- |
| 인증 계약 | `auth-login-contract.js` | 단일 로그인 성공·실패 응답이 맞는가 | 없음 |
| 인증 제한 계약 | `auth-rate-limit-contract.js` | IP·실패·XFF 제한이 맞는가 | 계약 불일치 0건 |
| 알림 전달 계약 | `notification-delivery-contract.js` | 실제 참가·취소 Outbox가 유실·중복 없이 알림이 되는가 | 없음 |
| **알림 혼합 부하** | `mixed-load-capacity.js` | 기준선 배수를 올릴 때 **어느 역할이 먼저 무너지는가** | 측정 구간 API 오류율·p95·drop |
| 인증 용량 | `auth-capacity.js` | 로그인 도착률의 무릎이 어디인가 | 오류·1초 거절 1% 미만, p95 1초 이하, drop 0 |
| 알림 polling 용량 | `notification-polling-capacity.js` | 읽기 경로만 격리했을 때 한계 (단독 진단) | 없음 |
| 알림 fan-out 용량 | `notification-fanout-capacity.js` | 이벤트당·수신자당 relay 처리 비용 (외삽용 단가) | 없음 |

**혼합 부하가 1차 측정이다.** 나머지 용량 3종은 혼합 부하에서 특정 역할이 먼저 무너졌을 때 그 역할만 격리해 원인을 좁히는 진단 도구다. 축별로 따로 잰 상한을 합쳐 "견딘다"를 판정하지 않는다.

계약 검증과 용량 측정을 한 결과로 합치지 않는다. 계약 검증은 작은 입력의 정확성을 판정하고, 용량 측정은 입력 조건과 결과 곡선을 기록한다.

## fixture와 격리

실행기는 `fixtures/users.sql`을 PostgreSQL에 먼저 적용한다. 사용자 수는 `LOAD_TEST_USER_COUNT`이며 기본 100명이다. 모든 사용자는 Run ID별 이메일을 사용하고 비밀번호는 `LoadTest-Password-2026!`이다.

### 알림 백로그 (읽기 경로 Run 전용)

`fixtures/notification-backlog.sql`은 각 fixture 사용자에게 보존 기간 안쪽 89일에 고르게 퍼진 알림을 심는다. `users.sql` 다음에 적용하며 psql 변수 `run_id`, `user_count`, `room_count`, `notifications_per_user`, `unread_percent`가 필요하다.

AWS 실행기는 `mixed-load-capacity`와 `notification-polling-capacity`에서 이 fixture를 자동으로 적용한다. 공식 campaign은 사용자당 알림 300건·미확인 5%를 모든 배수에서 고정한다. 단일 진단 Run은 아래 환경 변수를 지원하며 적용 여부와 실제 값은 `manifest.json`의 `notificationBacklog`에 남는다.

| 환경 변수 | 기본값 | 범위 |
| --- | --- | --- |
| `NOTIFICATION_BACKLOG_ROOM_COUNT` | 10 | 1..1000 |
| `NOTIFICATION_BACKLOG_PER_USER` | 300 | 1..10000 |
| `NOTIFICATION_BACKLOG_UNREAD_PERCENT` | 5 | 0..100 |

빈 테이블에서 재면 모든 조회가 즉시 끝나 실제 조건을 재현하지 못한다. 백로그 없이 나온 읽기 지연은 실제보다 낙관적이다.

미확인 개수 조회는 `db/vendor-migration/postgresql/V5__create_p1_notification_partial_indexes.sql`의 부분 인덱스 `idx_notifications_recipient_unread (recipient_user_id, id) WHERE read_at IS NULL`를 타므로 비용이 누적 전체가 아니라 미확인 수에 비례한다. 그래서 이 fixture는 `unread_percent`로 **미확인 비율**을 조절하는 것이 핵심이다. 반면 목록 조회는 `(recipient_user_id, created_at DESC, id DESC)`로 페이지를 넘기므로 누적 전체 깊이가 그대로 비용이 된다. 두 경로가 서로 다른 축에 반응하므로 `notifications_per_user`와 `unread_percent`를 함께 조절한다.

`notifications.room_id`가 `rooms` 외래 키이므로 이 fixture는 참조용 방 `room_count`개도 함께 만든다. 혼합 시나리오는 이 방을 조회하지 않으며, 방 목록 성능은 이번 결론에서 제외한다.

| Run | 백로그 적용 |
| --- | --- |
| `mixed-load-capacity`, `notification-polling-capacity` | **적용한다.** 읽기 경로가 측정 대상이다 |
| 계약 3종, `notification-fanout-capacity` | **적용하지 않는다.** 관찰 루프가 사용자 알림 목록 전체를 페이지로 훑으므로 백로그가 있으면 폴링이 느려져 전달 지연 측정이 왜곡된다 |

Run ID는 DB fixture만 분리한다. 인증 IP 제한은 Run ID와 무관하게 부하 발생기 IP의 10분 창을 공유한다.

- `auth-rate-limit-contract`의 각 case는 비어 있는 10분 창에서 한 번씩 실행한다.
- 특히 `login-ip`는 30회 버킷을 모두 사용하므로 다음 인증 실행 전에 10분을 기다린다.
- upstream 2개 관찰은 `auth_contract_upstream_coverage`에 기록한다. 2 미만은 계약 실패가 아니라 `INSUFFICIENT_DISTRIBUTION`으로 해석한다.
- fixture 정리 명령은 아직 제공하지 않는다. 성공·실패 분석이 끝날 때까지 데이터를 보존하고, 1단계에서는 성능 스택 `down`으로 DB와 함께 제거한다.

스크립트와 로그는 세션 쿠키, CSRF 토큰과 비밀번호를 출력하지 않는다.

## 로컬 예행

운영 실행 전에 로컬에서 스크립트가 도는지 먼저 확인한다. `compose.local.yml`은 프록시·Spring 두 대·PostgreSQL·Redis를 함께 띄우고 프록시가 `X-Albam-Mate-Upstream`을 붙이므로, **다중 인스턴스·공용 Redis·upstream 구분까지 그대로 재현된다.** 로컬 실행 명령은 [docs/COMMANDS.md](../../../docs/COMMANDS.md)를 따른다.

**로컬은 동작 확인용이며 용량 근거가 아니다.** 로컬 Spring에는 `-Xmx256m`도 CPU credit도 인스턴스 간 네트워크도 없다. 여기서 나온 지연·처리량은 `t4g.micro` 결과를 대신하지 못한다.

k6는 컨테이너로 실행할 수 있고 대상은 프록시 포트(`ALBAM_MATE_LOCAL_PROXY_PORT`, 기본 5173)다. `host.docker.internal`은 Docker Desktop에서만 기본 제공되므로 `--add-host`로 명시한다. 이 옵션은 Docker Desktop에서도 그대로 동작하니 항상 붙이면 된다.

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -v "$PWD/load-tests/k6/auth-notification:/scripts" \
  -e ALBAM_MATE_TARGET_URL=http://host.docker.internal:5173 \
  -e ALBAM_MATE_RUN_ID=local-smoke-1 \
  -e AUTH_CASE=correct \
  grafana/k6 run /scripts/auth-login-contract.js
```

Git Bash에서는 셸이 `/scripts` 경로를 Windows 경로로 바꿔 버리므로 `MSYS_NO_PATHCONV=1`을 앞에 붙이고 `-v`에도 `C:/...` 형식을 쓴다.

### fixture 적용

fixture는 로컬 PostgreSQL 컨테이너에 psql로 직접 적용한다. `run_id`는 `ALBAM_MATE_RUN_ID`와 반드시 같은 소문자 안전 문자열이어야 한다.

```bash
docker compose --env-file .env -f compose.local.yml cp load-tests/k6/auth-notification/fixtures/users.sql postgres:/tmp/users.sql
docker compose --env-file .env -f compose.local.yml exec -T postgres \
  bash -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v run_id=local-smoke-1 -v user_count=50 -f /tmp/users.sql'
```

읽기 경로 Run에는 알림 백로그도 적용한다. `user_count`는 위와 같은 값을 쓴다.

```bash
docker compose --env-file .env -f compose.local.yml cp load-tests/k6/auth-notification/fixtures/notification-backlog.sql postgres:/tmp/notification-backlog.sql
docker compose --env-file .env -f compose.local.yml exec -T postgres \
  bash -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v run_id=local-smoke-1 -v user_count=50 -v room_count=10 -v notifications_per_user=300 -v unread_percent=5 -f /tmp/notification-backlog.sql'
```

백로그는 멱등하지 않다. 같은 Run ID로 다시 적용하면 스크립트가 중단한다. 다시 깔아야 하면 새 Run ID를 쓰거나 해당 Run ID의 백로그 방과 알림을 먼저 지운다. 중복 검사와 적재는 Run ID 단위 advisory lock을 건 한 트랜잭션에서 실행하므로, 같은 Run ID로 두 번 동시에 적용해도 뒤에 온 쪽이 검사에서 멈춘다.

### 무엇을 어떤 조건으로 예행하는가

| 대상 | 로컬 실행 조건 |
| --- | --- |
| 계약 3종 | 그대로 실행한다. `login-ip` case는 31회 로그인으로 제한을 일부러 건드리므로 Run 사이에 Redis를 비운다 |
| `mixed-load-capacity` | `MIXED_LOAD_SMOKE=1`로 실행한다. `1`만 스모크를 활성화하며 빈 값·`0`·`false`는 공식 용량 Run으로 취급한다. 세션 5개·알림 이벤트 6건/분으로 고정되고 제한 상향 없이 돈다. 모든 VU가 미확인 개수와 알림 목록을 조회한다 |
| `notification-polling-capacity`, `notification-fanout-capacity` | 기본값의 로그인 수가 제한 안에 들어가므로 아래 제한 상향만 적용하면 그대로 돈다 |
| `auth-capacity` | 로그인 수가 제한을 크게 넘으므로 반드시 제한 상향이 필요하다 |

로컬은 10분 창이 끝나기를 기다릴 필요가 없다. Redis를 비우면 인증 제한 상태가 즉시 초기화된다.

```bash
docker compose --env-file .env -f compose.local.yml exec redis redis-cli --scan --pattern 'albam-mate:local:ratelimit:*' | xargs -r docker compose --env-file .env -f compose.local.yml exec -T redis redis-cli DEL
```

### 제한 상향 프로파일 예행

용량 시나리오가 기다리고 있는 제한 상향은 별도 코드 없이 환경 변수로 만든다. `app.security.auth-request.*`는 상한 제약이 없어 `@Min(1)`만 지키면 된다. `.env` 또는 셸에서 아래 변수를 설정하고 local Compose를 재기동한 뒤 `CAPACITY_PROFILE_ACK`를 넘긴다. `compose.local.yml`을 직접 수정하지 않는다.

| 환경 변수 | 대응 설정 |
| --- | --- |
| `ALBAM_MATE_LOGIN_LIMIT` | `login-limit` (기본 30) |
| `APP_SECURITY_AUTHREQUEST_LOGINFAILURELIMIT` | `login-failure-limit` (기본 5) |
| `APP_SECURITY_AUTHREQUEST_SIGNUPLIMIT` | `signup-limit` (기본 5) |

`hash-slots`와 bcrypt cost는 올리지 않는다. 그 둘은 측정 대상이다. 로컬에서 확인한 값과 절차를 그대로 운영 성능 환경에 옮기고 Run manifest에 남긴다.

이 방식은 로컬에서 확인했다. `notification-polling-capacity`를 50 VU로 돌리면 상향 전에는 정확히 30명만 로그인하고 20명이 `polling_setup_failures`로 남으며(`login-limit` 기본값 30), 위 환경 변수를 넣고 재기동하면 50명 전원이 세션을 얻는다. 별도 코드나 프로파일은 필요하지 않다.

## 계약 검증

인프라 저장소에서 실행한다.

```bash
AUTH_CASE=correct ./run.sh loadtest auth-login-contract
RATE_LIMIT_CASE=signup ./run.sh loadtest auth-rate-limit-contract
NOTIFICATION_CONTRACT_EVENT_COUNT=10 ./run.sh loadtest notification-delivery-contract
```

`AUTH_CASE=correct|wrong|missing`, `RATE_LIMIT_CASE=signup|login-ip|login-failure-reset|xff`를 지원한다. 모두 VU 1로 고정된다. 계약 검증 결과의 p95는 참고값일 뿐 용량 근거로 사용하지 않는다.

## 용량 측정 공통 가드

용량 측정은 로그인 IP 제한 30회/10분을 넘거나 반복 Run에서 이전 버킷의 영향을 받을 수 있다. 실행기는 App 두 대에 고정 성능 프로파일을 배포하고 실제 컨테이너 환경이 아래 값과 일치하는지 검사하며, 검사 전에는 용량 Run을 시작하지 않는다.

팀이 다음을 결정하고 실제 배포 설정을 확인한 뒤에만 명시적으로 승인 문자열을 전달한다.

- 로그인·로그인 실패 제한 `20000`, 회원가입 제한 `5`
- bcrypt cost `10`, 전역 hash slots `4`
- relay poll `5초`, batch `50`, JVM `-Xmx256m`, DB pool `8`

```bash
CAPACITY_PROFILE_ACK=auth-notification-perf-v1 ... ./run.sh loadtest <capacity-scenario>
```

이 문자열만으로는 실행할 수 없다. 인프라 실행기가 두 App 컨테이너의 실효 설정을 검증해 같은 결과 bundle에 남겨야 한다.

## 알림 혼합 부하 (1차 측정)

인증 용량과 분리해 알림 사용 흐름만 한 Run에 태운다. 동시 온라인 세션은 10초마다 `unread-count`를 조회하고, 고정 10%는 알림 목록도 조회한다. 참가·취소 API는 알림 이벤트의 자극원일 뿐 방·참가 도메인의 성능 결론에는 포함하지 않는다.

```bash
CAPACITY_PROFILE_ACK=auth-notification-perf-v1 \
MIXED_LOAD_MULTIPLIER=1 \
LOAD_TEST_USER_COUNT=640 \
./run.sh loadtest mixed-load-capacity
```

공식 campaign은 `1×→2×→5×→10×` 순서로 진행하고 최초 실패 뒤 상승을 멈춘다. 정상·실패 사이의 정수 배수를 추가로 측정하며, 1×부터 실패했을 때만 `MIXED_HALF_SCALE_ACK=one-x-failed`와 함께 `0.5×`를 사용한다. 1×는 온라인 세션 300명과 알림 이벤트 25건/분이고 배수를 곱한 값이 그대로 부하가 된다. 필요한 fixture 사용자 수는 `(온라인 세션 + MIXED_EVENT_MAX_VUS) × 2`이며 부족하면 시작 전에 실패한다.

공식 Run은 워밍업 2분, 측정 10분, 이벤트 유입 중단 후 수렴 관찰 3분이다. `phase=measurement` 지표만 성능 판정에 사용하고 워밍업·관찰 지표는 진단 근거로 보존한다. 알림함 사용자 10%는 첫 polling 주기부터 VU 번호로 결정론적으로 고정된다.

참가 이벤트 VU는 각자 주최자·참가자 두 사용자와 정원 1인 전용 방을 만들고, 한 iteration에서 참가와 취소를 한 번씩 수행한다. VU끼리 정원을 다투지 않으며 iteration당 알림 이벤트 두 건이 생긴다.

배수마다 아래를 함께 기록하고 **가장 먼저 무너진 역할과 그 배수**를 결론으로 남긴다.

| 역할 | 관측 |
| --- | --- |
| App1·App2 | CPU credit, JVM heap과 GC, 컨테이너 메모리 한도, Tomcat 스레드 |
| PostgreSQL | CPU, connection 사용량, lock wait, 느린 쿼리 |
| Redis | 메모리, 연결 수, 명령 지연 |
| relay | `claimedCount`, `durationMs`, `oldestProcessableAgeMs` |
| k6 | 요청 오류율(`mixed_request_errors`), 조회별 p50·p95·p99, `dropped_iterations` |

측정 구간의 대상 API별 오류율 1% 미만, p95 1초 이하와 `dropped_iterations=0`을 요구한다. 1× 대비 p95 2배 조건, 서버 전달 지연, backlog 수렴과 필수 관측 누락은 campaign 판정기가 결과 bundle을 함께 읽어 판정한다. 세션·fixture 준비 실패는 성능 실패가 아니라 Run 무효다.

k6 호스트 자원도 확인 대상이다. 10×는 VU 3천 개를 띄우므로, 부하 발생기가 먼저 포화하면 측정값이 아니라 발생기 한계를 기록하게 된다.

## 인증 용량 (단독 진단)

혼합 부하에서 인증 경로가 먼저 무너졌을 때 원인을 좁히는 용도다. 실제 사용에서 로그인은 세션당 한 번뿐이므로 이 Run의 도착률을 지배 부하로 해석하지 않는다.

한 Run은 하나의 고정 도착률만 측정한다. 탐색은 정상 로그인 `1→2→4→8→16 req/s`를 3분씩 올리다가 최초 실패에서 멈추고, 정상·실패 사이를 정수 단위로 좁힌다. 확정한 정상 경계와 실패 경계는 15분씩 각각 3회 반복한다. 정상 경계에서 `wrong`과 `missing`도 3회씩 실행해 실패 응답 비용의 대칭성을 비교하되 timing-attack 안전성의 정밀 증명으로 표현하지 않는다.

```bash
CAPACITY_PROFILE_ACK=auth-notification-perf-v1 \
AUTH_CAPACITY_CASE=correct \
AUTH_CAPACITY_RATE=4 \
AUTH_CAPACITY_DURATION_SECONDS=180 \
AUTH_CAPACITY_PRE_ALLOCATED_VUS=20 \
AUTH_CAPACITY_MAX_VUS=100 \
LOAD_TEST_USER_COUNT=100 \
./run.sh loadtest auth-capacity
```

**실행 전 조건이 하나 더 있다.** 제한 상향과 별개로, Run을 시작하기 전에 인증 요청 제한 상태를 비운다. 앞선 Run이 남긴 창이 살아 있으면 상향된 제한이라도 이동창이 곧바로 429를 내면서 아래 응답 분류의 전제가 깨진다. 로컬은 [제한 상태 초기화](#로컬-예행) 명령을 쓰고, 운영 성능 환경에서는 창 길이(기본 10분)만큼 비운 뒤 시작한다. 초기화 시점을 Run manifest에 남긴다.

`AUTH_CAPACITY_CASE=correct|wrong|missing`를 지원한다. 각 Run에서 제시 요청률, 완료 처리량, `auth_capacity_one_second_rejection_rate`, 완료·거절 p95, `dropped_iterations`, App CPU와 Redis 상태를 함께 기록한다. `AUTH_CAPACITY_RATE`가 `AUTH_CAPACITY_MAX_VUS`로 낼 수 있는 도착률을 넘으면 k6가 요청을 버리므로, 제시 도착률과 실제 도착률이 갈라진 Run은 `dropped_iterations`로 판정하고 곡선에서 분리한다.

응답은 세 갈래로 분류한다.

- 완료 응답과 `Retry-After: 1`인 429는 측정 대상이다. 후자는 `auth_capacity_one_second_rejections`에 **원인을 단정하지 않은 채** 쌓인다.
- `Retry-After`가 1보다 큰 429는 인증 요청 제한이 상향되지 않았다는 뜻이다. `auth_capacity_profile_violations`가 발생한 Run은 무효다.
- 1초 429와 그 밖의 예상 밖 응답이 각각 1% 이상이거나 완료 응답 p95가 1초를 넘거나 `dropped_iterations`가 발생하면 성능 실패다. 이 경계의 앞뒤를 campaign이 좁힌다.

슬롯 거절과 이동창 제한은 status도 응답 코드도 같고 `Retry-After`만 다르다. 슬롯 거절은 항상 1초이고 이동창 제한은 남은 창을 올림한 값이라, **1보다 큰 값은 제한 미상향의 확실한 증거**다. 반대로 1초는 창의 마지막 1초에서 이동창 제한도 낼 수 있어 **응답만으로는 원인을 구분할 수 없다**. 서버가 원인을 구분해 주기 전까지 스크립트는 이를 "1초 거절"로만 기록하며, 지표 이름도 슬롯을 단정하지 않는다.

1초 거절을 bcrypt 슬롯 거절로 읽으려면 두 전제를 함께 확인한다.

1. Run 시작 전에 제한 상태를 비웠다(위 실행 전 조건).
2. `auth_capacity_retry_after_seconds` 분포가 `min`부터 `max`까지 전부 1이다.

둘 중 하나라도 어긋나면 그 Run의 거절 분류 전체를 신뢰하지 않는다.

`correct`는 성공한 로그인마다 세션을 만들므로 측정 직후 로그아웃으로 반납한다. 세션 기본 만료는 30분이라, 반납하지 않으면 곡선을 그리려고 Run을 반복할 때 뒤쪽 Run이 앞선 Run의 세션이 쌓인 Redis 위에서 측정된다. 반납은 bcrypt 슬롯을 쓰지 않아 무릎 위치를 바꾸지 않지만 iteration당 요청이 늘어나므로, 같은 도착률에 필요한 VU도 함께 늘려 `dropped_iterations`를 확인한다. 반납 실패는 `auth_capacity_session_release_failures`에 남으며 Run을 실패시키지 않는다.

## 알림 polling 용량 (단독 진단)

혼합 부하에서 읽기 경로가 먼저 무너졌을 때 다른 부하를 걷어내고 같은 경로만 다시 재는 용도다.

로그인한 모든 VU가 `unread-count`를 주기 조회하고, `NOTIFICATION_PANEL_OPEN_PERCENT` 비율의 VU만 목록 첫 페이지도 조회한다. 이는 보이는 로그인 문서에서 미확인 수를 10초마다, 열린 알림함에서는 목록도 함께 조회하는 제품 계약을 반영한다.

```bash
CAPACITY_PROFILE_ACK=auth-notification-perf-v1 \
NOTIFICATION_POLLING_VUS=100 \
NOTIFICATION_PANEL_OPEN_PERCENT=10 \
NOTIFICATION_POLLING_INTERVAL_SECONDS=10 \
NOTIFICATION_POLLING_DURATION_SECONDS=300 \
LOAD_TEST_USER_COUNT=100 \
./run.sh loadtest notification-polling-capacity
```

VU마다 서로 다른 사용자와 Redis 세션을 사용한다. unread-count·목록의 요청 수, 오류율과 p50·p95·p99를 App CPU, Redis와 DB connection 지표와 함께 비교한다.

`constant-vus`는 VU를 한꺼번에 띄우므로 각 VU는 polling 주기 안에서 자기 순번만큼 기다린 뒤 로그인한다. `hash-slots`를 운영 후보값으로 유지한 채 로그인이 몰리면 bcrypt 슬롯이 대기 없이 거절하므로, 이 분산 없이는 소수 VU만 세션을 얻어 측정 자체가 무효가 된다. 같은 이유로 429 로그인은 **같은 iteration 안에서** 1·2·3·4초 backoff로 최대 5회까지 다시 시도하며, 재시도 수는 `polling_login_retries`에 기록한다. 이 대기는 실제 브라우저처럼 polling 위상도 분산하므로, 재시도가 반복해서 관측되면 부하 결과가 아니라 분산 설정을 먼저 확인한다.

재시도를 다음 주기로 미루지 않는 이유는 Run이 재시도 도중 끝나면 그 VU가 성공도 실패도 남기지 않기 때문이다. 그러면 실제보다 적은 세션으로 측정하고도 `polling_setup_failures` 임계는 통과한다. 같은 이유로 결론을 남긴 VU 수를 `polling_resolved_vus`로 세어 `NOTIFICATION_POLLING_VUS`와 같은지 임계로 요구하고, stagger가 실행 시간을 넘지 못하도록 `NOTIFICATION_POLLING_DURATION_SECONDS`가 주기의 2배 이상일 것을 시작 전에 검사한다. `mixed-load-capacity`의 browsing 세션도 같은 가드를 쓴다(`mixed_resolved_browsing_vus`).

## 알림 fan-out 용량 (단가 측정)

포화점을 찾는 Run이 아니다. 1× 기준선의 알림 유입은 25건/분이라 relay를 포화시킬 수 없으므로, 여기서는 **이벤트 하나와 수신자 한 명이 relay에 얼마를 요구하는지** 단가를 재고 그 값으로 더 큰 규모를 외삽한다. `NOTIFICATION_FANOUT_RECIPIENTS`를 1부터 10까지 올려 처리 비용이 이벤트 수를 따르는지 수신자 수를 따르는지 가른다.

실제 API로 방과 참가 관계를 만든다. 준비 과정의 참가 알림 backlog가 비워진 뒤 주최자가 방들을 취소해, 취소 이벤트 하나가 활성 참가자 N명에게 전달되는 fan-out을 측정한다.

```bash
CAPACITY_PROFILE_ACK=auth-notification-perf-v1 \
NOTIFICATION_FANOUT_RECIPIENTS=5 \
NOTIFICATION_FANOUT_EVENT_COUNT=100 \
LOAD_TEST_USER_COUNT=12 \
./run.sh loadtest notification-fanout-capacity
```

스크립트는 `NOTIFICATION_FANOUT_RECIPIENTS=1..10`, `NOTIFICATION_FANOUT_EVENT_COUNT=1..100`을 지원하지만 공식 campaign은 수신자 `1·5·10명 × 취소 이벤트 100건 × 3회`로 고정한다. `fanout_delivery_observed_delay`는 사용자 목록에서 관찰한 지연이고, 서버 정본은 App 로그의 `deliveryDelayMs`, `recipientCount`, `oldestProcessableAgeMs`다.

한 VU가 수신자를 차례로 조회하므로 뒤에 조회하는 수신자일수록 관찰 지연에 폴링 순서만큼의 값이 더 붙는다. 라운드마다 시작 수신자를 옮겨 이 편향을 특정 수신자에 고정하지 않고 흩는다. 따라서 `fanout_delivery_observed_delay`는 전체 분포로만 읽고, `fanout_recipient` 태그는 편향이 남았는지 확인하는 용도다. 수신자 사이 지연 비교는 App 로그로 판정한다.

fan-out 용량 Run에는 고정 p95 성공 임계가 없다. 입력 이벤트 수·수신자 수·App 인스턴스·relay 5초/50건 조건과 함께 지연, backlog 최대치와 입력 종료 후 0으로 수렴한 시간을 기록한다. 다만 알림 유실·중복과 제한 시간 안의 최종 수렴은 계약 실패로 처리한다.

## 결과 판정

- 계약 Run: 계약 불일치 0건이어야 한다.
- Run 무효: 설정 불일치, fixture·세션 준비 실패, 필수 지표 누락, 부하 발생기 지속 포화 또는 공식 반복의 역할별 시작 CPU credit 차이 5 초과다.
- 성능 실패: 대상 API 오류율 1% 이상, 측정 구간 p95 1초 초과 또는 1× 대비 2배 이상, `dropped_iterations` 발생, 100개 이상 서버 표본의 알림 전달 p95 30초 초과, `oldestProcessableAgeMs` 60초 초과 후 관찰 구간 미수렴 중 하나다.
- 알림 혼합 부하는 배수를 올리며 최초 실패에서 멈추고 정상·실패 사이를 정수로 좁힌다. 유효한 결과만 경계 계산에 사용한다.
- 알림 전달 지연은 최소 100개 유효한 서버 표본이 있을 때만 p95를 비교한다.
- 모든 Run은 release SHA, 이미지 digest, fixture 사용자 수와 시나리오 환경 변수가 담긴 `manifest.json` 및 App1·App2 로그를 함께 보관한다.
- 배수별 결과가 모이면 ADR-0030의 broker 대안 판단 근거와 ADR-0051의 확장 대상을 각각 정리한다. 부하로 답할 수 있는 것은 용량뿐이며, 소비자 증식 같은 구조 변화는 별도 판단이다.
