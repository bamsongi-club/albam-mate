# 일반 리뷰 기계 출력 계약

이 파일은 일반 reviewer·judge 기계 출력의 유일한 정본이다. 오케스트레이터는 agent를 시작하기 전에 파일 전체를 프롬프트에 그대로 전달하고 반환값을 직접 검증한다.

- reviewer와 judge는 한 줄에 JSON 객체 하나인 JSONL만 반환하고 Markdown, 코드 펜스, JSONL 밖 설명을 반환하지 않는다.
- 오케스트레이터는 기계 출력을 원문 그대로 사용자에게 노출하지 않고 검증된 레코드만 사람이 읽는 형식으로 확장한다.
- 일반 reviewer는 첫 줄에 `status`, 이후 채택 후보마다 `finding`을 반환한다. 발견이 없어도 `status`를 생략하지 않는다.
- 일반 reviewer의 `status` 필수 키는 `type`, `shard`, `complete`, `checkedRiskIds`, `uncoveredRiskIds`다.
- 일반 reviewer의 `finding` 필수 키는 `type`, `candidateId`, `dimension`, `severity`, `file`, `line`, `title`, `evidence`, `fix`, `confidence`다. PR diff 후보에는 `side`를 `RIGHT` 또는 `LEFT`로 추가한다.
- judge의 `judgment` 필수 키는 `type`, `candidateId`, `accepted`, `finalSeverity`, `rationale`, `confidence`다.

~~~json
{"type":"status","shard":"security-1","complete":true,"checkedRiskIds":["R1","R2"],"uncoveredRiskIds":[]}
{"type":"finding","candidateId":"security-1-F1","dimension":"security","severity":"major","file":"path/to/file","line":80,"side":"RIGHT","title":"짧은 제목","evidence":"실패 조건과 근거","fix":"짧은 수정 방향","confidence":"high"}
{"type":"judgment","candidateId":"security-1-F1","accepted":true,"finalSeverity":"major","rationale":"실패 경로가 고정 hunk에서 재현된다.","confidence":"high"}
~~~

- 일반 reviewer의 `complete`는 배정된 위험 ID와 대상 범위를 모두 확인했을 때만 true다.
- 오케스트레이터가 상태 반환을 요청하면 reviewer는 현재 `checkedRiskIds`와 `uncoveredRiskIds`를 즉시 반환한다.
- 일반 리뷰의 `line`은 대상 파일의 단일 앵커다. PR 모드에서는 diff 앵커만 inline으로 보내고 삭제 hunk는 `side=LEFT`를 쓴다. 파일 리뷰에서는 `side`를 생략할 수 있다.
- 일반 리뷰는 대형 diff가 아닐 때만 minor·nit을 보고한다.
- reviewer는 후보 수나 긍정 결과를 채우기 위해 Finding을 만들지 않는다. 확인된 Finding이 없으면 `status`만 반환하며 Good, 장문 설명, 전체 코드, PR 요약을 반환하지 않는다.
