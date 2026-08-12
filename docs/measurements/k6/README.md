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

- Markdown 결과 문서는 `<test-content>-<YYYY-MM-DD>.md` 형식을 사용한다. `<test-content>`는 영문 소문자 kebab-case로 쓰며, 도메인명이 아니라 실제로 측정하거나 판단한 업무 흐름을 표현한다. 예: `auth-notification-delivery-2026-08-11.md`
- 문서에는 시나리오, 실행 환경·명령, fixture 전제, 주요 지표, 결과 해석과 한계를 적는다. 여러 도메인이 섞인 경우에는 문서 본문에 관련 도메인을 적고, 파일이나 폴더를 도메인별로 나누지 않는다.
- `evidence/`에는 Markdown 문서의 수치를 뒷받침해야 할 때만 같은 `<test-content>-<YYYY-MM-DD>.json` 이름으로 검증·비식별화한 JSON 증거를 둔다. 여러 Run을 묶은 campaign manifest도 이 경계를 따른다.

## 캠페인 인덱스

| Campaign ID | 측정 구간 | 상태 | 보고서 | 판단서 | 근거 manifest | 대체 관계 |
| --- | --- | --- | --- | --- | --- | --- |
| `auth-notification-20260811T021040KST` | 2026-08-11 02:10:40~10:36:50 KST | `completed-with-limitations` | [인증·알림 AWS 용량 측정](auth-notification-capacity-2026-08-11.md) | [알림 broker 판단](notification-broker-decision-2026-08-11.md) | [campaign manifest](evidence/auth-notification-capacity-2026-08-11.json) | 최초 캠페인, 후속 없음 |
| `chat-delivery-20260811T172123KST` | 2026-08-11 17:18:50~18:36:49 KST | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정](chat-delivery-capacity-2026-08-11.md) | 없음 | [campaign manifest](evidence/chat-delivery-capacity-2026-08-11.json) | superseded by `chat-delivery-20260812T042245KST` |
| `chat-delivery-20260812T042245KST` | 2026-08-12 04:22:45~05:37:34 KST | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 재측정](chat-delivery-capacity-2026-08-12.md) | 없음 | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12.json) | superseded by `chat-delivery-20260812T090111KST` |
| `chat-delivery-20260812T090111KST` | 2026-08-12 09:01:11~10:35:38 KST | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 반복 측정](chat-delivery-capacity-2026-08-12-repeat.md) | 없음 | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-repeat.json) | `chat-delivery-20260812T042245KST`를 대체, 08-11 1차는 단계 태그 편향으로 비교 제외 |
| `redis-session-diagnostic-20260812` | 2026-08-12 11:42:54~12:04:36 KST | `completed-with-limitations` | [Redis 세션 연결 진단](redis-session-connection-diagnostic-2026-08-12.md) | 해당 없음 | [진단 evidence](evidence/redis-session-connection-diagnostic-2026-08-12.json) | 현행 기준 원인 진단, 후보 A/B 미실행 |

`completed-with-limitations`는 실행과 보고가 끝났지만 유효한 정상·실패 경계를 모두 확정하지 못했거나 원자료 접근 범위가 제한된 상태다. `completed`는 계획한 Run이 모두 유효하게 끝나 축별 경계를 확정한 상태다. `current` 판단서는 후속 문서가 `supersedes`로 대체하기 전까지 현재 판단으로 읽는다.

판단서는 측정 결과가 아키텍처 선택을 바꿀 때만 만든다. 원인이 설정·구현 결함이어서 수정 대상이 분명하면 판단서 대신 이슈로 넘긴다.

## 읽는 순서

1. 보고서의 `결론`과 `측정 조건`에서 무엇을 측정했고 무엇을 확정하지 못했는지 확인한다.
2. 세부 표에서는 각 Run의 `판정`과 `reportDisposition`을 함께 본다.
3. 판단서에서 측정 결과가 현재 아키텍처 선택에 미치는 범위와 재검토 조건을 확인한다.
4. 수치를 재검증할 때는 campaign manifest의 source revision과 Run bundle fingerprint를 사용한다.

## 근거와 정본 경계

- 보고서와 판단서는 요약·해석 정본이며 원자료 자체가 아니다.
- campaign manifest는 실행 소스와 원자료 bundle의 식별 정본이다.
- `reportDisposition=included`인 Run만 보고서 결론 계산에 사용한다.
- `reportDisposition=excluded`인 Run은 실패한 준비·계측 과정의 이력이며 정상·실패 경계 계산에 사용하지 않는다.
- 원자료는 현재 `local-only`다. manifest의 SHA-256은 로컬 bundle이 바뀌었는지 확인하지만, 이 저장소만으로 원자료 내용을 독립 재검증할 수 있다는 뜻은 아니다.
- 후속 측정은 새 Campaign ID와 manifest를 만들고, 기존 행의 `후속 없음`을 `superseded by <Campaign ID>`로 바꾼다. 기존 보고서와 manifest는 덮어쓰지 않는다.
- 성공률·지연 같은 수치는 실행 간 편차가 크다. **두 캠페인을 비교해 개선·악화를 판정하지 않는다.** 판정에는 같은 조건 반복 측정으로 얻은 범위끼리 비교한다. 재현되는 것은 경계 위치와 축 사이의 상대 관계다.

### 지표 수준 limitation

캠페인이 `completed-with-limitations`이거나 Run이 `PASS`여도 특정 지표만 해석 불가할 수 있다. 이때 campaign 상태, Run의 원시 수치, `reportDisposition=included`와 `PASS`는 보존하고 해당 지표의 limitation·영향 범위·재측정 조건을 보고서와 manifest에 함께 적는다. 제한된 지표는 정상·실패 경계나 단계 간 비교에 쓰지 않으며, 다른 독립 지표의 유효성까지 소급해 바꾸지 않는다.

## 상태 어휘

| 필드 | 값 | 의미 |
| --- | --- | --- |
| 캠페인 상태 | `completed-with-limitations` | 캠페인은 끝났지만 일부 경계나 근거 접근성이 제한됨 |
| 문서 상태 | `current` | 아직 후속 문서로 대체되지 않은 현재 판단 |
| 문서 상태 | `superseded` | 후속 캠페인이 대체했으며 before 근거로만 읽는다 |
| Run 판정 | `PASS` | 계약 또는 성능 임계를 유효하게 통과 |
| Run 판정 | `FAIL` | Run은 유효하지만 성능 임계를 통과하지 못함 |
| Run 판정 | `INVALID` | 준비·계측·필수 근거 조건을 만족하지 못해 경계 계산에서 제외 |
| 캠페인 판정 | `INCONSISTENT` | 탐색과 지속 Run이 재현되지 않아 정상·실패 경계를 확정할 수 없음 |

## ROOM 기준선과의 분리

`docs/measurements/results/`는 [ROOM-09 현행 일괄 처리 기준선](../room-09-bounded-processing-baseline.md)과 [ROOM-10 동시성 기준선](../room-10-measurement-contract.md)처럼 로컬 PostgreSQL 기준선의 원자료를 보존한다. HTTP k6 결과는 `docs/measurements/results/`에 추가하지 않고 `docs/measurements/k6/`에만 보존한다.
