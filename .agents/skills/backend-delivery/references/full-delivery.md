# 전체 백엔드 전달

## 범위와 패킷

- 사람이 작업 GitHub 이슈의 한 코멘트에서 승인한 최신 전체 `T1`…`Tn`만 순서대로 재사용한다. 이슈가 없으면 feature·bug 이슈 생성과 승인을, 관찰 가능한 동작·테스트 의도가 달라지면 원래 이슈의 새 전체 승인을 먼저 요청한다.
- 모든 `sourceRef`와 승인 URL은 승인 코멘트를 가리키며, PR 리뷰 코멘트 자체는 정본이나 승인이 아니다.
- `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](packet-template.json)에 따라 `allowedPaths`·`forbiddenPaths`를 포함한 패킷을 만든다. 미선언 공유 파일이 필요하면 결정을 요청한다.
- HTTP 경계 작업은 [HTTP 기능 테스트 매트릭스](../../../references/http-feature-test-matrix.md)를 적용하고 제외 근거를 패킷에 남긴다.

## 구현 위임

- 완성한 JSON을 저장소 밖의 고유한 임시 파일에 저장하고 `node scripts/validate-packet.mjs <임시-패킷.json>`을 통과시킨다. `<...>` placeholder 부재와 인용한 정본·사람 승인 사실도 직접 확인한다.
- 검증된 JSON만 `backend-developer`에 전달해 소유·테스트·금지 경계를 고정하고, 종료 후 임시 파일을 삭제한다.
- 구현 중 정본 충돌·선행 공개 계약 부재·미선언 공유 파일이 드러나면 구현을 멈추고 `DECISION_NEEDED`를 반환한다.

## 독립 검증

- 구현 에이전트는 패킷의 대상·최종 검증만 `docs/COMMANDS.md`에 따라 실행하고 결과를 보고한다.
- 메인 에이전트는 `node scripts/validate-backend-test-result.mjs --snapshot`의 JSON 출력을 snapshot 정본으로 고정한다.
  - 공통 CLI가 worktree 전체의 staged binary diff, unstaged binary diff, 정렬된 untracked 경로와 파일 bytes를 묶어 출력한 `baseCommit`, `implementationDiffHash`, `trackedDiffHash`, `canonicalSeed`를 그대로 사용한다. 산식을 자연어로 재구현하지 않는다.
  - expected JSON에는 snapshot 값, key-sorted canonical JSON UTF-8 바이트 SHA-256인 `packetHash`, 입력 순서의 `{ id, command }` T-ID별 승인 명령만 넣는다. 모든 T-ID에 명령을 하나씩 명시하고 다른 이슈·세션 설명은 tester에게 전달하지 않는다.
- 패킷 작성자·구현자와 다른 fresh `backend-tester`에게 expected JSON만 전달해 승인 명령을 원문 그대로 각각 한 번 실행시킨다.
  - tester는 `.codex/contracts/backend-test-result.schema.json` 결과를 반환하고, 메인은 `node scripts/validate-backend-test-result.mjs --result <result.json> --expected <expected.json>`으로 hash, T-ID 순서, 명령·verdict 관계와 source 수정·stage·commit·push·PR 생성 audit가 모두 false인지 검증한다.
  - validator 거부나 종합 `fail`은 구현자에게 돌려보내고, diff hash가 바뀌면 새 snapshot과 fresh tester 결과를 만든다.
  - 종합 `unverified`는 전달을 막고 같은 snapshot의 재실행 조건 또는 잔여 위험을 보고한다.
  - schema 유효 종합 `pass` 전에는 verifier나 `pr-writer`로 진행하지 않는다.
- 작성자·구현자·tester와 다른 fresh `review-code-reviewer`에게 고정 diff와 `requiredTests`의 `id`·`intent`만 전달한다. 계약 판정 `Approve`와 tester `pass`가 모두 있어야 전달한다.
- `Changes Requested`는 fail T-ID를 수정하고 대상 테스트를 재실행하며, `Incomplete`는 직접 판정할 구현·테스트를 보강한 뒤 fresh 검증을 반복한다.
- T-ID 계약 검증과 일반 위험 리뷰를 분리한다. 필요하면 HTTP 인증·인가·개인정보·동시성 차원을 별도로 리뷰한다.
- 유효한 새 리뷰 지적은 `SKILL.md`의 모드 선택으로 돌아가 다시 분류한다.
- Markdown을 바꾸면 `node scripts/check-doc-links.mjs`를 실행하고, 실행하지 못한 조건부 검증을 완료로 표시하지 않는다.
