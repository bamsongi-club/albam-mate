---
name: issue-writer
description: "저장소의 등록된 GitHub Issue Form을 요청 성격에 따라 선택하고, 근거 기반 이슈 제목·본문을 작성·검증하며 명시적으로 요청받으면 게시한다. 트리거: '이슈 작성해줘', '이슈 올려줘', '이슈 만들어줘', '리팩터링 이슈 게시해줘', '어떤 이슈 템플릿을 써야 해?'."
---

이 문서는 Claude Code 실행 어댑터다. 절차 정본은 [.agents/skills/issue-writer/SKILL.md](../../../.agents/skills/issue-writer/SKILL.md)이며 여기에 절차를 복사하지 않는다. 이슈 기준을 바꿀 때는 정본만 고친다.

- 정본을 먼저 읽고 그 경계와 정본 규칙, 게시 절차를 그대로 따른다.
- 정본이 참조하는 `templates.json`, `.github/ISSUE_TEMPLATE/*.yml`, `scripts/validate-template-registry.mjs`는 저장소 루트 기준 경로 그대로 쓴다.
- 정본을 읽지 못하면 템플릿을 추측하지 않고 중단한다.
- `node`와 `gh` 명령은 `Bash` 도구로 실행한다. 서브에이전트를 만들지 않고 메인 대화가 직접 수행한다.
