---
name: backend-delivery
description: "Albam Mate의 확정된 백엔드 기능·버그 수정과 기존 open PR의 선택 리뷰 수정을 위험도에 맞게 검증·전달한다. 기존 계약 안의 좁고 저위험인 이슈 구현·리뷰 수정은 review-fast로, 고위험·범위 확장 변경은 full-delivery로 처리할 때 사용한다. 답글·스레드 해결만 필요한 요청, 읽기 전용 리뷰, 문서·프런트엔드 작업은 제외한다."
---

이 문서는 Claude Code 실행 어댑터다. 절차 정본은 [.agents/skills/backend-delivery/SKILL.md](../../../.agents/skills/backend-delivery/SKILL.md)이며 여기에 절차를 복사하지 않는다. 절차를 바꿀 때는 정본만 고친다.

## 사용법

- 정본 `SKILL.md`를 먼저 읽고 그 [진입](../../../.agents/skills/backend-delivery/SKILL.md#진입)·[모드 선택](../../../.agents/skills/backend-delivery/SKILL.md#모드-선택)·[공통 전달 경계](../../../.agents/skills/backend-delivery/SKILL.md#공통-전달-경계)를 그대로 따른다.
- 정본이 링크하는 `references/review-fast.md`, `references/full-delivery.md`, `references/packet-template.json`은 정본 파일 기준 상대 경로이므로 `.agents/skills/backend-delivery/` 아래에서 읽는다.
- 정본을 읽지 못하면 절차를 추측하지 않고 중단한다.

## 실행 매핑

정본의 표현을 Claude Code에서 다음으로 읽는다. 이 표는 실행 수단만 바꾸며 판단 기준과 게이트는 바꾸지 않는다.

| 정본의 표현 | Claude Code에서 |
| --- | --- |
| `backend-developer`에 전달 | `Agent` 도구의 `subagent_type: backend-developer` |
| `pr-writer`를 사용 | `Skill` 도구의 `skill: pr-writer` |
| `gh-address-comments`로 확인 | 이 도구가 없으므로 `gh pr view`와 `gh api`로 현재 head와 미해결 스레드를 직접 확인한다 |
| `.\gradlew.bat ...`, `node scripts/...` | `docs/COMMANDS.md`의 현재 OS 열을 따르되 `Bash` 도구로 실행한다. Windows의 Git Bash에서는 `.\gradlew.bat`을 `./gradlew.bat`으로 바꾼다 |

- 패킷·manifest 작성과 검증 명령, `pr-writer` 호출, 최종 보고는 메인 대화가 맡는다. 서브에이전트에 넘기지 않는다.
