# k6 측정 문서

이 디렉터리는 k6 캠페인의 사람이 읽는 보고서·측정 기반 판단서와 AI가 근거를
추적하는 campaign manifest를 보존한다. 실행 스크립트와 fixture의 현재 정본은
[`load-tests/k6`](../../../load-tests/k6/)에 있고, 대용량 원자료 bundle은
`albam-mate-infra/.run/results/<Run-ID>/`에 별도로 보존한다.

## 캠페인 인덱스

| Campaign ID | 측정 구간 | 상태 | 보고서 | 판단서 | 근거 manifest | 대체 관계 |
| --- | --- | --- | --- | --- | --- | --- |
| `auth-notification-20260811T021040KST` | 2026-08-11 02:10:40~10:36:50 KST | `completed-with-limitations` | [인증·알림 AWS 용량 측정](auth-notification-capacity-2026-08-11T10-36-50-KST.md) | [알림 broker 판단](notification-broker-decision-2026-08-11T10-47-24-KST.md) | [campaign manifest](manifests/auth-notification-20260811T021040KST.json) | 최초 캠페인, 후속 없음 |

`completed-with-limitations`는 실행과 보고가 끝났지만 유효한 정상·실패 경계를
모두 확정하지 못했거나 원자료 접근 범위가 제한된 상태다. `current` 판단서는
후속 문서가 `supersedes`로 대체하기 전까지 현재 판단으로 읽는다.

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

## 상태 어휘

| 필드 | 값 | 의미 |
| --- | --- | --- |
| 캠페인 상태 | `completed-with-limitations` | 캠페인은 끝났지만 일부 경계나 근거 접근성이 제한됨 |
| 문서 상태 | `current` | 아직 후속 판단서로 대체되지 않은 현재 판단 |
| Run 판정 | `PASS` | 계약 또는 성능 임계를 유효하게 통과 |
| Run 판정 | `FAIL` | Run은 유효하지만 성능 임계를 통과하지 못함 |
| Run 판정 | `INVALID` | 준비·계측·필수 근거 조건을 만족하지 못해 경계 계산에서 제외 |
| 캠페인 판정 | `INCONSISTENT` | 탐색과 지속 Run이 재현되지 않아 정상·실패 경계를 확정할 수 없음 |
