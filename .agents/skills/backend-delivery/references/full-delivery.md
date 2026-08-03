# 전체 백엔드 전달

## 범위와 패킷

- 사람이 작업 GitHub 이슈의 한 코멘트에서 승인한 최신 전체 `T1`…`Tn`만 순서대로 재사용한다. 이슈가 없으면 feature·bug 이슈 생성과 승인을, 관찰 가능한 동작·테스트 의도가 달라지면 원래 이슈의 새 전체 승인을 먼저 요청한다.
- 모든 `sourceRef`와 승인 URL은 승인 코멘트를 가리키며, PR 리뷰 코멘트 자체는 정본이나 승인이 아니다.
- `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](packet-template.json)에 따라 `allowedPaths`·`forbiddenPaths`를 포함한 패킷을 만든다. 미선언 공유 파일이 필요하면 결정을 요청한다.
- HTTP 경계 작업은 [HTTP 기능 테스트 매트릭스](../../../references/http-feature-test-matrix.md)를 적용하고 제외 근거를 패킷에 남긴다.

## 구현 위임

- 완성한 JSON을 저장소 밖의 고유한 임시 파일에 저장하고 `node scripts/validate-packet.mjs <임시-패킷.json>`을 통과시킨다. `<...>` placeholder 부재와 인용한 정본·사람 승인 사실도 직접 확인한다.
- `postgresTest`가 필요하면 위임 전에 `docker version`으로 daemon 접근을 확인한다.
- 검증된 JSON만 `backend-developer`에 전달해 소유·대상 테스트·금지 경계를 고정한다. 구현자는 개발 중 대상 테스트만 실행하고, T-ID별 실제 test source와 필요한 H2·PostgreSQL 실행 경계를 보고한다.
- 구현 중 정본 충돌·선행 공개 계약 부재·미선언 공유 파일이 드러나면 구현을 멈추고 `DECISION_NEEDED`를 반환한다.

## Draft PR과 snapshot 고정

- 사용자가 커밋·push·PR 생성을 요청한 범위에서만 구현자의 대상 테스트 통과 뒤 원격 base를 한 번 갱신하고 필요한 정렬을 끝낸다. 관련 없는 변경이 섞인 worktree는 자동 rebase하지 않는다.
- `pr-writer`로 승인 범위만 커밋·push하고 Draft PR을 만든다. Draft의 현재 `headRefOid`를 독립 검증 snapshot으로 고정하며, PR 생성 자체를 검증 완료로 표시하지 않는다.
- 검증 중 `origin/develop`이 이동했다는 사실만으로 Draft branch를 rebase하거나 expected·tester·reviewer 결과를 무효화하지 않는다. 코드·테스트 또는 Draft head가 바뀌면 새 head에서 snapshot과 검증을 다시 만든다.

## 비게시 독립 검증과 Ready 전환

- 구현자의 concrete test manifest로 execution plan을 작성하고 `node scripts/build-backend-test-plan.mjs`로 현재 snapshot이 포함된 expected JSON을 만든다. 모든 T-ID는 실제 test source와 하나 이상의 execution을 참조하고, 패킷의 모든 `targetedTests`·`finalCommands`가 plan에 포함돼야 한다. builder는 `targetedTests`를 먼저, 그 밖의 T-ID 실행을 중간에, `finalCommands`를 마지막에 배치한다.
- 같은 명령은 한 번만 선언한다. 승인된 서로 다른 명령의 Gradle selector 포함 관계를 별도로 추론하거나 병합하지 않는다.
- 패킷 작성자·구현자와 다른 fresh `backend-tester`는 expected JSON만 받아 runner를 정확히 한 번 실행한다. runner 밖에서 명령별 wrapper, hash 계산기나 result JSON을 만들지 않는다.
- runner는 첫 execution `fail`에서 남은 실행을 중단한다. 구현자는 실패한 명령과 직접 관련 테스트만 반복해 수정하고 같은 실패가 반복되면 원인을 먼저 분석한다. 수정한 대상 테스트가 통과하면 새 snapshot·expected와 fresh tester로 전체 runner를 최종 한 번 실행한다. `unverified`는 환경·증거 부족 원인을 해결한 뒤 새 result 경로로 전체 runner를 다시 실행하며, 코드나 snapshot이 바뀌면 expected부터 새로 만든다.
- 같은 Draft head의 CI, tester와 fresh `review-code-reviewer` 자체 리뷰를 진행한다. reviewer에는 고정 diff와 `requiredTests`의 `id`·`intent`만 전달하고 runner 결과는 전달하지 않는다. 일반 위험 리뷰가 필요하면 `review-code`의 비게시 read-only 모드로 수행한다.
- tester·reviewer 결과와 Finding은 대화와 임시 검증 자료에만 유지한다. 사용자가 별도로 게시를 명시하지 않으면 GitHub PR review·comment·reply로 게시하거나 review thread를 만들고 해결하지 않는다.
- Ready 전환 직전에 최신 `origin/develop` 변경이 Draft의 변경 경로·공유 계약·API·스키마·마이그레이션·보안·빌드와 충돌하거나 merge conflict를 만드는지 확인한다. 영향이 없으면 기존 tester·reviewer 결과를 유지하고 현재 head의 CI와 mergeability만 확인한다. 저장소 규칙이 최신 base 통합 CI를 요구할 때만 그 CI를 추가한다. 영향이 있으면 한 번 정렬하고 바뀐 head에서 snapshot·tester·reviewer·CI를 다시 수행한다.
- 현재 Draft head에서 tester `pass`, T-ID 계약 `Approve`, 필요한 일반 리뷰 통과와 CI 성공을 모두 확인한 뒤에만 Ready for review로 전환해 사람 리뷰를 요청한다.
- Markdown을 바꾸면 `node scripts/check-doc-links.mjs`를 실행하고, 실행하지 못한 조건부 검증을 완료로 표시하지 않는다.
- 종료 후 저장소 밖 임시 packet·plan·expected·result와 로그를 삭제한다.
