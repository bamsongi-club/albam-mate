# GitHub PR 게시 계약

게시 모드에서만 중복 제거·재판정이 끝난 Finding을 한 번의 `COMMENT` review로 보낸다. PR 전체 요약을 review body로, 각 Finding을 해당 diff 라인의 inline comment로 보낸다.

- 최신 `headRefOid`를 `commit_id`로 쓴다.
- 추가·수정·문맥 라인은 `line`과 `side=RIGHT`, 삭제 라인은 preimage의 `line`과 `side=LEFT`를 쓴다.
- diff에 안전하게 앵커할 수 없는 Finding은 inline으로 억지로 보내지 않고 PR 전체 요약에 `file:line`으로 남긴다.
- payload에는 `commit_id`, `body`, `event=COMMENT`, comments의 `path`, `line`, `side`, `body`를 넣는다.
- 현재 저장소에 고정한 `repos/$repo/pulls/$pr/reviews` endpoint에 `gh api`로 POST한다.
- `event`는 판정과 무관하게 항상 `COMMENT`다. `APPROVE`나 `REQUEST_CHANGES`로 GitHub의 승인·변경 요청 상태를 바꾸지 않는다.
- 422가 나면 최신 head와 앵커를 한 번만 재검증하고, 계속 유효하지 않은 Finding은 요약으로 옮긴다. 남은 payload는 한 번만 다시 게시하며 재게시도 실패하면 중단하고 실패 원인을 보고한다.
- 응답의 review ID, URL, 실제 inline comment 수를 확인한 뒤에만 게시 완료라고 보고한다.
