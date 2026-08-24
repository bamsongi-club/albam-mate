# ADR-0091: MATCH-01 T10 AWS 성능 측정 유예와 기능 gate 분리

- 상태: 승인됨
- 작성일: 2026-08-24
- 결정일: 2026-08-24
- 관련: [결정 이슈 #1063](https://github.com/bamsongi-club/albam-mate/issues/1063), [MATCH-01 통합 정합성 검증 이슈 #746](https://github.com/bamsongi-club/albam-mate/issues/746), [T10 측정 이슈 #999](https://github.com/bamsongi-club/albam-mate/issues/999), [T10 runner PR #1047](https://github.com/bamsongi-club/albam-mate/pull/1047), [T11 baseline evidence #776](https://github.com/bamsongi-club/albam-mate/issues/776), [T11 비교 이슈 #1000](https://github.com/bamsongi-club/albam-mate/issues/1000), [T11 측정 경로 PR #1051](https://github.com/bamsongi-club/albam-mate/pull/1051), [MATCH-01 명세](../../p2/matching.md), [candidate 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md), [response 측정 계약](../../measurements/match-01-response-completion-baseline-contract.md), [ADR-0063](0063-match-baseline-measurement-gate.md), [ADR-0065](0065-match-candidate-claim-baseline-scope.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

`MATCH-01-AC9`는 candidate claim·제안 생성·응답 완료의 부하 지연과 DB lock 대기를 측정하고, 쿼리·인덱스·트랜잭션 개선 전후의 p95·처리량·실패율을 비교하도록 정의한다. 검증 매핑상 `MATCH-01-T10`은 후보 부하와 candidate claim의 쿼리·인덱스·트랜잭션 개선 전후 비교를, `MATCH-01-T11`은 응답 완료 경로의 고정 fixture·지연·처리량·실패율·최종 상태 정합성을 담당한다.

현재까지 확인한 사실은 다음과 같다.

- #776의 T11 response-completion baseline consumer는 자체 계약 기준으로 `RESPONSE_BASELINE_ACCEPTED`를 기록했다. 이는 개선 전후 비교쌍이나 최종 #746 gate의 동일 SHA 증거가 아니다. #1000과 PR #1051은 T11 before/after 측정·비교를 위한 후속 범위이며, 현재 그 비교 evidence는 없다.
- #999의 T10 AWS 직접 실행은 PostgreSQL `t4g.micro`에서 애플리케이션·runner·PostgreSQL이 같은 측정 환경 자원을 공유하는 동안 OOM으로 종료되어 `INVALID`가 됐다. 유효한 T10 raw measurement와 개선 전후 비교 결과는 생성되지 않았다.
- 현재 인프라 결정은 App1·App2를 `t4g.small`로 두되 PostgreSQL은 `t4g.micro`로 유지한다. PostgreSQL 증설은 이 작업의 승인 범위에 없으며, App 계층 상향만으로 PostgreSQL의 메모리 제약을 해소할 수 없다.
- 해당 AWS 임시 스택은 철거됐다. 같은 조건에서 T10을 다시 실행하려면 PostgreSQL 사양 또는 측정 topology를 바꾸는 별도 인프라 결정이 필요하다.

따라서 T10의 `INVALID`를 통과로 바꾸거나 T11 결과만으로 AC9 전체를 닫는 것은 검증 계약과 ADR-0065의 provenance 원칙을 위반한다. 반대로 현재 인프라 제약을 인정하지 않으면 기능 통합 검증까지 성능 측정 불가 상태에 묶인다. 기능 정합성 검증과 운영 성능 검증을 같은 완료 상태로 표시하지 않는 범위 결정이 필요하다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| T10 유효 결과가 생길 때까지 #746과 MATCH-01 전체를 보류 | 기존 AC9와 최종 gate 규칙을 그대로 지킨다. | PostgreSQL 증설을 하지 않는 현재 전제에서는 재시점이 없고, 이미 검증 가능한 기능 흐름까지 완료할 수 없다. | 제외 |
| #1000의 T11 결과를 T10까지 통과한 것으로 해석 | 추가 AWS 실행이 없다. | T10의 후보 부하·DB lock·쿼리 전후 비교를 검증하지 않으며, T10 `INVALID`를 사실상 `PASS`로 바꾸게 된다. | 제외 |
| PostgreSQL을 증설하거나 측정 topology를 즉시 변경해 T10을 재실행 | AC9 전체를 원래 계약대로 닫을 가능성이 있다. | 현재 인프라 승인 범위와 비용·자원 제약을 벗어나며, 별도 설계·승인·재현성 검토가 필요하다. | 보류 |
| T10을 현재 기능 gate에서 유예하고 성능 완료와 기능 완료를 분리 | `INVALID`를 보존하면서 기능 정합성 검증을 독립적으로 진행할 수 있다. 성능 미검증을 P2 상태에 명시할 수 있다. | MATCH-01 전체와 AC9는 완료되지 않으며, 유예를 적용한 별도 판정·문서 갱신이 필요하다. | **선택** |

## 결정

1. `MATCH-01-AC9`, `MATCH-01-T10`, `MATCH-01-T11`의 원래 계약은 삭제·축소·T-ID 변경 없이 유지한다. 이 ADR은 제품 성능 요구를 없애는 결정이 아니라, 현재 MVP 기능 gate에서 T10 측정을 유예하는 결정이다.
2. #746 gate가 이 ADR을 참조해 판정 문서·gate manifest에 유예를 명시하고 그 manifest로 실행될 때에만 T10을 `DEFERRED_BY_ADR_0091`로 별도 기록한다. 현재 #746 gate는 T10 미완료 상태로 유지한다. T10의 AWS `INVALID` artifact는 append-only 증거로 보존하며 `PASS`·`ACCEPTED`·유효 성능 결과로 재분류하지 않는다.
3. T11 결과는 T11 자체의 response-completion 증거로만 소비한다. #746 최종 gate가 T11을 사용하려면 머지 후 최종 `develop` SHA, artifact 경로, Git blob hash, 외부 raw digest가 현재 gate manifest와 일치해야 한다. 이전 SHA의 T11 결과를 최종 SHA 증거로 소급하지 않는다.
4. 현재 gate의 판정 축은 다음처럼 분리한다.
   - 기능·정합성 축: T10 성능 비교를 포함하지 않은 범위의 유효한 통합 evidence만 평가한다. 이 축에서도 모든 포함 artifact의 SHA·경로·hash·결과 정합성을 확인하고, 포함 결과의 `INVALID`·`FAILED`는 통과시키지 않는다.
   - 성능 축: T10이 유예되었으므로 `미측정`으로 남긴다. T11이 통과해도 T10이 없는 AC9 전체 또는 MATCH-01 전체 성능 완료를 선언하지 않는다.
5. P2 상태표를 갱신할 때는 기능 정합성 evidence와 운영 성능 evidence를 한 열에서 섞어 `검증 완료`로 표시하지 않는다. T10 유예 사유, PostgreSQL `t4g.micro` 제약, T10 `INVALID` 보존, 성능 결론 부재를 명시하고 운영 성능 상태는 `미측정`으로 유지한다.
6. 이 ADR은 #746 이슈 본문의 완료 기준을 자동으로 바꾸거나, #746을 자동으로 완료 처리할 권한을 부여하지 않는다. #746을 기능 gate 결과로 종료하려면 이 ADR을 참조해 이슈의 판정 범위와 결과 이름을 별도로 합의해야 하며, 그렇지 않으면 #746은 T10 미완료 상태로 남긴다.

## 결과

- 얻는 것:
    - T10 측정 실패 원인과 T11의 독립적인 통과 사실을 섞지 않는다.
    - 기능 통합 검증을 진행하더라도 성능·운영 용량을 검증했다고 과장하지 않는다.
    - 최종 SHA·artifact hash·`INVALID`·`FAILED` 판정의 기존 증거 원칙을 기능 gate에 계속 적용한다.
- 감수할 비용·위험:
    - T10이 유예되므로 AC9와 MATCH-01 전체는 성능 기준에서 미완료다.
    - 현재 기능 gate에 `DEFERRED_BY_ADR_0091`와 성능 미측정을 표현할 문서·판정 변경이 필요하다.
    - PostgreSQL 동시성·lock 대기·candidate claim 성능에 대한 운영 결론을 내릴 수 없다.
- 후속 작업:
    - #1063은 결정과 후속 정본 반영 상태를 별도 Issue 기록으로 유지하고, #999는 `INVALID` 증거가 보존된 종료 상태로 남긴다. #746을 기능 gate 결과로 종료하려면 이 ADR이 요구하는 별도 판정 범위·결과 이름 합의와 gate manifest 반영을 먼저 완료한 뒤 Issue 상태를 별도로 갱신한다. #1000은 PR #1051이 병합되고 T11 before/after evidence가 확보된 뒤에만 닫는다. 어느 Issue의 종료도 MATCH-01 운영 성능 검증 완료를 뜻하지 않는다.
    - P2 상태표와 #746 결과에는 이 ADR과 T10 유예를 연결하되, 운영 성능 열은 미측정으로 둔다.
    - 별도 infra 결정으로 PostgreSQL 사양 또는 측정 topology가 바뀌면 새 최종 release SHA에서 T10·T11을 다시 검증한다.

## 적용·호환·rollback

- 적용: 이 ADR 승인 후 #746의 판정 문서·gate manifest가 T10 유예를 명시할 때만 적용한다. 유예를 증거 누락의 은폐 수단으로 사용하지 않으며, 유효한 기능 evidence의 SHA·경로·hash 검증은 생략하지 않는다.
- 호환: ADR-0063의 PostgreSQL 우선 원칙과 ADR-0065의 candidate claim·최종 상태 evidence 분리 및 동일 SHA·Git blob hash 원칙은 유지한다. 이 ADR은 candidate 측정 계약의 fixture·metric·acceptance를 바꾸지 않는다.
- rollback: 승인된 T10 유예를 철회하거나 결정 범위를 바꾸려면 후속 ADR로 이 ADR을 대체하고, 후속 ADR 승인 및 #746 gate manifest 반영 뒤 상태를 전환한다. 그 전까지 T10 `INVALID`를 blocker로 유지하고 기능 상태·P2 검증 완료 갱신을 중단한다. 이후 비교 가능한 topology에서 유효한 T10 before/after 결과가 확보되면 후속 ADR의 결정에 따라 유예를 해제하고 검증 근거를 갱신한다.

## 보류 및 재검토

- 지금 하지 않는 것: PostgreSQL 증설, App·DB topology 재설계, T10 `INVALID` 재판정, T11 결과의 최종 SHA 소급, 성능 p95·처리량·실패율 목표 확정
- 보류 이유: PostgreSQL 증설은 현재 승인 범위가 아니며, 현재 T10 실행은 유효한 원자료를 만들지 못했다. 측정할 수 없는 값을 기능 완료로 채우지 않는다.
- 다시 검토할 조건:
    - PostgreSQL 사양 또는 측정 topology 변경이 별도 infra 결정으로 승인될 때
    - 최종 release SHA에서 동일 fixture·환경 profile·provenance를 보존한 T10 before/after 실행이 가능할 때
    - MATCH-01을 운영 출시 범위로 확장해 성능·용량 기준이 필수 조건이 될 때
    - PostgreSQL lock·CPU·메모리·디스크 병목에 대한 새로운 직접 증거가 생길 때

## 참고 자료

- [MATCH-01 완료 기준·검증 증거 매핑](../../p2/matching.md#완료-기준)
- [P2 기능 상태](../../p2/README.md#기능별-현재-상태)
- [MATCH 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md)
- [MATCH 응답 완료 지연 측정 계약](../../measurements/match-01-response-completion-baseline-contract.md)
- [ADR-0063: MATCH 후보 탐색 성능 baseline 측정 gate](0063-match-baseline-measurement-gate.md)
- [ADR-0065: MATCH candidate claim baseline 범위와 종합 정합성 gate](0065-match-candidate-claim-baseline-scope.md)
- [T10 runner PR #1047](https://github.com/bamsongi-club/albam-mate/pull/1047) — AWS 측정 실행 경로
- [T11 측정 경로 PR #1051](https://github.com/bamsongi-club/albam-mate/pull/1051) — before/after 측정·비교 validator 경로이며 비교 evidence 자체는 아님

## 검증

- 상태: 미검증
- 근거:
    - 계약: [MATCH-01 명세](../../p2/matching.md)의 AC9·T10·T11과 검증 매핑은 T10 후보 부하/전후 비교와 T11 응답 완료 비교를 별도 증거로 요구한다.
    - gate 기록: [#746 gate manifest](../../measurements/results/match-01/gates/match-01-gate.json)는 `MATCH_01_FUNCTIONAL_GATE_ACCEPTED_WITH_T10_DEFERRED`와 `performanceStatus: UNVERIFIED`를 기록하고, T10을 `DEFERRED_BY_ADR_0091` 및 보존된 `INVALID` evidence에 연결한다. #746의 종료는 이 기능 gate campaign의 종료이며 MATCH-01 운영 성능 완료를 뜻하지 않는다.
    - 테스트: #776의 T11 response-completion baseline consumer는 T11 자체의 계약 판정을 제공한다. [#1000 T11 before/after 비교 결과](../../measurements/results/match-01/response-completion/match-01-t11-response-completion-before-after-c392d66af159a06c32030361ed39c677d46df403.md)는 동일 fixture·환경 profile의 `RESPONSE_COMPARISON_ACCEPTED` evidence를 보존하며, PR #1051은 그 측정 경로와 비교 validator를 제공한다. 이 결과는 T10을 대체하지 않고 최종 develop gate evidence로 자동 소급하지 않는다.
    - 실행: [#999 T10 측정 이슈](https://github.com/bamsongi-club/albam-mate/issues/999)의 종료 기록과 [T10 `INVALID` 보고서](../../measurements/results/match-01/candidate-claim/match-01-t10-aws-invalid-2026-08-24.md)는 PostgreSQL `t4g.micro` OOM으로 유효한 raw·before/after 결과가 생성되지 않았음을 확인한다.
- 미검증:
    - 현재 `develop`에서 gate validator를 실행하면 manifest의 과거 `measuredGitCommitSha`가 현재 저장소 이력의 조상이 아니어서 `INVALID`가 된다. 따라서 manifest의 gate decision은 #746 campaign에 기록된 판정으로 보존하며, 현재 `develop`의 재검증 완료로 표시하지 않는다.
    - T10의 유효한 AWS before/after 성능 결과가 없다.
    - T10이 유예된 상태이므로 `MATCH-01-AC9`와 MATCH-01 전체 운영 성능 완료를 선언하지 않는다.

> 상태 값과 번호·대체 규칙은 [루트 ADR README](../README.md)를 따른다.
