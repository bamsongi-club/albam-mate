# GitHub PR 게시 계약

게시 모드에서만 중복 제거·재판정이 끝난 Finding을 한 번의 `COMMENT` review로 보낸다. PR 전체 요약을 review body로, 각 Finding을 해당 diff 라인의 inline comment로 보낸다.

- 최신 `headRefOid`를 `commit_id`로 쓴다.
- 추가·수정·문맥 라인은 `line`과 `side=RIGHT`, 삭제 라인은 preimage의 `line`과 `side=LEFT`를 쓴다.
- diff에 있지만 안전하게 앵커할 수 없는 Finding은 사용자 출력 표시 계약의 허용 절로 옮긴다. diff 밖의 파일·라인은 게시하지 않는다.
- payload에는 `commit_id`, `body`, `event=COMMENT`, comments의 `path`, `line`, `side`, `body`만 넣는다.
- 현재 저장소에 고정한 `repos/$repo/pulls/$pr/reviews` endpoint에 `gh api`로 POST한다.
- `event`는 판정과 무관하게 항상 `COMMENT`다. `APPROVE`나 `REQUEST_CHANGES`로 GitHub의 승인·변경 요청 상태를 바꾸지 않는다.

## 게시 전 검증과 복구

1. payload JSON과 같은 시점에 조회한 `gh pr diff` 원문을 임시 파일로 만든다.
2. 게시 직전 최신 `headRefOid`를 다시 조회한다.
3. 아래 validator로 payload, 최신 head와 diff를 함께 검증한다.

~~~text
node .agents/skills/review-code/scripts/validate-review-payload.mjs --payload <payload.json> --expected-head <headRefOid> --diff <pr.diff>
~~~

- validator는 payload 필드, review body와 inline comment의 고정 형식, `COMMENT` event, 최신 head SHA, diff 앵커와 심각도 집계를 검사한다.
- 검증에 실패한 payload는 게시하거나 validator를 우회하지 않는다.
- 형식·공백·이모지·집계 오류는 검증된 Finding을 바꾸지 않고 고정 템플릿으로 payload를 한 번 다시 작성한다. 최신 head와 diff를 다시 조회해 새 payload를 재검증하고, 통과하면 사용자의 게시 요청 범위 안에서 별도 확인 없이 게시한다.
- head·diff·앵커 오류는 기존 payload를 폐기한다. 최신 PR 스냅샷을 다시 고정하고 리뷰 판정과 Finding이 여전히 유효한지 확인한 뒤 새 payload를 검증한다.
- 재검증에도 실패하거나 미검토 범위·근거 부족을 안전하게 해소할 수 없으면 게시하지 않고 실제 실패 원인을 보고한다.
- 검증 성공 뒤 payload를 수정하지 않고 `gh api ... --method POST --input <payload.json>`에 전달한다. 게시 직전 head가 검증한 SHA와 달라졌으면 게시하지 않고 최신 스냅샷부터 다시 시작한다.
- 422가 나면 최신 head와 앵커를 한 번만 재검증한다. 계속 유효하지 않은 Finding은 허용된 요약 절로 옮기고 payload 전체를 다시 검증한다. 재게시도 실패하면 중단한다.
- 응답의 review ID, URL, 실제 inline comment 수를 확인한 뒤에만 게시 완료라고 보고한다.
