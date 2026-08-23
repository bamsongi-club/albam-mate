# MATCH-01 T10 AWS 측정 중단 보고서

## 판정

| 항목 | 판정 |
| --- | --- |
| 결과 상태 | `INVALID` |
| 후보 claim 정합성 | 미평가 |
| 성능 수치 채택 | 불가 |
| P2 상태표 갱신 | 하지 않음 |
| #746 완료 처리 | 보류 |

이번 실행은 candidate claim 정합성이 틀렸다는 `FAILED`가 아니다. 실행·관측 계약을 끝까지 충족하지 못해 `INVALID`로 분류한다. warm-up 1회와 measured round 3회를 완료하지 못했고, 1,000개 표본·두 matcher 완료·raw/report·PostgreSQL 관측 결과가 모두 갖춰지지 않았기 때문이다.

## 실행 provenance

| 항목 | 값 |
| --- | --- |
| 기능 | MATCH-01 T10 candidate claim baseline |
| AWS stack | `perf-jiwon` |
| AWS region | `ap-northeast-2` |
| 실행 source SHA | `032c456691f03c24160fdee685343131c182a0d2` |
| source working tree | clean |
| 최종 직접 실행 SSM command | `0362183b-7dca-477d-861c-127a58529691` |
| OOM kernel 확인 SSM command | `9ec7b8f8-e7a3-400d-a02e-403ec0314ba6` |
| 실행 후 상태 확인 SSM command | `da315cd9-0357-435f-a6c8-9cc87f23c771` |

비밀번호, SSM parameter 값, presigned URL은 이 문서에 기록하지 않는다.

## 핵심 원인

### 배포 환경의 인스턴스 타입이 서로 달랐다

“배포 환경을 Small로 올렸다”는 기억은 애플리케이션 서버에는 맞지만, 전체 스택은 다음과 같이 구성되어 있었다.

| 역할 | 인스턴스 타입 |
| --- | --- |
| app-a | `t4g.small` |
| app-b | `t4g.small` |
| PostgreSQL | `t4g.micro` |
| Redis | `t4g.micro` |
| load generator | `c7g.large` |

T10은 PostgreSQL의 `inet_server_addr()`와 JDBC target identity를 일치시켜야 하므로, 최종 runner는 PostgreSQL 컨테이너의 network namespace 안에서 실행해야 했다. 따라서 최종 실행의 Java 프로세스·Gradle·PostgreSQL이 PostgreSQL `t4g.micro`의 약 916 MiB RAM을 함께 사용했다. 이 경로에서는 애플리케이션 서버가 `t4g.small`이어도 PostgreSQL Micro가 실제 실행 한계가 된다.

따라서 이번 측정 불가 사유를 App1·App2의 인스턴스 부족으로 기록하지 않는다. App1·App2는 P2 OOM 대응 결정에 따라 이미 `t4g.small`이었고, Spring 컨테이너도 `1GiB` 프로파일을 사용했다. 이번 T10을 막은 것은 애플리케이션 계층이 아니라, 동일 network namespace 실행 계약 때문에 runner와 matcher JVM이 함께 올라간 PostgreSQL `t4g.micro`의 메모리 한계였다.

### 최종 실행은 Java OOM으로 ready barrier 전에 종료됐다

최종 직접 실행은 두 matcher가 준비되기 전에 Java worker가 종료 코드 137로 끝났다. 실행 후 PostgreSQL 호스트의 kernel 로그에는 다음 사실이 남았다.

- Docker 컨테이너의 `java` 프로세스가 OOM killer에 의해 종료됨
- 종료된 프로세스의 kernel 기록: `Out of memory: Killed process ... (java)`
- PostgreSQL 호스트에는 swap이 없음
- 실행 후 PostgreSQL 컨테이너 자체는 `healthy` 상태였지만 matcher 결과는 생성되지 않음

즉 이번 문제는 CPU credit 부족이나 candidate 결과의 assertion 실패가 아니라, T10이 요구하는 Gradle·runner·독립 matcher 2개를 PostgreSQL Micro에서 동시에 실행할 때 필요한 메모리를 확보하지 못한 것이다. 앞선 app-a 시도에서도 Java와 애플리케이션이 함께 동작하는 동안 가용 메모리가 약 1.64%까지 내려가 SSM 연결이 끊겼다. 이 현상 역시 메모리 압박과 일치한다.

## 실행 계약과 실제 결과

T10 계약은 다음을 모두 요구한다.

1. warm-up 1회와 독립 measured round 3회
2. measured round마다 1,000개 claim 시도와 matcher 2개 완료
3. correctness smoke, retry, lock wait, `pg_stat_statements` 관측
4. 실행 SHA·topology·fixture·raw digest·report 보존
5. 위 자료가 모두 유효할 때만 baseline 결과 채택

실제 결과는 다음과 같다.

| 확인 항목 | 실제 결과 |
| --- | --- |
| runner ready barrier | 도달하지 못함 |
| measured round | 0/3 완료 |
| matcher | worker OOM 종료 |
| raw artifact | 생성되지 않음 (`raw-absent`) |
| report | 생성되지 않음 (`report-absent`) |
| 최종 PostgreSQL 상태 | 실행 후 `healthy` |
| 성능 수치 | 없음 |

따라서 이 실행에서 p50·p95·p99·throughput·retry·lock wait를 계산하거나 before/after 비교를 만들 수 없다. 결과를 성공으로 맞추거나 일부 로그만으로 검증완료 처리해서는 안 된다.

## 조치 및 미완료 범위

이번 AWS stack은 이 보고서 작성 후 destroy했고, Terraform은 94개 리소스 삭제를 완료했다. destroy는 측정 결과를 성공으로 바꾸지 않으며, 현재 실행의 `INVALID` 증거와 원인은 이 문서로 보존한다.

T10을 다시 실행하려면 다음 중 하나가 선행되어야 한다.

- PostgreSQL 실행 호스트를 최소 `t4g.small` 수준으로 확장하고 같은 network namespace 실행 계약을 유지한다.
- PostgreSQL을 Micro로 유지해야 한다면, matcher·Gradle을 별도 메모리 여유가 있는 호스트에서 실행할 수 있도록 target identity 계약을 별도로 승인·설계한다.

재측정은 현재 실패 결과를 덮어쓰거나 임시 AWS 설정을 직접 바꾸는 방식으로 진행하지 않는다. 팀의 `albam-mate-infra` 역할·책임 분리, Terraform 변수·plan/apply, SSM 실행, 브랜치와 receipt 보존 컨벤션을 지키는 별도 인프라 변경으로 사전 승인한 뒤, 같은 source SHA·fixture·evidence 계약에서 다시 실행한다. 그 전까지 이 T10은 “App1·App2는 Small이지만 PostgreSQL이 Micro여서 측정 계약을 완료할 수 없었던 `INVALID`”로 보존한다.

그 재실행에서 모든 round와 관측 artifact가 유효하게 생성되기 전에는 T10을 검증완료로 표시하지 않는다. 이 보고서의 `INVALID`는 #746 통합 gate의 `PASS` 입력으로 사용할 수 없다.
