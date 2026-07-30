# 준비 게이트

## PR 스냅샷

1. `docs/CONVENTIONS.md`의 `## 커밋` 절을 읽고 커밋 제목 계약을 확인한다. 파일이나 절을 읽을 수 없으면 중단한다.
2. 사용자가 명시한 PR 번호가 양의 정수인지 확인해 `pr`에 보관한다.
3. 다음을 조회하고 `repo + PR number + title + baseRefOid + headRefOid`를 스냅샷으로 기록한다.

~~~shell
gh api user
gh repo view --json nameWithOwner,squashMergeAllowed
gh pr view <N> --json number,title,state,isDraft,author,baseRefName,baseRefOid,headRefName,headRefOid,isCrossRepository,mergeable,mergeStateStatus,reviewDecision,url
gh api --paginate repos/<owner>/<repo>/pulls/<N>/reviews
gh pr diff <N> --name-only
gh pr diff <N>
gh pr checks <N> --json bucket,name,state,workflow
~~~

4. 리뷰 목록에서 작성자가 아닌 리뷰어를 추린 뒤 각 리뷰어의 저장소 권한을 조회한다.

~~~shell
gh api "repos/$owner/$repo/collaborators/$reviewer/permission"
~~~

5. `gh pr diff` 출력이 잘리거나 명령이 실패하면 `gh api --paginate "repos/<owner>/<repo>/pulls/<N>/files?per_page=100"`로 모든 페이지의 파일과 patch를 조회하고 변경 파일을 빠뜨리지 않는다.

## 원격 준비 조건

다음 조건을 모두 만족해야 이해도 게이트로 진행한다.

- `gh api user`의 로그인과 `author.login`이 같다. 다르면 PR 작성자 본인이 요청하도록 안내하고 중단한다.
- PR이 OPEN, non-draft, same-repository PR이다. 닫힌 PR, 이미 머지된 PR, fork PR은 처리하지 않는다.
- `reviewDecision`이 `APPROVED`다.
- 봇·작성자·권한을 확인할 수 없는 사용자를 제외한다. API 응답의 permission이 `write` 또는 `admin`인 리뷰어만 신뢰하고 maintain 역할은 permission에서 `write`로 매핑한다.
- 신뢰한 리뷰어별 최신 유효 상태를 판정한다. `COMMENTED`, `DISMISSED`, `PENDING`을 제외한 최신 리뷰가 `APPROVED`이고 `commit_id`가 현재 `headRefOid`인 리뷰어가 한 명 이상이어야 한다.
- 신뢰한 리뷰어의 최신 유효 상태에 `CHANGES_REQUESTED`가 없다.
- 모든 review thread가 resolved 상태다.
- PR 제목이 정확히 `[type] 한국어 제목` 형식이고 `type`과 제목이 커밋 컨벤션을 충족한다.
- `[type]`과 제목을 분리해 scope 없이 `type: 한국어 제목` 형식의 `merge_subject`를 만든다. PR 제목을 그대로 재사용하거나 scope를 추론하지 않는다.
- `merge_subject`를 명령 문자열로 조립하거나 평가하지 않고 `--subject`의 인용된 단일 인자로 전달한다.
- 체크가 있으면 모든 bucket이 pass 또는 skipping이다. 체크가 없으면 등록된 CI가 없다고 명시한다.
- `mergeable`이 `MERGEABLE`이고 `mergeStateStatus`가 `CLEAN`이다. `UNKNOWN`이면 한 번만 다시 조회하고, 계속 `UNKNOWN`이면 중단한다. 그 외 상태도 원인을 보고하고 중단한다.
- 저장소가 squash merge를 허용한다.

미해결 review thread는 GraphQL로 모든 페이지를 조회한다. `hasNextPage`가 남았거나 하나라도 `isResolved: false`이면 완료로 판정하지 않는다.

~~~shell
gh api graphql --paginate -f owner=<owner> -f repo=<repo> -F number=<N> -f query='query($owner: String!, $repo: String!, $number: Int!, $endCursor: String) { repository(owner: $owner, name: $repo) { pullRequest(number: $number) { reviewThreads(first: 100, after: $endCursor) { nodes { isResolved } pageInfo { hasNextPage endCursor } } } } }'
~~~

조건이 하나라도 부족하면 퀴즈를 내지 말고 상태와 필요한 다음 조치만 보고한다.

## 로컬 브랜치 사전 점검

PR 메타데이터의 `headRefName`과 `headRefOid`를 각각 `head_ref`, `head_oid`에 보관한다. `head_ref`를 명령 문자열로 조립하거나 `eval`·`Invoke-Expression`으로 평가하지 않고, 검증 후 항상 인용된 단일 인자로 전달한다.

먼저 브랜치명 형식을 검증하고 실패하면 중단한다.

~~~shell
git check-ref-format --branch "$head_ref"
~~~

로컬 head 브랜치가 없으면 계속한다. 있으면 다음을 확인한다.

~~~shell
git branch --show-current
git status --short
git rev-parse --verify --end-of-options "refs/heads/$head_ref^{commit}"
git worktree list --porcelain
~~~

- 로컬 ref가 스냅샷 `headRefOid`와 다르거나 다른 worktree에서 checkout 중이면 중단한다.
- 현재 worktree가 head 브랜치라면 clean일 때만 계속한다.
- 현재 브랜치가 다른 브랜치라면 unrelated dirty 변경을 건드리지 않는다.
