# 결과 확인과 브랜치 정리

머지 명령 후 다음 조회로 `MERGED` 상태와 merge commit을 확인한다.

~~~shell
gh pr view "$pr" --json state,mergedAt,mergeCommit,headRefName,headRefOid,headRepository,url
~~~

- `MERGED`가 아니면 삭제하지 않는다. 이후 판단에는 머지 전 `head_ref`, `head_oid`만 사용한다.
- 원격·로컬 ref가 없으면 이미 삭제된 것으로 기록하고, OID가 `head_oid`와 다르면 새 커밋을 보존한다.
- 현재 브랜치가 `head_ref`라면 clean 상태에서 base로 전환한다. 로컬 base가 없으면 `origin/<baseRefName>`을 tracking하고, 다른 worktree가 `head_ref`를 사용 중이면 보존한다.
- 삭제 직전 OID를 다시 확인하고 expected OID를 지정한다. 확인 후 ref가 바뀌어 조건부 삭제가 실패하면 보존한다.

~~~shell
git rev-parse --verify --end-of-options "refs/heads/$head_ref^{commit}"
git update-ref -d "refs/heads/$head_ref" "$head_oid"
git ls-remote --heads origin "refs/heads/$head_ref"
git push --force-with-lease="refs/heads/$head_ref:$head_oid" origin ":refs/heads/$head_ref"
~~~

삭제가 실패하면 머지 성공과 원격·로컬 ref 잔존 상태를 구분해 보고한다.

최종 결과에는 PR 번호·URL, squash merge commit, 이해도 게이트 통과 방식과 결과, 원격 브랜치 삭제 여부, 로컬 브랜치 삭제 여부를 포함한다.
