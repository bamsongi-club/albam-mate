# 범위와 라우팅

## 목차

- [대상 확정](#대상-확정)
- [PR 범위와 게시 모드](#pr-범위와-게시-모드)
- [브랜치와 미커밋 범위](#브랜치와-미커밋-범위)
- [T-ID 계약 검증 모드](#t-id-계약-검증-모드)
- [일반 리뷰 실행 등급](#일반-리뷰-실행-등급)
- [차원 선택](#차원-선택)
- [샤드 경계](#샤드-경계)

## 대상 확정

다음 순서로 대상 하나를 확정하고 base/head SHA, 파일 목록, 대상 hunk 또는 파일 구간을 고정한다.

| 요청 형태 | 범위와 정본 |
| --- | --- |
| PR 번호 또는 URL + 리뷰 요청 | 현재 저장소의 원격 PR만 `gh pr view`와 `gh pr diff`로 읽는다. 로컬 미커밋 변경과 로컬 Git 범위 계산을 섞지 않는다. |
| 명시한 파일 | 지정 파일을 리뷰한다. diff를 함께 지정했으면 해당 hunk 중심, 아니면 파일 전체와 검증에 필요한 최소 심볼·호출자·테스트를 본다. |
| 현재 브랜치 변경 | 사용자가 지정한 base를 우선한다. 없으면 `origin/HEAD`, 그다음 존재하는 원격 브랜치 `origin/develop`·`origin/main`·`origin/master`·`origin/trunk` 순으로 base 후보를 찾고 merge-base를 사용한다. 모두 실패하면 base를 물어본다. |
| 미커밋 변경 포함 요청 | branch 범위와 별도로 staged diff, unstaged diff, untracked 파일을 모두 포함한다. |
| 사람이 승인한 T-ID 계약 + 고정 구현 diff | T-ID 계약 검증 모드로 승인된 T-ID의 `id`·`intent`와 고정 diff만 본다. 이슈 본문, 패킷 `completionCriteria`, 구현 세션 설명에서 요구사항을 역추정하지 않는다. |

명시한 파일·PR·브랜치 변경이 없고 T-ID 계약과 고정 diff도 없으면 리뷰 대상이 없다고 보고한다.

## PR 범위와 게시 모드

- PR 모드는 현재 저장소만 지원한다. 먼저 `gh repo view --json nameWithOwner,url`로 `repo`와 `repoUrl`을 고정한다.
- URL을 받으면 정규화한 URL의 origin과 owner/repo가 `repoUrl`과 일치하고, 경로 전체가 `<repo-path>/pull/<양의 정수>`와 정확히 일치할 때만 PR 번호를 추출한다. 접미 경로가 있거나 하나라도 다르면 지원하지 않는 저장소라고 보고 중단한다.
- 이후 모든 `gh pr` 호출에는 추출한 `$pr`와 `--repo "$repo"`를 쓰고, 게시 API도 `repos/$repo/pulls/$pr/reviews`만 쓴다.
- `gh auth status`, `gh repo view`, `gh pr view`의 number, title, body, state, url, base/head ref와 SHA, files를 확인한다. `gh pr diff`의 name-only와 patch를 범위 정본으로 쓴다.
- PR 식별자와 리뷰 요청이 함께 있으면 게시 모드다. 예를 들어 `17번 PR 리뷰해줘`는 게시 승인을 포함하므로 확인을 다시 묻지 않는다.
- 둘 중 하나가 없으면 텍스트 보고만 한다. 예를 들어 `17번 PR 설명해줘`, `이 브랜치 리뷰해줘`는 게시 모드가 아니다.
- 사용자가 게시 금지 또는 dry-run을 요청하면 게시하지 않는다.
- PR을 찾을 수 없거나 쓰기 권한이 없으면 로컬 리뷰 결과는 완성하고 게시 실패 원인만 정확히 보고한다.

## 브랜치와 미커밋 범위

미커밋 변경 포함 모드에서는 다음으로 staged·unstaged·untracked 범위를 각각 고정한다.

~~~shell
git diff --cached
git diff
git ls-files --others --exclude-standard
~~~

untracked 파일은 내용을 읽어 위험 매니페스트와 파일 목록에 넣는다. 제외한 파일이 있으면 최종 보고에 범위를 명시한다.

## T-ID 계약 검증 모드

- 실행 등급·차원·위험 매니페스트·샤딩을 적용하지 않는다.
- 패킷 작성자·구현자와 다른 fresh `review-code-reviewer` 하나를 사용한다.
- 부모는 `mode=test-contract`, 입력 순서의 T-ID `id`·`intent`, 고정 diff와 T-ID 출력 계약만 전달한다.
- reviewer의 조회·판정 행동은 `.codex/agents/review-code-reviewer.toml`을 따른다.
- 일반 위험 리뷰가 필요하면 별도 일반 모드로 실행하고 두 판정을 서로 덮어쓰지 않는다.

## 일반 리뷰 실행 등급

파일 수, 추가·삭제 줄 수와 변경 위험으로 실행 등급을 먼저 고정한다. 사용자가 명시한 차원은 등급보다 우선한다.

| 등급 | 조건 | 실행 | 전체 반환 시점 |
| --- | --- | --- | --- |
| fast | 8개 이하 파일, 400줄 이하이고 공개 계약·고위험 신호가 없음 | 주 차원 reviewer 1개 | 60초 목표, 90초에 반환 |
| standard | fast를 넘거나 공개 API·스키마·설정 계약을 변경하고 deep 신호가 없음 | reviewer 2개 | 90초 목표, 150초에 반환 |
| deep | 보안·개인정보·인증·인가·마이그레이션·동시성·데이터 손상 위험, 또는 30개 이상 파일·2,000줄 이상 | reviewer 최대 3개 | 일반 리뷰 실행 계약의 샤드 예산 적용 |

## 차원 선택

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

- 대상 파일, stat, PR 설명, 핵심 hunk 또는 지정 파일 내용을 보고 fast는 주 차원 1개, standard는 2개, deep은 최대 3개를 자동 선택한다. 각 차원에는 파일 또는 hunk 근거를 한 줄로 남긴다.
- 사용자가 하나의 차원만 요구하면 그것만 실행한다. 여러 차원을 명시하면 모두 포함하고 자동 선택은 총 3개가 될 때까지만 보충한다. 4개 이상을 명시하면 자동 차원을 더하지 않는다.
- 분기·상태·예외·계약은 correctness, 인증·입력·SQL·파일·명령은 security, 개인정보·로그·자유 텍스트는 privacy, 쿼리·반복·캐시·동시성은 performance 또는 cpu-perf-patterns를 우선한다.
- 새 분기·동작의 테스트는 해당 위험 담당 차원이 함께 본다. 사용자가 요구하거나 테스트가 변경의 중심일 때만 test-coverage를 독립 차원으로 고른다.
- DTO·스키마·API·명세 계약은 cross-file-consistency, 레이어·의존성 방향은 architecture, 명세·사용자 흐름 대비 동작은 behavioral-correctness, 레포 규칙 충돌은 conventions를 고른다.
- correctness와 behavioral-correctness, performance와 cpu-perf-patterns가 같은 근거를 보면 더 구체적인 하나만 선택한다.
- 후보가 3개를 넘으면 장애·보안·데이터 손상 위험, 사용자 영향, 결합도, 회귀 가능성 순으로 고른다.
- 실행 전에 security(보안), performance·cpu-perf-patterns(동시성·성능), cross-file-consistency(계약) 등 선택한 차원 후보와 근거를 알린다. 슬롯이 부족하면 시작 가능한 차원을 먼저 배치하고 남은 선택 차원은 슬롯이 생기는 순서대로 실행한다.

## 샤드 경계

- 각 패스에는 대상 종류(`file` 또는 `diff`), 관련 파일 구간 또는 hunk, 최소 문맥, 위험 ID, 동반 테스트·설정·정책·공개 계약만 준다.
- 지정 파일 리뷰에서는 파일 전체 또는 필요한 파일 구간과 최소 호출자·테스트를 부모가 함께 제공한다.
- 차원 범위가 8개 파일, 추가·삭제 800줄, 단일 파일 600줄 중 하나를 넘으면 클래스·함수·API 절 같은 논리 경계로 나눈다.
- 외부 계약 조회는 오케스트레이터가 한 번만 수행한다.
