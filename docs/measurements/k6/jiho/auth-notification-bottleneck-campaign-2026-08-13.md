# 인증·알림 병목 진단 캠페인 보고서 (2026-08-13)

## 결론

이 문서는 측정 전용 내부 Timer를 활성화한 통제 AWS 환경에서 인증과 알림을 분리해 반복 측정한 결과다. 운영 트래픽 통계나 운영 사이징 결론이 아니다.

- 인증 Campaign ID: `auth-noti-662-20260813h-auth`
- 알림 Campaign ID: `a662-h-notify-v4`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 관련 작업: [계측 이슈 #662](https://github.com/bamsongi-club/albam-mate/issues/662), [App PR #663](https://github.com/bamsongi-club/albam-mate/pull/663), [Infra PR #11](https://github.com/bamsongi-club/albam-mate-infra/pull/11)
- 후속 판단: [#695](https://github.com/bamsongi-club/albam-mate/issues/695)

- 인증은 8 req/s 15분 실행 3회가 모두 `PASS`, 16 req/s 15분 실행 3회가 모두 `FAIL`이었다.
- 16 req/s의 `bcrypt-permit` 평균은 `bcrypt-verify` 평균보다 1.09~2.16% 길어 20% 후보 기준에 미달했다. App2 CPU만 15초 간격 6~7회 연속 90% 이상이었으므로 permit 범위 병목이 아니라 정상적인 bcrypt CPU 비용으로 분류한다.
- 16 req/s에서는 App2의 verification gate 거절이 6,886~8,483건으로 bcrypt slot 거절 367~412건보다 컸다. 보호 제한과 App2 요청·CPU 편중을 분리하지 않고 bcrypt cost나 permit 수 변경을 주장하지 않는다.
- 알림은 0.5×·1×·2×를 각각 3회 실행해 9회 모두 `PASS`, 최대 단계 `PASS_AT_MAX`였다. 조회 p95는 17.65~24.80ms, relay p95는 3.105~4.776초였고 모든 Run이 처리 실패·retry·최종 backlog 0이었다.
- 앞선 512 MiB 실행과 준비 실패는 `INVALID`로 보존하고 경계 계산에서 제외한다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| AWS | account `<redacted>`, region `ap-northeast-2`, stack `perf-auth-notification-662`, `Environment=perf` |
| App | `t4g.small` 2대, CPU credit `unlimited`, JVM `-Xmx256m`, cgroup 1 GiB, Tomcat max 200, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro`, CPU credit `unlimited` |
| 발생기 | `c7g.large`, k6 `1.3.0` |
| relay | App 인스턴스당 poll 5초, batch 50 |
| 인증 App release | `c64e3ea4ab4a3045838caae48afcee9422c5092a` |
| 인증 backend / web image | `sha256:7a5bdaa24fd26c9576f405543b6521164fa6d6df94d32cae9896d7ad8df0de08` / `sha256:38d7adbbd9f2615a08c3b8a514c6f1b7d8878c5ba2ecca251bf8617587b011e9` |
| 알림 App release | `8b497ebd7c0f8aa3c8933c9683e9881b8d399325` |
| 알림 backend / web image | `sha256:0c017e3a717426ca198bb0bd17cd9d0ba3dbc7811cc8854ec037391c4563716a` / `sha256:d279492ef62b734dac15c6bb61d8751187934d25755f770d00fed216460c2228` |
| Infra release | `08b64de2978146ee9948a2b791466c87fbfe6b2d` |
| PostgreSQL / Redis image | `sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636` / `sha256:bd4a0d37e7cd830117ffec9329052b4a1887afa060c265e1768f82b177ff6f43` |
| 상태 격리 | 각 Run 전에 App 중지, PostgreSQL `public` schema와 Redis DB 초기화, Flyway·fixture 재적용 |
| 원자료 | `albam-mate-infra/.run/results/<Run-ID>/`; 비밀값과 실제 리소스 식별자는 커밋하지 않음 |

인증 뒤 App 캠페인 runner와 테스트만 보강해 알림 release SHA가 달라졌다. 인증 생산 경로는 바뀌지 않았으며 두 도메인의 결과를 서로 섞지 않는다. 모든 유효 Run은 release·image·실효 설정 일치, 내부 Timer, 역할별 15초 지표, CloudWatch credit, PostgreSQL 통계와 evaluator 판정을 갖는다.

## 인증 캠페인

탐색은 1·2·4·8 req/s가 `PASS`, 16 req/s가 `FAIL`이었다. 마지막 통과점 8 req/s와 첫 실패점 16 req/s를 각각 15분씩 3회 반복했다.

| 조건 | 반복 | 판정 | 완료 요청 p95 | permit 평균 | verify 평균 |
| ---: | ---: | --- | ---: | ---: | ---: |
| 8 req/s | n1 | PASS | 174.60ms | 116.923ms | 108.675ms |
| 8 req/s | n2 | PASS | 175.32ms | 117.727ms | 109.353ms |
| 8 req/s | n3 | PASS | 175.56ms | 117.353ms | 108.992ms |
| 16 req/s | n1 | FAIL | 373.86ms | 155.662ms | 153.978ms |
| 16 req/s | n2 | FAIL | 353.08ms | 145.809ms | 142.731ms |
| 16 req/s | n3 | FAIL | 370.99ms | 155.067ms | 152.524ms |

16 req/s의 완료 요청 p95는 1초 이내였지만 세 번 모두 1초 429와 예상 밖 응답 임계를 위반했고 n3는 dropped iteration도 발생했다.

### rejection과 CPU

| 반복 | App | verification gate | bcrypt slot | Redis unavailable | CPU 평균 / 최대 | 90% 이상 최장 연속 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| n1 | App1 | 377 | 2 | 8 | 26.34% / 54.10% | 0 |
| n1 | App2 | 8,483 | 367 | 420 | 47.55% / 99.53% | 7 |
| n2 | App1 | 190 | 2 | 7 | 29.33% / 53.10% | 0 |
| n2 | App2 | 6,886 | 385 | 490 | 55.23% / 99.47% | 6 |
| n3 | App1 | 370 | 4 | 7 | 25.96% / 54.48% | 0 |
| n3 | App2 | 7,907 | 412 | 550 | 50.92% / 99.53% | 6 |

`bcrypt-permit`과 실제 verify의 차이는 세 번 모두 10% 이내이고, App2 CPU가 15초 간격 4회 이상 90%를 넘었다. 계획의 판정 규칙에 따라 bcrypt permit 범위는 강한 후보가 아니다. 다만 동일 로그인 fixture의 보호 제한과 App2 편중이 용량 결과에 함께 나타났으므로 [후속 판단 #695](https://github.com/bamsongi-club/albam-mate/issues/695)에서 시나리오 분리와 요청 분포 재현 범위를 정한다.

## 알림 캠페인

각 Run은 warm-up 2분, 측정 10분, relay 관찰 3분을 거친 뒤 App·PostgreSQL·Redis·발생기 증거를 회수했다. 0.5× 세 번이 모두 통과한 뒤 1×, 1× 세 번이 모두 통과한 뒤 2×로 상승했다.

| 조건 | 반복 | 판정 | 목록 p95 | unread p95 | relay p95 | relay 표본 | 최종 backlog |
| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 0.5× | n1 | PASS | 23.24ms | 21.11ms | 4.757초 | 152 | 0 |
| 0.5× | n2 | PASS | 24.80ms | 22.66ms | 3.105초 | 152 | 0 |
| 0.5× | n3 | PASS | 23.72ms | 21.02ms | 4.258초 | 152 | 0 |
| 1× | n1 | PASS | 24.07ms | 21.39ms | 4.756초 | 302 | 0 |
| 1× | n2 | PASS | 22.03ms | 18.30ms | 4.765초 | 302 | 0 |
| 1× | n3 | PASS | 23.49ms | 21.09ms | 4.623초 | 302 | 0 |
| 2× | n1 | PASS | 20.02ms | 17.65ms | 4.667초 | 602 | 0 |
| 2× | n2 | PASS | 20.05ms | 20.06ms | 4.776초 | 602 | 0 |
| 2× | n3 | PASS | 24.36ms | 19.78ms | 4.619초 | 602 | 0 |

9회 모두 `processedCount == deliveryDelaySampleCount`, `failedCount=0`, `retryScheduledCount=0`, 최종 `processableOutboxCount=0`, waiting lock 0이었다. App별 Hikari pending 최대도 0이고 15초 간격 CPU 90% 이상 연속 표본은 없었다. 조회 API, relay, App CPU, DB·connection 경합 중 계획의 병목 조건을 만족한 항목이 없으므로 알림 병목 이슈를 만들지 않는다.

PostgreSQL `pg_stat_statements`, content·total count·unread count의 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`과 내부 query·relay Timer는 모든 Run에 존재했다. 목록의 content/total count와 unread count는 별도 HTTP 요청이며 한 요청의 단계처럼 합산하지 않는다.

## 제외한 알림 시도

- `auth-noti-662-20260813h-notification`: 당시 runner가 계획의 고정 0.5×를 obsolete ACK로 거부해 두 attempt 모두 부하 전에 `INVALID`였다.
- `a662-h-notify-v2`: setup 로그인 집중으로 두 attempt 모두 `INVALID`였다. browsing 로그인 시작을 90초에 분산한 뒤 새 Campaign ID로 다시 실행했다.
- `a662-h-notify-v3`: 0.5×·1×·2× 각 3회가 evaluator `PASS`였지만, 수동 재배포에서 `SPRING_MEMORY_LIMIT=1g`가 빠져 두 App cgroup이 512 MiB였다. 계획의 1 GiB 계약과 다르므로 결과를 발견 즉시 폐기하고 경계·병목 판정에 사용하지 않았다.

각 실패·무효 attempt 디렉터리와 로그는 로컬에 그대로 보존했다. 유효하지 않은 실행을 정상점 또는 실패점으로 승격하지 않는다.

## 비용과 철거

알림 재측정은 2026-08-13 16:59~20:18 KST에 실행했다. AWS Price List API와 보수 상수로 산출한 10시간 상한은 `$4.707446575342466`이며 `$5` 사전 가드를 통과했다. 이 값은 실제 청구액이 아니라 다음을 모두 포함한 상한이다.

- compute `$1.44`
- gp3 78 GiB의 10시간분 `$0.09744657534246576`
- public IPv4 `$0.25`, 기타 reserve `$0.20`
- `t4g` unlimited surplus 최악값 `$2.72`

최종 bundle을 회수한 뒤 Terraform이 94개 리소스를 destroy했다. `terraform state list`는 0행이었고 AWS live API로 다음이 모두 0임을 확인했다.

- 실행 중·중지 중 인스턴스, EBS volume
- VPC, subnet, security group, route table, internet gateway, ENI, Elastic IP
- 내부 Route53 hosted zone, 캠페인 IAM role
- Ansible transfer bucket은 `head-bucket`이 존재하지 않음을 반환

Resource Groups Tagging API의 삭제 지연 표시는 live 리소스 판정에 사용하지 않았다. 리소스별 live API와 Terraform state를 철거 정본으로 사용했다.

## 해석 한계와 후속

- 이 환경은 병목 진단을 위한 `t4g.small + unlimited` 통제 환경이다. 운영 가능 범위는 별도 `t4g.micro + standard` 캠페인으로 확인해야 한다.
- 목록 content/total count와 unread count는 서로 다른 HTTP 요청이다. 내부 Timer의 같은 이름이나 합산 수치로 한 요청처럼 해석하지 않는다.
- 내부 Timer는 원인 근거이고 외부 PASS/FAIL 기준을 대체하지 않는다.
- 인증 결과만으로 bcrypt cost 감소나 permit 증가를 성능 개선으로 권고하지 않는다.
- 원시 로그, 계정, IP, 인스턴스·volume·bucket·zone 식별자는 로컬 결과에만 보존한다.
