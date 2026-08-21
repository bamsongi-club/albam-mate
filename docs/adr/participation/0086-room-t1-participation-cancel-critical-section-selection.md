# ADR-0086: ROOM T1 참가 취소·자동 승격 critical section 전략을 실측으로 선택

- 상태: 승인됨
- 작성일: 2026-08-21
- 결정일: 2026-08-21
- 관련: [GitHub Issue #955](https://github.com/bamsongi-club/albam-mate/issues/955), [비교 실행 Issue #781](https://github.com/bamsongi-club/albam-mate/issues/781), [후보 A PR #947](https://github.com/bamsongi-club/albam-mate/pull/947), [후보 B PR #960](https://github.com/bamsongi-club/albam-mate/pull/960), [ADR-0046](0046-room-waitlist-persistence-conditional-transition-retry.md), [ADR-0083](0083-room-t1-optimistic-lock-selection.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

[ADR-0046](0046-room-waitlist-persistence-conditional-transition-retry.md)은 참가 취소, 첫 현재 `WAITING`의 조건부 승격, 승격 참가 관계와 ROOM 변경을 하나의 `REQUIRES_NEW` 트랜잭션에서 함께 커밋하거나 롤백하도록 정했다. 첫 후보가 경쟁으로 먼저 바뀌면 최신 FIFO 후보를 다시 읽으며, 단일 최신 대기 상태·오류 우선순위와 ROOM version 충돌 경계를 유지해야 한다.

현행 구현은 참가 취소 때 ROOM 인원을 감소시켜 flush하고, 현재 첫 `WAITING`을 조회한 뒤 조건부 승격에 성공하면 ROOM 인원을 다시 증가시켜 flush한다. 승격 성공 경로의 최종 ROOM 인원·상태 순변화는 0이지만 ROOM write와 version 증가는 두 번 발생한다. 대기 전이는 첫 `WAITING` 조회와 해당 행의 조건부 `PROMOTED` 갱신을 서로 다른 데이터베이스 호출로 수행한다.

[Issue #781](https://github.com/bamsongi-club/albam-mate/issues/781)은 이 비용을 줄이는 두 방향을 서로 독립된 variant로 비교했다. 후보 A는 상쇄되는 ROOM 인원 감소·증가와 explicit flush를 줄이되 version-only ROOM write로 기존 충돌 경계를 보존한다. 후보 B는 첫 `WAITING` 선택과 조건부 승격의 데이터베이스 왕복을 줄인다. 후보 A는 Draft [PR #947](https://github.com/bamsongi-club/albam-mate/pull/947) head `e4d104a5ef6f2c8fa06b144acc6b0a44d89be076`, 후보 B는 Draft [PR #960](https://github.com/bamsongi-club/albam-mate/pull/960) head `3c6aa743e1371af23962de142ee4b37e1df136fc`에 구현했고, 둘 다 공통 source base `d3ea2a9ca9c972c9fdbcd8800d9c63de0240f9cc`에서 필수 CI를 통과했다.

세 source의 fixture model blob은 같았지만 `rooms.region`에 허용되지 않는 `ROOM-K6` 값을 넣어 원격 fixture가 schema check에서 실패했다. 알고리즘 구현을 바꾸지 않고 실행 가능성만 복구하기 위해 세 clean source에 같은 fixture-only patch(`ROOM-K6` → `홍대`)와 회귀 검증을 적용해 V0 `43b074570fc7de25bacb3f972868b2c86bfd1839`, V1 `b8504236619d78824e5ee85c2e4588f39d736b89`, V2 `f08c59e4934c764ef187639a2935e41414b03280`을 만들었다. 이 derived SHA로 동일한 T1 hot c8 fixture와 harness를 실행해 원본 variant의 차이를 섞지 않았다.

[ADR-0083](0083-room-t1-optimistic-lock-selection.md)은 ROOM T1의 retry 전략으로 현행 즉시 재시도 낙관 락을 유지하기로 결정했다. 이 ADR은 그 retry 결정을 다시 비교하지 않고 모든 variant에 동일하게 적용한다. 이번 결정 질문은 ADR-0046과 ADR-0083의 정합성 경계를 유지하면서 어떤 참가 취소·자동 승격 persistence shape를 production에 둘 것인지다.

판단 기준은 다음과 같다.

1. 하나의 `REQUIRES_NEW` 트랜잭션, FIFO·조건부 전이, 오류 우선순위, ROOM version 충돌 경계와 전체 rollback을 유지한다.
2. 부분 취소·중복 승격·정원 초과·FIFO 역전이나 허용되지 않은 종단 상태가 하나라도 발생한 후보는 성능과 무관하게 제외한다.
3. V0·V1·V2는 같은 source base·retry 정책·환경·fixture·관측 산식에서 독립적으로 실행해 각 변경의 효과를 귀속할 수 있어야 한다.
4. 주 지표는 성공 응답 `room_request_duration{outcome:success}` p95의 후보별 세 run 중앙값이고, 성공 건수·허용된 409·업무 완료 건수/초를 guardrail로 비교한다. p99와 aggregate query/transaction·CPU·Hikari·lock 신호는 진단값으로만 보존하며 특정 SQL 비용 개선 근거로 사용하지 않는다.
5. code SHA, 환경, manifest, raw artifact, digest, 유효 표본 수와 teardown 근거가 없거나 서로 맞지 않는 결과는 선택 근거로 사용하지 않는다.
6. 정확한 정량 채택 기준은 AWS 실행 전에 #781에서 승인하며, 실행 결과를 본 뒤 후보에 유리하게 새로 만들거나 바꾸지 않는다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| V0. 현행 persistence shape 유지 | 이미 검증된 ADR-0046의 저장·충돌 순서를 그대로 사용하고 새 production 변경이 없다. | 승격 성공에서 상쇄되는 ROOM write/version 증가와 첫 후보 선택·조건부 승격 왕복이 유지된다. | 선택 |
| V1. ROOM write 축소 | 승격 성공의 ROOM 인원 순변화를 코드에 그대로 반영해 중복 ROOM write를 줄이고, 기존 `claimVersion`과 managed ROOM 동기화로 version 충돌 경계를 유지할 수 있다. | bulk version 갱신과 영속성 컨텍스트 동기화가 추가되며, 후보가 소진되는 경쟁에서 실제 인원 감소를 빠뜨리면 ROOM 상태·인원 불일치가 생긴다. | 이번 선택에서 제외, Draft 보존 |
| V2. 첫 WAITING 선택·조건부 승격 왕복 축소 | 현재 FIFO 후보 선택과 허용된 `WAITING → PROMOTED` 전이를 더 적은 데이터베이스 왕복으로 처리할 수 있다. | PostgreSQL 전용 `MATERIALIZED` CTE와 data-modifying `UPDATE ... RETURNING` 계약을 유지해야 하며, 경쟁으로 `promoted=false`가 반환되면 호출자가 최신 FIFO를 다시 조회해야 한다. | 이번 선택에서 제외, Draft 보존 |
| V1과 V2를 한 variant로 결합 | 두 비용을 함께 줄일 가능성이 있다. | 어느 변경이 성능·정합성 결과를 만들었는지 귀속할 수 없고 #781의 독립 variant 계약을 위반한다. | 이번 비교에서 제외 |

## 결정

V0 현행 persistence shape를 production 기준으로 선택한다. 후보 A(V1) PR #947와 후보 B(V2) PR #960은 이번 선택에 반영하지 않고 Draft로 보존한다. 두 PR의 병합·닫기 여부는 이 ADR의 범위에 포함하지 않으며, 별도 지시가 있을 때만 결정한다.

[Issue #781의 승인된 T1~T5 계약](https://github.com/bamsongi-club/albam-mate/issues/781#issuecomment-5361820883)에 따라 2026-08-21에 각 variant를 T1 hot c8·5 rounds로 세 번씩, 총 아홉 번 실행했다. 아홉 run은 모두 source/release/harness/infra provenance와 artifact digest가 일치하고 `COMPLETED`, k6 exit code `0`, before/after diagnosis `PASS`, `final-result=PASS`, `resource-signals=PASS`를 만족해 `VALID/PASS`였다.

| variant | 원본 SHA | fixture-only derived SHA | 성공 p95 (ms) | p95 중앙값 | 성공 / 허용 409 합계 | T5 기계적 결과 |
| --- | --- | --- | --- | ---: | ---: | --- |
| V0 | `d3ea2a9` | `43b07457` | 613.387 / 233.240 / 190.070 | 233.240 | 48 / 72 | 선택 |
| V1 | `e4d104a5` | `b8504236` | 457.929 / 625.617 / 150.593 | 457.929 | 45 / 75 | p95 96.3% 악화, 성공·409 guardrail 열화 |
| V2 | `3c6aa743` | `f08c59e` | 548.845 / 567.947 / 179.045 | 548.845 | 48 / 72 | p95 135.3% 악화 |

V1과 V2는 모두 세 run 중 두 run에서 V0보다 낮은 p95를 보였지만, 사전 승인된 핵심 기준인 p95 중앙값 최소 20% 개선을 충족하지 못했다. V1은 성공 건수와 허용 409도 V0보다 불리했다. V2는 성공·허용 409 합계와 업무 완료율 guardrail에 문제는 없었지만, p95 중앙값은 크게 악화됐다. 따라서 T5의 `RETAIN_V0` 결과와 현재 서비스 규모·참가 취소 자동 승격 경로의 사용량을 함께 근거로 V0를 선택한다. V1과 V2가 모든 조건에서 부적절하다는 뜻은 아니며, 아래 재검토 조건에서만 다시 비교한다.

## 결과

- 얻는 것:
    - 현재 규모에서 추가 영속성 복잡성 없이 ADR-0046의 저장 불변식과 ADR-0083의 retry 전략을 유지한다.
    - correctness 실패나 불완전한 artifact를 빠른 후보로 오인하지 않고, 사전 승인된 기준으로 V0 선택 근거를 남긴다.
- 감수할 비용·위험:
    - 승격 성공 경로의 중복 ROOM write와 첫 후보 선택·조건부 승격의 두 데이터베이스 호출은 유지된다.
    - V0의 첫 run p95 변동이 커, 더 넓은 조건을 새로 실행하려면 별도 이슈와 사전 판정 기준이 필요하다.
- 후속 작업:
    - V1·V2 Draft PR은 열린 상태로 결과와 재검토 조건을 보존한다.
    - 새 후보·새 부하 조건 또는 attribution 도구를 도입하려면 별도 이슈에서 계약을 승인한 뒤 재측정한다.

## 적용·호환·rollback

- 적용: V0는 현행 production persistence shape이므로 이번 결정에 따른 runtime 코드, API, DB schema, 배포 구성 변경은 없다.
- 호환: 어떤 후보를 선택해도 외부 API·오류 envelope, DB schema, FIFO 정책, T2 등록, retry/backoff와 전역 잠금 전략은 바꾸지 않는다.
- rollback: V1·V2를 production에 반영하지 않으므로 별도 rollback은 없다. 이후 후보를 반영했다가 회귀가 확인되면 해당 변경을 되돌려 V0로 복구한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - V1과 V2의 결합 후보
    - T2 대기 등록 `claimVersion`, retry/backoff와 전역 ROOM 잠금 전략 변경
    - API·DB schema·FIFO 정책 변경
    - 후보 B의 현재 단일 statement 계약을 넘어선 SQL shape·DB portability 변경
- 보류 이유: #781은 두 최적화의 효과를 독립적으로 귀속하는 실험이며, 공통 정합성 경계 밖의 변경을 섞으면 원인과 rollback 단위를 구분할 수 없다.
- 다시 검토할 조건:
    - 취소 후 자동 승격이 빈번해지고, `ROOM` version 충돌·재시도·write 또는 lock wait가 이 경로의 p95 악화 원인으로 확인될 때는 V1을 우선 검토한다.
    - 첫 `WAITING` 조회와 조건부 승격의 데이터베이스 왕복 또는 경쟁 후 재조회가 대기열 승격 지연의 주된 원인으로 확인될 때는 V2를 우선 검토한다.
    - 선택 후보의 운영 지표에서 correctness 회귀, 예상 밖 4xx·5xx, 재시도 소진 또는 수용 불가한 tail latency·DB 비용이 확인될 때
    - 단일 p95 급등이나 일반적인 사용자 증가만으로는 재검토하지 않는다. 새 조건을 도입하면 별도 이슈에서 FIFO·rollback·ROOM 상태 불변식, 동일 환경·fixture와 사전 정의된 채택 기준을 승인한 뒤 비교한다.
    - 독립 결과가 V1과 V2 결합 후보의 추가 비교를 정당화하고 별도 범위·판정 기준을 승인할 때

## 참고 자료

- [GitHub Issue #955: ROOM T1 참가 취소·자동 승격 critical section 전략과 후속 ADR 확정](https://github.com/bamsongi-club/albam-mate/issues/955)
- [GitHub Issue #781: T1 참가 취소·자동 승격 critical section 축소 실험](https://github.com/bamsongi-club/albam-mate/issues/781)
- [Issue #781 최소 AWS 측정 계약](https://github.com/bamsongi-club/albam-mate/issues/781#issuecomment-5361820883)
- [ROOM T1 critical section 비교 결과](../../measurements/k6/jiwon/room-t1-critical-section-comparison-2026-08-21.md)
- [ROOM T1 critical section campaign evidence](../../measurements/k6/jiwon/evidence/room-t1-critical-section-comparison-2026-08-21.json)
- [PR #947: 참가 취소 자동 승격 ROOM 쓰기 축소](https://github.com/bamsongi-club/albam-mate/pull/947)
- [PR #960: 첫 WAITING 선택과 조건부 승격 왕복 축소](https://github.com/bamsongi-club/albam-mate/pull/960)
- [ADR-0046: ROOM 대기열을 단일 최신 상태로 저장하고 조건부 전이·등록 재시도를 조정](0046-room-waitlist-persistence-conditional-transition-retry.md)
- [ADR-0083: ROOM T1 잠금 전략으로 현행 낙관 락을 유지](0083-room-t1-optimistic-lock-selection.md)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - 공통 source base `d3ea2a9c`에서 V1 PR #947 head `e4d104a5`, V2 PR #960 head `3c6aa743`을 독립 variant로 구현했고, 세 variant에 동일한 fixture-only schema compatibility patch만 적용한 derived SHA로 AWS 입력을 고정했다.
    - 계약:
        - #781의 최신 승인 T1~T5는 V0·V1·V2의 독립 변경 귀속, correctness hard gate, T1 hot c8 세 번씩의 측정과 보수적 V0 fallback 산식을 요구한다.
    - 테스트:
        - PR #947과 PR #960은 각 후보의 H2·PostgreSQL FIFO·교차 경합·rollback 필수 테스트를 통과했다.
    - CI:
        - PR #947 head `e4d104a5`와 PR #960 head `3c6aa743`의 필수 CI와 coverage gate가 통과했다.
    - AWS 측정:
        - [비교 결과](../../measurements/k6/jiwon/room-t1-critical-section-comparison-2026-08-21.md)와 [campaign evidence](../../measurements/k6/jiwon/evidence/room-t1-critical-section-comparison-2026-08-21.json)에 V0·V1·V2 각각 세 run, 총 아홉 run의 source/tree/fixture patch provenance, non-secret artifact digest, k6 exit code, before/after diagnosis, final result와 resource signal을 보존했다.
        - 모든 run은 `VALID/PASS`였고, 위 표의 p95 중앙값과 guardrail로 T5를 적용한 `RETAIN_V0` 결과를 V0 선택 근거로 사용했다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
