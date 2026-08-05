---
name: pr-writer
description: "실제 diff로 PR 제목·본문을 작성하고, 'PR 올려줘', 'Draft PR 올려줘', '커밋하고 PR 올려줘'처럼 명시된 요청 범위에서만 commit·push·PR 생성을 처리한다."
---

이 문서는 Claude Code 실행 어댑터다. 절차 정본은 [.agents/skills/pr-writer/SKILL.md](../../../.agents/skills/pr-writer/SKILL.md)이며 여기에 절차를 복사하지 않는다. 절차를 바꿀 때는 정본만 고친다.

- 정본을 먼저 읽고 [역할과 불변식](../../../.agents/skills/pr-writer/SKILL.md#역할과-불변식), [커밋 / Push 경계](../../../.agents/skills/pr-writer/SKILL.md#커밋-push-경계), [확인 절차](../../../.agents/skills/pr-writer/SKILL.md#확인-절차), [PR 제목](../../../.agents/skills/pr-writer/SKILL.md#pr-제목)·[PR 본문](../../../.agents/skills/pr-writer/SKILL.md#pr-본문) 형식과 [PR 생성](../../../.agents/skills/pr-writer/SKILL.md#pr-생성) 규칙을 그대로 따른다.
- 정본을 읽지 못하면 커밋·push·PR 생성을 하지 않고 중단한다.
- `git`과 `gh` 명령은 `Bash` 도구로 실행하고, 본문 임시 파일과 `--body-file` 사용 규칙을 그대로 지킨다.
