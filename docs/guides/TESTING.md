# How to 백엔드 테스트와 커버리지 검증

이 문서는 Albam Mate 백엔드의 H2 빠른 테스트, Testcontainers PostgreSQL 검증과 JaCoCo 커버리지 게이트를 실행하고 결과를 해석하는 방법을 설명한다. 자주 반복하는 태스크만 필요하면 [프로젝트 명령](../COMMANDS.md#개발-환경-확인)을 사용한다.

## 준비 사항

- 모든 백엔드 검증에는 Java 21과 저장소의 Gradle Wrapper가 필요하다.
- `test`와 `build`는 H2를 사용하므로 Docker가 필요하지 않다.
- `postgresTest`와 통합 커버리지 게이트에는 실행 가능한 Docker 환경이 필요하다.
- 테스트 배치와 작성 규칙은 [일반 테스트 작업 안내](../../src/test/AGENTS.md)를 따른다.

| 검증 범위 | Gradle 태스크 | 담당 계약 |
| --- | --- | --- |
| 빠른 반복 | `test`, `build` | 단위 테스트, Spring context, MVC, 표준 JPA와 H2로 검증 가능한 서비스 흐름 |
| PostgreSQL | `postgresTest` | Flyway, PostgreSQL 전용 SQL·타입·제약·잠금·격리·동시성 |
| 외부 fixture PostgreSQL 성능 | `postgresTest` exact selector | 저장소 밖 17만 행 fixture의 대표 검색 조합·실행 계획·page·count 시간 |
| 빠른 커버리지 | `jacocoTestCoverageVerification` | H2 `test` 결과만 사용하는 로컬 회귀 게이트 |
| 정본 커버리지 | `jacocoAllTestCoverageVerification` | `test`와 `postgresTest` 결과를 합산하는 CI 판정 게이트 |

H2와 PostgreSQL을 나누는 근거는 [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md), 커버리지 방지선의 근거는 [ADR-0017](../adr/platform/0017-test-coverage-branch-ratchet.md)이 소유한다.

## 빠른 H2 테스트 실행

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

macOS·Linux:

```sh
./gradlew test
./gradlew build
```

`build`는 컴파일·테스트와 H2 결과 기반 빠른 커버리지 게이트를 함께 실행한다. H2 통과를 PostgreSQL 전용 동작의 검증 근거로 사용하지 않는다.

## PostgreSQL 검증 실행

`postgresTest`는 Testcontainers가 관리하는 임시 PostgreSQL 18.4 컨테이너를 사용한다. 빈 데이터베이스에 Flyway 마이그레이션을 적용하고 Hibernate `ddl-auto=validate`와 PostgreSQL 전용 계약을 검증한다.

Windows PowerShell:

```powershell
.\gradlew.bat postgresTest --no-daemon --stacktrace
```

macOS·Linux:

```sh
./gradlew postgresTest --no-daemon --stacktrace
```

`GameMetadataSearchPerformancePostgresTest`는 저장소 밖 fixture와 순위 CSV를 요구한다. `issue420.fixture`가 없으면 JUnit 조건으로 건너뛰므로 기본 `postgresTest`는 실패하지 않는다. 네 입력 경로를 준비한 뒤에는 [게임 카탈로그 적재 가이드](GAME_CATALOG_IMPORT.md#17만-행-성능-fixture-계약)의 `postgresTest` exact selector 명령으로 실행한다.

[ADR-0023](../adr/platform/0023-p0-flyway-baseline-reset-player-count-stages.md)의 일회성 기준선 재생성 뒤에는 다음 규칙을 지킨다.

- 이전 V1~V3를 적용한 데이터베이스를 재사용하지 않는다.
- 로컬 데이터는 정확한 Compose 프로젝트를 확인하고 명시적으로 승인한 경우에만 `down --volumes`로 초기화한다.
- 공유·RDS 환경은 정확한 대상을 확인한 뒤 별도로 재생성한다.
- 기존 테이블을 남기고 `flyway_schema_history`만 삭제하지 않는다.

## 커버리지 게이트 실행

커버리지는 조건식의 한쪽만 실행해도 올라가는 라인 수치가 아니라 분기를 주 기준으로 판정한다. 조건문이 없는 코드의 회귀를 놓치지 않도록 전체 라인 최소선을 보조로 함께 둔다.

Docker 없이 H2 결과만 빠르게 확인한다.

Windows PowerShell:

```powershell
.\gradlew.bat jacocoTestReport jacocoTestCoverageVerification
```

macOS·Linux:

```sh
./gradlew jacocoTestReport jacocoTestCoverageVerification
```

제출 전에는 Docker 환경에서 H2와 PostgreSQL 결과를 합산하는 정본 게이트를 실행한다.

Windows PowerShell:

```powershell
.\gradlew.bat jacocoAllTestReport jacocoAllTestCoverageVerification
```

macOS·Linux:

```sh
./gradlew jacocoAllTestReport jacocoAllTestCoverageVerification
```

각 게이트는 담당 Test 태스크의 JaCoCo 실행 데이터만 사용한다. `build/jacoco`의 모든 `.exec`를 읽으면 이번에 실행하지 않은 suite의 과거 결과가 남아 테스트 변경·삭제 뒤에도 분기를 덮는 거짓 통과가 생길 수 있다.

## 결과 해석과 최소선 갱신

HTML 리포트에서 미커버 분기의 파일과 줄을 확인한다.

```text
build/reports/jacoco/test/html/index.html
build/reports/jacoco/jacocoAllTestReport/html/index.html
```

최소선은 목표치가 아니라 도입 시점의 실측값을 바닥으로 고정한 회귀 방지선이다. 올리는 변경은 그대로 반영하고, 내리는 변경은 이유를 PR에 남긴다. 패키지별 값과 전체 분기·라인 값은 [build.gradle](../../build.gradle)의 `gatedBranchCoverage`와 `applyCoverageRules`가 정본이다.

`verifyCoverageRuleTargets`는 다음 두 경우에 실패한다.

- 규칙 대상 패키지가 현재 리포트에 없다.
- 분기가 10개 이상인 패키지에 개별 최소선이 없다.

패키지를 옮기거나 새로 만들면 같은 변경에서 `gatedBranchCoverage`를 갱신하고, 새 항목은 현재 실측값을 0.01 단위로 내려 적는다. 대상 목록만 확인하려면 운영체제에 맞는 Wrapper로 `verifyCoverageRuleTargets`를 실행한다.

## CI 판정

CI는 `build`에서 H2 전용 게이트를 제외한 뒤 `postgresTest`, 합산 리포트와 정본 게이트를 하나의 흐름으로 실행한다. H2 게이트가 PostgreSQL 전용 분기를 먼저 미검증으로 판정해 정본 게이트를 가로막지 않게 하기 위해서다.

합산 리포트가 생성되면 전체 분기·라인 비율을 job summary에 남기고 HTML·XML을 `jacoco-coverage-<run attempt>` artifact로 14일간 보관한다. 게이트가 실패해도 리포트 생성 단계까지 진행됐다면 같은 artifact에서 미커버 위치를 확인한다.

## 문제 해결

- Docker 데몬이 없거나 Testcontainers가 컨테이너를 시작하지 못하면 테스트 실패가 아니라 실행 환경 제약으로 기록한다. 실행하지 못한 `postgresTest`와 정본 커버리지 범위를 보고에 명시한다.
- 커버리지 게이트가 실패하면 최소선을 먼저 내리지 않는다. HTML 리포트에서 새 미커버 분기·라인과 삭제되거나 이동한 테스트를 확인한다.
- `verifyCoverageRuleTargets`가 실패하면 오류에 표시된 패키지가 실제로 이동·삭제됐는지 또는 새로 추가됐는지 확인한 뒤 `gatedBranchCoverage`를 현재 구조에 맞춘다.
- PostgreSQL 전용 테스트를 H2에서 통과시키기 위해 운영 SQL이나 Flyway 마이그레이션을 단순화하지 않는다.

## 관련 문서

- 반복 실행 명령: [프로젝트 명령](../COMMANDS.md)
- 테스트 배치와 작성 규칙: [일반 테스트 작업 안내](../../src/test/AGENTS.md)
- PostgreSQL 테스트 전용 규칙: [PostgreSQL 테스트 작업 안내](../../src/postgresTest/AGENTS.md)
- H2와 PostgreSQL 경계: [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md)
- 커버리지 방지선: [ADR-0017](../adr/platform/0017-test-coverage-branch-ratchet.md)
