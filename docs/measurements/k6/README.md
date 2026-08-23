# k6 부하테스트 결과 문서

## 목적과 경계

이 디렉터리는 k6 부하테스트에서 재현 또는 의사결정 근거로 보존하기로 승인한 결과 문서와 선택적 증거 파일을 관리한다. k6 시나리오·fixture 생성기와 매 실행 원시 산출물의 관리 규칙은 [Load Tests](../../../load-tests/README.md)를 따른다.

상위 `README.md`는 공통 배치와 보존 규칙만 설명한다. 소유자별 테스트·캠페인 목록은 각 소유자 폴더의 `README.md`에서 관리하며 이 문서에 다시 모으지 않는다.

`build/k6/<test-content>/`에는 매 실행의 fixture, 실행 bundle, 원시 로그와 원시 k6 결과를 두며 Git에 커밋하지 않는다. 원시 산출물에는 비밀값과 실제 환경의 리소스 식별자가 포함될 수 있다. 이 중 검증·비식별화 절차를 마친 결과만 이 문서 아래에 별도로 보존하며, 탐색·반복 실행 결과를 모두 문서로 옮기지 않는다.

## 디렉터리 배치

```text
docs/
  measurements/
    k6/
      README.md
      <owner>/
        README.md
        <test-content>-<YYYY-MM-DD>.md
        evidence/
          <test-content>-<YYYY-MM-DD>.json
```

`<owner>`에는 팀에서 합의한 영문 소문자 식별자를 사용한다. 파일의 최초 추가자를 기준으로 소유자를 정하고, 이후 공동 수정이 있더라도 별도 합의 없이 소유자 폴더를 바꾸지 않는다.

## 소유자 README

- 현재 소유자별 campaign 인덱스: [jiwon ROOM k6 측정 문서](jiwon/README.md)

각 `docs/measurements/k6/<owner>/README.md`에는 해당 소유자가 보존한 테스트만 정리한다. 최소한 다음 내용을 포함한다.

- 결과 문서와 선택적 판단서
- 대응하는 `evidence/` 파일
- Campaign ID와 상태가 있는 경우 그 값
- 후속 측정이 기존 결과를 대체하는 관계

상위 README나 다른 소유자의 README에는 이 목록을 중복하지 않는다.

## 파일명과 내용

- Markdown 결과 문서는 `<test-content>-<YYYY-MM-DD>.md` 형식을 사용한다. `<test-content>`는 영문 소문자 kebab-case로 쓰며, 도메인명이 아니라 실제로 측정하거나 판단한 업무 흐름을 표현한다.
- 문서에는 시나리오, 실행 환경·명령, fixture 전제, 주요 지표, 결과 해석과 한계를 적는다. 소유자 폴더만 1차 분류로 사용하며 도메인 하위 폴더를 추가하지 않는다.
- 각 소유자의 `evidence/`에는 Markdown 문서의 수치를 뒷받침해야 할 때만 검증·비식별화한 JSON 증거를 둔다. 여러 Run을 묶은 campaign manifest도 이 경계를 따른다.
- campaign manifest는 원자료를 식별하는 데 필요한 비밀이 아닌 source·artifact 무결성 식별값은 보존할 수 있다. 비밀번호·credential-derived hash·토큰·세션·CSRF·URL·실제 fixture/resource ID·원시 SQL·로그는 보존하지 않는다.
- 후속 측정은 새 Campaign ID와 manifest를 만들고 기존 보고서와 manifest를 덮어쓰지 않는다.

## 근거와 정본 경계

- 보고서와 판단서는 요약·해석 정본이며 원자료 자체가 아니다.
- campaign manifest는 실행 소스와 원자료 bundle의 식별 정본이다.
- `reportDisposition=included`인 Run만 보고서 결론 계산에 사용한다.
- `reportDisposition=excluded`인 Run은 실패한 준비·계측 과정의 이력이며 정상·실패 경계 계산에 사용하지 않는다.
- 원자료가 `local-only`라면 manifest의 SHA-256은 로컬 bundle의 변경 여부만 증명한다. 이 저장소만으로 원자료 내용을 독립 재검증할 수 있다는 뜻은 아니다.
- 성공률·지연처럼 실행 간 편차가 큰 값은 같은 조건의 반복 측정 범위로 비교한다.
- 후속 측정은 같은 release를 다시 잰 경우에만 기존 캠페인을 대체한다. release가 바뀌면 이전 캠페인을 before 기준선으로 남긴다.

## 상태 어휘

| 필드 | 값 | 의미 |
| --- | --- | --- |
| 캠페인 상태 | `completed` | 계획한 유효 Run과 보고가 완료됨 |
| 캠페인 상태 | `completed-with-limitations` | 캠페인은 끝났지만 일부 경계나 근거 접근성이 제한됨 |
| 문서 상태 | `current` | 아직 후속 문서로 대체되지 않은 현재 판단 |
| 문서 상태 | `superseded` | 후속 캠페인이 대체했으며 before 근거로만 읽음 |
| Run 판정 | `PASS` | 계약 또는 성능 임계를 유효하게 통과 |
| Run 판정 | `FAIL` | Run은 유효하지만 성능 임계를 통과하지 못함 |
| Run 판정 | `INVALID` | 준비·계측·필수 근거 조건을 만족하지 못해 경계 계산에서 제외 |
| Manifest v3 실행 상태 | `COMPLETED` | 시나리오가 완주했고 threshold 위반이 없다. 보고서 Run 판정은 `PASS` |
| Manifest v3 실행 상태 | `COMPLETED_WITH_THRESHOLD_VIOLATIONS` | 시나리오는 완주했지만 하나 이상의 threshold를 넘었다. 보고서 Run 판정은 `FAIL` |
| 캠페인 판정 | `INCONSISTENT` | 탐색과 지속 Run이 재현되지 않아 정상·실패 경계를 확정할 수 없음 |
| 캠페인 판정 | `PASS_AT_MAX` | 계획한 최대 단계까지 유효하게 통과했지만 그보다 높은 부하의 용량은 증명하지 않음 |

`reportDisposition=included`은 원자료를 보고서 계산에 쓸 수 있다는 뜻일 뿐 Run 판정을 바꾸지 않는다. 따라서 유효한 `FAIL` Run도 `included`일 수 있다.

## ROOM 기준선과의 분리

`docs/measurements/results/`는 [ROOM-09 현행 일괄 처리 기준선](../room-09-bounded-processing-baseline.md)과 [ROOM-10 동시성 기준선](../room-10-measurement-contract.md)처럼 로컬 PostgreSQL 기준선의 원자료를 보존한다. HTTP k6 결과는 `docs/measurements/results/`에 추가하지 않고 `docs/measurements/k6/<owner>/`에 보존한다.
