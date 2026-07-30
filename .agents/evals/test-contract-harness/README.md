# 테스트 계약 eval 재현 경계

이 디렉터리는 테스트 계약 절차의 고정 입력과 공개 가능한 핵심 결과만 보관한다. 모델의 개별 응답 원문과 상세 실행 기록은 저장소에 커밋하지 않고 팀 Private Brain에만 보관한다.

## 공개 저장소에 남기는 항목

| 경로 | 역할 |
| --- | --- |
| `cases.json` | fresh agent 및 기계 검사 케이스의 고정 정의 |
| `fixtures/**` | 기계 검사에 필요한 비민감 입력 |
| `results-summary.json` | 입력·결과 해시, 집계 결과와 한계만 담은 핵심 요약 |
| `scripts/run-test-contract-harness.mjs` | JSON 무결성, 입력 해시, 기계 검사와 공개 경계를 반복 검증하는 runner |

`runs/`와 `private/`는 `.gitignore` 대상이다. 다음 정보는 이 저장소의 파일, Issue, PR 본문에 기록하지 않는다.

- 모델의 개별 응답 원문과 상세 run JSON
- raw prompt, 로컬 절대 경로와 세션 메타데이터
- 개인정보, 비밀값과 팀 Private Brain 내부 경로

## 실행

공개 산출물만으로 무결성과 현재 요약을 확인한다.

~~~powershell
node scripts/run-test-contract-harness.mjs --check
~~~

Private Brain에 보관할 receipt의 연결 필드를 먼저 계산할 때는 상세 run 파일을 명시한다. 출력에는 전달한 로컬 경로가 포함되지 않는다.

~~~powershell
node scripts/run-test-contract-harness.mjs --receipt-seed --member <member> --target-commit <40자리 SHA> --private-run <run-json> --private-run <run-json>
~~~

원본 run과 receipt를 Private Brain에 보관한 다음 같은 입력으로 공개 요약을 생성한다.

~~~powershell
node scripts/run-test-contract-harness.mjs --write --member <member> --target-commit <40자리 SHA> --generated-on <YYYY-MM-DD> --archive-receipt <receipt-json> --private-run <run-json> --private-run <run-json>
~~~

runner는 모델을 호출하지 않는다. 보관된 실행의 candidate 입력이 대상 커밋과 일치하고 현재 case의 모든 check를 덮을 때만 `pass`·`fail`로 집계하며, 모델 호출이나 입력 일치를 증명하지 못하면 `not-run`으로 기록한다.

RV-01을 포함한 fresh-agent run은 다음을 지킨다.

- candidate arm 입력에 `review-code/SKILL.md`, `review-code-reviewer.toml`, `scope-and-routing.md`, `output-contract.md` 네 파일의 내용을 모두 포함한다.
- `instructionArms.candidate`에 대응하는 `reviewCodeBlob`, `reviewCodeReviewerBlob`, `reviewCodeScopeAndRoutingBlob`, `reviewCodeOutputContractBlob` OID를 기록한다.
- 입력이나 blob OID가 하나라도 없거나 대상 commit과 다르면 `candidateInputMatch=false`다.

## Private Brain receipt

receipt에는 실행일, 대상 커밋, 공개 입력 해시, 상세 결과 묶음 해시와 `coreLinkHash`를 기록한다. 공개 요약에는 receipt의 내용 해시와 같은 `coreLinkHash`만 남기며 Private Brain의 내부 경로는 남기지 않는다.

## 팀원별 Private Brain 보관 규칙

동시에 실행한 결과가 날짜별 순번으로 충돌하지 않도록 프롬프트 기록과 같은
`BAMSONGI_MEMBER` 값을 receipt의 `member` 네임스페이스로 사용한다. Private Brain의
실제 내부 경로와 팀원 목록은 공개 저장소에 기록하지 않는다.

실행별 `archiveId`는 `YYYYMMDDTHHMMSSZ-<short-sha>-<UUID>` 형식으로 UTC 시각, 대상 commit 짧은 SHA와 UUID 계열 suffix를 함께 사용해 같은 팀원의 동시 실행에서도 파일명이 겹치지 않게 한다. 실행할 때마다 여러 팀원이
함께 수정하는 공용 manifest는 두지 않으며, 필요한 목록은 개별 receipt를 읽어 생성한다.
서로 다른 파일을 쓰더라도 Private Brain의 보관 대상 브랜치가 먼저 갱신되면 push가 거절될 수 있으므로 해당 대상 브랜치의 원격 변경을 다시 반영한 뒤 재시도한다.
