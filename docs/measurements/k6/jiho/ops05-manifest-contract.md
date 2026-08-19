# OPS-05 통합 실행 Manifest 계약

## 목적

이 문서는 `OPS-05` 배포 검증에서 알림·채팅·참가 대기열의 통제 시나리오 결과를 하나의 비식별 manifest로 판정하는 준비 계약이다. 현재는 검증기와 H2 계약 테스트만 구현됐으며 AWS 배포·dashboard·실측 결과는 없다.

## 입력과 판정

실행자는 원시 bundle과 별도로 `build/k6/ops05/<run-id>/manifest.json`을 만들고 다음을 넣는다.

- 40자리 고정 `releaseSha`, UTC 시작·종료 시각, 실제 사용자·운영 ROOM이 아니라는 `isolated` fixture 선언과 fixture SHA-256. 실행자는 독립적으로 받은 같은 release SHA와 해당 checkout root를 함께 넘기며, validator는 Git HEAD와 세 required scenario source의 staged·unstaged 변경 없음까지 확인한다.
- 변경하지 않는 알림·채팅·대기열 시나리오 3개의 상대 경로와 현재 source SHA-256
- HTTP·DB·metric·허용된 정제 log·dashboard query artifact의 bundle-root 상대 경로와 SHA-256. 검증기는 bundle 안의 실제 파일을 다시 해시하고, 정규화한 결과만 읽는다.
- 기능별 기술 수락·업무 결과·사용자 가시 결과와 `attempt`, `businessSuccess`, `businessRejection`, `technicalFailure`

일반 Run은 거절·기술 실패가 0이고 모든 최종 결과가 참일 때만 `PASS`다. 통제 실패·복구 Run에서 알림은 `notification.relay.events`의 `retry_scheduled`·`failed`·후속 `processed`를 기록하며, `attempt = processed + retryScheduled + failed`, `businessSuccess = processed`, `technicalFailure = retryScheduled + failed`, `businessRejection = 0`을 만족해야 한다. 채팅·대기열은 `businessRejection`과 `technicalFailure`를 각각 기록하고 후속 성공과 대기열 불변식 위반 0건이 있어야 `PASS`다. release·fixture·source·필수 artifact가 누락되거나 맞지 않으면 `INVALID`, 유효한 근거에서 최종 결과·복구·불변식이 실패하면 `FAIL`이다.

```powershell
node scripts/measurements/ops05-manifest-validator.mjs `
  --manifest build/k6/ops05/<run-id>/manifest.json `
  --bundle-root build/k6/ops05/<run-id>/artifacts `
  --release-root <fixed-release-checkout> `
  --expected-release-sha <deployed-release-sha>
```

검증기 출력은 판정과 민감하지 않은 reason code만 남긴다. artifact는 세 업무 흐름의 boolean 결과와 정수 outcome만 허용하며, 예상 밖 field, bundle 밖 경로와 symlink는 `INVALID`다. fixture 값, URL, 인증정보, resource ID, 원시 로그나 그 내용을 유추할 수 있는 값은 manifest·문서·Git에 기록하지 않는다.

## 실측 전 점검

- 배포 release와 세 read-only source hash를 실행 시작 뒤 변경하지 않는다.
- 격리 fixture와 cleanup이 실제 사용자 데이터·운영 ROOM에 닿지 않는지 실행기에서 확인한다.
- HTTP·DB·metric·정제 log·dashboard query artifact가 모두 생성된 뒤에만 manifest 판정을 실행한다.
- 이 검증은 배포 검증 단계에서 한 번만 수행하며 상시 write synthetic으로 등록하지 않는다.
