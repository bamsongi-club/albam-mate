---
name: backend-delivery
description: "확정된 범위·정본에 따른 Albam Mate 백엔드 기능·버그 수정을 backend-developer에 위임·검증·전달한다. 승인된 테스트 계약이 있는 GitHub 이슈나 그 이슈에 매핑된 선택 PR 리뷰의 백엔드 수정에 사용한다. 코멘트 처리 전용 요청, 읽기 전용 리뷰, 문서·프런트엔드 작업은 제외한다."
---

# 백엔드 전달 절차

## 1. 범위 확정

1. `AGENTS.md`가 가리키는 기능 정본과 필요한 명령·컨벤션 문서만 읽는다.
2. PR 피드백 요청이면 `gh-address-comments`로 현재 head의 미해결 스레드를 조회한다.
   - 사용자가 선택한 유효한 수정만 확정하고 저장소 정본과 대조한다. 리뷰 코멘트는 근거이지 제품 정본이 아니다.
   - 모호·상충하거나 설명만 필요한 코멘트는 구현하지 않고 보고하거나 답글 초안을 만든다.
   - 명시적 요청 없이 GitHub 답글을 쓰거나 스레드를 해결하지 않는다.
3. 사람이 최신 전체 `T1`…`Tn`을 승인한 GitHub 이슈만 `workItem.kind=issue`와 이슈 번호로 위임한다.
   - 이슈 없는 작업은 위임하지 않는다. feature 또는 bug 이슈 생성과 사람의 코멘트 승인을 먼저 요청한다.
   - 리뷰 코멘트도 원래 이슈에 매핑한다. 원래 이슈가 없으면 먼저 만든다.
4. `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](references/packet-template.json)을 읽고 placeholder 없는 JSON 객체를 만든다.
   - `featureId`는 확정된 기능 ID가 있을 때만 `workItem`에 추가한다.
   - PR 피드백 요구사항은 `summary`와 `completionCriteria`에, 저장소 정본은 `canonicalSources`에 넣는다.
   - 모든 `requiredTests.sourceRef`와 `testContractApproval.commentUrl`은 동일한 이슈 코멘트 정본 URL로 둔다. 이 코멘트는 사람이 최신 전체 T-ID를 승인한 것이다.
5. 모든 배열을 채우고 `allowedPaths`와 `forbiddenPaths`를 명시한다. 미선언 공유 파일이 필요하면 위임하지 말고 결정을 요청한다.
6. HTTP API 신규 구현·계약 변경·기존 HTTP 경계 버그 수정에는 계약 문서 변경 여부와 무관하게 [HTTP 기능 테스트 매트릭스](../../references/http-feature-test-matrix.md)를 읽고 적용한다. HTTP 경계는 경로·메서드·인증·인가·CSRF·입력·응답·상태 변경을 포함한다.
   - 적용 항목은 `completionCriteria`와 `validation.targetedTests`에 넣는다.
   - 제외 항목은 이유와 함께 `confirmedDecisions`에 남긴다.
7. PR 피드백이 기존 T-ID의 구현 누락만 바로잡으면 원래 승인 코멘트의 최신 전체 T-ID를 재사용한다.
   - 관찰 가능한 동작이나 테스트 의도를 추가·변경하면 사람이 원래 이슈에 최신 전체 `T1`…`Tn`을 새 코멘트로 승인할 때까지 위임하지 않는다.
   - 리뷰 코멘트 자체는 테스트 계약 승인이 아니다.

## 2. 구현 위임

- 위임 주체는 [루트 작업 안내](../../../AGENTS.md)를 따른다. 서브에이전트는 패킷을 메인 에이전트에 반환한다.
- 위임 직전에 완성한 JSON을 저장소 밖의 고유한 임시 패킷 파일로 저장하고 `node scripts/validate-packet.mjs <임시-패킷.json>`을 실행한다. 실패하면 위임하지 않으며, 통과한 파일의 내용만 그대로 전달한다.
- 스키마 검증과 별도로 템플릿의 `<...>` placeholder가 남지 않았는지 확인한다. 하나라도 남으면 위임하지 않는다.
- 위임 또는 중단 처리가 끝나면 임시 패킷 파일을 삭제하고 저장소 안에는 패킷 파일을 남기지 않는다.
- JSON 패킷만 `backend-developer`에 전달하고, 소유·테스트·금지 경계를 패킷으로 고정한다.
- 구현 에이전트에게는 커밋과 푸시를 맡기지 않는다.
- 구현 중 정본 충돌·선행 공개 계약 부재·미선언 공유 파일이 드러나면 구현을 멈추고 `DECISION_NEEDED`를 부모에게 반환한다.

## 3. 검증과 리뷰

- 구현 에이전트는 패킷의 `validation.targetedTests`와 `validation.finalCommands`를 실행하고 전체 test·build를 반복하지 않는다.
- 메인 에이전트는 변경 범위를 확인한 뒤 승인 경로의 staged diff, unstaged diff, untracked 파일 목록과 내용을 각각 `git diff --cached -- <승인-경로>`, `git diff -- <승인-경로>`, `git ls-files --others --exclude-standard -- <승인-경로>`로 수집해 구체적인 구현 스냅샷으로 고정하고 `git diff --check`를 실행한다.
- 이어 패킷 작성자·구현자와 다른 fresh `review-code-reviewer`에게 `review-code`의 `T-ID 계약 검증` 모드를 맡긴다.
  - verifier에는 `mode=test-contract`, `requiredTests`에서 추출한 `id`·`intent`, 고정 diff만 전달한다.
  - 이슈 본문, `completionCriteria`를 포함한 나머지 패킷 필드, 구현 세션 설명은 전달하지 않는다.
- T-ID 계약 검증 결과와 일반 위험 리뷰 결과를 분리해 보존한다. 위험 기반 리뷰가 필요하면 일반 `review-code` 모드를 별도로 실행하고, HTTP 인증·인가·개인정보·동시성 변경은 해당 리뷰 차원을 명시한다.
- `review-code`가 T-ID 결과 형식·개수·순서를 검증해 집계한 종합 판정이 `Approve`일 때만 4절로 진행한다.
  - 형식 오류: 결과를 폐기하고 fresh verifier로 재검증한다.
  - `Incomplete`: 미검증 T-ID를 직접 판정할 구현·테스트 변경을 고정 diff에 보강하고 fresh verifier로 재검증한다.
  - `Changes Requested`: `fail` T-ID를 수정하고 대상 테스트를 다시 실행한 뒤 fresh verifier로 재검증한다.
  - `Approve` 전에는 `pr-writer` 호출과 커밋·푸시·PR 생성을 금지한다.
- 유효한 일반 리뷰 지적만 1절의 승인 규칙을 지킨 좁은 패킷으로 반영하고, 해당 대상 테스트를 다시 실행한다.
- 모든 수정이 끝난 뒤 PostgreSQL 전용 검증이 필요하지 않은 작업은 메인 에이전트가 현재 OS의 Gradle Wrapper로 `build`를 한 번 실행한다.
- Flyway, PostgreSQL 전용 SQL·제약 또는 데이터베이스 동시성 변경은 test 전용 커버리지 게이트의 합산 판정 간섭을 피하도록 다음 순서로 실행한다.
  1. `build -x jacocoTestCoverageVerification --no-daemon --stacktrace`
  2. `postgresTest jacocoAllTestReport jacocoAllTestCoverageVerification --no-daemon --stacktrace`

  두 번째 명령에서 PostgreSQL 검증과 정본 커버리지 게이트를 함께 실행한다.
- Markdown을 함께 바꾸면 `node scripts/check-doc-links.mjs`를 추가로 실행한다.
- 실행 환경 제약으로 필요한 조건부 검증을 실행하지 못하면 완료로 표시하지 않고 잔여 위험으로 보고한다.

## 4. 전달

- 커밋·푸시·PR 요청이면 `pr-writer`를 사용하고, 사용자가 승인한 작업 범위의 파일만 명시 경로로 스테이징한다.
- 로컬·upstream·원격 SHA와 worktree 상태를 확인하되 깨끗한 worktree를 요구하거나 관련 없는 staged·unstaged·untracked 변경을 정리하지 않는다.
- 사용자가 CI 통과까지 요청했거나 완료 기준이 CI를 요구할 때만 원격 CI 완료를 기다린다. 그 외에는 PR 생성 사실과 현재 CI 상태를 구분해 보고한다.
