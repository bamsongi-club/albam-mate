# 출력 계약

이 파일은 reviewer·judge 기계 출력과 오케스트레이터의 사용자 출력 형식에 대한 유일한 정본이다. 오케스트레이터는 agent를 시작하기 전에 해당 모드의 기계 출력 계약을 프롬프트에 그대로 전달한다. agent가 이 파일을 직접 읽도록 요구하지 않는다.

## 기계 출력 공통 규칙

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
- 3분 반환 시점에 reviewer는 현재 `checkedRiskIds`와 `uncoveredRiskIds`를 즉시 반환한다.
- 일반 리뷰의 `line`은 대상 파일의 단일 앵커다. PR 모드에서는 diff 앵커만 inline으로 보내고 삭제 hunk는 `side=LEFT`를 쓴다. 파일 리뷰에서는 `side`를 생략할 수 있다.
- 일반 리뷰는 대형 diff가 아닐 때만 minor·nit을 보고한다.
- reviewer는 후보 수나 긍정 결과를 채우기 위해 Finding을 만들지 않는다. 확인된 Finding이 없으면 `status`만 반환하며 Good, 장문 설명, 전체 코드, PR 요약을 반환하지 않는다.

## T-ID 계약 verifier 출력

입력 순서대로 T-ID마다 `type`·`testId`·`verdict`·`evidence` 네 키만 이 순서로 담은 `test-verdict` 한 줄을 반환한다. `status`·`finding`을 반환하지 않는다.

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

## 심각도

| | 레벨 | 의미 |
| --- | --- | --- |
| 🔴 | `critical` | 즉시 악용 가능한 취약점, 데이터 손상, 크래시, 핵심 요구사항 차단 |
| 🟠 | `major` | 머지 전 수정이 필요한 실질 버그 또는 설계 결함 |
| 🟡 | `minor` | 개선 권장이나 차단하지 않는 문제 |
| ⚪ | `nit` | 취향 또는 사소한 제안 |

## Finding 표시 형식

라인별 코멘트와 일반 모드의 Finding은 문제마다 아래 형식을 그대로 쓴다. 제목 구분자, 섹션 제목, 이모지를 임의로 바꾸거나 생략하지 않고 위치·문제점·수정 방향을 한 문장으로 압축하지 않는다.

~~~text
<이모지> <레벨> | <제목>
위치: <file> (line <line>)

**🔍 문제점**
<실패 조건, 영향, 계약 근거>

**🔧 수정 방향**
<무엇을 어떻게 바꿀지 또는 짧은 예시>
~~~

- 게시 모드의 inline comment에서는 GitHub이 앵커를 이미 표시하므로 `위치` 줄을 생략한다. 일반 모드와 요약으로 옮긴 Finding에는 남긴다.
- 확인한 점은 특별히 언급할 가치가 있을 때만 `**✅ 확인한 점**` 절로 덧붙인다. 섹션 제목은 이모지와 볼드를 그대로 쓰고 번호를 붙이지 않는다.
- 코드 예시는 서술보다 더 명확하고 짧을 때만 수정 방향 절 아래 코드 블록으로 쓴다.
- 앵커 라인을 그대로 대체하는 짧은 수정이면 게시 모드에서 GitHub의 `suggestion` 코드 블록으로 쓴다.

## PR 전체 요약 형식

PR 전체 요약은 한 번만 쓰고 아래 형식을 그대로 쓴다.

~~~text
## 판정: Approve | Changes Requested | Blocked | Incomplete

심각도: 🔴 <n>  🟠 <n>  🟡 <n>  ⚪ <n>

변경 요약: 이번 변경의 동작을 2~3줄.

### 주요 지적 (critical/major만)

- 🔴 file:line — 제목
- 🟠 file:line — 제목

### 다음 액션

- ...
~~~

- 절 제목은 `###` 헤딩으로 쓰고 헤딩·목록 앞뒤를 빈 줄로 분리한다. GitHub에서 다음 절이 앞 목록에 붙는 것을 막기 위함이다.
- `### 잘된 점`은 확인된 사실이 있을 때만 변경 요약 다음에 선택적으로 출력한다. 채우기 위한 일반적인 칭찬은 만들지 않는다.
- 미검토 범위가 있으면 `Incomplete`, critical이 하나라도 있으면 `Blocked`, major가 있으면 `Changes Requested`, 그 외 완료된 리뷰면 `Approve`로 판정한다.
- minor·nit은 라인 코멘트에만 남긴다.
- 직설적으로 쓰되 비난하지 않는다.
