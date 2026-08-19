# OPS-05 통합 실행 Manifest 계약

## 목적

이 문서는 `OPS-05` 배포 검증에서 알림·채팅·참가 대기열의 통제 시나리오 결과를 하나의 비식별 manifest로 판정하는 계약이다. 2026-08-19에 고정 release `b23f114da67010cb955fcb12e546331990a6e2eb`로 임시 AWS 검증 배포를 한 번 수행해 정상·통제 실패/복구 Run과 dashboard를 실측했고, 원자료 기반 5개 stage 요약 artifact를 채택한 뒤 스택을 철거했다.

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

## 2026-08-19 실측 결과

동일한 고정 release에서 HTTP 기술 수락, DB 업무 결과, CloudWatch metric, 허용된 정제 log, dashboard 사용자 가시 결과의 5개 stage를 각각 요약했다. 원시 bundle과 원자료 SHA-256을 고정한 채택 manifest는 Git에 추적하지 않는 `build/k6/ops05/`와 인프라 `.run/`에 보존하며, 문서에는 비식별 집계만 남긴다.

| Run | 흐름 | attempt | 업무 성공 | 업무 거절 | 기술 실패 | 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 정상 | 알림 | 20 | 20 | 0 | 0 | `PASS` |
| 정상 | 채팅 | 3 | 3 | 0 | 0 | `PASS` |
| 정상 | 참가 대기열 | 8 | 8 | 0 | 0 | `PASS` |
| 통제 실패·복구 | 알림 | 65 | 63 | 0 | 2 | `PASS` (`retry_scheduled` 1, `failed` 1, 후속 성공) |
| 통제 실패·복구 | 채팅 | 92 | 53 | 1 | 38 | `PASS` (거절·기술 실패 분리, 후속 성공) |
| 통제 실패·복구 | 참가 대기열 | 36 | 27 | 1 | 8 | `PASS` (거절·기술 실패 분리, 불변식 위반 0, 후속 성공) |

CloudWatch metric data는 두 판정 구간 모두 `Complete`였고, 배포 중 dashboard의 알림·채팅·대기열·relay 지연·relay age 식 5개와 고정 release 조건 5개를 직접 조회했다. 예약어 `release`는 모든 식에서 인용된 상태로 확인했다. 예행 rate Run은 채택하지 않았으며, 기존 범용 collector의 `INVALID`는 이번 흐름 밖의 auth·PostgreSQL resource metric이 없어서 발생했으므로 OPS-05 판정에 사용하지 않았다.

실측 뒤 Terraform 124개 resource를 철거했다. 후속 확인에서 Terraform state resource, EC2 instance, EBS volume, Elastic IP, 대상 VPC, dashboard가 모두 0개였으며, 이는 임시 검증 배포 완료이지 상시 운영 배포를 뜻하지 않는다.

## 재실측 전 점검

- 배포 release와 세 read-only source hash를 실행 시작 뒤 변경하지 않는다.
- 격리 fixture와 cleanup이 실제 사용자 데이터·운영 ROOM에 닿지 않는지 실행기에서 확인한다.
- HTTP·DB·metric·정제 log·dashboard query artifact가 모두 생성된 뒤에만 manifest 판정을 실행한다.
- 이 검증은 배포 검증 단계에서 한 번만 수행하며 상시 write synthetic으로 등록하지 않는다.
