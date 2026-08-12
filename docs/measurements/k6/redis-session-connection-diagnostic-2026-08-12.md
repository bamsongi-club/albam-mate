# Redis 세션 연결 진단 보고서 (2026-08-12)

## 결론

같은 `t4g.small` App 2대·1GiB 컨테이너 조건에서 공개 API와 인증 세션 API를 150 VU로 비교했다. 공개 제어 Run은 오류 없이 통과했지만 인증 Run은 Redis 연결 수립 폭증과 함께 실패했다. App 메모리 부족을 제거한 뒤에도 인증 경로에서만 재현됐으므로, 이번 결과는 Redis 인스턴스 사양 부족보다 현재 App의 Redis 연결 수명주기 문제를 첫 개선 대상으로 지지한다.

- Campaign ID: `redis-session-diagnostic-20260812`
- 캠페인 상태: `completed-with-limitations`
- 완료 범위: 현행 설정의 공개·인증 기준 Run과 원인 진단
- 미완료 범위: 후보 설정 A/B, 복구 통합 테스트, mixed 0.5× 재실행
- 중단 사유: 후보 수정은 기존 [Issue #607](https://github.com/bamsongi-club/albam-mate/issues/607) 담당자의 작업 범위다. 별도 합의 없이 코드를 변경하지 않았다.
- 근거 식별자: [비식별 evidence](evidence/redis-session-connection-diagnostic-2026-08-12.json)

| Run | 모드 | 판정 | 측정 오류율 | 측정 p95 | Redis 수락 연결 증가 | connect timeout stack |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| `redisdiag-20260812-public-current-n1` | `public-control` | PASS | 0% | 8.68ms | 164 | 0 |
| `redisdiag-20260812-auth-current-n1` | `authenticated-session` | FAIL | 66.93% | 6,040.00ms | 30,555 | 2,171 |

인증 Run의 150개 VU는 모두 로그인 준비를 완료했다. 따라서 인증 결과는 fixture 사용자 해석 실패나 로그인 준비 실패가 아니다. 측정 요청 4,500건 중 1,488건이 성공하고 3,012건이 실패했다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 시각 | 공개 2026-08-12 11:42:54~11:50:12 KST, 인증 11:57:17~12:04:36 KST |
| App | `t4g.small` 2대, Spring container 1GiB, JVM `-Xmx256m`, Tomcat max thread 64, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large`, k6 1.3.0 |
| 시나리오 | 150 VU, 10초 간격, warm-up 60초, 측정 300초, 관찰 60초 |
| 인증 자극 | fixture 로그인 후 `/api/users/me/notifications/unread-count` |
| 공개 제어 | 비인증 공개 조회 API |
| fixture | 사용자 150명, 참조 방 10개, 사용자당 알림 300건, 미확인 5% |
| App release | `47fc7eca5d9c46b6ad742f036444ff6c934c7f11` |
| backend image | `sha256:1f6f0646c5bfee54affdb0107799e42390a6ad74466253d4167820534021aa0b` |
| web image | `sha256:abbe6e852f28a3ba62ab6d58dba5075bd66f08b279925ac9f5ce6b552030519e` |
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

## 판정

이번에 확인한 범위에서는 다음 인과관계가 가장 잘 맞는다.

1. 공개 요청은 같은 호스트·VU 조건에서 정상이다.
2. 인증 요청은 세션 read/save마다 많은 Redis 연결을 만든다.
3. Redis 동시 client나 메모리는 낮지만 conntrack이 포화된다.
4. 포화 이후 App의 Redis 전용 연결 생성이 1초 안에 끝나지 않아 세션 요청이 5xx와 장시간 응답으로 실패한다.

따라서 현행 `setShareNativeConnection(false)`와 연결 풀 부재가 만드는 단기 연결 churn을 첫 개선 후보로 유지한다. `t4g.small` 증설은 App OOM 변수를 제거했지만 이 문제를 해결하지 못했다. 다만 후보 설정을 적용한 A/B를 실행하지 않았으므로 `shareNativeConnection=true`의 개선 효과 자체는 아직 측정 완료 상태가 아니다.

## 후속 작업

Issue #607 담당자가 후보 변경을 구현하면 같은 스택 사양으로 다음 순서만 다시 실행한다.

1. 후보 `authenticated-session`: 수락 연결 증가 90% 이상 감소, connect timeout 0, 오류율 1% 미만, p95 1초 이하를 모두 요구한다.
2. Redis 중단·재기동 복구 테스트: 제한 시간 내 실패, fallback·명령 재생 없음, 재기동 뒤 새 요청 성공을 확인한다.
3. 후보가 통과할 때만 mixed 0.5×를 실행하고 relay 표본 100건 이상을 요구한다.

이번 문서는 원인 진단 결과이며 Issue #607의 구현 범위나 담당자를 변경하지 않는다.

## 원자료와 한계

- 원자료는 로컬 Infra 진단 워크트리의 `.run/results/<Run-ID>/`에 보존했다.
- 각 bundle에는 manifest, k6 summary/console, App 로그, 15초 CSV·JSONL, Redis INFO, PostgreSQL 진단, CloudWatch 자료와 evaluator 판정이 있다.
- 최초 두 Run의 호스트 `ss` 계측은 Docker host namespace를 보므로 container Redis TCP state를 직접 보여주지 못했다. 이 사실은 결과에 숨기지 않았고, 후보 Run용 수집기는 container PID network namespace를 추가로 읽도록 보완했다. 연결 churn 판단은 Redis 누적 수락 연결, HELLO/SETINFO 명령 수, conntrack, App stack trace를 함께 사용했다.
- stack 수는 HTTP 요청 수가 아니라 로그 표본 수다.
- 후보 A/B와 mixed Run을 실행하지 않았으므로 전체 용량 합격을 주장하지 않는다.
