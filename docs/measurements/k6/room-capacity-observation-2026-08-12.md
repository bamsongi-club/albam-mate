# ROOM AWS 관측 보강 보고서 (2026-08-11 23:24~2026-08-12 02:48 KST)

## 결론

- Campaign ID: `room-capacity-20260812T032259KST`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 문서 인덱스: [k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/room-capacity-observation-2026-08-12.json)
- 대체 관계: `room-k6-20260811T142439Z`를 `supplements`하며, 원본 보고서나 manifest를 대체하지 않는다.

이 문서는 기존 ROOM k6 캠페인 구간을 덮는 과거 CloudWatch 지표를 읽기 전용으로 조회해 보강한 기록이다. 새로운 k6 실행, 애플리케이션·인프라 변경, redeploy, apply 또는 down은 수행하지 않았다.

전체 Query window에서 App 2대, PostgreSQL, Redis 및 load generator의 EC2 CPU에 포화 징후는 없었고, burst credit을 조회한 네 t4g.micro도 credit 고갈 상태가 관측되지 않았다. 가장 높은 CPU 값은 PostgreSQL의 32.525%였다.

다만 이 관측은 캠페인 전체 구간의 CPU·credit만 추가 집계한 것이다. host/container/JVM/DB 진단이 없고, 당시 배포 image·설정과 infra revision·dirty state가 보존되지 않았다. 따라서 이 결과는 성능 용량 경계, 시나리오별 병목 또는 AWS 스택의 전체 용량 결과를 확정하지 않는다.

## 관측 조건과 정본 경계

| 항목 | 값 |
| --- | --- |
| 대상 원본 캠페인 | `room-k6-20260811T142439Z` |
| CloudWatch Query window | UTC 2026-08-11T14:24:00Z~2026-08-11T17:48:00Z (KST 2026-08-11 23:24~2026-08-12 02:48) |
| 조회 방식 | CloudWatch `GetMetricData`의 과거 데이터 읽기 전용 조회 |
| 리전 | `ap-northeast-2`의 비식별 전용 성능 스택 |
| EC2 CPU | `CPUUtilization`, `Maximum`, 60초 period |
| burst credit | `CPUCreditBalance`, `Minimum`, 300초 period |
| 메모리 보강 조회 | CWAgent `mem_used_percent`, `mem_available_percent`와 10초 memory query |
| 새 부하 실행·상태 변경 | 없음 |

CPU와 credit 표의 최소·최대·평균은 각각 위 statistic 시계열의 campaign-wide 추가 집계다. 즉 CPU의 각 표본은 60초 구간 최대값이고, credit의 각 표본은 300초 구간 최소값이다. 표의 평균은 이 statistic 표본들의 산술평균이며, 순간 원시값이나 시나리오별 값이 아니다.

## 관측 대상 토폴로지

현재 Terraform configuration에서 관찰된 리소스 범위는 App `t4g.micro` 2대, PostgreSQL `t4g.micro` 1대, Redis `t4g.micro` 1대, load generator `c7g.large` 1대다. 이 범위는 과거 캠페인 실행 시점의 실제 topology를 증명하지 않으며, 과거 배포의 image digest·release 설정·infra SHA·dirty state도 원자료에 보존되지 않았다.

원본 ROOM 앱 source SHA는 `1d549673e30723999892f737783c53a27b3e70c3`이고, ROOM load-test tree Git object는 `8583a57eab18c0c16e2344d7a8274de0d911ea52`이다. 현재 `perf.env`는 당시 설정과 다르므로 이 문서는 이를 역사적 배포 설정으로 제시하지 않는다.

## CloudWatch 관측 결과

| 역할 | CPU 표본 수 | CPU 최소 / 최대 / 평균 (%) | Credit 표본 수 | Credit 최소 / 최대 / 평균 |
| --- | ---: | --- | ---: | --- |
| App A | 204 | 1.2916666666666665 / 4.8 / 1.6673594670656495 | 41 | 57.00889831666667 / 90.22727673333333 / 73.32353161829268 |
| App B | 204 | 1.1744127936031983 / 4.189641673191291 / 1.472662499827974 | 41 | 58.8744086 / 92.89785648333333 / 75.62295185528455 |
| PostgreSQL | 204 | 1.2580817169899352 / 32.525 / 2.0805757479996814 | 41 | 58.35561995 / 90.0842899 / 74.41637093373984 |
| Redis | 204 | 1.2083333333333335 / 1.5999999999999999 / 1.3168709451006761 | 41 | 60.870519433333335 / 95.53472833333333 / 78.18395434918699 |
| load generator | 204 | 0.2249999999999999 / 7.033333333333332 / 0.7349281884878515 | 조회하지 않음 | - |

이 window에서는 CPU saturation 또는 CPU credit의 소진을 관측하지 못했다. PostgreSQL은 다섯 대상 중 최대 CPU가 가장 높았지만 32.525%이며, 이 한 지표만으로 데이터베이스가 병목이 아니라고 진단할 수는 없다.

## 사용할 수 없는 관측치

CWAgent `mem_used_percent`, `mem_available_percent`, `cpu_usage_user`, `disk_used_percent` 시계열은 이 스택에서 모두 0개였고, 10초 memory query도 모두 `sampleCount=0`이었다. 이 결과는 `OBSERVATION_UNAVAILABLE`로 기록한다. 0% 사용량이 아니며, 메모리·디스크·사용자 CPU 상태나 원인을 진단하지 않는다.

| 관측 항목 | 상태 | 해석 금지 |
| --- | --- | --- |
| CWAgent `mem_used_percent` | `OBSERVATION_UNAVAILABLE` | 0% 사용량 또는 메모리 여유로 해석하지 않음 |
| CWAgent `mem_available_percent` | `OBSERVATION_UNAVAILABLE` | host 메모리 여유로 해석하지 않음 |
| CWAgent `cpu_usage_user` | `OBSERVATION_UNAVAILABLE` | 프로세스·사용자 CPU 상태로 해석하지 않음 |
| CWAgent `disk_used_percent` | `OBSERVATION_UNAVAILABLE` | 디스크 사용량으로 해석하지 않음 |
| 10초 memory query | `OBSERVATION_UNAVAILABLE` | 메모리 시계열이 0이었다고 해석하지 않음 |

## 한계와 후속 측정 조건

- 관측 지표는 campaign-wide CPU·credit뿐이므로 원본 k6의 특정 Run, 논리 시나리오, VU·fixture 조건과 직접 연결할 수 없다.
- CloudWatch CPU·credit만으로 host/container memory, JVM heap·GC, Hikari, PostgreSQL lock·query 또는 Redis 상태를 판정할 수 없다.
- historical deploy config가 보존되지 않아 현행 설정과 당시 실행 환경의 동등성을 주장할 수 없다.
- 원본 보고서의 HTTP 결과와 이 문서의 AWS 관측은 함께 읽되, 어느 쪽도 다른 쪽을 대체하거나 용량 경계를 확정하지 않는다.

용량 경계나 병목을 판단하려면 새 Campaign ID에서 고정된 release·image digest·infra revision과 시작 credit을 보존하고, 시나리오별 k6 지표와 동일 시간축의 host/container/JVM/DB 진단을 함께 수집해야 한다.
