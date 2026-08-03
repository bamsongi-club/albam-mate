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

## 독립 검증

- 원격 base를 한 번 갱신하고 필요한 정렬을 끝낸다. 관련 없는 변경이 섞인 worktree는 자동 rebase하지 않는다.
- 구현자의 concrete test manifest로 execution plan을 작성하고 `node scripts/build-backend-test-plan.mjs`로 현재 snapshot이 포함된 expected JSON을 만든다. 모든 T-ID는 실제 test source와 하나 이상의 execution을 참조하고, 패킷의 모든 `targetedTests`·`finalCommands`가 plan에 포함돼야 한다.
- 같은 명령은 한 번만 선언한다. 승인된 서로 다른 명령의 Gradle selector 포함 관계를 별도로 추론하거나 병합하지 않는다.
- 패킷 작성자·구현자와 다른 fresh `backend-tester`는 expected JSON만 받아 runner를 정확히 한 번 실행한다. runner 밖에서 명령별 wrapper, hash 계산기나 result JSON을 만들지 않는다.
- runner `pass`만 다음 단계로 전달한다. `fail`은 구현자에게 돌려보내고, `unverified`는 환경·증거 부족 원인을 해결한 뒤 새 result 경로로 전체 runner를 다시 실행한다. 코드나 snapshot이 바뀌면 expected부터 새로 만든다.
- runner가 통과한 고정 diff와 `requiredTests`의 `id`·`intent`만 fresh `review-code-reviewer`에 전달한다. T-ID 계약 `Approve`와 tester `pass`가 모두 있어야 전달한다. 일반 위험 리뷰가 필요하면 별도 `review-code` 일반 모드로 수행한다.
- Markdown을 바꾸면 `node scripts/check-doc-links.mjs`를 실행하고, 실행하지 못한 조건부 검증을 완료로 표시하지 않는다.
- 종료 후 저장소 밖 임시 packet·plan·expected·result와 로그를 삭제한다.
