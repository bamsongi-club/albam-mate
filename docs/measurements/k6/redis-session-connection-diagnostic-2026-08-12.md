# Redis 세션 연결 진단 보고서 (2026-08-12)

## 결론

같은 `t4g.small` App 2대·1GiB 컨테이너 조건에서 수정 전 공개·인증 Run과 Issue #607 수정 후 인증 Run을 비교했다. 수정 전 인증 Run은 Redis 연결 수립 폭증과 함께 실패했지만, Spring Session 전용 공유 연결과 자동 재연결을 적용한 후보 Run은 측정 요청 4,500건을 모두 성공했다. Redis 수락 연결은 30,555건에서 1,258건으로 95.883% 감소했고 connect timeout은 2,171건에서 0건이 됐다. 따라서 단기 Redis TCP 연결 churn이 실패 원인이었고 Issue #607 변경이 이를 제거했다는 A/B 결과를 확보했다.

- Campaign ID: `redis-session-diagnostic-20260812`
- 캠페인 상태: `completed`
- 완료 범위: 수정 전 공개·인증 진단, Issue #607 후보 인증 A/B, mixed 0.5× 후속 검증
- 제품 변경: [PR #638](https://github.com/bamsongi-club/albam-mate/pull/638) 머지 결과를 사용했으며 이번 측정 작업에서 제품 코드를 추가 수정하지 않았다.
- 근거 식별자: [비식별 evidence](evidence/redis-session-connection-diagnostic-2026-08-12.json)

| Run | 모드 | 판정 | 측정 오류율 | 측정 p95 | Redis 수락 연결 증가 | connect timeout stack |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| `redisdiag-20260812-public-current-n1` | `public-control` | PASS | 0% | 8.68ms | 164 | 0 |
| `redisdiag-20260812-auth-current-n1` | `authenticated-session` | FAIL | 66.93% | 6,040.00ms | 30,555 | 2,171 |
| `redisdiag-20260812-auth-after607-n2` | `authenticated-session` | PASS | 0% | 15.95ms | 1,258 | 0 |

인증 Run의 150개 VU는 모두 로그인 준비를 완료했다. 따라서 인증 결과는 fixture 사용자 해석 실패나 로그인 준비 실패가 아니다. 측정 요청 4,500건 중 1,488건이 성공하고 3,012건이 실패했다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 시각 | 수정 전 공개 11:42:54~11:50:12 KST, 수정 전 인증 11:57:17~12:04:36 KST, 후보 인증 21:04:37~21:11:53 KST, mixed 21:17:49~21:33:05 KST |
| App | `t4g.small` 2대, Spring container 1GiB, JVM `-Xmx256m`, Tomcat max thread 64, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large`, k6 1.3.0 |
| 시나리오 | 150 VU, 10초 간격, warm-up 60초, 측정 300초, 관찰 60초 |
| 인증 자극 | fixture 로그인 후 `/api/users/me/notifications/unread-count` |
| 공개 제어 | 비인증 공개 조회 API |
| fixture | 사용자 150명, 참조 방 10개, 사용자당 알림 300건, 미확인 5% |
| App release | 수정 전 `47fc7eca5d9c46b6ad742f036444ff6c934c7f11`, 후보 `e3e14156ead43008a41906b36c7adf8691b5f6ce` (`develop`의 PR #638 머지 `d31af72` + 진단 자산) |
| 후보 backend image | `sha256:7deb1229f53772ce3368a7f9c05979da6fbc73162d178fa0a1fd37a8f95f3903` |
| 후보 web image | `sha256:66228bfbd81a445a4e806da5a54a2401826bd3340a72e796d20e62df3b2466a6` |
| PostgreSQL image | `sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636` |
| Redis image | `sha256:bd4a0d37e7cd830117ffec9329052b4a1887afa060c265e1768f82b177ff6f43` (Redis 8.4.5) |

두 Run 모두 시작 전에 App을 중지하고 PostgreSQL `public` schema와 Redis DB를 초기화한 뒤 Flyway와 같은 fixture를 다시 적용했다. App1·App2의 release SHA, image digest, OCI revision과 실제 1GiB memory limit가 일치하지 않으면 초기화 전에 중단하도록 했다.

## 진단 결과

### 공개 제어

공개 Run은 측정 요청 4,500건이 모두 성공했다. Redis 수락 연결은 164건만 증가했고 Redis 오류 응답, rejected connection, listen overflow/drop, App Redis connection timeout은 모두 0이었다. App conntrack 최대는 262, Redis 호스트 conntrack 최대는 138이었다.

이 결과는 같은 VU 패턴에서 일반 HTTP, PostgreSQL, Nginx, VPC 또는 부하 발생기가 먼저 무너지는 가설을 지지하지 않는다.

### 인증 세션

인증 Run에서는 Redis 수락 연결이 30,555건 증가했다. 이는 공개 Run의 약 186배다. Redis 동시 client 최대는 6에 불과했고 rejected connection과 listen overflow/drop은 0이었다. 연결이 오래 유지되기보다 짧게 생성·종료되는 형태와 일치한다.

App 로그의 Redis 연결 실패 stack trace는 총 3,418회였고, 그중 connect timeout 표본은 2,171회였다. 작업 경로별 stack 표본은 다음과 같다. 한 stack에 여러 표식이 포함될 수 있으므로 합계를 서로 더해 HTTP 실패 요청 수로 해석하지 않는다.

| 분류 | stack 표본 |
| --- | ---: |
| Spring Session read | 1,136 |
| Spring Session save | 1,549 |
| 인증 Lua | 0 |
| 채팅 Lua | 0 |
| Pub/Sub | 0 |

오류 구간에는 `RedisSessionRepository.findById`, `RedisSessionRepository.save`, `RedisSession.saveDelta`와 1초 `ConnectTimeoutException`이 함께 나타났다. Redis 호스트 conntrack은 수집된 최대값 7,680에 도달했다. App 호스트에서도 각각 최대 9,156과 8,346이 관측됐다. 반면 Redis 사용 메모리는 약 2MiB, 연결 거부와 listen drop은 0이었다.

Redis `errorstat_ERR` 30,398건은 `HELLO` 호출 30,398건과 정확히 같고 `CLIENT SETINFO`는 60,796건이었다. 애플리케이션 명령의 `failed_calls`는 `EVALSHA` 초기 `NOSCRIPT` 9건 외에는 증가하지 않았다. 이 상관관계는 `ERR`이 연결 초기화 handshake와 함께 발생했음을 강하게 시사하지만, 이번 수집기는 Redis 오류 응답 원문을 저장하지 않았으므로 구체적인 오류 문구와 원인을 확정하지 않는다.

### Issue #607 후보 A/B

후보 Run은 Spring Session 전용 `LettuceConnectionFactory`에 native connection 공유와 자동 재연결을 적용한 PR #638 머지 결과를 사용했다. 일반 Redis 연결 정책과 1초 연결·2초 명령 timeout, `REJECT_COMMANDS`는 유지했다.

| 판정 기준 | 수정 전 | 후보 | 결과 |
| --- | ---: | ---: | --- |
| Redis 수락 연결 증가 | 30,555 | 1,258 (95.883% 감소) | 90% 이상 감소 충족 |
| connect timeout stack | 2,171 | 0 | 충족 |
| 측정 요청 오류율 | 66.933% | 0% (4,500/4,500 성공) | 1% 미만 충족 |
| 측정 p95 | 6,040.00ms | 15.953ms | 1초 이하 충족 |

후보 Run도 로그인 사용자 150/150을 확보했다. 전체 HTTP 실패율 1.284%에는 로그인 준비 과정의 재시도 87건이 포함되지만 setup failure는 0이고, 판정 대상인 로그인 완료 후 측정 요청은 오류가 없었다. Redis `errorReplyDelta`는 1,109건 남았으나 App 로그의 Redis 연결 실패·connect timeout·세션 read/save 실패 stack은 모두 0이었다. Redis conntrack 최대도 7,680에서 1,181로 낮아졌고 listen overflow/drop과 rejected connection은 0이었다.

### mixed 0.5× 후속 검증

`redisdiag-20260812-mixed-half-after607-n1`은 340명 fixture, 2분 warm-up, 10분 측정, 3분 관찰로 실행해 PASS했다.

| 항목 | 결과 |
| --- | ---: |
| unread-count 측정 p95 | 14.749ms |
| 알림 목록 측정 p95 | 18.049ms |
| 참가 측정 p95 | 41.317ms |
| 취소 측정 p95 | 39.776ms |
| relay 전달 표본 | 152건 |
| relay p50 / p95 / p99 | 1,268 / 3,846 / 4,341ms |
| relay retry / failed / 최종 backlog | 0 / 0 / 0 |
| Redis 연결 실패·connect timeout stack | 0 / 0 |

relay 표본이 유효성 기준 100건을 넘었고, 처리 152건 전부 성공했으며 관찰 종료 시 처리 가능한 outbox backlog가 없었다. 따라서 Redis 원인 제거 뒤 알림 polling·참가/취소·relay 경로에 새 병목이 드러나지 않았다.

## 판정

이번에 확인한 범위에서는 다음 인과관계가 가장 잘 맞는다.

1. 공개 요청은 같은 호스트·VU 조건에서 정상이다.
2. 인증 요청은 세션 read/save마다 많은 Redis 연결을 만든다.
3. Redis 동시 client나 메모리는 낮지만 conntrack이 포화된다.
4. 포화 이후 App의 Redis 전용 연결 생성이 1초 안에 끝나지 않아 세션 요청이 5xx와 장시간 응답으로 실패한다.

따라서 수정 전 `setShareNativeConnection(false)`가 Spring Session 요청마다 만든 단기 연결 churn을 직접 원인으로 확정한다. `t4g.small` 증설은 App OOM 변수를 제거했지만 이 문제를 해결하지 못했고, 같은 사양에서 연결 공유·자동 재연결만 적용한 후보가 네 가지 A/B 기준을 모두 충족했다. mixed 0.5×도 유효 PASS였으므로 이번 완료 범위에서는 추가 인프라 증설이 해결책이라는 근거가 없다.

## 후속 작업

Issue #607의 코드 테스트와 이번 실제 A/B·mixed 검증은 완료됐다. 별도 용량 캠페인에서 더 높은 배수를 측정하려면 이 결과를 새로운 정상 기준선으로 사용하되, 이번 문서만으로 전체 시스템의 최대 용량 경계를 주장하지 않는다.

## 원자료와 한계

- 원자료는 로컬 Infra 진단 워크트리의 `.run/results/<Run-ID>/`에 보존했다.
- 각 bundle에는 manifest, k6 summary/console, App 로그, 15초 CSV·JSONL, Redis INFO, PostgreSQL 진단, CloudWatch 자료와 evaluator 판정이 있다.
- 최초 두 Run의 호스트 `ss` 계측은 Docker host namespace를 보므로 container Redis TCP state를 직접 보여주지 못했다. 후보 Run 수집기는 container PID network namespace를 추가로 읽었고 App workload의 Redis `TIME_WAIT` 최대 586건을 기록했다.
- stack 수는 HTTP 요청 수가 아니라 로그 표본 수다.
- 후보 인증과 mixed 0.5×는 각각 한 번 실행했다. A/B 원인 확인과 후속 경로 검증에는 포함하지만 반복 Run 범위나 전체 최대 용량 경계로 확대 해석하지 않는다.
