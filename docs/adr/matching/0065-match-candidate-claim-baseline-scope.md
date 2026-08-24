# ADR-0065: MATCH candidate claim baseline 범위와 종합 정합성 gate

- 상태: 승인됨
- 작성일: 2026-08-15
- 결정일: 2026-08-15
- 관련: [MATCH-01 기술 계약 이슈 #737](https://github.com/bamsongi-club/albam-mate/issues/737), [PR #741](https://github.com/bamsongi-club/albam-mate/pull/741), [ADR-0063](0063-match-baseline-measurement-gate.md), [MATCH-01 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md), [MATCH-01 완료 기준](../../p2/matching.md#완료-기준)
- 대체 대상: [ADR-0063](0063-match-baseline-measurement-gate.md)의 candidate claim baseline 범위와 정합성 gate 해석
- 후속 ADR: 없음

## 맥락

ADR-0063은 MATCH baseline에서 후보 선점 성능과 중복·부분 성공·현재 상태 복구의 정합성을 함께 확인하도록 결정했다. 그러나 실제 candidate claim 측정 계약은 `WAITING → PROPOSED`와 Proposal·Member 저장만 측정하며, 최종 `ACCEPT`·Party 확정·`GET /api/matches/current` 복구는 별도 통합 검증으로 닫아야 한다. 이 범위를 구분하지 않으면 candidate claim latency 결과만으로 전체 MATCH baseline이 통과한 것으로 오해할 수 있다.

이 ADR은 ADR-0063의 PostgreSQL 우선·Redis 재검토 원칙을 바꾸지 않는다. candidate claim 측정의 구체 범위와, 성능 gate가 최종 상태 정합성 증거를 함께 요구한다는 해석만 부분 대체한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| candidate claim fixture에 ACCEPT·Party·재접속 복구까지 모두 넣기 | 한 실행에서 많은 흐름을 확인할 수 있다. | 서로 다른 lock·복구 흐름의 latency가 섞이고 fixture가 재현하기 어려워져 candidate claim 성능 원인을 잃는다. | 제외 |
| candidate claim 결과만으로 ADR-0063 전체 baseline을 수락 | 실행·판정이 단순하다. | 중복 성공·Party·현재 상태 복구를 확인하지 않아 성능·Redis 재검토 근거가 불완전하다. | 제외 |
| candidate claim baseline과 최종 상태 통합 검증을 분리하고, 두 증거를 함께 요구 | 측정 원인을 보존하면서 전체 MATCH 정합성 gate를 닫을 수 있다. | baseline runner와 PostgreSQL 통합 검증 결과를 모두 보관해야 한다. | 선택 |

## 결정

1. candidate claim baseline의 측정 대상은 `prioritySince ASC, requestId ASC` 후보 선점과 같은 트랜잭션의 `WAITING → PROPOSED`·Proposal·Member 저장이다. latency metric 이름은 `candidate claim transaction latency`이며 최종 ACCEPT·Party provisioning·current-state read latency를 포함하지 않는다.
2. 고정 fixture의 유효한 measured round는 정확히 `500`개 Proposal, `1,000`개 Member 전이, `1,000`개 입력 request의 정확히 한 번의 claim과 fixture manifest의 tie 순서를 확인해야 한다. 중복 Proposal·중복 claim·부분 전이는 0건이어야 한다.
3. `BASELINE_ACCEPTED`는 candidate claim 측정 결과만으로 전체 MATCH 정합성 통과를 의미하지 않는다. ADR-0063의 종합 gate와 Redis 재검토 근거로 사용하려면 `MATCH-01-T1`의 단일 현재 요청, `MATCH-01-T5`의 terminal·최종 Party 확정, `MATCH-01-T6`~`T7`의 PREPARING·재접속·current-state 복구 증거가 함께 유효해야 한다.
4. candidate claim baseline은 `PREPARING`·`ACTIVE` Party나 열린 Proposal을 fixture에 섞지 않는다. 해당 흐름의 correctness는 별도 통합 검증 결과와 원자료로 연결한다.

## 결과

- candidate claim p95·lock wait·retry·throughput의 원인을 최종 Party·복구 처리와 섞지 않는다.
- 모든 MATCH 정합성 gate를 통과하지 않은 결과로 Redis business lock 도입 또는 운영 성능 통과를 주장할 수 없다.
- baseline JSON은 candidate claim 분포와 tie 검증을 보관하고, baseline JSON과 `MATCH-01-T1`, `MATCH-01-T5`~`T7` 통합 검증 artifact는 각각 실행한 40자 `measuredGitCommitSha`를 기록한다. 종합 gate는 `docs/measurements/results/match-01/gates/`의 별도 manifest 하나가 `measuredGitCommitSha`와 필수 증거 ID별 저장소 상대 경로·각 artifact의 `gitCanonicalBlobSha256`을 기록할 때만 평가한다.
- `gitCanonicalBlobSha256`은 gate를 평가하는 커밋에서 상대 경로가 가리키는 Git blob의 원본 바이트(`git rev-parse HEAD:<path>`로 blob을 정하고 `git cat-file blob <blob>`으로 읽은 바이트)를 SHA-256으로 계산한 값이다. 서로 내용이 다른 artifact가 같은 SHA-256을 기록할 필요는 없다. 필수 증거가 없거나 중복되고, artifact 안의 `measuredGitCommitSha`가 manifest와 다르거나, 경로의 실제 blob SHA-256이 manifest 값과 다르면 종합 gate를 `INVALID`로 판정한다. 결과 artifact가 자기 SHA-256을 자기 내용 안에 기록하지 않아 순환 해시를 만들지 않는다.

## 적용·호환·rollback

- 적용: [MATCH-01 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md)이 이 ADR의 fixture·metric·acceptance 범위를 소유하고, [MATCH-01 명세](../../p2/matching.md)의 T1·T5~T7 결과를 종합 gate의 별도 증거로 연결한다. 생산 코드·runner·Flyway는 아직 없다.
- 호환: ADR-0063의 PostgreSQL 정본, Redis 선행 도입 금지, 동일 workload 비교 원칙과 기존 MATCH 제품 규칙은 유지한다.
- rollback: 범위 분리로 인한 문서 해석 문제가 발견되면 candidate claim 결과를 폐기하고 ADR-0063 및 후속 결정의 재검토를 연다. 측정 결과만으로 이미 통과한 것으로 소급하지 않는다.

## 보류 및 재검토

- 지금 하지 않는 것: candidate claim p95에 최종 Party·현재 상태 조회 시간을 합산, 단일 baseline 실행으로 모든 MATCH 상태를 대표, 결과 없이 Redis business lock 도입
- 다시 검토할 조건: candidate claim 외의 흐름을 같은 측정 세션으로 합쳐야 하는 요구가 생기거나, T1·T5~T7 통합 검증이 candidate claim fixture와 독립 증거를 유지할 수 없을 때

## 참고 자료

- [ADR-0063: MATCH 후보 탐색 성능 baseline 측정 gate](0063-match-baseline-measurement-gate.md)
- [MATCH-01 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md)
- [MATCH-01 기술 계약 이슈 #737](https://github.com/bamsongi-club/albam-mate/issues/737)

## 검증

- 상태: 검증됨
- 근거:
    - 계약: measurement contract가 candidate claim transaction의 fixture·metric·acceptance와 최종 상태 통합 검증의 분리 경계를 기록한다.
    - 구현: candidate claim PostgreSQL runner·결과 JSON report와 MATCH 통합 gate validator가 측정과 기능 정합성 evidence를 별도 입력으로 소비한다.
    - 테스트: `MatchCandidateClaimBaselinePostgresTest`, `MatchCandidateClaimBaselineExternalRunnerPostgresTest`와 `match01-integration-gate.test.mjs`가 `500`개 Proposal·`1,000`개 Member·tie 순서와 T1·T5~T7 분리 판정을 검증한다.
    - 실행: 저장된 candidate claim 결과와 최종 gate가 candidate evidence를 자체 기준에서 `ACCEPTED`로 판정한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
