# backend-tester 운영 outcome pilot

이 디렉터리는 실제 백엔드 작업 한 건에서 구현자 판단과 독립 `backend-tester` 실행 결과를 비교한 단일 pilot의 공개 가능한 핵심 결과만 보관한다. 일반적인 모델 성능이나 생산성 향상을 주장하지 않으며, 측정 대상 snapshot과 실행 집계에 한정해 해석한다.

## 측정 범위

- pilot 이슈: `#226`
- 측정 대상: `#263` 알림 저장 모델·모듈 기반 구현
- 승인 테스트 계약: 대상 이슈에서 사람이 승인한 `T1`~`T6`
- base commit: `884375b409625f978a1170eef5f007f144325925`
- snapshot: commit을 만들기 전에 고정한 implementation diff
- tester 실행 전 판단: `proceed`

평가 snapshot은 commit이 생성되기 전의 고정 diff였으므로 `targetCommit`은 `null`이다. 이후 생성·병합된 PR head나 merge commit을 이 pilot이 검증한 target commit으로 소급하지 않는다.

## 실행 결과

| 실행 | snapshot | 결과 | 명령 합산 시간 | 해석 |
| --- | --- | --- | ---: | --- |
| 1 | 초기 implementation diff | `T1`~`T6` fail | 188,253ms | 독립 선택 실행에서 `@SpringBootConfiguration`을 찾지 못하는 격리 실행 결함을 검출해 전달을 차단했다. |
| 2 | 수정 implementation diff | `T1`~`T6` fail | 179,479ms | 각 명령이 약 30초에서 종료된 실행기 시간 제한으로 판정했다. 코드 변경 없이 충분한 제한을 적용한 fresh tester를 다시 요구했다. |
| 3 | 수정 implementation diff | `T1`~`T6` pass | 190,900ms | 시작·종료 snapshot이 일치했고 기존 schema·expected validator가 여섯 결과를 승인했다. |

- fresh tester 실행: 3회
- tester 명령 누적 시간: 558,632ms
- 구현 재작업: 2회
- 사람 개입: 1회
- 금지 행위 audit: 세 실행 모두 제품·테스트 수정, stage, commit, push, PR 생성 `false`

구현자의 `proceed` 판단 뒤 fresh tester가 전체 suite만으로 드러나지 않았던 실제 격리 실행 결함을 검출했고, 수정 후 새 snapshot의 tester와 T-ID reviewer가 통과했다. 따라서 이 사례의 outcome은 `caught-risk`다.

## 최종 검증 기록

- 전체 H2 `test --rerun-tasks`: 통과
- 최종 fresh tester의 PostgreSQL `T1`~`T5`와 모듈 `T6`: 통과
- 동일 snapshot의 전체 `postgresTest`, CI 대응 build, H2·PostgreSQL 합산 JaCoCo gate와 `conventionCheck`: 통과
- T-ID reviewer: `T1`~`T6` 모두 pass, 종합 Approve
- 별도 일반 위험 리뷰: Finding 없음
- 기존 test-contract harness: 8 cases, 31 checks 통과, 대상 모델 실행은 `not-run`
- 문서 링크와 `git diff --check`: 통과

위 항목은 pilot 당시 고정 snapshot에서 기록한 결과다. 이 디렉터리를 추가한 현재 브랜치의 검증 결과는 PR 본문에서 별도로 보고한다.

## Private archive 연결

상세 tester 입력·응답, 구현 packet, plain session event와 세션 메타데이터는 Private Brain에 보관했다. 공개 저장소에는 다음 연결 정보만 남긴다.

- archive status: `archived`
- hash algorithm: `sha256`
- receipt hash: `6c8b0f9f02b0782580967d537ada8a611dc54aec01543137cffa2d1479240737`
- core link hash: `b3adf386756d1455fafb9e0736f26ad1194105f7d03f10da76ad16bfda25571a`

`coreLinkHash`는 suite, 두 이슈 번호, base commit, 최종 implementation diff hash, packet hash, 세 tester result hash와 outcome을 canonical JSON으로 직렬화한 뒤 계산했다. receipt와 공개 요약의 값이 같아야 한다.

다음 정보는 공개 파일, Issue와 PR 본문에 남기지 않는다.

- raw prompt와 모델·agent 응답 원문
- 전체 테스트 로그와 세션 원문
- 로컬 절대 경로와 Private Brain 내부 경로
- 개인정보, 비밀값과 저장소 인증 정보

## 한계

- 실제 백엔드 작업 한 건의 단일 관찰 결과이며 다른 작업이나 모델의 성능을 일반화하지 않는다.
- 두 번째 실행의 fail은 제품 결함이 아니라 실행기 제한이므로 tester의 위험 검출 성과에 포함하지 않는다.
- child agent 내부에서 별도로 남지 않은 로그는 재구성하지 않았고, 부모 세션에 노출된 plain test output과 원본 구조화 결과만 Private archive에 보관했다.
- pilot 이후의 리뷰 수정과 병합 상태는 이 평가 snapshot의 결과가 아니다.
