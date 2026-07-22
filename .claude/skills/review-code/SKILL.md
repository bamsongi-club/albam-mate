---
name: review-code
description: "코드 또는 GitHub PR 리뷰 요청 시 diff 근거로 관련 차원을 최대 3개 자동 선택해 subagent/multi-agent로 리뷰하고, 도구가 없으면 순차 리뷰한다. 일반 리뷰는 read-only 리포트로 내며, 사용자가 PR 번호나 URL을 지정해 리뷰를 요청하면 완료 후 요약과 라인별 코멘트를 해당 GitHub PR에 게시한다. 트리거: '리뷰해줘', '코드 리뷰', 'N번 PR 리뷰해줘', '병렬 리뷰', '백엔드적으로 봐줘', '컨벤션 봐줘', '/review-code'."
---

## 역할

요청된 코드 또는 현재 브랜치 변경사항을, **차원별 전문 리뷰 패스**로 나누어 리뷰한다.
사용 중인 런타임이 subagent/multi-agent 도구를 제공하면 병렬 실행하고, 없으면 메인 에이전트가 같은 차원 잠금 규칙으로 순차 실행한다.
메인 대화가 오케스트레이터다. 코드 수정·리팩토링 패치·요청하지 않은 기능 추가는 하지 않는다(모든 차원 리뷰도 read-only).
일반 리뷰는 텍스트 보고만 한다. 사용자가 PR 번호 또는 URL과 함께 리뷰를 요청한 경우에만, 리뷰 결과를 해당 GitHub PR에 게시하는 작업까지 명시적으로 요청한 것으로 본다.

## 왜 병렬로 쪼개는가

- **차원별 전문성**: 한 프롬프트가 여러 관점을 동시에 보면 주의가 분산된다. "너는 X만 본다"고 못박은 리뷰 패스는 그 렌즈로만 깊게 판다.
- **동시 실행 속도**: 런타임이 병렬 subagent/multi-agent 실행을 지원하면 여러 차원을 한 번에 던지고 결과만 모은다.

## 차원 (10개 후보)

| # | 차원 | 초점 |
| --- | --- | --- |
| 1 | correctness | 논리 버그, 엣지 케이스, null/예외, 경계 조건 |
| 2 | security | 인증·인가, 인젝션, 민감정보 노출, 입력 검증 |
| 3 | conventions | 레포 컨벤션 문서 위반, 네이밍, 시간 처리, 문서화 주석 |
| 4 | performance | N+1, 불필요 쿼리, 캐싱, 자료구조 |
| 5 | test-coverage | 누락 테스트, 취약한 단언, 게이트 |
| 6 | architecture | 레이어 경계, 책임 분리, 과한 설계 |
| 7 | cross-file-consistency | 파일 간 계약 불일치, 시그니처 표류 |
| 8 | privacy | 개인정보 처리, 로깅 노출, 보존 |
| 9 | cpu-perf-patterns | 핫 패스 할당, 불필요 반복, 동기 블로킹 |
| 10 | behavioral-correctness | 명세 대비 실제 동작, 회귀 |

### diff 기반 차원 라우팅

`AUTO_DIMENSION_LIMIT = 3`

- 차원 선택을 위해 별도 subagent를 만들지 않는다. 오케스트레이터가 변경 파일 목록, diff stat, PR 설명과 핵심 diff hunk만 보고 먼저 선택한다.
- 리뷰할 diff가 없으면 차원 리뷰를 실행하지 않고 대상이 없다고 보고한다. diff가 있으면 자동 선택 차원을 1~3개로 제한한다.
- 실행 코드의 분기·상태·예외·계약 변경은 `correctness`, 인증·인가·입력·SQL·파일·명령·비밀정보 변경은 `security`, 쿼리·반복·캐시·동시성·대량 처리는 `performance` 후보로 삼는다.
- 새 동작·버그 수정·분기 추가에 대응하는 테스트가 부족하면 `test-coverage`, 모듈·레이어·의존성 방향이 바뀌면 `architecture`, DTO·스키마·API 등 여러 파일의 계약이 함께 바뀌면 `cross-file-consistency` 후보로 삼는다.
- 개인정보·로그·분석·보존 변경은 `privacy`, 핫 패스·대량 할당·동기 블로킹은 `cpu-perf-patterns`, 명세·PR 설명·사용자 흐름 대비 동작 확인이 필요하면 `behavioral-correctness`, 레포 규칙과 직접 충돌할 가능성이 있으면 `conventions` 후보로 삼는다.
- 각 자동 선택 차원에는 구체적인 파일 또는 diff 근거를 한 줄로 남긴다. 근거가 없는 차원은 활성화하지 않는다.
- 후보가 3개를 넘으면 장애·보안·데이터 손상 위험, 사용자 영향, 변경 범위와 결합도, 회귀 가능성 순으로 상위 3개만 고른다.
- `correctness`와 `behavioral-correctness`, `performance`와 `cpu-perf-patterns`가 같은 근거를 중복해서 보면 더 구체적인 하나만 선택한다.
- 사용자가 "오직 X", "X만"이라고 하면 요청한 차원만 실행하고 자동 선택하지 않는다.
- 사용자가 차원을 명시하면 반드시 포함하고, 자동 차원은 전체 선택 수가 3개가 될 때까지만 보충한다. 사용자가 4개 이상을 직접 명시한 경우에는 명시 차원을 모두 실행하고 자동 차원을 추가하지 않는다.
- 실행 전에 `자동 선택 2/3: correctness — <근거>; test-coverage — <근거>`처럼 선택 결과를 알린다. 선택되지 않은 차원의 리뷰 패스나 subagent는 만들지 않는다.
- 런타임 동시 실행 슬롯이 부족하면 선택 차원을 배치 실행하되, 선택 개수를 늘리지 않는다.

## 실행 절차

### 1. 리뷰 범위 1회 계산 (오케스트레이터)

```shell
git status --short
git diff --stat
git diff --name-only
```

- 사용자가 PR 번호 또는 URL을 지정했으면 `gh auth status`로 인증을 확인한다. 번호만 지정했으면 `gh repo view --json nameWithOwner`로 현재 저장소를 쓰고, URL이면 URL의 owner/repo를 쓴다. `gh pr view <PR> --json number,title,body,state,url,baseRefName,baseRefOid,headRefName,headRefOid,files`로 대상을 확정하고, `gh pr diff <PR> --name-only`와 `gh pr diff <PR> --patch`로 원격 PR diff를 읽는다. 로컬 미커밋 변경은 이 범위에 섞지 않는다.
- 대상이 브랜치 변경사항이면 base를 자동 감지한다: `git symbolic-ref --quiet refs/remotes/origin/HEAD`(없으면 `git remote show origin`에서 HEAD branch 확인, 그래도 없으면 `main`→`master` 순으로 존재하는 것)를 base로 삼아 `git diff --stat <base>...HEAD`. 사용자가 base를 명시하면 그것을 우선한다.
- 특정 파일을 지정했으면 그 범위로 좁힌다.
- 통계를 본 뒤 실제 diff는 필요한 파일 범위로만 확보한다.

### GitHub PR 게시 모드 판정

- `17번 PR 리뷰해줘`, `PR #17 리뷰`, PR URL과 함께 한 리뷰 요청처럼 **PR 식별자와 리뷰 요청이 함께 있으면** 게시 모드다. 별도의 게시 확인을 다시 묻지 않는다.
- `이 브랜치 리뷰해줘`, `PR처럼 리뷰해줘`, `17번 PR 설명해줘`처럼 둘 중 하나가 없으면 게시 모드가 아니다.
- 사용자가 게시하지 말라고 하거나 dry-run을 요청하면 게시하지 않는다.
- PR을 찾을 수 없거나 인증·쓰기 권한이 없으면 로컬 리뷰 결과는 완성하되 게시 실패를 정확히 알린다.

### 2. 차원별 리뷰 실행

활성 차원마다 독립 리뷰 패스를 만든다.
Claude Code에서는 `Agent` 같은 subagent 도구를, Codex에서는 사용 가능한 multi-agent/sub-agent 도구를 우선 사용한다.
도구가 없으면 메인 에이전트가 활성 차원을 하나씩 순차 리뷰하되, 각 차원별로 아래 입력을 그대로 적용한다.
각 차원 프롬프트에 아래를 주입한다 — 독립 리뷰 패스가 범위를 재해석하지 않게 한다:

- **리뷰 범위**: 변경 파일 목록 + (필요 시) 핵심 diff. 개별 리뷰 패스가 git을 재실행하지 않아도 되게 명시.
- **차원 잠금**: "너는 오직 `<차원>`만 본다. 다른 차원 문제는 무시하라."
- **read-only 못박기**: "코드를 수정하지 마라. 파일 수정 도구와 쓰기 명령 금지. 발견만 보고하라."
- **컨벤션 소스**(conventions 차원 한정): 레포의 컨벤션 문서를 기준으로 삼아라. 존재하는 것을 순서대로 찾는다 — `AGENTS.md`/`CLAUDE.md`가 가리키는 컨벤션 정본, `docs/CONVENTIONS.md`, `CONTRIBUTING.md`, `.editorconfig`, 린터·포매터 설정(`.eslintrc*`, `ruff.toml`, `checkstyle`, `spotless` 등). 명시된 컨벤션 문서가 없으면 해당 언어의 관용을 기준으로 삼는다.
- **반환 형식**: 아래 "서브에이전트 반환 형식"을 그대로 지시.

### 3. 병합·우선순위화 (오케스트레이터)

- 각 차원별 Findings를 모아 중복 제거(같은 위치·같은 문제는 하나로).
- severity 순으로 정렬(아래 "심각도" 순서).
- 차원 간 상충(예: 성능 vs 가독성)은 트레이드오프로 명시.

### 4. GitHub PR 게시 (게시 모드만)

- 병합과 우선순위화가 끝난 뒤에만 게시한다. 2층 PR 전체 요약을 review body로, 각 Finding의 1층 코멘트를 실제 diff 라인의 inline review comment로 묶는다.
- 최신 `headRefOid`를 `commit_id`로 사용한다. 추가·수정·문맥 라인은 `line`과 `side: RIGHT`, 삭제 라인은 이전 파일의 `line`과 `side: LEFT`를 사용한다. 폐기 예정인 `position` 방식은 사용하지 않는다.
- diff에 포함되지 않아 안전하게 앵커할 수 없는 Finding은 억지로 inline 처리하지 말고 PR 전체 요약에 `file:line`과 함께 넣는다.
- GitHub의 `POST /repos/{owner}/{repo}/pulls/{PR}/reviews`를 사용해 한 번의 review로 게시한다. `event`는 항상 `COMMENT`로 설정해 GitHub의 승인·변경 요청 상태를 임의로 바꾸지 않는다.
- UTF-8 JSON payload는 다음 필드를 사용한다: `commit_id`, `body`, `event`, `comments[]`의 `path`, `line`, `side`, `body`. Finding이 없으면 `comments`를 생략하고 요약만 게시한다.
- `gh api --method POST -H "Accept: application/vnd.github+json" "repos/{owner}/{repo}/pulls/{PR}/reviews" --input <payload-file>`로 전송한다. 422가 나면 최신 head와 diff line을 다시 확인해 한 번만 재구성한다. 앵커가 계속 유효하지 않으면 해당 Finding을 요약으로 옮기고 나머지 유효 코멘트와 함께 게시한다.
- 응답의 review ID/URL과 실제 inline comment 수를 확인한 뒤에만 게시 완료라고 보고한다. 실패하면 오류를 그대로 알리고 게시했다고 주장하지 않는다.

## 심각도 (4단계)

| | 레벨 | 의미 |
| --- | --- | --- |
| 🔴 | `critical` | 머지 차단. 데이터 손상·보안 취약·크래시·명세 위반. |
| 🟠 | `major` | 머지 전 수정 권장. 실질 버그·설계 결함. |
| 🟡 | `minor` | 개선 권장하나 차단 아님. |
| ⚪ | `nit` | 취향·사소. 무시 가능. |

## 출력: 2층

코드에는 손대지 않는 read-only 리뷰다. 일반 모드에서는 아래를 **텍스트로만 출력**하고, GitHub PR 게시 모드에서는 같은 내용을 review body와 inline comment로 실제 게시한 뒤 URL과 코멘트 수를 함께 보고한다.

### 1층 — 라인별 코멘트 (발견마다 1개)

각 Finding을 파일·라인에 앵커해 아래 4종을 그대로 쓴다:

```
<이모지> <레벨> | <제목>            (예: 🔴 critical | 결제 확정 전 재고 차감 누락)
TL;DR: 무엇이 / 왜 문제인지 한 줄.
Good: 이 코드에서 잘 된 점 또는 지키려던 의도 한 줄 (비난 완화·맥락).
→ Fix:
```<lang>
<수정 제안 코드>
```
```

`nit`은 코드 블록 없이 한 줄 제안만 달아도 된다.

### 2층 — PR 전체 요약 (1개)

```
## 판정: Approve | Changes Requested | Blocked
심각도: 🔴 <n>  🟠 <n>  🟡 <n>  ⚪ <n>
Walkthrough: 이번 변경이 무엇을 하는지 2~3줄.
잘된 점:
- ...
주요 지적 (critical/major만):
- 🔴 file:line — 제목
- 🟠 file:line — 제목
다음 액션:
- ...
```

판정 규칙: `critical` 하나라도 있으면 **Blocked**, 없고 `major` 있으면 **Changes Requested**, 둘 다 없으면 **Approve**(minor/nit은 코멘트로만).

직설적으로 쓰되 비난하지 않는다. 필요할 때만 짧은 예시 코드를 든다.

## 차원별 반환 형식

각 리뷰 패스는 자기 차원의 Findings만 "1층 — 라인별 코멘트" 형식으로 반환한다(차원명을 머리에 붙인다). 발견 없으면 `No findings.`.
오케스트레이터가 이를 모아 2층 요약을 만든다.

## 기본 규칙

- 코드를 직접 수정하지 않는다. 전체 구현 코드를 제공하지 않는다.
- 확인하지 않은 검증(테스트 통과 등)을 완료로 표시하지 않는다.
- 차원별 결과가 근거 없이 단정하면 오케스트레이터가 걸러낸다(위치·근거 없는 Finding은 낮춰 다룬다).
- 판단 기준(특히 과한 설계 제한, 컨벤션)은 위 "컨벤션 소스"에서 찾은 레포의 컨벤션 문서를 단일 소스로 따른다. 여기 중복 기재하지 않는다.
