# ADR-0086: ROOM T1 참가 취소·자동 승격 critical section 전략을 실측으로 선택

- 상태: 제안됨
- 작성일: 2026-08-21
- 결정일: 미정
- 관련: [GitHub Issue #955](https://github.com/bamsongi-club/albam-mate/issues/955), [비교 실행 Issue #781](https://github.com/bamsongi-club/albam-mate/issues/781), [후보 A PR #947](https://github.com/bamsongi-club/albam-mate/pull/947), [후보 B PR #960](https://github.com/bamsongi-club/albam-mate/pull/960), [ADR-0046](0046-room-waitlist-persistence-conditional-transition-retry.md), [ADR-0083](0083-room-t1-optimistic-lock-selection.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

[ADR-0046](0046-room-waitlist-persistence-conditional-transition-retry.md)은 참가 취소, 첫 현재 `WAITING`의 조건부 승격, 승격 참가 관계와 ROOM 변경을 하나의 `REQUIRES_NEW` 트랜잭션에서 함께 커밋하거나 롤백하도록 정했다. 첫 후보가 경쟁으로 먼저 바뀌면 최신 FIFO 후보를 다시 읽으며, 단일 최신 대기 상태·오류 우선순위와 ROOM version 충돌 경계를 유지해야 한다.

현행 구현은 참가 취소 때 ROOM 인원을 감소시켜 flush하고, 현재 첫 `WAITING`을 조회한 뒤 조건부 승격에 성공하면 ROOM 인원을 다시 증가시켜 flush한다. 승격 성공 경로의 최종 ROOM 인원·상태 순변화는 0이지만 ROOM write와 version 증가는 두 번 발생한다. 대기 전이는 첫 `WAITING` 조회와 해당 행의 조건부 `PROMOTED` 갱신을 서로 다른 데이터베이스 호출로 수행한다.

[Issue #781](https://github.com/bamsongi-club/albam-mate/issues/781)은 이 비용을 줄이는 두 방향을 서로 독립된 variant로 비교한다. 후보 A는 상쇄되는 ROOM 인원 감소·증가와 explicit flush를 줄이되 version-only ROOM write로 기존 충돌 경계를 보존한다. 후보 B는 첫 `WAITING` 선택과 조건부 승격의 데이터베이스 왕복을 줄인다. 후보 A는 Draft [PR #947](https://github.com/bamsongi-club/albam-mate/pull/947) head `e4d104a5ef6f2c8fa06b144acc6b0a44d89be076`, 후보 B는 Draft [PR #960](https://github.com/bamsongi-club/albam-mate/pull/960) head `3c6aa743e1371af23962de142ee4b37e1df136fc`에 구현했고, 둘 다 공통 source base `d3ea2a9ca9c972c9fdbcd8800d9c63de0240f9cc`에서 필수 CI를 통과했다. 같은 조건의 AWS 반복 비교와 production 채택 판정은 아직 완료되지 않았다.

[ADR-0083](0083-room-t1-optimistic-lock-selection.md)은 ROOM T1의 retry 전략으로 현행 즉시 재시도 낙관 락을 유지하기로 결정했다. 이 ADR은 그 retry 결정을 다시 비교하지 않고 모든 variant에 동일하게 적용한다. 이번 결정 질문은 ADR-0046과 ADR-0083의 정합성 경계를 유지하면서 어떤 참가 취소·자동 승격 persistence shape를 production에 둘 것인지다.

판단 기준은 다음과 같다.

1. 하나의 `REQUIRES_NEW` 트랜잭션, FIFO·조건부 전이, 오류 우선순위, ROOM version 충돌 경계와 전체 rollback을 유지한다.
2. 부분 취소·중복 승격·정원 초과·FIFO 역전이나 허용되지 않은 종단 상태가 하나라도 발생한 후보는 성능과 무관하게 제외한다.
3. V0·V1·V2는 같은 source base·retry 정책·환경·fixture·관측 산식에서 독립적으로 실행해 각 변경의 효과를 귀속할 수 있어야 한다.
4. 완료율, 허용된 409, p95·p99, 업무 완료 건수/초, query call/time, transaction duration과 DB 비용을 같은 UTC 구간에서 비교한다.
5. code SHA, 환경, manifest, raw artifact, digest, 유효 표본 수와 teardown 근거가 없거나 서로 맞지 않는 결과는 선택 근거로 사용하지 않는다.
6. 정확한 정량 채택 기준은 AWS 실행 전에 #781에서 승인하며, 실행 결과를 본 뒤 후보에 유리하게 새로 만들거나 바꾸지 않는다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| V0. 현행 persistence shape 유지 | 이미 검증된 ADR-0046의 저장·충돌 순서를 그대로 사용하고 새 production 변경이 없다. | 승격 성공에서 상쇄되는 ROOM write/version 증가와 첫 후보 선택·조건부 승격 왕복이 유지된다. | 비교 기준선·유효한 승자가 없을 때의 fallback |
| V1. ROOM write 축소 | 승격 성공의 ROOM 인원 순변화를 코드에 그대로 반영해 중복 ROOM write를 줄이고, 기존 `claimVersion`과 managed ROOM 동기화로 version 충돌 경계를 유지할 수 있다. | bulk version 갱신과 영속성 컨텍스트 동기화가 추가되며, 후보가 소진되는 경쟁에서 실제 인원 감소를 빠뜨리면 ROOM 상태·인원 불일치가 생긴다. | 비교 후보, 미선택 |
| V2. 첫 WAITING 선택·조건부 승격 왕복 축소 | 현재 FIFO 후보 선택과 허용된 `WAITING → PROMOTED` 전이를 더 적은 데이터베이스 왕복으로 처리할 수 있다. | PostgreSQL 전용 `MATERIALIZED` CTE와 data-modifying `UPDATE ... RETURNING` 계약을 유지해야 하며, 경쟁으로 `promoted=false`가 반환되면 호출자가 최신 FIFO를 다시 조회해야 한다. | 비교 후보, 미선택 |
| V1과 V2를 한 variant로 결합 | 두 비용을 함께 줄일 가능성이 있다. | 어느 변경이 성능·정합성 결과를 만들었는지 귀속할 수 없고 #781의 독립 variant 계약을 위반한다. | 이번 비교에서 제외 |

## 결정

최종 production 전략 선택은 보류한다. 이 ADR이 `제안됨`인 동안 V0·V1·V2는 모두 선택되지 않은 비교 대상이며, 후보 구현 PR은 `develop`에 병합하지 않는다.

#781은 다음 절차로 결정 근거를 만든다.

1. 공통 source base는 `d3ea2a9ca9c972c9fdbcd8800d9c63de0240f9cc`로 고정하고, 현재 측정 입력은 V0 `d3ea2a9c`, V1 `e4d104a5`, V2 `3c6aa743`으로 구분한다. 측정 전 후보 head가 바뀌면 code SHA·변경 경로·필수 CI를 다시 고정한다.
2. 단위·PostgreSQL 교차 경합·rollback 검증의 correctness hard gate를 먼저 적용한다. `FAIL` 후보는 성능 순위에서 제외한다.
3. 사전 승인된 반복 수·환경·fixture·지표·정량 기준과 provenance 계약을 만족하는 결과만 `VALID`로 분류한다. 누락·변조·불일치·표본 부족 결과는 `INVALID`로 제외한다.
4. `VALID`이며 correctness와 정량 기준을 모두 통과한 후보 중 사전 승인된 우선순위로 하나만 선택한다. 통과 후보가 없거나 효과를 독립적으로 귀속할 수 없으면 V0를 유지한다.
5. #781 결과와 evidence를 이 ADR에 반영해 V0·V1·V2 중 하나의 선택·제외 이유, 적용 SHA, rollout·rollback과 재검토 조건을 확정한 뒤 상태와 결정일을 갱신한다.

정확한 성능 임계값과 동률 처리 규칙은 아직 승인되지 않았다. 이 항목과 별도 승인된 AWS 실행, #781의 유효 결과가 생기기 전에는 이 ADR을 `승인됨`으로 바꾸지 않는다.

## 결과

- 얻는 것:
    - 후보 구현과 production 선택을 분리하고, 무엇을 어떤 근거로 채택했는지 Participation 정본에 남긴다.
    - correctness 실패나 불완전한 artifact를 빠른 후보로 오인하지 않고, 유효한 승자가 없으면 검증된 V0로 돌아간다.
    - ADR-0046의 저장 불변식과 ADR-0083의 retry 전략을 모든 후보의 공통 전제로 고정한다.
- 감수할 비용·위험:
    - 동일 source base의 독립 variant와 PostgreSQL·AWS 반복 실행이 필요해 구현·측정 시간이 늘어난다.
    - 이 ADR은 정량 기준과 결과가 들어오기 전까지 제안 상태로 남고 후보 PR을 production에 병합할 수 없다.
    - 후보 B는 PostgreSQL 전용 Repository 반환 계약과 동시성 회귀를 계속 유지해야 한다.
- 후속 작업:
    - #781에서 사전 정량 기준과 유효 표본·provenance 계약을 승인하고 V0·V1·V2의 정합성·성능·DB 비용 evidence를 만든다.
    - #955에서 유효 결과를 검토해 이 ADR의 최종 선택·결과·검증을 갱신하고 승인한다.
    - ADR 승인 뒤 선택 후보만 production PR로 반영하고 탈락 후보 PR은 merge하지 않은 채 결과와 재검토 조건을 보존한다.

## 적용·호환·rollback

- 제안 단계: 이 ADR과 인덱스만 추가하며 runtime 코드, API, DB schema와 배포 구성은 변경하지 않는다.
- 적용: 최종 선택이 V0이면 현재 `develop`을 유지한다. V1 또는 V2이면 ADR에 기록한 선택 SHA의 변경만 최신 `develop`에 rebase해 별도 검증한 뒤 반영한다.
- 호환: 어떤 후보를 선택해도 외부 API·오류 envelope, DB schema, FIFO 정책, T2 등록, retry/backoff와 전역 잠금 전략은 바꾸지 않는다.
- rollback: V1 또는 V2 반영 뒤 회귀가 확인되면 해당 production 변경을 되돌려 V0 persistence shape로 복구한다. 이미 생성된 Flyway migration을 수정·삭제하는 rollback은 이번 후보에 포함하지 않는다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - V1과 V2의 결합 후보
    - T2 대기 등록 `claimVersion`, retry/backoff와 전역 ROOM 잠금 전략 변경
    - API·DB schema·FIFO 정책 변경
    - 후보 B의 현재 단일 statement 계약을 넘어선 SQL shape·DB portability 변경
- 보류 이유: #781은 두 최적화의 효과를 독립적으로 귀속하는 실험이며, 공통 정합성 경계 밖의 변경을 섞으면 원인과 rollback 단위를 구분할 수 없다.
- 다시 검토할 조건:
    - V0·V1·V2 중 유효한 승자가 없고 측정이 다음 병목을 구체적으로 식별할 때
    - 선택 후보의 운영 지표에서 correctness 회귀, 예상 밖 4xx·5xx, 재시도 소진 또는 수용 불가한 tail latency·DB 비용이 확인될 때
    - 독립 결과가 V1과 V2 결합 후보의 추가 비교를 정당화하고 별도 범위·판정 기준을 승인할 때

## 참고 자료

- [GitHub Issue #955: ROOM T1 참가 취소·자동 승격 critical section 전략과 후속 ADR 확정](https://github.com/bamsongi-club/albam-mate/issues/955)
- [GitHub Issue #781: T1 참가 취소·자동 승격 critical section 축소 실험](https://github.com/bamsongi-club/albam-mate/issues/781)
- [PR #947: 참가 취소 자동 승격 ROOM 쓰기 축소](https://github.com/bamsongi-club/albam-mate/pull/947)
- [PR #960: 첫 WAITING 선택과 조건부 승격 왕복 축소](https://github.com/bamsongi-club/albam-mate/pull/960)
- [ADR-0046: ROOM 대기열을 단일 최신 상태로 저장하고 조건부 전이·등록 재시도를 조정](0046-room-waitlist-persistence-conditional-transition-retry.md)
- [ADR-0083: ROOM T1 잠금 전략으로 현행 낙관 락을 유지](0083-room-t1-optimistic-lock-selection.md)

## 검증

- 상태: 미검증
- 근거:
    - 구현:
        - 공통 source base `d3ea2a9c`에서 V1 PR #947 head `e4d104a5`, V2 PR #960 head `3c6aa743`을 독립 variant로 구현했다.
    - 계약:
        - #781의 최신 승인 T1~T5는 V0·V1·V2의 독립 변경 귀속, 정합성 hard gate, 같은 조건의 반복 성능·비용 비교와 production 채택 gate를 요구한다.
        - #955는 #781의 결과를 입력으로 신규 Participation ADR에서 최종 전략을 선택하고, 후보 구현·AWS 실행·artifact 생성을 범위 밖으로 분리한다.
    - 테스트:
        - PR #947과 PR #960은 각 후보의 H2·PostgreSQL FIFO·교차 경합·rollback 필수 테스트를 통과했다.
    - CI:
        - PR #947 head `e4d104a5`와 PR #960 head `3c6aa743`의 필수 CI와 coverage gate가 통과했다.
- 미검증:
    - AWS 실행 전 승인된 정확한 정량 기준과 최소 유효 표본·provenance 계약
    - `docs/measurements/k6/jiwon/room-t1-critical-section-comparison.md`와 evidence JSON의 유효 비교 결과
    - 최종 V0·V1·V2 선택, 적용 SHA, rollout·rollback과 CI 근거

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
