# backend-tester 운영 outcome series

이 디렉터리는 AIW-07에서 고정한 고위험 백엔드 작업 다섯 건의 공개 가능한 측정 결과를 보관한다. 측정 프로토콜을 충족한 결과와 증거 부족 결과를 분리하며, 일반적인 모델 성능이나 생산성 향상을 주장하지 않는다.

## 측정 범위

- 측정 이슈: [#315](https://github.com/bamsongi-club/albam-mate/issues/315)
- 고정 cohort:
  - Case 1: [#270](https://github.com/bamsongi-club/albam-mate/issues/270) / [PR #339](https://github.com/bamsongi-club/albam-mate/pull/339)
  - Case 2: [#294](https://github.com/bamsongi-club/albam-mate/issues/294) / [PR #317](https://github.com/bamsongi-club/albam-mate/pull/317)
  - Case 3: [#266](https://github.com/bamsongi-club/albam-mate/issues/266) / [PR #329](https://github.com/bamsongi-club/albam-mate/pull/329)
  - Case 4: [#267](https://github.com/bamsongi-club/albam-mate/issues/267) / [PR #340](https://github.com/bamsongi-club/albam-mate/pull/340)
  - Case 5: [#268](https://github.com/bamsongi-club/albam-mate/issues/268) / [PR #365](https://github.com/bamsongi-club/albam-mate/pull/365)
- 회고 사례: [#265](https://github.com/bamsongi-club/albam-mate/issues/265) / [PR #309](https://github.com/bamsongi-club/albam-mate/pull/309)을 `R1`로 별도 기록한다.
- outcome: `caught-risk`, `confirmed-only`, `missed-risk`, `inconclusive`

Case 2·3 완료 뒤 [Draft-first·fail-fast 프로토콜 정합화](https://github.com/bamsongi-club/albam-mate/issues/315#issuecomment-5166340258)가 이루어졌다. 프로토콜 전환 전후 결과를 같은 품질의 표본으로 소급하지 않는다.

## Case 결과

| Case | outcome | 측정 근거 | 최종 전달 상태 |
| --- | --- | --- | --- |
| 1 | `inconclusive` | 최초 위험 snapshot의 tester가 환경·증거 부족으로 `unverified`였고 reviewer 수정 뒤 head가 바뀌어 검출 여부를 재현할 수 없다. | PR #339 병합 |
| 2 | `inconclusive` | tester·reviewer 결과를 보기 전 Draft head 사전 등록이 없다. | PR #317 병합 |
| 3 | `inconclusive` | PR 생성 전 diff 등록은 있으나, 리뷰 수정 뒤 병합된 head를 사전 등록하고 동일 head에서 tester를 실행한 증거가 없다. | PR #329 병합 |
| 4 | `missed-risk` | 등록 snapshot의 fresh tester가 통과한 뒤 독립 리뷰가 actionable risk를 발견했다. | 추가 사람 리뷰 수정을 거쳐 PR #340 병합 |
| 5 | `missed-risk` | tester가 coverage gate 한 건을 차단했지만, 다음 tester-pass snapshot에서 T-ID reviewer와 후속 사람 리뷰가 추가 위험을 발견했다. | 추가 사람 리뷰 수정을 거쳐 PR #365 병합 |

Case 1·4·5의 runner 수치와 판정은 각각의 공개 결과 코멘트에 기록된 측정 snapshot 기준이다.

- [Case 1 결과](https://github.com/bamsongi-club/albam-mate/issues/315#issuecomment-5166883093)
- [Case 4 결과](https://github.com/bamsongi-club/albam-mate/issues/315#issuecomment-5168353695)
- [Case 5 결과](https://github.com/bamsongi-club/albam-mate/issues/315#issuecomment-5175010322)

Case 4·5의 공개 결과 코멘트 이후 PR head가 다시 변경됐다. 이후 사람 리뷰와 최종 CI·병합은 제품 전달 완료 근거이며, 앞선 tester 측정 snapshot의 `pass`를 새 head에 소급하지 않는다.

## 집계

정식 cohort 다섯 건의 outcome은 다음과 같다.

| outcome | 건수 | 비율 |
| --- | ---: | ---: |
| `caught-risk` | 0 | 0% |
| `confirmed-only` | 0 | 0% |
| `missed-risk` | 2 | 40% |
| `inconclusive` | 3 | 60% |

- 비교 가능한 Case: 2건
- 비교 가능한 Case 중 `missed-risk`: 2건
- tester가 실제로 전달을 차단한 별도 위험: Case 5 coverage gate 1건
- 제품 전달 완료: 5건 모두 병합·이슈 종료

표본의 60%가 `inconclusive`이므로 검출률이나 생산성 효과를 수치로 일반화하지 않는다. 비교 가능한 두 건이 모두 `missed-risk`라는 사실은 tester 단독 승인을 지지하지 않지만, Case 5의 coverage 차단은 결정적 runner의 실행 게이트 가치를 보여준다.

## R1 회고 사례

R1은 정식 cohort 등록 전에 결과가 관찰된 #265 / PR #309 사례다. 독립 T-ID 실행과 snapshot validator가 통과한 head에서 후속 사람 리뷰가 PostgreSQL 시간 창의 flaky 위험을 발견해 수정했다.

- 회고 판정: `missed-risk`
- 정식 다섯 건의 분자·분모에서 제외
- 사전 등록이 없으므로 정식 Case와 같은 인과 비교로 사용하지 않음

## Private archive 상태

Case 1·4·5의 공개 결과에는 `not-archived`와 receipt 부재가 명시되어 있다. Case 2·3과 R1에도 검증 가능한 receipt가 없다. 임시 packet·expected·result와 raw 로그를 사후에 재구성하지 않으며, 존재하지 않는 receipt hash나 core link hash를 만들지 않는다.

- archive status: `not-archived`
- archived formal cases: 0 / 5
- receipt hash: 없음
- core link hash: 없음
- R1 archive: `not-archived`

따라서 AIW-07은 archive 완료가 아니라 공개 증거만으로 `completed-with-limitations` 상태에서 종료한다. 이 상태는 후속 평가에서 replay 가능한 archive로 사용하면 안 된다.

## 결론

1. 결정적 runner는 승인 명령 실행, snapshot 일치, JUnit freshness, fail-fast와 validator 검증에 유용하다.
2. `backend-tester`가 통과한 snapshot에서도 T-ID reviewer와 일반 위험 리뷰가 actionable risk를 발견했으므로 tester는 semantic review를 대체하지 않는다.
3. LLM wrapper 자체의 추가 가치는 이 series에서 입증되지 않았다. runner의 가치와 LLM 실행 주체의 가치를 구분해야 한다.
4. 후속 실험은 실행자 교체와 명령 집합 축소를 분리하고, 동일 snapshot에서 동등성과 비용을 각각 측정해야 한다.

## 한계

- 다섯 건 중 세 건은 프로토콜 증거 부족으로 `inconclusive`다.
- #266~#268은 같은 알림 relay 수명주기에 속해 독립 표본으로 보기 어렵다.
- Case 2·3 사이에 측정 프로토콜이 정합화됐다.
- Case 4·5의 측정 결과 코멘트 뒤 추가 사람 리뷰와 head 변경이 있었다.
- raw 실행 자료와 receipt가 없어 replay·독립 재계산이 불가능하다.
- R1은 사전 등록 전 관찰된 회고 사례다.

이 결과는 Albam Mate의 해당 다섯 작업에 대한 운영 관찰이며, 다른 저장소·모델·작업 유형의 일반 성능 주장으로 사용하지 않는다.
