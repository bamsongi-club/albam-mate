# k6 부하테스트 결과 문서

## 목적과 경계

이 디렉터리는 k6 부하테스트에서 재현 또는 의사결정 근거로 보존하기로 승인한 결과 문서와 선택적 증거 파일을 관리한다. k6 시나리오·fixture 생성기와 매 실행 원시 산출물의 관리 규칙은 [Load Tests](../../../load-tests/README.md)를 따른다.

`build/k6/<domain>/`에는 매 실행의 fixture, 실행 bundle, 원시 로그와 원시 k6 결과를 두며 Git에 커밋하지 않는다. 이 디렉터리에는 비밀값과 실제 환경의 리소스 식별자를 제거하고 검증 절차를 마친 결과만 보존한다. 탐색·반복 실행 결과를 모두 문서로 옮기지 않는다.

## 디렉터리 배치

```text
docs/
  measurements/
    k6/
      README.md
      <test-content>-<YYYY-MM-DD>.md
      evidence/
        <test-content>-<YYYY-MM-DD>.json
```

## 파일명과 내용

- Markdown 결과 문서는 `<test-content>-<YYYY-MM-DD>.md` 형식을 사용한다. `<test-content>`는 영문 소문자 kebab-case로 쓰며, 도메인명이 아니라 실제로 측정한 업무 흐름을 표현한다. 예: `auth-notification-delivery-2026-08-11.md`
- 문서에는 시나리오, 실행 환경·명령, fixture 전제, 주요 지표, 결과 해석과 한계를 적는다. 여러 도메인이 섞인 경우에는 문서 본문에 관련 도메인을 적고, 파일이나 폴더를 도메인별로 나누지 않는다.
- `evidence/`에는 Markdown 문서의 수치를 뒷받침해야 할 때만 같은 `<test-content>-<YYYY-MM-DD>.json` 이름으로 검증·비식별화한 JSON 증거를 둔다.

## ROOM 기준선과의 분리

`docs/measurements/results/`는 [ROOM-09 현행 일괄 처리 기준선](../room-09-bounded-processing-baseline.md)과 [ROOM-10 동시성 기준선](../room-10-measurement-contract.md)처럼 로컬 PostgreSQL 기준선의 원자료를 보존한다. HTTP k6 결과는 `docs/measurements/results/`에 추가하지 않고 `docs/measurements/k6/`에만 보존한다.
