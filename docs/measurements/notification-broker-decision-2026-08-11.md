# 알림 broker 판단서 (2026-08-11)

## 결정

현재 PostgreSQL transactional outbox와 polling relay를 유지한다. Kafka·RabbitMQ를 도입하지 않는다.

이 결정은 PostgreSQL relay가 모든 규모를 견딘다는 뜻이 아니다. 이번에 유효하게 검증한 범위는 App 2대, poll 5초, 인스턴스당 batch 50에서 취소 이벤트 100개와 수신자 1·5·10명의 fan-out 단가뿐이다. 지속 혼합 부하는 App cgroup OOM 때문에 유효한 정상·실패 경계를 만들지 못했으므로 relay 포화점은 미측정 상태다.

## 근거

fan-out 공식 9회는 모두 다음 계약을 만족했다.

| 수신자 | 클라이언트 취소 전달 표본 | 서버 relay 표본 | server-side p95 범위 | p99 범위 | 최대 oldest age 범위 | 최종 backlog / 실패 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | Run당 100 | Run당 200 | 4.210~4.607초 | 4.692~4.896초 | 3.178~4.686초 | 0 / 0 |
| 5 | Run당 500 | Run당 600 | 4.434~4.549초 | 4.778~4.845초 | 3.655~4.982초 | 0 / 0 |
| 10 | Run당 1,000 | Run당 1,100 | 4.505~4.968초 | 4.897~5.676초 | 3.360~4.559초 | 0 / 0 |

서버 표본은 준비 단계의 참가 알림을 포함하므로 취소 표본보다 이벤트 100개만큼 많다. 모든 Run은 처리·표본 수가 같고 retry·failed가 0이었다. 수신자 수가 10배가 되어도 server-side p95 중앙값은 4.259초에서 4.742초로 증가했다. batch 수는 1명 13~22, 5명 47~59, 10명 80~83으로 작업량에 따라 늘었지만 PostgreSQL waiting lock과 최종 처리 가능 backlog는 0이었다.

반면 알림 혼합 부하는 0.5×와 1× 모두 App memory limit에 도달해 Java가 종료됐다. PostgreSQL 최대 CPU는 12.20%, Redis는 5.43%, load generator는 8.51%였고 DB lock·미수렴 backlog 증거는 없었다. 현재 실패를 broker 부재나 PostgreSQL relay 병목으로 설명할 근거가 없다.

## PostgreSQL relay 유지 가능 범위

현재 증거로 주장할 수 있는 범위는 다음뿐이다.

- 한 번의 Run에서 취소 이벤트 100개, 이벤트당 수신자 최대 10명
- 준비 알림을 포함한 서버 처리 최대 1,100표본
- poll 5초, App 인스턴스당 batch 50, App 2대
- server-side p95 최대 4.968초, p99 최대 5.676초
- 가장 오래된 처리 가능 이벤트 최대 4.982초, 최종 backlog 0
- retry와 failed 0, 클라이언트 취소 전달 유실·중복 0

이 결과를 초당 지속 유입량이나 일별 운영 한계로 외삽하지 않는다. 이벤트 생성은 1 VU가 순차 수행했고, 혼합 읽기 부하와 동시에 relay 포화점을 찾은 시험이 아니다.

## 최소 튜닝 후 확인할 경계

첫 후속 변경은 broker가 아니라 App Tomcat 최대 thread 200→64다. 변경 이유와 재측정 순서는 [성능 보고서](auth-notification-capacity-2026-08-11.md#최소-개선-후보와-재측정-계획)를 따른다.

튜닝 뒤 다음이 모두 유효한 결과로 반복될 때 PostgreSQL relay의 지속 사용 범위를 다시 적는다.

- 알림 0.5×와 1×의 정상·실패 경계
- 최소 100개 server-side 전달 표본의 p95
- `oldestProcessableAgeMs` 최대값과 관찰 종료 시 backlog 0 여부
- PostgreSQL CPU·connection·waiting lock과 App Hikari pending
- 같은 역할의 반복 시작 CPU credit 차이 5 이내

App OOM을 제거한 뒤 PostgreSQL 또는 connection pool이 최초 병목으로 바뀌면, 그때 relay poll·batch·worker 조정을 한 가지씩 비교한다. 미리 batch를 키우거나 worker를 늘리지 않는다.

## 대안 비교

| 대안 | 적합한 문제 | 현재 판단 |
| --- | --- | --- |
| 현행 PostgreSQL relay 조정 | 단일 서비스의 웹 알림, DB 트랜잭션과 같은 source of truth, 짧은 retry | 현재 선택. 이번 fan-out 범위에서 유실·실패·backlog 문제가 없었다. |
| App 수평 확장 또는 relay 전용 프로세스 | App 요청 처리와 relay CPU·memory 격리, 같은 PostgreSQL 계약 유지 | broker보다 먼저 비교할 저비용 대안. 단, 현재는 App memory 조정 전이라 아직 실행하지 않는다. |
| RabbitMQ | 작업 큐, ACK·재시도·DLQ, 소비자별 라우팅과 짧은 보존 | 독립 worker가 필요하지만 장기 replay가 핵심이 아닐 때 후보. DB commit과 publish 사이 이중 쓰기는 Outbox/relay 또는 publisher confirm 설계가 계속 필요하다. |
| Kafka | 여러 독립 consumer group, 장기 보존·replay, 높은 지속 처리량, 소비자별 offset | 현재 요구와 측정에는 과하다. 클러스터 운영, partition·retention, schema, lag, 재처리와 DB-broker 전달 경계를 추가한다. |

## broker 재검토 조건

다음 중 하나가 실제로 측정되면 PostgreSQL relay 조정, 전용 worker, RabbitMQ와 Kafka를 같은 ADR에서 비교한다.

1. App OOM과 CPU credit 조건을 제거하고 poll·batch·worker를 조정한 뒤에도 최소 100표본의 server-side p95 30초 또는 oldest processable age 60초를 반복 위반한다.
2. PostgreSQL CPU·lock·connection pool이 relay 때문에 최초 병목이라는 Run 근거가 생긴다.
3. 독립 consumer가 둘 이상이 되어 consumer별 처리 위치·재시도·보존이 필요하다.
4. 일부 consumer를 별도 프로세스나 서비스로 독립 배포해야 한다.
5. 장기 replay·감사 보존 또는 업무 DB와 알림 장애의 운영상 격리가 요구된다.
6. 운영의 완전한 일별 구간에서 p95 목표 위반이 3회 연속 발생하고 최소 relay 조정으로 회복되지 않는다.

조건이 생겨도 Kafka를 자동 선택하지 않는다. 단기 작업 큐면 RabbitMQ, 장기 replay와 다중 consumer group이면 Kafka, 단일 서비스 내 웹 알림이면 PostgreSQL relay와 전용 worker가 더 단순할 수 있다.

## 상태 구분

- 구현됨: PostgreSQL outbox·polling relay, 수신자별 멱등 생성, retry·failure·recovery 계약
- AWS에서 측정됨: fan-out 1·5·10명 × 100개 이벤트 × 3회 PASS
- 측정되지 않음: 지속 relay 포화점, broker 대비 처리량, 외부 채널, 별도 consumer 배포
- 제안됨: Tomcat 최대 thread 64의 단일 변경과 동일 조건 재측정
- 결정되지 않음: Kafka/RabbitMQ 도입, App instance type 변경, relay batch·worker 변경
