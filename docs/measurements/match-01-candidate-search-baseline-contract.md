# MATCH-01 후보 탐색 baseline 측정 계약

## 목적과 범위

이 문서는 [ADR-0063](../adr/matching/0063-match-baseline-measurement-gate.md)와 그 candidate claim 범위 해석을 정한 [ADR-0065](../adr/matching/0065-match-candidate-claim-baseline-scope.md)의 PostgreSQL 우선 baseline을 재현할 fixture, 실행 round, 원자료와 결과 채택 기준을 하나로 고정한다. 결과는 운영 SLO나 Redis business lock 도입 결론이 아니다. local Testcontainers 경로와 전용 외부 PostgreSQL runner가 이 계약을 실행하며, 결과 파일은 실행 시 생성한다.

이 baseline은 PostgreSQL에서 `prioritySince ASC, requestId ASC` 순으로 `FOR UPDATE SKIP LOCKED` 후보를 선점하고, 같은 트랜잭션에서 `WAITING → PROPOSED`와 제안 생성을 확정하는 **candidate claim transaction** 경로만 측정한다. 최종 `ACCEPT`·Party 확정·현재 상태 snapshot 복구는 이 baseline latency의 범위가 아니며, 별도 통합 검증에서 정합성을 확인한다. 제품 조건과 정합성 불변식은 [MATCH-01 명세](../p2/matching.md), 저장·잠금 조건은 [ERD](../ERD.md), 실행 흐름은 [아키텍처](../ARCHITECTURE.md)가 정본이다. 기존 Testcontainers local 경로와 별도로 `matchCandidateExternalMeasurement`는 전용 외부 PostgreSQL target을 사용하며, 비밀번호는 `ISSUE775_JDBC_PASSWORD` 환경변수로만 전달한다.

## 외부 PostgreSQL runner 안전 경계

`matchCandidateExternalMeasurement`는 외부 target에 접속하기 전에 다음을 모두 확인한다.

1. `issue775.measurement=true`와 `match01.external.allow-mutation=true`가 명시되었는지 확인한다.
2. `match01.external.jdbc-url`·사용자·raw 출력 경로·환경 profile 파일·40자 `measuredGitSha`를 확인하고, 비밀번호는 `ISSUE775_JDBC_PASSWORD`에서만 읽는다. JDBC URL·시스템 속성·JSON·로그에 비밀번호를 둘 수 없다.
3. 환경 profile이 `stackId`, `region`, `accountAlias`, `databaseRole`, `runner`, `ephemeral`, `releaseSha` allowlist의 scalar JSON이고 `ephemeral=true`인지 확인한다. 현재 runner checkout이 clean이며 HEAD도 지정한 `measuredGitSha`와 같아야 한다.
4. target provisioner가 `match01_control.match01_external_target_metadata`를 만들고, login 불가 전용 provisioner role을 schema/table owner로 둔 뒤 해당 metadata schema/table에는 runner role의 `USAGE`와 `SELECT`만 부여해야 한다. row의 `schema_version`, `stack_id`, database·role·user, 실제 server address/port와 동일한 JDBC endpoint, runner ID, `ephemeral`, release SHA를 provisioner가 기록한다. 또한 PostgreSQL 서버에 `shared_preload_libraries=pg_stat_statements`를 설정하고 measurement database에 `CREATE EXTENSION pg_stat_statements`를 실행한 뒤, runner role에 `GRANT EXECUTE ON FUNCTION public.pg_stat_statements_reset(oid, oid, bigint, boolean)`을 부여해야 한다. runner는 이 table이 없거나 owner가 login 가능하거나 metadata에 대한 DML/TRUNCATE 권한이 있거나 통계 reset 권한이 없으면 중단한다.
5. runner는 같은 PostgreSQL connection에서 `current_database()`, `current_user`, `inet_server_addr()`, `inet_server_port()`를 읽어 provisioned metadata와 JDBC URL의 database·endpoint를 모두 대조하고 `pg_stat_statements` view와 reset 함수 EXECUTE 권한도 확인한다. JDBC URL은 실제 server address와 port를 직접 사용해야 하므로 DNS alias·failover/load-balancing endpoint는 거절한다. profile의 stack·role·runner·release SHA·`ephemeral=true`와 측정 SHA도 row와 일치해야 하며, 이 preflight가 끝나기 전에는 measurement table mutation에 도달하지 않는다. preflight에 성공한 물리 connection은 Java `JdbcTemplate`의 측정·mutation 전체에 고정해 재사용하고, 독립 matcher worker의 connection-init 단계에서도 같은 identity·metadata·read-only 권한을 검증한다.
6. 외부 target은 PostgreSQL인지 `select 1`로 확인한 뒤, baseline 부하 전에 혼합 범위 correctness smoke를 실행한다. smoke가 `R1·R3` proposal 한 건과 `partySize=2`를 증명하지 못하면 baseline을 시작하지 않는다.
7. smoke 뒤 각 round마다 MATCH measurement 테이블을 초기화하고 동일 fixture·2 matcher·matcher당 500회 계약을 실행한다. 운영 데이터가 있는 DB에는 실행하지 않는다.

raw 입력은 allowlist로 검증한 `environmentProfile`, 혼합 범위 smoke의 canonical materialized manifest 원자료와 input/manifest hash, 각 round의 measured SHA·topology configuration hash·lock wait·correctness를 보존한다. 외부 실행은 Java runner가 Node report를 `--external`로 호출하는 명시적 경계이며, report는 외부 provenance가 없거나 runner·target·profile shape가 바뀐 입력을 local report로 강등하지 않는다. 외부 report에서는 smoke와 raw digest를 항상 검증하고, 정렬된 canonical raw observation의 SHA-256을 raw 입력과 report에 기록해 판정 시 다시 대조한다. Java runner가 저장한 raw 입력 뒤 `match01-candidate-baseline-report.mjs`를 실행해 `.report.json` 판정을 만들며, 이 결과가 `BASELINE_ACCEPTED`가 아니거나 raw digest가 어긋나면 before/after 수치나 P2 상태표에 채택하지 않는다.

ADR-0063의 baseline gate는 하나의 latency 수치로 모든 MATCH 흐름을 대신하지 않는다. 이 문서는 candidate claim transaction의 부하·lock wait·retry와 `WAITING → PROPOSED` 정합성을 담당하고, 최종 `ACCEPT`·Party/접근 확정은 `MATCH-01-T5` 통합 검증, `PREPARING`·재접속·현재 상태 snapshot 복구는 `MATCH-01-T6`·`MATCH-01-T7` 통합 검증에서 별도 증거로 닫는다. 따라서 이 fixture에 `PREPARING`·`ACTIVE` Party나 열린 제안을 섞어 candidate claim p95의 원인을 바꾸지 않는다.

## 고정 fixture와 실행 topology

| 항목 | 고정값 또는 필수 기록값 |
| --- | --- |
| 기준 SHA | 실행한 `git rev-parse HEAD` 값 |
| fixture generator | `MATCH-01-CANDIDATE-BASELINE-V2`. 아래 ordinal·시각 배정 규칙까지 같은 버전의 입력 계약이다 |
| 요청 | 게임·플랫폼 조건 없이 사용자 인원 범위 `[2, 4]`를 가진 `WAITING` 요청 정확히 1,000건. `PREPARING`·`ACTIVE`·`CLOSED` Party와 열린 제안은 0건 |
| 인원 범위 | 모든 요청의 사용자 인원 범위는 `[2, 4]`다. 따라서 claim당 2명, 기대 가능한 candidate claim은 500개 proposal·1,000개 member 전이 |
| 우선순위 | fixture ordinal `1..200`은 두 행씩 같은 `prioritySince`를 갖는 100개 동점 쌍, `201..1000`은 서로 다른 `prioritySince`를 갖는다. 모든 tie는 `requestId ASC`로 판정 가능해야 한다 |
| 차단 | `MATCH_BLOCKS`는 0건. 차단 필터 정확성은 기능 통합 테스트에서 별도로 검증하며, 이 baseline에 숨은 선택도 변수를 넣지 않는다 |
| matcher | 같은 애플리케이션 SHA·설정의 독립 matcher 프로세스 2개가 하나의 PostgreSQL DB를 공유한다. Redis business lock은 사용하지 않는다 |
| 시작 | 두 matcher가 각각 500회의 claim 시도를 준비한 뒤 같은 barrier에서 시작한다. 총 계획 표본은 round당 1,000 claim 시도다 |
| 배경 작업 | 후보 탐색과 무관한 scheduler·relay·retention 작업은 끄거나, 끌 수 없으면 이름·설정·실행 SQL을 결과에 기록한다 |

fixture 생성·truncate·통계 초기화는 측정 구간 밖에서 끝낸다. 각 round는 fixture를 새로 만들므로 이전 round의 `PROPOSED`·제안·잠금 상태를 재사용하지 않는다. fixture 입력은 다음 알고리즘으로 고정한다.

1. 요청과 synthetic 사용자는 각각 fixture ordinal `1..1000`을 하나씩 가지며 요청 ordinal과 사용자 ordinal은 같다. 다른 MATCH 요청·제안·Party·차단 관계가 없는 초기 DB에서 ordinal 오름차순으로 한 트랜잭션에 삽입한다.
2. 기준 시각은 `2026-01-01T00:00:00Z`다. ordinal `1..200`의 `queuedAt`·`prioritySince`는 모두 `기준 시각 + ceil(ordinal / 2)초`로 설정한다. 따라서 `(1, 2)`부터 `(199, 200)`까지 정확히 100개 동점 쌍이 생긴다.
3. ordinal `201..1000`의 `queuedAt`·`prioritySince`는 모두 `기준 시각 + ordinal초`로 설정해 다른 요청·동점 쌍과 겹치지 않게 한다. 모든 행의 인원 범위는 `[2, 4]`다.
4. DB 삽입 전에 `fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize` 열 순서와 ordinal 오름차순, UTF-8·LF·마지막 LF를 사용하는 CSV를 만든다. 이 바이트의 SHA-256을 `fixtureInputSha256`으로 기록하고 모든 warm-up·measured round에서 같은 값인지 먼저 검증한다.
5. 삽입 뒤 materialized fixture manifest에는 위 입력 열과 실제 `userId`·`requestId`, 기대 tie 순서를 기록한다. 각 동점 쌍에서 낮은 ordinal의 `requestId`가 더 작아야 하며, 다르면 측정을 시작하지 않고 해당 round를 `INVALID`로 남긴다.

## 혼합 범위 correctness smoke

후보 선택의 혼합 범위 정합성은 성능 표본에 섞지 않고 baseline 실행 전 별도 smoke로 검증한다. 아래 다섯 요청을 `prioritySince ASC, requestId ASC` 순서로 넣고, 차단 관계는 두지 않는다.

| 요청 | 인원 범위 |
| --- | --- |
| `R1` (anchor) | `[2,4]` |
| `R2` | `[4,4]` |
| `R3` | `[2,2]` |
| `R4` | `[4,4]` |
| `R5` | `[4,4]` |

검증 결과는 `targetPartySize`를 anchor의 최소값부터 오름차순으로 시도할 때 `targetPartySize=2`에서 `R2`를 건너뛰고 `R1·R3`를 선택하는 `partySize=2` proposal 한 건이어야 한다. `R2`처럼 현재 target과 호환되지 않는 요청을 건너뛰며, FIFO로 먼저 선택된 호환 요청을 뒤 요청으로 바꾸지 않는 것도 함께 확인한다. smoke 결과와 실행한 commit SHA는 baseline 결과 artifact에 기록하되, latency 표본·`BASELINE_ACCEPTED`의 1,000건 계산에는 포함하지 않는다.

## round와 수집 방식

1. 같은 fixture·topology로 warm-up round 1회를 실행하고, 그 결과는 통계·판정에서 제외한다.
2. fixture와 PostgreSQL 통계를 초기화한 뒤 독립 measured round 3회를 실행한다.
3. 각 measured round마다 두 matcher는 barrier에서 함께 500회씩 claim을 시도한다. claim 시도에는 후보가 없어 끝나는 경우도 포함한다.
4. 각 논리 claim 시도는 후보 `SELECT ... FOR UPDATE SKIP LOCKED` 직전부터 모든 retry를 거쳐 candidate claim transaction이 commit·rollback·후보 없음으로 끝날 때까지 `System.nanoTime()`으로 한 개의 latency 표본을 남긴다. retry 횟수와 재시도별 raw duration은 별도 필드에 보존하되, candidate claim transaction p95의 표본 수는 논리 claim 1,000개로 고정한다.
5. measured round 직전에 `pg_stat_statements_reset()`을 실행하고, round 뒤 후보 claim SQL의 calls·total execution time·rows·shared block hit/read를 수집한다. barrier 해제부터 두 matcher 종료까지 `pg_stat_activity`를 10 ms마다 조회해 `wait_event_type = 'Lock'` 표본 수와 전체 snapshot 수를 기록한다. 이 관측 창의 시작·끝 UTC 시각과 sampling 실패도 결과에 남긴다.

기술 오류, timeout, matcher 하나의 조기 종료, fixture 개수 불일치, 원자료·DB 통계·lock wait 관측 누락은 표본에서 제외하지 않는다. 해당 round 전체를 `INVALID`로 표시하고 원인과 이미 수집한 원자료를 보존한다.

## 산식과 결과 채택

- 유효한 measured round의 candidate claim transaction latency p95는 논리 claim 시도 전체 `n = 1,000`을 오름차순 정렬한 뒤 nearest-rank `ceil(0.95 × n)`번째 값으로 계산한다. 후보 없음과 retry를 포함한 최종 논리 시도는 위 수집 규칙대로 포함한다. 이 값에 최종 응답·Party provisioning·현재 상태 조회 시간은 포함하지 않는다.
- 세 measured round가 모두 유효할 때만 baseline을 비교할 수 있다. 결과 문서는 각 round의 p50·p95·p99, 처리량, retry 수, DB lock wait, PostgreSQL 비용과 세 round p95의 중앙값·최댓값을 함께 제시한다. 가장 좋은 한 round만 선택하지 않는다.
- `BASELINE_ACCEPTED`는 세 round 모두 유효하고, 각 round에 1,000개 표본·두 matcher 완료·원자료·DB 통계·lock wait 기록이 있으며, 기대한 `500`개 proposal·`1,000`개 member 전이·`1,000`개 입력 request의 정확히 한 번의 claim·fixture manifest의 tie 순서가 실제 결과와 일치하고, 한 요청의 둘 이상 `PROPOSED` 점유·중복 제안·제안 회원 일부만 전이된 부분 claim이 0건인 경우에만 부여한다. 최종 ACCEPT·Party 확정·현재 상태 복구는 별도 통합 검증 결과로 기록하며 이 baseline의 acceptance로 대신하지 않는다. 이는 SLO 달성을 뜻하지 않는다.
- `INVALID`는 실행 또는 관측 계약을 충족하지 않아 성능 비교에 쓸 수 없는 결과다. `FAILED`는 실행·관측 계약을 충족한 뒤 candidate claim 정합성 검증이 실패한 결과다. 기술 오류·timeout·matcher 조기 종료·fixture 개수 불일치·관측 누락은 `INVALID`로, 실행 완료 후 candidate claim 정합성 위반은 `FAILED`로 분류한다. `INVALID`·`FAILED` 어느 경우에도 Redis 도입 결론이나 성능 수치를 채택하지 않는다.

## 종합 정합성 gate manifest

baseline JSON과 `MATCH-01-T1`, `MATCH-01-T5`~`T7` 통합 검증 artifact는 각각 실행한 40자 `measuredGitCommitSha`를 기록한다. 종합 gate는 `docs/measurements/results/match-01/gates/`의 별도 manifest 하나가 `measuredGitCommitSha`와 필수 증거 ID별 저장소 상대 경로·각 artifact의 `gitCanonicalBlobSha256`을 기록할 때만 평가한다.

`gitCanonicalBlobSha256`은 gate를 평가하는 커밋에서 상대 경로가 가리키는 Git blob의 원본 바이트(`git rev-parse HEAD:<path>`로 blob을 정하고 `git cat-file blob <blob>`으로 읽은 바이트)를 SHA-256으로 계산한 값이다. 서로 내용이 다른 artifact가 같은 SHA-256을 기록할 필요는 없다. 필수 증거가 없거나 중복되고, artifact 안의 `measuredGitCommitSha`가 manifest와 다르거나, 경로의 실제 blob SHA-256이 manifest 값과 다르면 종합 gate를 `INVALID`로 판정한다. 결과 artifact가 자기 SHA-256을 자기 내용 안에 기록하지 않아 순환 해시를 만들지 않는다.

## 원자료 보존과 재검토

구현 뒤 각 실행 결과는 `docs/measurements/results/match-01/`에 버전 관리 JSON으로 보존한다. JSON은 실행한 40자 Git commit SHA·환경·`fixtureInputSha256`·materialized fixture manifest·warm-up 여부·각 measured round의 1,000개 candidate claim transaction latency·retry·proposal 500개/멤버 1,000개/입력 request 1,000개 claim 결과 분포·tie 순서 검증·DB 통계·lock wait 설정과 원자료·정합성 검증 결과를 포함한다. 최종 응답·Party 확정·현재 상태 복구의 통합 검증 결과는 별도 artifact로 연결한다. 결과 artifact와 별도 증거의 식별·해시·유효성 판정은 [종합 정합성 gate manifest](#종합-정합성-gate-manifest)를 따른다. 개선 전후 비교는 같은 fixture·topology·환경 profile로 한 실행 세션에서 만든 `BASELINE_ACCEPTED` 결과끼리만 수행하며, 다른 하드웨어·DB 설정 결과를 직접 순위화하지 않는다.

다음 경우에 [ADR-0063](../adr/matching/0063-match-baseline-measurement-gate.md)의 재검토 절차를 연다.

- `BASELINE_ACCEPTED` 결과에서 candidate claim transaction p95, DB lock wait 또는 retry가 서비스 목표를 정할 만큼 지속적으로 높게 나타난 경우에는 PostgreSQL 쿼리·인덱스·트랜잭션 개선을 먼저 비교하고, 해결되지 않는 근거가 있으면 Redis를 포함한 대안을 비교하는 새 ADR을 작성한다.
- `FAILED`가 실제 영속 상태의 정합성 위반을 기록한 경우에는 해당 구현을 성능 통과로 처리하지 않고 중단·되돌린다. 그 결과는 Redis를 바로 도입하는 근거가 아니라, PostgreSQL 정합성 복구안과 Redis를 포함한 대안을 비교하는 새 ADR을 열어야 하는 근거다.

fixture·runner·관측이 불완전한 `INVALID`는 재검토 gate가 아니다. 원인을 고친 뒤 같은 계약으로 다시 측정한다. `FAILED`는 정합성 위반의 원인을 확인해야 하며, 실제 영속 상태 위반이면 위 절차에 따라 중단·되돌리고 새 ADR을 연다. 어느 경우에도 새 ADR 승인 전에는 Redis business lock을 도입하지 않는다.

> 문서 관리: 소유자 `밤송이클럽 MATCHING 담당` · 최종 검증일 `2026-08-15` · 폐기 조건 `MATCH-01 후보 탐색 측정 계약이 승인된 단일 측정 정본으로 대체될 때`
