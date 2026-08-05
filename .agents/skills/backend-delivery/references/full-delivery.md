# 전체 백엔드 전달

## 범위와 패킷

- 사람이 작업 GitHub 이슈의 한 코멘트에서 승인한 최신 전체 `T1`…`Tn`만 순서대로 재사용한다. 이슈가 없으면 feature·bug 이슈 생성과 승인을, 관찰 가능한 동작·테스트 의도가 달라지면 원래 이슈의 새 전체 승인을 먼저 요청한다.
- 모든 `sourceRef`와 승인 URL은 승인 코멘트를 가리키며, PR 리뷰 코멘트 자체는 정본이나 승인이 아니다.
- `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](packet-template.json)에 따라 `allowedPaths`·`forbiddenPaths`를 포함한 v3 패킷을 만든다. 미선언 공유 파일이 필요하면 결정을 요청한다.
- HTTP 경계 작업은 [HTTP 기능 테스트 매트릭스](../../../references/http-feature-test-matrix.md)를 적용하고 제외 근거를 패킷에 남긴다.

## 구현 위임

- 완성한 JSON을 저장소 밖의 고유한 임시 파일에 저장하고 `node scripts/validate-packet.mjs <임시-패킷.json>`을 통과시킨다. `<...>` placeholder 부재와 인용한 정본·사람 승인 사실도 직접 확인한다.
- `postgresTest`가 필요하면 위임 전에 `docker version`으로 daemon 접근을 확인한다.
- 검증된 JSON만 `backend-developer`에 전달해 소유·대상 테스트·금지 경계를 고정한다. 구현자는 T-ID별 테스트를 먼저 Red로 확인하고 최소 구현으로 Green을 만든 뒤, task별 최종 Green과 실제 source·exact selector manifest를 보고한다.
- 구현자가 반환한 T-ID별 Red 보고와 최종 Green을 확인하고, manifest를 저장소 밖 임시 JSON으로 만들어 `node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree>`를 통과시킨다. 이 검사는 manifest와 함께 실제 변경 경로가 packet의 소유 경계와 항상 read-only 목록 안인지 감사하므로 범위 밖 변경을 따로 눈으로 확인하지 않는다.
- 구현 중 정본 충돌·선행 공개 계약 부재·미선언 공유 파일이 드러나면 구현을 멈추고 `DECISION_NEEDED`를 반환한다.
- 새 생산 패키지 또는 `gatedBranchCoverage`에 없는 변경 패키지가 구현 중 확인되면 사용자 결정을 기다리지 않는다. `build.gradle` 조건부 허용 경로와 `.\gradlew.bat jacocoTestReport verifyCoverageRuleTargets` 완료 기준을 packet에 추가해 다시 검증하고, 같은 구현자에게 필요한 map 변경만 후속 전달한다.
- map을 바꾸면 `node scripts/validate-coverage-ratchet.mjs`를 통과시킨다. 이 검사가 실패한 build 변경은 래칫 예외로 허용하지 않고 별도 고위험 범위로 packet에 명시한다. coverage 명령은 전체 H2 test 1회를 포함하며 약 70초가 걸릴 수 있지만 Docker와 `postgresTest`는 요구하지 않는다. 분기 10개 미만이면 map을 바꾸지 않고, 10개 이상이면 H2 실측값을 0.01 단위로 내린 최소선만 추가한다. 기존 비율 회귀와 PostgreSQL 합산 coverage는 CI에 맡긴다.

## TDD 사이클

1. 구현자는 T-ID를 직접 검증하는 exact selector 테스트를 생산 코드보다 먼저 작성하고 `--rerun` Red와 기대 실패를 보고한다.
2. 최소 생산 코드로 같은 selector를 `--rerun --fail-fast` Green으로 만든다.
3. 리팩터링 뒤 task별 모든 selector를 묶어 최종 Green과 test manifest를 보고한다.

## Draft PR과 snapshot 고정

- 사용자가 커밋·push·PR 생성을 요청한 범위에서만 구현자의 대상 테스트 통과 뒤 원격 base를 한 번 갱신하고 필요한 정렬을 끝낸다. 관련 없는 변경이 섞인 worktree는 자동 rebase하지 않는다.
- 구현자의 실제 최종 Green과 manifest로 아래 `PR 테스트 항목 handoff`를 먼저 만들고 `pr-writer`에 전달한다.
- `pr-writer`로 승인 범위만 커밋·push하고 Draft PR을 만든다. Draft의 현재 `headRefOid`를 검증 head로 고정하며, PR 생성 자체를 검증 완료로 표시하지 않는다.
- `origin/develop` 이동만으로 Draft branch를 rebase하거나 현재 head의 결과를 무효화하지 않는다. 코드·테스트 또는 Draft head가 바뀌면 새 head에서 아래 검증을 모두 반복한다.

## 고정 head 검증과 Ready 전환

- 고정한 Draft head에서 같은 manifest를 `node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree>`로 다시 검증한다.
- `build.gradle`을 바꿨다면 고정한 head에서 `node scripts/validate-coverage-ratchet.mjs --base <고정한-base-sha>`를 통과시킨다. 인자 없이 실행하면 이미 커밋된 래칫 변경이 빈 diff가 되어 하향·삭제를 놓친다.
- manifest의 H2 selector를 한 `.\gradlew.bat test ... --rerun --fail-fast` 명령으로 묶어 최대 한 번 재실행한다. PostgreSQL selector가 있으면 먼저 `docker version`을 확인하고 한 `.\gradlew.bat postgresTest ... --rerun --fail-fast` 명령으로 묶어 최대 한 번 재실행한다.
- 두 재실행 모두 해당 Test task가 실제로 실행돼야 한다. `--rerun` 없이 같은 selector를 다시 돌리면 Gradle이 `UP-TO-DATE`로 건너뛰고 종료 코드 0을 내므로, `Task :test`·`Task :postgresTest`가 `UP-TO-DATE`로 끝난 실행은 이 게이트의 통과 근거로 쓰지 않는다.
- 같은 Draft head에서 `review-code` 일반 리뷰를 비게시 read-only로 한 번 호출한다. 고정 diff, 승인된 T-ID와 manifest를 함께 전달해 변경 위험과 assertion·mock·실행 경계·누락 테스트를 일반 Finding으로 검토하며 T-ID별 별도 verdict를 만들지 않는다.
- targeted 실행, 일반 리뷰와 현재 head CI가 모두 성공해야 Ready for review로 전환한다. 결과와 Finding은 대화와 임시 자료에만 유지하고, 사용자가 별도로 요청하지 않으면 review·comment·reply를 게시하거나 thread를 해결하지 않는다.
- Ready 전환 직전에 최신 `origin/develop`이 변경 경로·공유 계약·API·스키마·마이그레이션·보안·빌드와 충돌하거나 merge conflict를 만드는지 확인한다. 영향이 없으면 현재 결과를 유지하고 CI와 mergeability만 확인한다. 영향이 있어 head를 바꾸면 manifest 검증, task별 targeted 실행, 일반 리뷰와 CI를 모두 새 head에서 반복한다.
- Markdown을 바꾸면 `node scripts/check-doc-links.mjs`를 실행하고, 실행하지 못한 조건부 검증을 완료로 표시하지 않는다.

## PR 테스트 항목 handoff

- Draft 생성과 Ready 전환 때마다 최신 head의 실제 성공 결과로 `.github/PULL_REQUEST_TEMPLATE.md`의 `## 테스트 및 확인` 체크박스를 다시 만들고 `pr-writer`에 전달한다.
- T-ID가 1개든 6개든 각각 한 줄씩 `단순 클래스명#메서드명 (task)`으로 쓰며 같은 T-ID의 복수 evidence는 `;`로 구분한다. 단순 클래스명이 충돌할 때만 package-qualified class명을 쓴다.
- H2와 PostgreSQL Green 명령은 실행한 task별 한 줄로 묶고 coverage ratchet을 성공했을 때만 별도 한 줄을 둔다. 실행하지 않은 PostgreSQL·coverage와 Red 내용은 넣지 않으며 실제 성공한 항목만 `[x]`로 표시한다.
- 다음 형식으로 만들고 heading과 템플릿 순서를 바꾸지 않는다.

```markdown
- [x] T1 — `NotificationReadCommandServiceTest#미존재_알림을_숨긴다` (`test`)
- [x] T2 — `NotificationReadPostgresTest#읽음_시각을_보존한다` (`postgresTest`)
- [x] Green H2 — `.\gradlew.bat test --tests "..." --rerun --fail-fast`
- [x] Green PostgreSQL — `.\gradlew.bat postgresTest --tests "..." --rerun --fail-fast`
- [x] Coverage ratchet — `.\gradlew.bat jacocoTestReport verifyCoverageRuleTargets`
```

- 종료 후 저장소 밖 임시 packet과 manifest는 삭제하지 않고 Private Brain의 전달 아카이브로 옮긴다. 아카이브 경로와 파일 구조는 Private Brain 정본을 따르고 공개 파일에 적지 않는다.
- 이관을 마치면 `archiveId`와 receipt의 packet·manifest SHA-256을 대화 보고에 남긴다. 이 두 값이 handoff 식별자이므로 경로 대신 이것으로 아카이브를 지목한다. 이관이나 receipt를 확인하지 못하면 임시 파일을 삭제하지 않고 확인하지 못한 사실을 보고한다.
