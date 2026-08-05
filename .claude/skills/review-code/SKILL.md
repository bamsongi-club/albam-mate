---
name: review-code
description: "현재 브랜치 diff·지정 파일·현재 저장소 PR을 read-only로 일반 리뷰한다. 코드 리뷰, PR 리뷰·게시, 병렬·관점별 리뷰, 이미 한 리뷰 뒤의 2차 관점 확인과 테스트 적합성 검토 요청에 사용한다."
---

이 문서는 Claude Code 실행 어댑터다. 절차 정본은 [.agents/skills/review-code/SKILL.md](../../../.agents/skills/review-code/SKILL.md)이며 여기에 절차를 복사하지 않는다. 리뷰 기준을 바꿀 때는 정본만 고친다.

## 사용법

- 정본 `SKILL.md`를 먼저 읽고 그 [불변식](../../../.agents/skills/review-code/SKILL.md#불변식)과 [상위 오케스트레이션](../../../.agents/skills/review-code/SKILL.md#상위-오케스트레이션) 순서를 그대로 따른다.
- 정본이 링크하는 `references/*.md`는 정본 파일 기준 상대 경로이므로 `.agents/skills/review-code/` 아래에서 읽는다.
- 정본을 읽지 못하면 리뷰 기준을 추측하지 않고 중단한다.

## 실행 매핑

정본의 표현을 Claude Code에서 다음으로 읽는다. 이 표는 실행 수단만 바꾸며 범위 고정, 기계 출력 계약, 게시 전 payload 검증은 바꾸지 않는다.

| 정본의 표현 | Claude Code에서 |
| --- | --- |
| `review-code-reviewer` 배치 | `Agent` 도구의 `subagent_type: review-code-reviewer` |
| `review-code-judge`로 재판정 | `Agent` 도구의 `subagent_type: review-code-judge` |
| 병렬 배치 | 한 응답에 여러 `Agent` 호출을 함께 넣는다. 호출을 나눠 보내면 순차 실행된다 |
| `.codex/agents/`의 모델·sandbox 정본 | 두 어댑터 정의의 frontmatter를 쓴다. `tools`에 Edit·Write·Bash가 없어 read-only가 도구 수준에서 강제된다 |
| `git`, `gh`, `node ... validate-review-payload.mjs` | `Bash` 도구로 실행한다. Windows의 Git Bash에서도 그대로 동작한다 |

## Claude Code 실행 제약

- 서브에이전트에 git·gh 도구가 없다. 고정한 diff hunk, 파일 구간과 동반 문맥을 오케스트레이터가 프롬프트에 직접 주입하고 "직접 확인하라"고 넘기지 않는다.
- 실행 중인 서브에이전트에 상태 반환을 요청하거나 중간에 중단할 수 없다. 시간 기반 제한 대신 spawn 전에 샤드 크기를 정하고, 프롬프트에 후보 상한을 자기 제한으로 넣는다.
- 세션 정책이나 권한 설정으로 `Agent` 도구를 쓸 수 없으면 메인 에이전트가 같은 관점 잠금·같은 입력으로 순차 리뷰하고, 서브에이전트 없이 실행했다는 사실을 최종 보고에 남긴다.
