# OPS-03 실패·이상 AWS 제한 실측 (2026-08-19)

## 결론

고정된 애플리케이션 release를 임시 AWS 검증 환경에 배포해 4xx 기준선과 대표 5xx·Redis 불능·scheduler 실패·복구를 통제 실행했다. Run `ops03-20260819-03`은 중앙 로그와 CloudWatch alarm 자동 판정을 통과했고, 사람이 SNS 경고·복구 이메일 수신을 확인했다. 실측 뒤 스택 전용 자원과 비밀값을 삭제했다.

이 결과는 [`OPS-03`](../p2/monitoring.md#ops-03-실패와-이상)의 `AC1`~`AC3`·`AC5`에 대한 제한 실측이다. AI·Tool 기능이 배포되지 않아 `AC4`는 적용하지 않았고, timeout 경로는 애플리케이션 자동 검증까지만 완료했다. 임시 검증 배포를 상시 운영 배포, 장기 SLO·SLA 또는 용량 기준으로 해석하지 않는다.

## 고정 소스와 실행 구간

| 구분 | 고정값 |
| --- | --- |
| 관련 이슈 | [#733](https://github.com/bamsongi-club/albam-mate/issues/733) |
| 애플리케이션 merge | [`d317b5e60956e2f9a5fdc3e996bdcae9c551f32f`](https://github.com/bamsongi-club/albam-mate/commit/d317b5e60956e2f9a5fdc3e996bdcae9c551f32f), [PR #840](https://github.com/bamsongi-club/albam-mate/pull/840) |
| 측정 인프라 merge | [`be0f987134e755eb062591882e7c20dcfc727df5`](https://github.com/bamsongi-club/albam-mate-infra/commit/be0f987134e755eb062591882e7c20dcfc727df5), 비공개 [infra PR #38](https://github.com/bamsongi-club/albam-mate-infra/pull/38) |
| 증거 수집기 merge | [`c4bbea343b4e74b77fddc565679b5ed4a1834159`](https://github.com/bamsongi-club/albam-mate-infra/commit/c4bbea343b4e74b77fddc565679b5ed4a1834159), 비공개 [infra PR #40](https://github.com/bamsongi-club/albam-mate-infra/pull/40) |
| 환경 | `ap-northeast-2` 임시 AWS 성능 검증 환경 |
| 유효 측정 구간 | `2026-08-19T06:57:29Z` ~ `2026-08-19T07:15:04Z` |
| 자동 증거 수집 완료 | `2026-08-19T07:34:27Z` |

측정 인프라 SHA는 장애 주입과 복구를 실행한 배포를, 수집기 SHA는 같은 Run의 CloudWatch 지연 평가와 UTC timestamp 정규화를 반영한 증거 판정을 가리킨다. 수집기 수정 뒤 장애를 다시 주입하지 않고 고정된 측정 구간만 재조회했다.

## 유효 Run 판정

| 단계·검증 | 관측 결과 | 판정 |
| --- | --- | --- |
| 4xx 기준선 | `http_request_failed` 장애 event 0건 | PASS |
| Redis 불능 5xx | `HTTP_SERVER_ERROR` 1건, Redis down 전이 2건 | PASS |
| Redis 복구 | recovered 전이 2건이 down 뒤 발생 | PASS |
| scheduler 실패 | 고정 실패 구간에서 relay scheduler 실패 8건 | PASS |
| 중앙 로그 경계 | sanitizer 거절 0건, 금지 field 검출 0건 | PASS |
| alarm | 스택 계약 alarm 10개 중 대표 5개가 `ALARM → OK`, 시작·최종 상태 `OK` | PASS |
| 실제 전달 | SNS 경고·복구 이메일 모두 수신 | PASS |

대표 alarm 복구는 HTTP 5xx, Redis down, scheduler failure 세 범주를 모두 포함했다. 4xx를 장애율에 합산하지 않았고 수집 공백을 값 `0`으로 합성하지 않았다. 원본 사용자 입력, 예외 message, 요청·응답 body, 인증정보, 사용자 식별자와 원시 로그는 Git에 보존하지 않았다.

## 제외한 실행

| Run | 제외 이유 | 장애·복구 상태 |
| --- | --- | --- |
| `ops03-20260819-01` | 공식 Redis·PostgreSQL image의 배포 소유 표식을 잘못 판정해 fault injection 전에 실패 안전 종료 | Redis·PostgreSQL 정상 유지 |
| `ops03-20260819-02` | synthetic 로그인 probe가 CSRF 계약을 충족하지 못해 HTTP 403을 반환 | 중단 trap이 Redis를 복구했으며 결과 판정에서 제외 |

두 실행은 탐색·계측 실패이며 정상·실패 경계의 근거로 사용하지 않았다. 소유 표식, CSRF probe, UTC timestamp, CloudWatch alarm 평가 유예와 boto3 preflight를 수정한 뒤 Run #03만 최종 보고에 포함했다.

## 철거와 보존 경계

- SNS 수신 확인 뒤 Terraform이 스택 자원 123개를 삭제했다.
- Terraform 밖에서 생성한 스택 전용 SSM 비밀값 9개도 정확한 경로로 삭제했다.
- 후속 직접 조회에서 실행 중 EC2, EBS volume, EIP, VPC, log group, alarm, dashboard, SNS topic, 전용 IAM, Route53 zone, scheduler와 SSM parameter가 0개임을 확인했다.
- 종료된 EC2 기록과 Resource Groups Tagging API의 삭제된 ID는 AWS eventual consistency 동안 잠시 보일 수 있으나 실행·과금 자원으로 판정하지 않았다.
- 공유 ECR·bootstrap·운영 영수증 저장소는 스택 전용 자원이 아니므로 삭제하지 않았다.

## 증거와 한계

비식별화한 판정 manifest는 [Run #03 manifest](results/ops-03/ops03-20260819-03.json)에 보존한다. 접근 제한된 원시 CloudWatch 응답과 로컬 teardown receipt는 저장소에 복사하지 않으며, manifest의 SHA-256은 해당 로컬 증거 파일의 변경 여부만 식별한다. 이 저장소만으로 원자료 내용을 독립 재검증할 수 있다는 뜻은 아니다.

이 실측은 한 release의 실패 신호·경고·복구 연결을 검증한다. 장기 트래픽, 다중 AZ 장애, timeout fault injection, AI provider·Tool Calling, 상시 on-call, 실제 청구 비용과 운영 용량은 측정하지 않았다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-19` · 폐기 조건 `같은 release의 더 완전한 OPS-03 운영 실측이 이 결과를 명시적으로 대체할 때`
