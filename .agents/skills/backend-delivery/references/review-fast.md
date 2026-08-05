# 저위험 전달 fast path

## 범위

- 현재 이슈 또는 선택한 코멘트, 직접 관련된 정본 절, 변경 대상과 대상 테스트까지만 확인한다. 후속 이슈 전체나 관련 없는 ADR·문서를 탐색하지 않는다.
- 생산 코드 변경이 없는 경우 메인 에이전트가 코멘트의 테스트 경로만 직접 최소 수정한다.
- 생산 코드를 바꾸는 경우 현재 이슈의 승인된 테스트 계약을 유지하고 `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](packet-template.json)에 따라 좁은 v3 패킷을 만든다. 저장소 밖 임시 파일을 `node scripts/validate-packet.mjs <packet.json>`으로 검증한 뒤 `backend-developer`에 전달한다.
- 정본 충돌·선행 계약 부재·미선언 공유 파일이 드러나면 구현을 멈춘다.

## TDD 사이클

1. 구현자는 T-ID를 직접 검증하는 exact selector 테스트를 생산 코드보다 먼저 작성하고 `--rerun` Red와 기대 실패를 보고한다.
2. 최소 생산 코드로 같은 selector를 `--rerun --fail-fast` Green으로 만든다.
3. 리팩터링 뒤 task별 모든 selector를 묶어 최종 Green과 test manifest를 보고한다.

## PR 리뷰 수정 배치

리뷰 수정은 코멘트를 입력으로 받되 계약 단위는 승인된 T-ID를 그대로 유지한다. 코멘트를 새 계약 축으로 만들지 않는다.

- 사용자가 선택한 코멘트를 승인된 계약 안과 밖으로만 나눈다. 코멘트 유형으로 모드를 나누지 않는다.
- 계약 안 코멘트는 전부 한 배치로 묶어 사이클을 한 번만 돈다. 계약 밖 코멘트는 배치에서 빼고 원래 이슈의 새 전체 승인을 요청해 따로 전달하며, 계약 밖 코멘트 하나 때문에 나머지를 `full-delivery`로 올리지 않는다.
- 배치 안에서는 코멘트 유형에 따라 다음을 적용한다.
  - 실패 조건 지적: 그 실패를 재현하는 테스트를 먼저 만들어 Red를 관찰하고 승인된 T-ID의 evidence로 추가한다. 새 T-ID가 필요할 만큼 크면 계약 밖이다.
  - 테스트 품질 지적: 관찰 가능한 생산 동작이 그대로면 Red 없이 수정하고 사유를 남긴다.
  - 동작을 바꾸지 않는 리팩터링·네이밍: `TDD_NOT_APPLICABLE`과 구체적인 이유를 남긴다.
- Red가 필요한 코멘트가 여럿이면 `--fail-fast` 없이 한 명령으로 묶어 모든 Red를 함께 관찰한다. 최종 Green은 배치 전체에 대해 task별 한 번만 확인한다.
- 테스트 파일·메서드명·selector가 바뀌면 manifest를 다시 만들어 검증하고 `PR 테스트 항목 handoff`를 최신 결과로 다시 만든다.
- 배치 도중 `DECISION_NEEDED`나 재분류가 발생하면 이미 Green인 코멘트까지만 커밋 범위로 두고, 남은 코멘트의 스레드는 미해결로 남긴 뒤 무엇이 남았는지 보고한다.

## 검증

- 구현자의 T-ID별 Red 보고, 최종 Green과 manifest를 확인한다. manifest는 저장소 밖 임시 파일로 만들고 `node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree>`를 통과시킨다. 이 검사는 manifest와 함께 실제 변경 경로가 packet의 소유 경계와 항상 read-only 목록 안인지 감사하므로 범위 밖 변경을 따로 눈으로 확인하지 않는다.
- 구현자가 성공시킨 targeted 테스트를 메인 에이전트가 반복하지 않는다. `git diff --check`와 커밋 훅의 `conventionCheck`를 추가 게이트로 사용하고 전체 회귀는 GitHub CI에 맡긴다. 훅이 성공한 검사는 반복하지 않는다.
- 직접 테스트 부재, 대상 테스트 실패, 정본 충돌, 범위 확대 또는 고위험 변경이 드러나면 `SKILL.md`로 돌아가 `full-delivery`로 재분류한다.

## 커버리지 래칫 예외

- 구현 중 새 생산 패키지 또는 `gatedBranchCoverage`에 없는 변경 패키지가 확인되면 사용자 결정을 기다리지 않는다. packet에 `build.gradle` 조건부 허용 경로와 coverage 완료 기준을 추가해 다시 검증하고, 같은 구현자에게 필요한 map 변경만 후속 전달한다.
- `build.gradle`이 바뀌면 `node scripts/validate-coverage-ratchet.mjs`를 통과시킨다. 이 검사가 변경이 `gatedBranchCoverage` 항목 추가와 최소선 상향뿐인지 판정하며, 실패하면 `full-delivery`로 재분류한다.
- 비래칫 생산 패키지가 있으면 `.\gradlew.bat jacocoTestReport verifyCoverageRuleTargets`를 성공시킨다. 이 명령은 전체 H2 test 1회와 커버리지 구조 검사를 포함하며 약 70초가 걸릴 수 있지만 Docker와 `postgresTest`는 요구하지 않는다. 분기 10개 미만이면 map을 바꾸지 않고, 10개 이상이면 H2 실측값을 0.01 단위로 내린 최소선만 추가한다. 이미 래칫된 패키지의 비율 회귀와 PostgreSQL 합산 coverage는 CI에 맡긴다.

## PR 테스트 항목 handoff

PR을 요청받으면 `pr-writer` 호출 전에 최신 실제 결과로 `.github/PULL_REQUEST_TEMPLATE.md`의 `## 테스트 및 확인`에 넣을 체크박스를 만든다.

```markdown
- [x] T1 — `NotificationReadCommandServiceTest#미존재_알림을_숨긴다` (`test`)
- [x] T2 — `NotificationReadPostgresTest#읽음_시각을_보존한다` (`postgresTest`)
- [x] Green H2 — `.\gradlew.bat test --tests "..." --rerun --fail-fast`
- [x] Green PostgreSQL — `.\gradlew.bat postgresTest --tests "..." --rerun --fail-fast`
- [x] Coverage ratchet — `.\gradlew.bat jacocoTestReport verifyCoverageRuleTargets`
```

- T-ID가 1개든 6개든 각각 한 줄씩 `단순 클래스명#메서드명 (task)`으로 쓰고, 같은 T-ID의 복수 evidence는 한 체크박스에서 `;`로 구분한다. 단순 클래스명이 충돌할 때만 package-qualified class명을 쓴다.
- Green 명령은 task별 한 줄로 묶는다. 실행하지 않은 PostgreSQL·coverage와 Red 내용은 넣지 않고 실제 성공한 항목만 `[x]`로 표시한다.
- 템플릿 heading과 순서를 바꾸지 않는다. 전달 종료 후 저장소 밖 packet과 manifest는 삭제하지 않고 Private Brain의 전달 아카이브로 옮긴다. 아카이브 경로와 파일 구조는 Private Brain 정본을 따르고 공개 파일에 적지 않는다.
