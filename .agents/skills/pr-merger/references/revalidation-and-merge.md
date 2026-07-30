# 재검증과 머지

이해도 게이트 통과 직후 [준비 게이트](preparation-gates.md) 전체를 다시 실행한다. 스냅샷이나 diff·리뷰·thread·CI·mergeability·로컬 안전 상태가 달라지면 이해도 결과를 무효화하고 중단한다.

모두 유지될 때만 스냅샷 HEAD와 `merge_subject`를 고정해 인용된 단일 인자로 다음 명령만 실행한다.

~~~shell
gh pr merge "$pr" --squash --match-head-commit "$head_oid" --subject "$merge_subject"
~~~

명령이 실패하면 다른 전략이나 우회 옵션으로 재시도하지 않는다. 현재 GitHub 상태와 오류를 보고한다.
