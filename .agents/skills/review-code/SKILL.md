---
name: review-code
description: "현재 브랜치 diff, 지정 파일, 또는 현재 저장소의 GitHub PR을 read-only로 코드 리뷰할 때 사용한다. 관련 관점을 최대 3개 선택해 병렬 또는 순차 검토하며, PR 번호나 URL과 함께 리뷰를 요청하면 검증된 결과를 해당 PR에 게시한다. 트리거: 코드 리뷰, PR 리뷰, 병렬 리뷰, 특정 관점 리뷰, /review-code."
---

## 역할과 불변 규칙

- 요청한 변경 또는 파일을 차원별 전문 리뷰 패스로 검토한다.
- 코드·설정·문서를 수정하거나 요청하지 않은 테스트를 작성하지 않는다.
- 오케스트레이터가 범위, 위험 매니페스트, 공통 검증, 중복 제거, 최종 보고와 PR 게시를 맡는다.
- 샤드는 한 줄 JSON 레코드(JSONL)만 반환한다. 오케스트레이터만 사용자 보고와 GitHub 쓰기를 한다.
- 위치, 실패 시나리오, 계약 근거가 있는 Finding만 채택한다. 실행하지 않은 테스트나 확인하지 않은 사실을 완료로 표시하지 않는다.

## 범위와 게시 모드

다음 순서로 대상을 하나만 확정하고 base/head SHA, 파일 목록, 대상 hunk 또는 파일 구간을 고정한다.

| 요청 형태 | 범위와 정본 |
| --- | --- |
| PR 번호 또는 URL + 리뷰 요청 | 현재 저장소의 원격 PR만 gh pr view와 gh pr diff로 읽는다. 로컬 미커밋 변경과 로컬 Git 범위 계산을 섞지 않는다. |
| 명시한 파일 | 지정 파일을 리뷰한다. diff를 함께 지정했으면 해당 hunk 중심, 아니면 파일 전체와 검증에 필요한 최소 심볼·호출자·테스트를 본다. |
| 현재 브랜치 변경 | 사용자가 지정한 base를 우선한다. 없으면 origin/HEAD, 존재하는 develop·main·master·trunk 순으로 base 후보를 찾고 merge-base를 사용한다. 모두 실패하면 base를 물어본다. |
| 미커밋 변경 포함 요청 | branch 범위와 별도로 staged diff, unstaged diff, untracked 파일을 모두 포함한다. |

- PR 모드는 현재 저장소만 지원한다. 먼저 `gh repo view --json nameWithOwner,url`로 `repo`와 `repoUrl`을 고정한다. URL을 받으면 정규화한 URL이 `repoUrl/pull/<양의 정수>` 접두로 시작하는지 확인해 PR 번호를 추출하고, 아니면 지원하지 않는 저장소라고 보고 중단한다. 이후 모든 gh pr 호출에는 추출한 `$pr`와 `--repo "$repo"`를 쓰고, 게시 API도 `repos/$repo/pulls/$pr/reviews`만 쓴다.
- PR 모드에서는 gh auth status, gh repo view, gh pr view의 number, title, body, state, url, base/head ref와 SHA, files를 확인한다. gh pr diff의 name-only와 patch를 범위 정본으로 쓴다.
- 미커밋 변경 포함 모드에서는 git diff --cached, git diff, git ls-files --others --exclude-standard로 staged·unstaged·untracked 목록을 각각 고정한다. untracked 파일은 내용을 읽어 위험 매니페스트와 파일 목록에 넣고, 제외했다면 최종 보고에 범위를 명시한다.
- PR 식별자와 리뷰 요청이 함께 있으면 게시 모드다(`17번 PR 리뷰해줘`). 사용자가 PR을 지정한 것이 게시 승인이므로 확인을 다시 묻지 않는다. 둘 중 하나가 없으면(`17번 PR 설명해줘`, `이 브랜치 리뷰해줘`) 텍스트 보고만 하고, 사용자가 게시 금지·dry-run을 요청하면 게시하지 않는다.
- 명시한 파일·PR·브랜치 변경이 모두 없으면 리뷰 대상이 없다고 보고한다.
- PR을 찾을 수 없거나 쓰기 권한이 없으면 로컬 리뷰 결과는 완성하고 게시 실패 원인만 정확히 보고한다.

## 실행 등급·예산과 완료 조건

오케스트레이터는 파일 수, 추가·삭제 줄 수와 변경 위험으로 실행 등급을 먼저 고정한다. 사용자가 명시한 차원은 등급보다 우선한다.

| 등급 | 조건 | 실행 | 전체 반환 시점 |
| --- | --- | --- | --- |
| fast | 8개 이하 파일, 400줄 이하이고 공개 계약·고위험 신호가 없음 | 주 차원 reviewer 1개 | 60초 목표, 90초에 반환 |
| standard | fast를 넘거나 공개 API·스키마·설정 계약을 변경하고 deep 신호가 없음 | reviewer 2개 | 90초 목표, 150초에 반환 |
| deep | 보안·개인정보·인증·인가·마이그레이션·동시성·데이터 손상 위험, 또는 30개 이상 파일·2,000줄 이상 | reviewer 최대 3개 | 아래 샤드 예산 적용 |

- 전체 반환 시점에는 새 탐색을 중단하고 확인한 coverage와 Finding을 반환한다. 미검토 위험이 남으면 `Incomplete`로 판정한다. 사용자 승인 대기 시간은 예산에서 제외한다.
- 다음 제한은 샤드 하나에 적용한다. fast·standard에서는 남은 전체 예산이 더 짧으면 전체 반환 시점을 우선한다.

| 조건 | 행동 |
| --- | --- |
| 기본 예산 | 3분 반환 시점, 4분 강제 중단, 후보 최대 3개 |
| 3분 반환 시점 | 오케스트레이터가 상태 반환을 요청한다. 샤드는 확인한 위험 ID와 미검토 위험 ID를 JSONL로 반환하고 종료한다. |
| 4분 강제 중단 | 상태 요청에 응답하지 않은 샤드만 오케스트레이터가 중단하고, 미검토 위험을 절반 이하 크기로 재분할한다. 재분할은 두 단계까지만 한다. |
| 재분할 후 미검토 범위 존재 | 파일·hunk·위험 ID를 적고 최종 판정을 Incomplete로 한다. |
| 30개 이상 파일 또는 추가·삭제 2,000줄 이상 | minor·nit을 탐색하지 않고 그 사실을 최종 보고에 남긴다. |

- 오케스트레이터는 샤드마다 최대 8개 위험 매니페스트를 만든다.

| 변경 신호 | 위험 항목에 함께 넣을 근거 |
| --- | --- |
| 새 분기 | 참·거짓 경로와 대응 테스트 |
| 외부 계약 | 필요한 공식 1차 출처 최대 2개 |
| 개인정보 | 입력 → 저장 → 출력 경계 |
| 파일·명령 | 경로 경계와 실패 모드 |

- 각 위험 ID와 대상 hunk 또는 파일 구간에 담당 샤드를 하나 배정하고 coverage 장부를 유지한다. 배정 항목을 모두 확인한 샤드만 complete=true를 반환한다.
- Codex에서는 review-code-reviewer로 스캔하고, 보안·개인정보, critical·major, 차원 간 상충, medium confidence 후보만 review-code-judge로 재판정한다. high-confidence minor·nit만 있으면 판정기를 실행하지 않고, 판정 대상이 여러 개면 한 요청으로 묶는다. 각 agent의 모델·reasoning effort·sandbox는 `.codex/agents/` 설정을 정본으로 삼는다.
- 상위 모델 프로필은 검증된 개선을 확인하는 통제 실험에만 쓰고, 자동 폴백으로 사용하지 않는다.
- 지정 agent를 쓸 수 없으면 현재 런타임 기본값으로 폴백하고 실제 프로필을 최종 보고에 남긴다.

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

### 대상 기반 차원 라우팅

- 오케스트레이터가 대상 파일, stat, PR 설명, 핵심 hunk 또는 지정 파일 내용을 보고 fast는 주 차원 1개, standard는 2개, deep은 최대 3개를 자동 선택한다. 각 차원에는 파일 또는 hunk 근거를 한 줄로 남긴다.
- 사용자가 하나의 차원만 요구하면 그것만 실행한다. 여러 차원을 명시하면 모두 포함하고 자동 선택은 총 3개가 될 때까지만 보충한다. 4개 이상을 명시하면 자동 차원을 더하지 않는다.
- 분기·상태·예외·계약은 correctness, 인증·입력·SQL·파일·명령은 security, 개인정보·로그·자유 텍스트는 privacy, 쿼리·반복·캐시·동시성은 performance 또는 cpu-perf-patterns를 우선한다. 새 분기·동작의 테스트는 해당 위험 담당 차원이 함께 보고, 사용자가 요구하거나 테스트가 변경의 중심일 때만 test-coverage를 독립 차원으로 고른다.
- DTO·스키마·API·명세 계약은 cross-file-consistency, 레이어·의존성 방향은 architecture, 명세·사용자 흐름 대비 동작은 behavioral-correctness, 레포 규칙 충돌은 conventions를 고른다.
- correctness와 behavioral-correctness, performance와 cpu-perf-patterns가 같은 근거를 보면 더 구체적인 하나만 선택한다. 후보가 3개를 넘으면 장애·보안·데이터 손상 위험, 사용자 영향, 결합도, 회귀 가능성 순으로 고른다.
- 실행 전에 선택 결과와 근거를 알린다. 슬롯이 부족하면 선택한 차원만 배치 실행한다.

### 차원별 샤딩

- 각 패스에는 대상 종류(`file` 또는 `diff`), 관련 파일 구간 또는 hunk, 최소 문맥, 위험 ID, 동반 테스트·설정·정책·공개 계약만 준다. 지정 파일 리뷰에서는 파일 전체 또는 필요한 파일 구간과 최소 호출자·테스트를 부모가 함께 제공한다.
- 차원 범위가 8개 파일, 추가·삭제 800줄, 단일 파일 600줄 중 하나를 넘으면 클래스·함수·API 절 같은 논리 경계로 나눈다.
- 변경 hunk와 위험 ID에는 담당 샤드를 하나 배정한다. 계약·테스트 같은 동반 hunk만 필요한 샤드에 중복하고, 외부 계약 조회는 오케스트레이터가 한 번만 수행한다.

## 실행 절차

### 1. 공통 준비

- branch 또는 worktree 모드에서 git status --short, git diff --stat, 필요한 hunk diff, git diff --check를 한 번 실행한다. --stat의 파일 수와 추가·삭제 줄 수로 대형 diff 여부를 판정한다. 미커밋 변경 포함 모드에서는 staged·unstaged diff를 각각 확인하고, untracked 파일 내용도 범위에 포함한다.
- 저장소 정본이 요구하는 테스트·링크 검사는 오케스트레이터가 한 번만 실행한다. 가능하면 샤드와 병렬로 실행한다.
- 외부 훅·SDK·API·파일 형식 계약은 후보가 나온 뒤 채택이나 심각도 판단에 필요한 경우에만 공식 1차 출처를 한 번 확인한다. 변경 자체를 이해하는 데 필수인 계약만 공통 준비에서 먼저 확인한다.

### 2. 차원별 패스

- Codex에서는 실행 등급에서 정한 reviewer 수만 사용한다. standard·deep의 reviewer는 가능하면 병렬로 실행하고, 도구가 없으면 같은 규칙으로 순차 실행한다.
- 각 패스에 고정 base/head SHA 또는 파일 범위, 대상 종류, shard ID, 위험 ID, 정확한 파일 구간 또는 hunk와 최소 문맥, 동반 파일, 외부 계약 요약을 준다.
- 패스에는 자기 차원만 보게 하고, 파일 수정·쓰기 명령·전체 git diff·git log·빌드·테스트 재실행을 금지한다.
- conventions 패스는 AGENTS.md 또는 CLAUDE.md가 가리키는 정본, docs/CONVENTIONS.md, CONTRIBUTING.md, .editorconfig, 린터·포매터 설정 순으로 기준을 찾는다. 정본이 없으면 언어 관용을 쓴다.

### 3. 병합과 재판정

- 같은 위치·같은 문제를 하나로 합치고 coverage 장부의 미검토 범위를 먼저 처리한다.
- 추가·수정·문맥 라인은 head의 해당 줄과 문맥으로, 삭제 라인은 preimage와 LEFT diff 문맥으로 다시 검증한다.
- 외부 계약이 필요한 후보는 공식 1차 출처로, 보안·개인정보·critical·major·상충·medium confidence 후보는 판정기로 재확인한다.
- 위치·실패 시나리오·계약 근거가 없거나 low confidence인 후보는 제외한다. 검증된 Finding만 최종 형식으로 확장한다.

## GitHub PR 게시

게시 모드에서만 병합이 끝난 Finding을 한 번의 COMMENT review로 보낸다. PR 전체 요약을 review body로, 각 Finding을 해당 diff 라인의 inline comment로 보낸다.

- 최신 headRefOid를 commit_id로 쓴다. 추가·수정·문맥 라인은 line과 side=RIGHT, 삭제 라인은 preimage의 line과 side=LEFT를 쓴다.
- diff에 안전하게 앵커할 수 없는 Finding은 inline으로 억지로 보내지 않고 PR 전체 요약에 file:line으로 남긴다.
- payload에는 commit_id, body, event=COMMENT, comments의 path, line, side, body를 넣고 gh api로 reviews endpoint에 POST한다. event는 판정과 무관하게 항상 COMMENT다. APPROVE나 REQUEST_CHANGES로 GitHub의 승인·변경 요청 상태를 바꾸지 않는다.
- 422가 나면 최신 head와 앵커를 한 번만 재검증한다. 계속 유효하지 않은 Finding은 요약으로 옮긴다.
- 응답의 review ID, URL, 실제 inline comment 수를 확인한 뒤에만 게시 완료라고 보고한다.

## 심각도 (4단계)

| | 레벨 | 의미 |
| --- | --- | --- |
| 🔴 | `critical` | 즉시 악용 가능한 취약점, 데이터 손상, 크래시, 핵심 요구사항 차단 |
| 🟠 | `major` | 머지 전 수정이 필요한 실질 버그 또는 설계 결함 |
| 🟡 | `minor` | 개선 권장이나 차단하지 않는 문제 |
| ⚪ | `nit` | 취향 또는 사소한 제안 |

## 출력

샤드는 기계 처리용 JSONL만 반환한다. 오케스트레이터는 이를 원문 그대로 사용자에게 노출하지 않고, 일반 모드의 텍스트 보고와 게시 모드의 review body·inline comment로 읽기 쉽게 확장한다.

~~~json
{"type":"status","shard":"security-1","complete":true,"checkedRiskIds":["R1","R2"],"uncoveredRiskIds":[]}
{"type":"finding","candidateId":"security-1-F1","dimension":"security","severity":"major","file":"path/to/file","line":80,"side":"RIGHT","title":"짧은 제목","evidence":"실패 조건과 근거","fix":"짧은 수정 방향","confidence":"high"}
~~~

- complete는 배정된 위험 ID와 대상 범위를 모두 확인했을 때만 true다. soft limit에서는 checkedRiskIds와 uncoveredRiskIds를 즉시 반환한다.
- line은 대상 파일의 단일 앵커다. PR 모드에서는 diff 앵커만 inline으로 보내고, 삭제 hunk는 side=LEFT를 쓴다. 파일 리뷰에서는 side를 생략할 수 있다.
- 대형 diff가 아닐 때만 minor·nit을 보고한다. 샤드는 Good, 장문 설명, 전체 코드, PR 요약을 반환하지 않는다.

라인별 코멘트와 일반 모드의 Finding은 문제마다 아래 형식을 그대로 쓴다. 제목 구분자, 섹션 제목, 이모지를 임의로 바꾸거나 생략하지 않고, 위치·문제점·수정 방향을 섞어 한 문장으로 압축하지 않는다.

~~~text
<이모지> <레벨> | <제목>
위치: <file> (line <line>)

**🔍 문제점**
<실패 조건, 영향, 계약 근거>

**🔧 수정 방향**
<무엇을 어떻게 바꿀지 또는 짧은 예시>
~~~

- 게시 모드의 inline comment에서는 GitHub이 앵커를 이미 표시하므로 `위치` 줄을 생략한다. 일반 모드와 요약으로 옮긴 Finding에는 남긴다.
- 확인한 점은 특별히 언급할 가치가 있을 때만 `**✅ 확인한 점**` 절로 덧붙인다. 섹션 제목은 이모지와 볼드를 그대로 쓰고 번호를 붙이지 않는다. 코드 예시는 서술보다 더 명확하고 짧을 때만 수정 방향 절 아래 코드 블록으로 쓰고, 앵커 라인을 그대로 대체하는 짧은 수정이면 게시 모드에서 GitHub의 `suggestion` 코드 블록으로 쓴다.

PR 전체 요약은 한 번만 쓰고 아래 형식을 그대로 쓴다.

~~~text
## 판정: Approve | Changes Requested | Blocked | Incomplete

심각도: 🔴 <n>  🟠 <n>  🟡 <n>  ⚪ <n>

변경 요약: 이번 변경의 동작을 2~3줄.

### 잘된 점

- ...

### 주요 지적 (critical/major만)

- 🔴 file:line — 제목
- 🟠 file:line — 제목

### 다음 액션

- ...
~~~

- 절 제목은 `###` 헤딩으로 쓰고 헤딩과 목록 앞뒤에 빈 줄을 넣는다. 빈 줄을 빠뜨리면 GitHub이 다음 절 제목을 앞 목록의 마지막 항목에 이어 붙여 절 구분이 사라진다.
- 미검토 범위가 있으면 Incomplete, critical이 하나라도 있으면 Blocked, major가 있으면 Changes Requested, 그 외 완료된 리뷰면 Approve로 판정한다.
- minor·nit은 라인 코멘트에만 남긴다. 직설적으로 쓰되 비난하지 않는다.
