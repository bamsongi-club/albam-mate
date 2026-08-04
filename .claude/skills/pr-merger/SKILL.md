---
name: pr-merger
description: "작성자 본인이 명시한 GitHub PR을 승인·CI·diff 이해도 게이트 후 squash merge하고 head 브랜치를 안전하게 정리한다. 'PR #5 머지해줘', '리뷰 끝난 PR 합쳐줘' 같은 요청에 사용한다."
---

이 문서는 Claude Code 실행 어댑터다. 절차 정본은 [.agents/skills/pr-merger/SKILL.md](../../../.agents/skills/pr-merger/SKILL.md)이며 여기에 절차를 복사하지 않는다. 머지 기준을 바꿀 때는 정본만 고친다.

- 정본을 먼저 읽고 그 불변식과 상위 오케스트레이션 순서를 그대로 따른다.
- 정본이 링크하는 `references/*.md`는 정본 파일 기준 상대 경로이므로 `.agents/skills/pr-merger/` 아래에서 읽는다.
- 정본을 읽지 못하면 게이트를 추측하지 않고 중단한다.
- `git`과 `gh` 명령은 `Bash` 도구로 실행한다. 서브에이전트를 만들지 않고 메인 대화가 직접 수행한다.
- Diff 이해도 게이트는 사용자 답변을 기다리는 단계다. 자문자답하거나 사용자를 대신해 채점을 통과시키지 않는다.
