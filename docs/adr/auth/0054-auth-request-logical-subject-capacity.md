# ADR-0054: 인증 요청 제한 용량을 논리 주체 단위로 관리

- 상태: 승인됨
- 작성일: 2026-08-10
- 결정일: 2026-08-09
- 관련: [ADR-0013](0013-p0-password-storage-auth-request-protection.md), [API 인증 요청 남용 제한](../../API.md#인증-요청-남용-제한), [아키텍처 다중 인스턴스 실행](../../ARCHITECTURE.md#다중-인스턴스-실행), [Issue #534](https://github.com/bamsongi-club/albam-mate/issues/534), [Issue #535](https://github.com/bamsongi-club/albam-mate/issues/535), [PR #538](https://github.com/bamsongi-club/albam-mate/pull/538)
- 대체 대상: 없음
- 후속 ADR: 없음

> 한 줄 요약: 만료된 자리만 재사용하고 유효한 기존 제한 상태는 보존한다. 용량이 가득 찼을 때 처음 보는 주체만 `Retry-After` 없는 503으로 거절한다.

## 맥락

인증 요청 제한기는 회원가입·로그인 요청 상태를 Redis에 저장한다. 공격자가 서로 다른 IP나 이메일·IP 조합을 계속 만들면 이동 창 동안 상태가 계속 늘어날 수 있다. 인증 제한기는 세션·채팅과 Redis를 공유하므로, 인증 상태 증가는 다른 기능의 메모리 여유에도 영향을 줄 수 있다.

기존 설정에는 `max-ip-keys`와 `max-failure-keys`가 있었지만 운영 Redis 구현은 이 상한을 적용하지 않았다. 테스트용 인메모리 구현은 상한에 도달하면 유효한 상태를 축출했다. 이 차이 때문에 테스트가 운영의 용량 위험과 축출 우회를 재현하지 못했다.

이 문서에서 사용하는 용어는 다음과 같다.

- **물리 상태**: Redis에 실제로 저장되는 signup bucket, login bucket, 실패 bucket 또는 로그인 검증 gate다.
- **논리 IP 주체**: 같은 원격 IP의 signup·login 물리 상태를 하나로 묶은 단위다.
- **논리 실패 주체**: 같은 `(정규화 이메일, 원격 IP)`의 실패 bucket과 로그인 검증 gate를 하나로 묶은 단위다.
- **기존 주체**: 등록부에 이미 있으며 아직 물리 상태가 남아 있는 논리 주체다.
- **신규 주체**: 등록부에 없고 새 물리 상태를 만들려는 논리 주체다.
- **포화**: 만료 항목을 회수한 뒤에도 등록된 논리 주체 수가 설정 상한에 도달한 상태다.

판단 기준은 다음과 같다.

- Redis 상태 수에 명시적인 상한이 있어야 한다.
- 공격자가 신규 상태를 만들어 유효한 기존 제한 상태를 밀어낼 수 없어야 한다.
- 포화 원인이 요청자 본인의 요청 횟수 초과인지, 시스템 용량 부족인지 외부 응답에서 구분해야 한다.
- Redis와 인메모리 구현의 관찰 가능한 동작이 같아야 한다.
- 이메일·IP 또는 그 다이제스트를 로그와 메트릭 라벨에 노출하지 않아야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 논리 주체 등록부를 두고 만료 항목만 회수한다 | 용량 상한과 기존 제한 상태를 함께 지킨다. Redis와 인메모리 구현을 같은 계약으로 맞출 수 있다. | 등록부가 포화되면 정상적인 신규 주체도 일시적으로 503을 받을 수 있다. | 선택 |
| 오래된 유효 상태를 축출한다 | 신규 주체를 계속 받을 수 있다. | 공격자가 신규 상태를 반복 생성해 기존 제한 상태를 밀어내고 제한을 우회할 수 있다. | 제외 |
| 개별 Redis 키 TTL과 `maxmemory`에만 의존한다 | 구현이 단순하다. | 인증 제한기가 사용할 수 있는 용량을 명시적으로 제한하지 못하고 다른 Redis 기능과 메모리를 경쟁한다. | 제외 |
| 포화 요청을 429로 거절한다 | 기존 요청 횟수 제한 응답을 재사용할 수 있다. | 포화는 다른 주체가 용량을 소모해도 발생하므로 요청자 책임을 뜻하는 429와 맞지 않는다. 용량 회복 시각도 약속할 수 없다. | 제외 |

## 결정

IP 계열과 실패 계열에 각각 논리 주체 등록부를 둔다. `max-ip-keys`와 `max-failure-keys`는 물리 Redis 키 수가 아니라 활성 논리 주체 수의 상한이다. 두 설정의 기본값은 각각 `10,000`이다. 이 수치는 ADR-0013이 정한 값이 아니라 이번 결정에서 확정한 기본값이다.

관찰 가능한 계약은 다음과 같다.

| 조건 | 처리 | 외부 결과 |
| --- | --- | --- |
| 기존 주체가 요청한다 | 기존 요청 횟수·로그인 실패 규칙으로 계속 평가한다. | 허용 또는 기존 429 계약 |
| 신규 주체가 요청하고 등록부에 자리가 있다 | 새 논리 주체를 등록하고 물리 상태를 만든다. | 허용 또는 기존 제한 계약 |
| 신규 주체가 요청하고 등록부가 포화됐다 | 사용자 조회·생성, PostgreSQL 접근과 bcrypt 실행 전에 중단한다. | `503 SERVICE_UNAVAILABLE`, `Retry-After` 없음 |
| 물리 상태의 TTL이 만료됐다 | 만료된 등록을 회수하고 상한을 다시 판정한다. | 회수된 자리를 신규 주체가 사용할 수 있음 |
| Redis 결과를 안전하게 확인할 수 없다 | 인메모리 fallback 없이 처리를 중단한다. | `503 SERVICE_UNAVAILABLE`, `Retry-After` 없음 |

유효한 물리 상태와 진행 중인 로그인 검증 gate는 포화를 이유로 축출하지 않는다. `checkLoginFailureAllowed()`처럼 새 상태를 만들지 않는 평가는 포화만으로 막지 않는다. 실패 초기화와 gate 해제처럼 용량을 반납하는 처리도 포화만으로 막지 않는다.

Redis 구현은 계열별 ZSET 등록부를 사용한다. member는 논리 주체의 SHA-256 다이제스트이고, score는 그 주체에 남은 물리 상태 중 가장 늦은 만료 시각이다. Lua 원자 연산 하나에서 Redis 서버 시각을 기준으로 다음 순서를 수행한다.

1. 만료된 등록부 항목을 회수한다.
2. 현재 논리 주체가 기존 주체인지 확인한다.
3. 신규 주체라면 등록 수와 상한을 비교한다.
4. 허용되는 경우 물리 상태를 처리하고 등록부 score를 갱신한다.

실패 bucket과 gate 중 하나를 제거한 뒤 다른 상태가 남으면 남은 TTL로 score를 다시 계산한다. 둘 다 없으면 등록부에서 논리 주체를 즉시 제거한다. 인메모리 구현도 같은 논리 주체, 만료 회수, 축출 금지와 포화 응답 계약을 따른다.

관측은 다음 고정 라벨만 사용한다.

- 사용률 gauge: `family=ip|failure`
- 거절 counter: `family=ip|failure`, `reason=capacity_saturated|redis_unavailable`

사용률은 `현재 등록 수 / 설정 상한`이다. 여러 인스턴스의 gauge는 합계가 아니라 최댓값으로 해석한다. 한 인스턴스에서 인증 제한 window 동안 새 관측이 없으면 그 인스턴스의 gauge는 0으로 돌아간다. 이메일·IP 원문과 논리 주체 다이제스트는 로그·메트릭·라벨에 넣지 않는다.

## 결과

- 얻는 것:
    - 인증 제한 상태의 논리 주체 수를 명시적으로 제한한다.
    - 신규 상태가 유효한 기존 제한 상태를 밀어내는 우회 경로를 막는다.
    - Redis와 인메모리 구현이 같은 포화·만료 계약을 재현한다.
    - 포화 503과 Redis 장애 503을 메트릭에서 구분한다.
- 감수할 비용·위험:
    - IP 등록부가 포화되면 처음 보는 IP의 정상 회원가입·로그인도 자리가 생길 때까지 503을 받을 수 있다.
    - 실패 등록부가 포화되면 처음 보는 `(정규화 이메일, 원격 IP)`의 로그인 검증이 503을 받을 수 있다. 서로 다른 주체를 대량 생성한 공격이 정상 신규 주체의 로그인을 일시적으로 막을 수 있다.
    - 용량 회복은 다른 주체의 TTL 만료나 명시적인 상태 반납에 달려 있으므로 정확한 재시도 시각을 제공하지 않는다.
    - 논리 주체 상한은 실제 Redis 물리 키 수나 바이트 사용량의 상한이 아니다. 물리 키 수와 메모리 사용량은 별도로 측정해야 한다.
- 후속 작업:
    - 운영 유사 부하에서 논리 주체 수, 물리 키 수와 Redis 메모리 사용량의 관계를 측정한다.
    - `family`별 사용률과 `capacity_saturated` 거절을 관측해 기본 상한 `10,000`의 적정성을 검토한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - SHA-256 논리 주체 다이제스트를 HMAC으로 바꾸는 작업과 키 회전 정책
    - 프로세스 종료 뒤 gate가 TTL까지 남는 시간을 줄이는 작업
    - JVM별 비밀번호 해시 슬롯 4개 정책 변경
    - Redis 장애 시 fail-closed 계약 변경
    - Redis Cluster용 namespace hash tag 도입
    - 실제 AWS 부하테스트와 상한 자동 조정
- 보류 이유:
    - 이번 결정은 단일 Redis에서 용량을 제한하면서 기존 제한 상태를 보존하는 경계를 확정한다. 키 보호 방식, Redis 배치 방식과 운영 상한 튜닝은 별도의 보안·배포·측정 근거가 필요하다.
- 다시 검토할 조건:
    - `capacity_saturated` 거절이 정상 트래픽에서 반복되거나 장시간 지속될 때
    - 사용률이 경고 기준 70%·85%·100%에 자주 도달할 때
    - 실패 등록부 포화로 정상 신규 주체의 로그인이 반복해서 차단될 때
    - 인증 제한의 물리 키나 메모리 사용량이 같은 Redis의 세션·채팅 가용성에 영향을 줄 때
    - Redis Cluster를 도입하거나 Redis 장애 복구 계약을 바꿀 때

## 참고 자료

- [Issue #534 결정 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/534#issuecomment-5231623619)
- [Issue #535 구현 범위와 승인 테스트](https://github.com/bamsongi-club/albam-mate/issues/535)
- [PR #538 구현과 검증](https://github.com/bamsongi-club/albam-mate/pull/538)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - [`RedisAuthenticationRequestLimiter`](../../../src/main/java/cloud/bamsongi/albammate/infra/redis/RedisAuthenticationRequestLimiter.java)는 IP·실패 등록부의 만료 회수, 기존 주체 확인, 상한 판정, 물리 상태 처리와 TTL score 갱신을 Lua 원자 연산으로 수행한다.
        - [`InMemoryAuthenticationRequestLimiter`](../../../src/main/java/cloud/bamsongi/albammate/global/security/ratelimit/InMemoryAuthenticationRequestLimiter.java)는 유효 상태를 축출하지 않고 Redis와 같은 논리 주체·포화 계약을 재현한다.
        - [`AuthenticationRequestLimiterMetrics`](../../../src/main/java/cloud/bamsongi/albammate/global/security/ratelimit/AuthenticationRequestLimiterMetrics.java)는 고정 `family`·`reason` 라벨로 사용률과 거절 원인을 기록한다.
    - 계약:
        - [`API.md`](../../API.md#인증-요청-남용-제한)는 기존 주체의 429 규칙, 신규 주체의 `Retry-After` 없는 503과 인증 처리 전 종료를 명시한다.
        - [`ARCHITECTURE.md`](../../ARCHITECTURE.md#다중-인스턴스-실행)는 Redis 원자 처리, profile별 구현과 메트릭 집계 계약을 명시한다.
    - 테스트:
        - [`InMemoryAuthenticationRequestLimiterTest`](../../../src/test/java/cloud/bamsongi/albammate/global/security/ratelimit/InMemoryAuthenticationRequestLimiterTest.java)와 [`RedisAuthenticationRequestLimiterPostgresTest`](../../../src/postgresTest/java/cloud/bamsongi/albammate/infra/redis/RedisAuthenticationRequestLimiterPostgresTest.java)는 논리 주체 공유, 포화 선차단, 기존 주체 유지, 동시 상한, TTL 회수, 실패 bucket·gate 공유와 용량 반납을 검증한다.
        - [`RedisAuthenticationRequestLimiterTest`](../../../src/test/java/cloud/bamsongi/albammate/infra/redis/RedisAuthenticationRequestLimiterTest.java)와 [`AuthenticationRequestLimiterMetricsTest`](../../../src/test/java/cloud/bamsongi/albammate/global/security/ratelimit/AuthenticationRequestLimiterMetricsTest.java)는 계약 밖 Lua 응답의 fail-closed 처리와 메트릭 라벨·만료를 검증한다.
    - CI:
        - [PR #538](https://github.com/bamsongi-club/albam-mate/pull/538)에서 Backend Fast, Local Runtime, PostgreSQL 1/2·2/2, Coverage Gate와 CI Gate가 통과한 뒤 `develop`에 병합됐다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
