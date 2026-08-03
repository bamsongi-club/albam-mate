# 저위험 전달 fast path

## 범위

- 현재 이슈 또는 선택한 코멘트, 직접 관련된 정본 절, 변경 대상과 대상 테스트까지만 확인한다. 후속 이슈 전체나 관련 없는 ADR·문서를 탐색하지 않는다.
- 생산 코드 변경이 없는 경우 메인 에이전트가 코멘트의 테스트 경로만 직접 최소 수정한다.
- 생산 코드를 바꾸는 경우 현재 이슈의 승인된 테스트 계약을 유지하고 `.codex/contracts/backend-implementation-packet.schema.json`과 [패킷 템플릿](packet-template.json)에 따라 좁은 패킷을 만든다. 스키마 유효 패킷을 `backend-developer`에 전달하고 종료 후 임시 파일을 삭제한다.
- 패킷의 `validation.targetedTests`에는 코멘트에 직접 대응하는 테스트만, `validation.finalCommands`에는 중복되지 않는 형식·정적 검사만 둔다.
- 정본 충돌·선행 계약 부재·미선언 공유 파일이 드러나면 구현을 멈춘다.

## 검증

- 생산 코드 변경이 없으면 메인 에이전트가, 생산 코드를 바꾸면 구현 에이전트가 대상 테스트를 한 번만 실행한다. PostgreSQL 테스트는 Docker 접근을 먼저 확인한다.
- 구현 에이전트가 성공한 대상 테스트를 메인 에이전트가 다시 실행하지 않는다. 메인 에이전트는 패킷의 정적·형식 `finalCommands`만 한 번 실행하되, 커밋 훅의 `conventionCheck`가 성공했다면 같은 검사를 반복하지 않는다.
- snapshot·expected JSON, fresh `backend-tester`, fresh `review-code-reviewer`를 만들지 않는다. 전체 회귀 검증은 push 후 GitHub CI에 맡긴다.
- 대상 테스트 실패, 직접 테스트 부재, 정본 충돌, 범위 확대가 발생하면 전달을 중단하고 `SKILL.md`의 모드 선택으로 돌아가 다시 분류한다.
