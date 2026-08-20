# ADR-0083: ROOM T1 잠금 전략으로 현행 낙관 락을 유지

- 상태: 승인됨
- 작성일: 2026-08-20
- 결정일: 2026-08-20
- 관련: [GitHub Issue #787](https://github.com/bamsongi-club/albam-mate/issues/787), [ADR-0005](0005-room-participation-optimistic-locking.md), [ADR-0046](0046-room-waitlist-persistence-conditional-transition-retry.md), [ROOM 잠금 전략 비교](../../measurements/room-lock-strategy-comparison.md), [ROOM 잠금 전략 비교 의사결정 보고서](../../measurements/results/room-lock-strategy-comparison/decision-report.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

ROOM T1의 잠금 전략 후보는 A(현행 즉시 재시도 낙관 락), B(낙관 락 + bounded jitter), C(`PESSIMISTIC_WRITE`)였다. #786의 최초 600회 비교 계획은 사용자 승인에 따라 빠른 결정을 위한 최소 범위로 축소했다. 실제 근거는 T1·constant-arrival-rate·constant-mixed·c8(초당 8건)·60초에서 A/B 각각 4회와 C fail-fast 원자료다.

현재 `develop`은 후보 A의 핵심 동시성 구현을 이미 사용한다. `RoomOptimisticLockRetrier`와 `RoomWaitlistRegistrationCoordinator`의 후보 A blob은 결정 시점 `origin/develop`과 같고, `Room`은 `@Version`으로 ROOM 행의 동시 변경을 감지한다. 따라서 이 ADR은 새 잠금 구현을 도입하는 변경이 아니라, 같은 조건에서 검증한 결과로 현행 전략을 유지할지 결정한다.

이번 근거는 T1에만 한정한다. T2는 이번 timeboxed 비교에 포함하지 않았으므로, T2의 전략을 새로 선택하거나 T1 결과를 ROOM 전체 명령에 일반화하지 않는다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| A. 현행 즉시 재시도 낙관 락 | 추가 대기 정책 없이 낙관 락 충돌만 최초 포함 최대 3회 재시도한다. 동일 T1 조건에서 A의 p50·p95·p99 중앙값이 B보다 낮았다. | 더 높은 실제 경합에서는 즉시 재시도가 재충돌을 늘릴 수 있다. | 선택 |
| B. bounded jitter를 둔 낙관 락 | 동시 재시도를 시간상 분산할 수 있다. | A보다 성공률 이점이 없고, 측정한 p50·p95·p99 중앙값이 모두 높았다. sleep·jitter 정책이라는 새 production 복잡성도 추가된다. | 제외 |
| C. `PESSIMISTIC_WRITE`와 lock timeout | 같은 ROOM 변경을 데이터베이스 행 잠금으로 직렬화할 수 있다. | p4에서 실제 5xx와 response contract failure가 각각 4건 발생해 T7 1차 분류가 `FAIL`이었다. nonzero k6 종료 뒤 resource signal이 없어 runner artifact는 별도로 `INVALID`였으며, 성능 비교 대상이 아니다. | 제외 |

## 결정

ROOM T1의 production 잠금 전략으로 후보 A, 즉 현행 즉시 재시도 낙관 락을 유지한다.

후보 A를 적용하기 위한 새 production 코드, API, 스키마 또는 배포 구성을 만들지 않는다. #791은 production 구현이 아니라 측정용 문서·PostgreSQL 테스트 변경이므로 merge하지 않는다. 후보 B와 C의 production 변경도 merge하지 않는다.

T2는 이번 ADR의 새 선택 범위가 아니다. ADR-0046이 정한 현행 낙관 락·조건부 version claim 및 단일 재시도 예산은 유지하되, T2의 우열을 T1 수치로 판단하지 않는다.

## 결과

- 얻는 것:
    - A/B 각각 1,920 ROOM 요청의 동일 T1 조건에서 A는 5xx·contract failure 없이 통과했고, B보다 낮은 지연 중앙값을 보였다.
    - 현행 production 구현을 유지하므로 외부 API 오류 계약, ROOM version, 대기 순번, 데이터베이스 스키마와 배포 구성은 변하지 않는다.
    - C의 실제 correctness 실패를 성능 개선으로 해석하지 않고, 원인 규명과 재검증 전에는 후보에서 제외한다.
- 감수할 비용·위험:
    - A/B 비교는 후보당 4회, T1·constant-mixed·c8 한 조건에 한정돼 더 높은 경합·다른 분포·T2의 성능을 보장하지 않는다.
    - 현행 즉시 재시도는 운영에서 충돌·소진이 커질 경우 다시 검토해야 한다.
- 후속 작업:
    - #791·#792·#793은 이 ADR과 연결해 merge 없이 종료한다.
    - T2 또는 새 고경합 근거로 전략을 바꿀 필요가 생기면 별도 비교와 새 ADR로 결정한다.

## 적용·호환·rollback

- 적용: 결정 시점 `origin/develop`의 `RoomOptimisticLockRetrier`와 `RoomWaitlistRegistrationCoordinator`를 그대로 사용한다. 이 ADR은 production code 변경을 만들지 않는다.
- 호환: `ROOM_CONCURRENT_MODIFICATION`을 포함한 기존 오류 계약, `ROOMS.version`, 대기 순번 제약과 현재 데이터베이스·배포 구성은 유지한다.
- rollback: 새 runtime 변경이 없으므로 rollback할 배포 단위가 없다. 후속 B 또는 C 실험은 A를 바꾸는 독립 production 변경으로 분리한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - T2, barrier·hot/spread 조건, c2·c4·c16, T3·T4·T5의 추가 비교
    - 후보 B의 부분 도입
    - 후보 C의 5xx 원인을 추정만으로 고쳐 재도입
- 보류 이유: 이번 결정은 사용자 승인 timeboxed T1 근거만 사용한다. A보다 나은 B의 이점은 확인되지 않았고, C는 correctness hard gate를 통과하지 못했다.
- 다시 검토할 조건:
    - 운영 또는 같은 provenance의 T1 측정에서 A의 재시도 소진, 예상 밖 응답, HTTP 5xx 또는 tail latency가 수용 불가로 확인될 때
    - T2 또는 더 높은 실제 경합에서 A/B/C의 별도 비교가 필요해질 때
    - C의 5xx 원인이 코드·오류 매핑·관측으로 특정되고, 같은 correctness 계약에서 유효한 재측정이 성공할 때

## 참고 자료

- [ROOM 잠금 전략 비교 의사결정 보고서](../../measurements/results/room-lock-strategy-comparison/decision-report.md)
- [ROOM 잠금 전략 비교 측정 계약](../../measurements/room-lock-strategy-comparison-contract.md)
- [ADR-0005: 방 참가 동시성 제어에 낙관 락을 사용](0005-room-participation-optimistic-locking.md)
- [ADR-0046: ROOM 대기열을 단일 최신 상태로 저장하고 조건부 전이·등록 재시도를 조정](0046-room-waitlist-persistence-conditional-transition-retry.md)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - `Room`은 `@Version`으로 ROOM 동시 변경을 감지한다.
        - `RoomOptimisticLockRetrier`는 낙관 락 충돌만 지연 없이 최초 포함 최대 3회 시도하고, 소진 시 `ROOM_CONCURRENT_MODIFICATION`을 반환한다.
        - `RoomWaitlistRegistrationCoordinator`는 ROOM 충돌과 정확한 대기 순번 UNIQUE 충돌을 같은 3회 예산으로 처리한다.
    - 계약:
        - #786은 A/B/C 후보 SHA와 T1·constant-mixed·c8·8 req/s·60초 조건을 보존했고, 최종 report는 winner를 자동으로 만들지 않는다.
    - 테스트:
        - A는 4회·1,920 ROOM 요청에서 성공 1,918건, 허용된 동시성 409 2건, 예상 밖 4xx·5xx·contract failure 0건이었다. 성공 요청 지연 중앙값은 p50 51.689ms, p95 87.731ms, p99 179.609ms였다.
        - B는 4회·1,920 ROOM 요청에서 성공 1,917건, 허용된 동시성 409 3건, 예상 밖 4xx·5xx·contract failure 0건이었다. 성공 요청 지연 중앙값은 p50 58.445ms, p95 102.966ms, p99 287.297ms였다.
        - C p4는 480 ROOM 요청 중 실제 5xx·contract failure가 같은 4개 응답에 함께 기록되어 T7 1차 `FAIL`이었다. resource signal 누락으로 runner artifact가 `INVALID`였지만, 그 누락이 correctness 실패를 없애지는 않는다.
    - CI:
        - #786 결과 보존 PR #935는 문서·측정 도구 변경에 대해 CI 통과 후 merge됐다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
