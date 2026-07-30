# 결과 확인과 브랜치 정리

머지 명령 후 다음 조회로 `MERGED` 상태와 merge commit을 확인한다.

~~~shell
gh pr view "$pr" --json state,mergedAt,mergeCommit,headRefName,headRefOid,headRepository,url
~~~

- `MERGED`가 아니면 브랜치를 삭제하지 않는다.
- 삭제 판단에는 머지 전 스냅샷의 `head_ref`, `head_oid`만 사용하고 머지 후 조회값으로 덮어쓰지 않는다.
- 원격·로컬 ref가 없으면 이미 삭제된 것으로 기록한다.
- 남아 있는 ref는 스냅샷 `head_oid`와 같을 때만 조건부 삭제하고, 다르면 새 커밋을 보존한다.
- 현재 브랜치가 `head_ref`라면 clean 상태를 다시 확인하고 base 브랜치로 전환한다.
- 로컬 base가 없으면 `origin/<baseRefName>`을 tracking하도록 만든다.
- 다른 worktree에서 사용 중이면 로컬 ref를 보존한다.
- 로컬 ref의 현재 OID를 다시 확인한 뒤 expected OID를 지정해 삭제한다. 확인과 삭제 사이에 ref가 바뀌면 `git update-ref`가 실패하므로 보존하고 보고한다.
- 원격 ref의 현재 OID를 다시 확인한 뒤 expected OID를 lease로 지정해 삭제한다. 확인과 삭제 사이에 ref가 바뀌면 push가 실패하므로 보존하고 보고한다.

~~~shell
git rev-parse --verify --end-of-options "refs/heads/$head_ref^{commit}"
git update-ref -d "refs/heads/$head_ref" "$head_oid"
git ls-remote --heads origin "refs/heads/$head_ref"
git push --force-with-lease="refs/heads/$head_ref:$head_oid" origin ":refs/heads/$head_ref"
~~~

삭제가 실패하면 머지 성공과 원격·로컬 ref 잔존 상태를 구분해 보고한다.

최종 결과에는 PR 번호·URL, squash merge commit, 이해도 게이트 통과 방식과 결과, 원격 브랜치 삭제 여부, 로컬 브랜치 삭제 여부를 포함한다.
