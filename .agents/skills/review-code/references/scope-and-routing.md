# 범위와 라우팅

## 목차

- [대상 확정](#대상-확정)
- [PR 범위와 게시 모드](#pr-범위와-게시-모드)
- [브랜치와 미커밋 범위](#브랜치와-미커밋-범위)
- [T-ID 계약 검증 모드](#t-id-계약-검증-모드)
- [일반 리뷰 라우팅](#일반-리뷰-라우팅)
- [샤드 경계](#샤드-경계)

## 대상 확정

다음 순서로 대상 하나를 확정하고 base/head SHA, 파일 목록, 대상 hunk 또는 파일 구간을 고정한다.

| 요청 형태 | 범위와 정본 |
| --- | --- |
| PR 번호 또는 URL + 리뷰 요청 | 현재 저장소의 원격 PR만 `gh pr view`와 `gh pr diff`로 읽는다. 로컬 미커밋 변경과 로컬 Git 범위 계산을 섞지 않는다. |
| 명시한 파일 | 지정 파일을 리뷰한다. diff를 함께 지정했으면 해당 hunk 중심, 아니면 파일 전체와 검증에 필요한 최소 심볼·호출자·테스트를 본다. |
| 현재 브랜치 변경 | 사용자가 지정한 base를 우선한다. 없으면 현재 저장소의 개발 기준, 해당 브랜치의 PR target·upstream과 원격 ref를 확인해 실제 통합 대상을 정한다. 이 저장소에서는 존재를 확인한 `origin/develop`을 `origin/main`보다 우선하고 `origin/HEAD`를 자동 우선하지 않는다. 대상과 head의 merge-base를 쓰고, 근거가 충돌하거나 대상을 확정할 수 없을 때만 묻는다. |
| 미커밋 변경 포함 요청 | branch 범위와 별도로 staged diff, unstaged diff, untracked 파일을 모두 포함한다. |
| 사람이 승인한 T-ID 계약 + 고정 구현 diff | T-ID 계약 검증 모드로 승인된 T-ID의 `id`·`intent`와 고정 diff만 본다. 이슈 본문, 패킷 `completionCriteria`, 구현 세션 설명에서 요구사항을 역추정하지 않는다. |

명시한 파일·PR·브랜치 변경이 없고 T-ID 계약과 고정 diff도 없으면 리뷰 대상이 없다고 보고한다.
고정한 뒤 대상 내용이나 base/head가 바뀌면 기존 판정을 재사용하지 않고 스냅샷을 다시 고정한다.

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

## 일반 리뷰 라우팅

- 사용자가 지정한 차원은 그대로 따른다. 지정하지 않았다면 대상 stat·설명·핵심 hunk의 변경 위험과 결합도를 보고 서로 다른 실패 경로를 담당할 reviewer 수와 최대 3개 차원을 선택하고 각 근거를 한 줄로 남긴다.
- 차원 이름과 분류표를 고정하지 않는다. 논리·보안·개인정보·성능·테스트·아키텍처·파일 간 계약·사용자 동작·저장소 규칙 중 실제 변경 위험을 가장 직접 설명하는 관점을 쓴다.
- 같은 근거를 중복 검토하지 않는다. 보안, 데이터 손상, 공개 계약과 사용자 영향이 큰 위험을 먼저 다루고 미검토 위험이 남으면 `Incomplete`로 보고한다.

## 샤드 경계

- 각 패스에는 고정한 스냅샷 식별자, shard·차원·위험 ID, 정확한 대상 조각과 필요한 동반 문맥만 준다.
- 한 패스가 신뢰성 있게 검토하기에 크면 클래스·함수·API 절 같은 논리 경계로 나누되 요청 범위와 위험 ID의 coverage를 잃지 않는다.
