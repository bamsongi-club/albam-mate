# 준비 게이트

## 원격 스냅샷

1. `docs/CONVENTIONS.md`의 `## 커밋` 절을 읽는다. 파일이나 절이 없으면 중단한다.
2. 사용자가 명시한 양의 정수 PR 번호를 `pr`에 보관하고 다음을 조회한다.

~~~shell
gh api user
gh repo view --json nameWithOwner,squashMergeAllowed
gh pr view <N> --json number,title,state,isDraft,author,baseRefName,baseRefOid,headRefName,headRefOid,isCrossRepository,mergeable,mergeStateStatus,reviewDecision,url
gh api --paginate repos/<owner>/<repo>/pulls/<N>/reviews
gh pr diff <N> --name-only
gh pr diff <N>
gh pr checks <N> --json bucket,name,state,workflow
~~~

작성자가 아닌 리뷰어의 저장소 권한도 조회한다.

~~~shell
gh api "repos/$owner/$repo/collaborators/$reviewer/permission"
~~~

`repo + PR number + title + baseRefName + baseRefOid + headRefName + headRefOid`와 전체 diff를 스냅샷으로 기록한다. `gh pr diff`가 실패하거나 잘리면 `gh api --paginate "repos/<owner>/<repo>/pulls/<N>/files?per_page=100"`로 모든 파일과 patch를 읽는다.

## 원격 준비 조건

다음 조건을 모두 만족해야 이해도 게이트로 진행한다.

- 로그인 사용자가 PR 작성자이고 PR이 OPEN, non-draft, same-repository 상태다.
- `reviewDecision`이 `APPROVED`다. 봇·작성자·권한 미확인 사용자는 제외하고, permission이 `write` 또는 `admin`인 리뷰어만 신뢰한다(`maintain`은 `write`로 매핑).
- 신뢰한 리뷰어 중 `COMMENTED`, `DISMISSED`, `PENDING`을 제외한 최신 리뷰가 현재 `headRefOid`에 대한 `APPROVED`인 사람이 한 명 이상이다.
- 신뢰한 리뷰어의 최신 유효 상태에 `CHANGES_REQUESTED`가 없다.
- 모든 review thread가 resolved 상태다.
- PR 제목이 커밋 컨벤션에 맞는 `[type] 한국어 제목` 형식이다. scope를 추론하지 않고 `type: 한국어 제목`의 `merge_subject`로 변환해 인용된 단일 인자로만 전달한다.
- 체크가 있으면 모든 bucket이 pass 또는 skipping이다. 체크가 없으면 등록된 CI가 없다고 명시한다.
- `mergeable=MERGEABLE`, `mergeStateStatus=CLEAN`이고 저장소가 squash merge를 허용한다. `UNKNOWN`은 한 번만 다시 조회하며 나머지는 중단한다.

미해결 review thread는 GraphQL로 모든 페이지를 조회한다. `hasNextPage`가 남았거나 하나라도 `isResolved: false`이면 완료로 판정하지 않는다.

~~~shell
gh api graphql --paginate -f owner=<owner> -f repo=<repo> -F number=<N> -f query='query($owner: String!, $repo: String!, $number: Int!, $endCursor: String) { repository(owner: $owner, name: $repo) { pullRequest(number: $number) { reviewThreads(first: 100, after: $endCursor) { nodes { isResolved } pageInfo { hasNextPage endCursor } } } } }'
~~~

모든 페이지를 읽지 못했거나 조건이 하나라도 부족하면 퀴즈 없이 상태와 다음 조치만 보고한다.

## 로컬 브랜치 사전 점검

`headRefName`과 `headRefOid`를 `head_ref`, `head_oid`에 보관한다. `head_ref`는 명령으로 평가하지 않고 형식 검증 후 인용된 단일 인자로만 쓴다.

~~~shell
git check-ref-format --branch "$head_ref"
~~~

로컬 head 브랜치가 있으면 다음을 확인한다. 없으면 계속한다.

~~~shell
git branch --show-current
git status --short
git rev-parse --verify --end-of-options "refs/heads/$head_ref^{commit}"
git worktree list --porcelain
~~~

- 로컬 ref가 `head_oid`와 다르거나 다른 worktree에서 checkout 중이면 중단한다.
- 현재 worktree가 `head_ref`라면 clean일 때만 진행하고, 다른 브랜치라면 unrelated dirty 변경을 건드리지 않는다.
