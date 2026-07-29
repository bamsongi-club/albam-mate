---
name: backend-delivery
description: "범위와 정본이 확정된 Albam Mate 백엔드 기능·버그 수정을 backend-developer에 위임하고 검증·전달한다. GitHub 이슈, 이슈 없는 확정 기능, 선택된 PR 리뷰 코멘트가 백엔드 코드 수정으로 이어질 때 사용한다. 코멘트 조회·선별·답글·스레드 해결만 필요한 요청, 읽기 전용 리뷰, 문서·프런트엔드 작업에는 사용하지 않는다."
---

# 백엔드 전달 절차

## 1. 범위 확정

1. `AGENTS.md`가 가리키는 기능 정본과 필요한 명령·컨벤션 문서만 읽는다.
2. PR 피드백 요청이면 `gh-address-comments`로 현재 head의 미해결 스레드를 조회하고, 사용자가 선택한 유효한 수정만 확정한다. 리뷰 코멘트는 근거이지 제품 정본이 아니므로 저장소 정본과 대조한다. 모호하거나 상충하거나 설명만 필요한 코멘트는 구현하지 말고 보고하거나 답글 초안을 만든다. 명시적 요청 없이 GitHub 답글을 쓰거나 스레드를 해결하지 않는다.
3. 검증한 GitHub 이슈는 `workItem.kind=issue`, 이슈가 없는 확정 작업은 `feature`와 합의된 식별자를 사용한다. 리뷰 코멘트는 새 kind가 아니라 원래 이슈 또는 기능에 매핑한다.
4. `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](references/packet-template.json)을 읽고 placeholder 없는 JSON 객체를 만든다. PR 피드백의 요구사항은 `summary`와 `completionCriteria`에 넣고, `canonicalSources`에는 저장소 정본만 넣는다.
5. 모든 배열을 채우고 `allowedPaths`와 `forbiddenPaths`를 명시한다. 미선언 공유 파일이 필요하면 위임하지 말고 결정을 요청한다.
6. HTTP API를 새로 구현하거나 계약을 바꾸거나 기존 HTTP 경계(경로·메서드·인증·인가·CSRF·입력·응답·상태 변경)의 버그를 수정하면, 계약 문서 변경 여부와 무관하게 [HTTP 기능 테스트 매트릭스](references/http-feature-test-matrix.md)를 읽고 적용 항목을 `completionCriteria`와 `validation.targetedTests`에 넣는다. 제외 항목은 `confirmedDecisions`에 이유를 남긴다.

## 2. 구현 위임

- `backend-developer` 위임은 메인 에이전트만 수행한다. 서브에이전트는 패킷을 메인 에이전트에 반환한다.
- JSON 패킷만 `backend-developer`에 전달하고, 소유·테스트·금지 경계를 패킷으로 고정한다.
- 구현 에이전트에게는 커밋과 푸시를 맡기지 않는다.
- 구현 중 정본 충돌·선행 공개 계약 부재·미선언 공유 파일이 드러나면 구현을 멈추고 결정으로 되돌린다.

## 3. 검증과 리뷰

- 구현 에이전트는 패킷의 `validation.targetedTests`와 `validation.finalCommands`를 실행하고 전체 test·build를 반복하지 않는다.
- 메인 에이전트는 변경 범위와 `git diff --check`를 고정한 뒤, 위험에 맞는 독립 리뷰를 수행한다. HTTP 인증·인가·개인정보·동시성 변경은 리뷰 차원을 명시한다.
- 유효한 리뷰 지적만 좁은 패킷으로 반영하고, 해당 대상 테스트를 다시 실행한다.
- 모든 수정이 끝난 뒤 PostgreSQL 전용 검증이 필요하지 않은 작업은 메인 에이전트가 현재 OS의 Gradle Wrapper로 `build`를 한 번 실행한다.
- Flyway, PostgreSQL 전용 SQL·제약 또는 데이터베이스 동시성 변경은 test 전용 커버리지 게이트가 합산 판정을 가로막지 않도록 `build -x jacocoTestCoverageVerification --no-daemon --stacktrace`를 실행한 뒤, `postgresTest jacocoAllTestReport jacocoAllTestCoverageVerification --no-daemon --stacktrace`로 PostgreSQL 검증과 정본 커버리지 게이트를 한 번에 실행한다.
- Markdown을 함께 바꾸면 `node scripts/check-doc-links.mjs`를 추가로 실행한다.
- 실행 환경 제약으로 필요한 조건부 검증을 실행하지 못하면 완료로 표시하지 않고 잔여 위험으로 보고한다.

## 4. 전달

- 커밋·푸시·PR 요청이면 `pr-writer`를 사용하고, 최종 diff에 포함된 파일만 스테이징한다.
- 로컬·upstream·원격 SHA와 깨끗한 worktree를 확인한다.
- 사용자가 CI 통과까지 요청했거나 완료 기준이 CI를 요구할 때만 원격 CI 완료를 기다린다. 그렇지 않으면 PR 생성 사실과 현재 CI 상태를 구분해 보고한다.
