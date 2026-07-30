# 재검증과 머지

이해도 게이트 통과 직후 준비 게이트의 원격 PR 스냅샷·준비 조건과 로컬 브랜치 사전 점검을 모두 다시 실행한다.

- PR 번호, 제목, `baseRefOid`, `headRefOid`가 기존 스냅샷과 같고 모든 게이트가 유지될 때만 스냅샷 HEAD와 `merge_subject`를 고정한다.
- 달라진 항목이 있으면 머지하지 말고 새 상태를 보고한다.
- 고정한 값을 인용된 단일 인자로 전달해 다음 명령만 실행한다.

~~~shell
gh pr merge "$pr" --squash --match-head-commit "$head_oid" --subject "$merge_subject"
~~~

명령이 실패하면 다른 전략이나 우회 옵션으로 재시도하지 않는다. 현재 GitHub 상태와 오류를 보고한다.
