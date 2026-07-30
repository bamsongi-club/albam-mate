# T-ID verifier 기계 출력 계약

이 파일은 T-ID verifier 기계 출력의 유일한 정본이다. 오케스트레이터는 agent를 시작하기 전에 파일 전체를 프롬프트에 그대로 전달하고 반환값을 직접 검증한다.

- verifier는 한 줄에 JSON 객체 하나인 JSONL만 반환하고 Markdown, 코드 펜스, JSONL 밖 설명을 반환하지 않는다.
- 입력 순서대로 T-ID마다 `type`·`testId`·`verdict`·`evidence` 네 키만 이 순서로 담은 `test-verdict` 한 줄을 반환한다. `status`·`finding`을 반환하지 않는다.

~~~json
{"type":"test-verdict","testId":"T1","verdict":"pass","evidence":"고정 diff가 계약 동작을 직접 보여준다."}
~~~

- `type`은 `test-verdict`, `testId`는 입력 ID와 같아야 한다.
- 고정 diff가 계약을 직접 뒷받침하면 `pass`, 직접 위반하면 `fail`, 증거가 부족하면 `unverified`다.
- 오케스트레이터는 각 줄을 JSON으로 파싱하고 레코드 개수, 키 네 개, 키 순서, `type`, 입력 T-ID와 같은 순서의 `testId`, 허용 verdict와 비어 있지 않은 evidence를 검증한다.
- 하나라도 어긋나면 결과를 보정하거나 누락 판정을 추론하지 않는다. 종합 판정을 `Incomplete`로 고정하고 fresh verifier 재검증을 요구한다.
- 하나라도 `fail`이면 `Changes Requested`, `fail` 없이 `unverified`가 있으면 `Incomplete`, 모두 `pass`이면 `Approve`로 집계한다.
- 사용자 보고에서는 `unverified`를 `미검증`으로 표시한다.
- T-ID 종합 판정·레코드와 별도 일반 위험 리뷰의 판정·Finding은 서로 덮어쓰지 않고 각각 보존한다.
