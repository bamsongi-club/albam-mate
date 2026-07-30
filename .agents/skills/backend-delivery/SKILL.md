---
name: backend-delivery
description: "확정된 범위·정본에 따른 Albam Mate 백엔드 기능·버그 수정을 backend-developer에 위임·검증·전달한다. 승인된 테스트 계약이 있는 GitHub 이슈나 그 이슈에 매핑된 선택 PR 리뷰의 백엔드 수정에 사용한다. 코멘트 처리 전용 요청, 읽기 전용 리뷰, 문서·프런트엔드 작업은 제외한다."
---

# 백엔드 전달 절차

## 1. 범위 확정

- PR 피드백은 `gh-address-comments`로 현재 head를 확인하고, 사용자가 선택한 유효한 수정만 저장소 정본과 대조한다. 모호·상충하거나 설명만 필요한 코멘트는 구현하지 않으며, 명시적 요청 없이 답글을 게시하거나 스레드를 해결하지 않는다.
- 사람이 작업 GitHub 이슈의 한 코멘트에서 승인한 최신 전체 `T1`…`Tn`만 순서대로 재사용한다. 이슈가 없으면 feature·bug 이슈 생성과 승인을, 관찰 가능한 동작·테스트 의도가 달라지면 원래 이슈의 새 전체 승인을 먼저 요청한다. 모든 `sourceRef`와 승인 URL은 그 승인 코멘트를 가리키며, PR 리뷰 코멘트 자체는 정본이나 승인이 아니다.
- `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](references/packet-template.json)에 따라 `allowedPaths`·`forbiddenPaths`를 포함한 패킷을 만든다. 미선언 공유 파일이 필요하면 결정을 요청한다.
- HTTP 경계 작업은 [HTTP 기능 테스트 매트릭스](../../references/http-feature-test-matrix.md)를 적용하고, 제외 근거를 패킷에 남긴다.

## 2. 구현 위임

- 완성한 JSON을 저장소 밖의 고유한 임시 파일에 저장하고 `node scripts/validate-packet.mjs <임시-패킷.json>`을 통과시킨다. 별도로 `<...>` placeholder 부재와 인용한 정본·사람 승인 사실을 직접 확인한다.
- 검증된 JSON만 `backend-developer`에 전달해 소유·테스트·금지 경계를 고정하고, 종료 후 임시 파일을 삭제한다.
- 구현 중 정본 충돌·선행 공개 계약 부재·미선언 공유 파일이 드러나면 구현을 멈추고 `DECISION_NEEDED`를 부모에게 반환한다.

## 3. 검증과 리뷰

- 구현 에이전트는 패킷의 대상·최종 검증만 `docs/COMMANDS.md`에 따라 실행하고 결과를 보고한다.
- 메인 에이전트는 승인 경로의 staged·unstaged·untracked 변경 내용을 빠짐없이 고정 diff로 만들고 `git diff --check`를 실행한다.
- 작성자·구현자와 다른 fresh `review-code-reviewer`에게 `review-code`의 T-ID 계약 검증을 맡긴다. `requiredTests`의 `id`·`intent`와 고정 diff만 전달하고, 그 계약의 종합 판정이 `Approve`일 때만 4절로 진행한다.
- `Changes Requested`는 fail T-ID를 수정하고 대상 테스트를 재실행하며, `Incomplete`는 unverified T-ID를 직접 판정할 구현·테스트를 고정 diff에 보강한다. 둘 다 fresh verifier로 재검증하고, `Approve` 전에는 `pr-writer`, 커밋·푸시·PR 생성을 금지한다.
- T-ID 검증과 일반 위험 리뷰는 분리한다. 필요하면 일반 `review-code`를 별도로 실행하고 HTTP 인증·인가·개인정보·동시성 변경의 해당 차원을 명시한다.
- 유효한 일반 리뷰 지적만 1절의 승인 규칙을 지킨 좁은 패킷으로 반영하고, 해당 대상 테스트를 다시 실행한다.
- 최종 검증은 작업 조건과 `docs/COMMANDS.md`를 따르며, 실행하지 못한 조건부 검증은 완료로 표시하지 않는다.

## 4. 전달

- 커밋·푸시·PR 요청이면 `pr-writer`를 사용하고, 사용자가 승인한 작업 범위의 파일만 명시 경로로 스테이징한다.
- 로컬·upstream·원격 SHA와 worktree 상태를 확인하되 깨끗한 worktree를 요구하거나 관련 없는 staged·unstaged·untracked 변경을 정리하지 않는다.
- 사용자가 CI 통과까지 요청했거나 완료 기준이 CI를 요구할 때만 원격 CI 완료를 기다린다. 그 외에는 PR 생성 사실과 현재 CI 상태를 구분해 보고한다.
