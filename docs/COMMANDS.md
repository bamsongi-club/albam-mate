# Albam Mate Commands

이 문서는 팀이 반복해서 사용하는 프로젝트 명령의 기준 문서다. 별도 안내가 없다면 모든 명령은 `albam-mate` 저장소 루트에서 실행한다.

최초 설정, 긴 실행 절차와 문제 해결은 [프로젝트 가이드](guides/README.md)에서 찾고, 이 문서에는 반복 명령과 짧은 실행 조건만 둔다.

## 개발 환경 확인

프로젝트 애플리케이션에는 Java 21이 필요하고, 문서 링크 검사와 프롬프트 기록 훅에는 Node.js 20 이상이 필요하다.

```sh
java --version
node --version
```

Gradle은 별도 설치본 대신 저장소의 Wrapper를 사용한다.

| 작업 | Windows PowerShell | macOS·Linux |
|---|---|---|
| Gradle 버전 확인 | `.\gradlew.bat --version` | `./gradlew --version` |
| 애플리케이션 실행 | `.\gradlew.bat bootRun` | `./gradlew bootRun` |
| 테스트 | `.\gradlew.bat test` | `./gradlew test` |
| 빌드 | `.\gradlew.bat build` | `./gradlew build` |
| 산출물 제거 후 빌드 | `.\gradlew.bat clean build` | `./gradlew clean build` |
| Java 컨벤션 검사 | `.\gradlew.bat conventionCheck` | `./gradlew conventionCheck` |
| 코드 포맷 검사 | `.\gradlew.bat spotlessCheck` | `./gradlew spotlessCheck` |
| 코드 포맷 자동 수정 | `.\gradlew.bat spotlessApply` | `./gradlew spotlessApply` |
| 커버리지 리포트 | `.\gradlew.bat jacocoTestReport` | `./gradlew jacocoTestReport` |
| 커버리지 리포트 (통합 포함) | `.\gradlew.bat jacocoAllTestReport` | `./gradlew jacocoAllTestReport` |
| 분기 커버리지 검사 | `.\gradlew.bat jacocoTestCoverageVerification` | `./gradlew jacocoTestCoverageVerification` |
| 분기 커버리지 검사 (통합 포함) | `.\gradlew.bat jacocoAllTestCoverageVerification` | `./gradlew jacocoAllTestCoverageVerification` |
| CI shard 커버리지 병합 | `.\gradlew.bat jacocoMergedTestReport jacocoMergedTestCoverageVerification` | `./gradlew jacocoMergedTestReport jacocoMergedTestCoverageVerification` |
| 커버리지 규칙 대상 확인 | `.\gradlew.bat verifyCoverageRuleTargets` | `./gradlew verifyCoverageRuleTargets` |
| 프론트엔드 회귀 테스트 (Vitest) | `Set-Location frontend; npm.cmd test` | `cd frontend && npm test` |

저장소에는 운영 데이터소스 연결값이 없어 PostgreSQL 설정 없는 `bootRun`은 데이터소스 자동 설정에서 실패한다. H2를 쓰는 `test`와 `build`에는 PostgreSQL이 필요 없다.

## 로컬 개발

`.env` 준비와 운영체제별 실행은 [로컬 개발 환경 실행](guides/LOCAL_DEVELOPMENT.md)을 따른다. 아래 명령은 `.env`가 준비된 뒤 반복해서 사용한다. 기본 local Compose는 프록시·Spring 두 대·PostgreSQL·Redis를 함께 실행한다.

현재 소스로 프록시, Spring 애플리케이션 두 대, PostgreSQL과 Redis를 함께 빌드·실행하고 상태를 확인한다. 이 `local` Compose가 기본 로컬·데모·P1 검증 환경이며 단일 Spring 실행은 검증 경계로 사용하지 않는다.

```sh
docker compose --env-file .env -f compose.local.yml up -d --build --wait
docker compose --env-file .env -f compose.local.yml ps
```

최근 로그를 확인하고 전체 스택을 내린다. `down`은 named volume의 PostgreSQL 데이터를 보존한다.

```sh
docker compose --env-file .env -f compose.local.yml logs --tail 200
docker compose --env-file .env -f compose.local.yml down
```

필요하면 애플리케이션 없이 PostgreSQL과 Redis 의존성만 시작해 DB·Redis 클라이언트 작업을 확인하고, 작업을 마친 뒤 중지한다.

```sh
docker compose --env-file .env -f compose.local.yml up -d --wait postgres redis
docker compose --env-file .env -f compose.local.yml ps postgres redis
docker compose --env-file .env -f compose.local.yml stop postgres redis
```

로컬 PostgreSQL 데이터를 명시적으로 초기화할 때만 named volume까지 삭제한다.

```sh
docker compose --env-file .env -f compose.local.yml down --volumes
```

Compose가 실행 중인 상태에서 프록시를 통과하는 동일 세션 검증은 별도 명령으로 실행한다. 일반 `postgresTest`는 외부 Compose 의존성을 만들지 않도록 이 테스트를 건너뛴다.

```sh
JAVA_TOOL_OPTIONS='-Dissue471.localProxy=true' ./gradlew postgresTest --tests "cloud.bamsongi.albammate.global.security.session.LocalMultiProxyRuntimePostgresTest" --rerun --fail-fast --no-daemon --stacktrace
```

## 운영 Compose

호스트 준비, 이미지 게시, 배포·롤백 의미와 Docker 계약 검증은 [P1 AWS 다중 인스턴스 실행안](guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md)을 따른다. 아래 명령은 App1의 `/etc/albam-mate/app1.env`와 TLS 인증서가 준비된 운영 호스트에서 반복해서 사용한다.

비밀값을 출력하지 않고 설정을 검증한 뒤 두 이미지를 받고 health check가 통과할 때까지 기다린다.

```sh
docker compose --env-file /etc/albam-mate/app1.env -f compose.production.yml config --quiet
docker compose --env-file /etc/albam-mate/app1.env -f compose.production.yml up -d --wait
```

서비스 상태, 최근 로그와 실제 이미지 태그를 확인한다.

```sh
docker compose --env-file /etc/albam-mate/app1.env -f compose.production.yml ps
docker compose --env-file /etc/albam-mate/app1.env -f compose.production.yml logs --tail 200
docker compose --env-file /etc/albam-mate/app1.env -f compose.production.yml images
```

컨테이너만 내린다. 외부 RDS의 데이터는 이 명령의 대상이 아니다.

```sh
docker compose --env-file /etc/albam-mate/app1.env -f compose.production.yml down
```

## 인증·알림 계약 검증과 용량 측정

시나리오와 fixture는 [loadtests README](../loadtests/README.md), AWS 실행·로그 수집은 `albam-mate-infra` 저장소의 `run.sh loadtest`를 따른다. 팀이 합의한 하나의 `RELEASE_SHA`에 앱 이미지와 이 디렉터리를 함께 고정한 뒤 실행한다.

아래는 계약 검증이며 모두 VU 1로 고정된다.

```sh
AUTH_CASE=correct ./run.sh loadtest auth-login-contract
RATE_LIMIT_CASE=signup ./run.sh loadtest auth-rate-limit-contract
NOTIFICATION_CONTRACT_EVENT_COUNT=10 ./run.sh loadtest notification-delivery-contract
```

용량 측정 시나리오는 인증 요청 제한 상향이 필요해 별도 실행 가드를 둔다. 대상 시나리오와 실행 조건은 [loadtests README의 용량 측정 공통 가드](../loadtests/README.md#용량-측정-공통-가드)를 따른다.

이 명령은 `albam-mate-infra` 저장소에서 실행한다. 실제 실행 전에 인프라 생성·배포 승인이 별도로 필요하며, 소셜 OAuth 부하는 1단계 범위에 포함하지 않는다.

## PostgreSQL 마이그레이션 검증

`postgresTest`는 Testcontainers가 관리하는 임시 PostgreSQL 18.4 컨테이너에서 Flyway 마이그레이션, Hibernate 스키마 검증과 PostgreSQL 전용 계약을 확인한다. 외부 fixture가 필요한 17만 행 성능 클래스도 이 task를 사용하며, `issue420.fixture` 시스템 속성이 없으면 JUnit 조건으로 건너뛴다. fixture를 준비한 정확한 성능 실행 명령과 측정 필드 의미는 [게임 카탈로그 적재 가이드](guides/GAME_CATALOG_IMPORT.md#17만-행-게임-기본-정보성능-fixture-계약)를 따른다. 데이터베이스 재생성 규칙과 실패 해석은 [백엔드 테스트와 커버리지 검증](guides/TESTING.md#postgresql-검증-실행)을 따른다.

Windows PowerShell:

```powershell
.\gradlew.bat postgresTest --no-daemon --stacktrace
```

macOS·Linux:

```sh
./gradlew postgresTest --no-daemon --stacktrace
```

외부 fixture를 사용하는 게임 메타데이터 성능 측정은 [게임 카탈로그 적재 가이드](guides/GAME_CATALOG_IMPORT.md#17만-행-게임-기본-정보성능-fixture-계약)의 경로·checksum 조건을 준비한 뒤 `postgresTest`의 exact selector로 실행한다. fixture가 없는 기본 `postgresTest`에서는 이 성능 클래스가 건너뛰며, CI 합산 커버리지의 입력을 만들지 않는다.

### ROOM-09c 현행 일괄 처리 기준선 측정

ROOM-09c 기준선의 측정 profile·원자료 필드·결과 해석은 [현행 일괄 처리 기준선 측정](measurements/room-09-bounded-processing-baseline.md)을 따른다. 기본 `postgresTest`는 WAITING 없는 소형 `100/20` fixture의 계약과 1회 warm-up·5회 실측을 실행한다. 중형 `10,000/2,000`과 대형 `50,000/10,000`은 명시적인 시스템 속성이 있을 때만 실행한다.

Windows PowerShell:

```powershell
docker version
$hadJavaToolOptions = Test-Path Env:JAVA_TOOL_OPTIONS
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        '-Dissue383.measurement=true'
    } else {
        "$previousJavaToolOptions -Dissue383.measurement=true".Trim()
    }
    .\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionBaselineMeasurementPostgresTest.승인_규모_기준선을_측정한다" --rerun --fail-fast
} finally {
    if ($hadJavaToolOptions) {
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    } else {
        Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    }
}
```

macOS·Linux:

```sh
docker version
JAVA_TOOL_OPTIONS='-Dissue383.measurement=true' ./gradlew postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionBaselineMeasurementPostgresTest.승인_규모_기준선을_측정한다" --rerun --fail-fast
```

성능 합격선이나 운영값은 이 측정에서 임의로 정하지 않는다. 실행 뒤 `build/reports/measurements/room-09c-{small|medium|large}.json`의 고정 시각·seed·data identifier·후보·결과·환경·`pg_stat_statements` 원자료와 문서의 재현 명령을 함께 확인한다.

### ROOM-09d 측정 보고 단계

ROOM-09d는 측정과 보고를 나눈다. 대형 한 조합이 수십 분이라, 재현 명령·대비 표·SHA-256 같은 파생물이 보존 원자료와 어긋날 때 측정을 다시 돌려 맞출 수 없기 때문이다. 파생물은 보존 원자료만 읽어 다시 만든다.

```bash
node scripts/room09-measurement-report.mjs --check
```

```bash
node scripts/room09-measurement-report.mjs --write
```

`--check`는 아무것도 쓰지 않고 어긋남만 보고하며 CI가 같은 명령을 실행한다. `--write`는 원자료를 정본으로 파생물을 다시 만들고, 재현 메타데이터를 뺀 나머지 값이 모두 같은지 확인한 뒤에만 파일을 쓴다. 새로 측정했다면 `build/reports/measurements/`의 JSON을 `docs/measurements/results/room-09d/`로 복사한 뒤 `--write`를 실행한다. 측정 profile과 결과 해석은 [현행 일괄 처리 기준선 측정](measurements/room-09-bounded-processing-baseline.md#보고-단계)을 따른다.

## 분기 커버리지 확인

H2 전용 빠른 게이트와 H2·PostgreSQL 합산 정본 게이트의 의미, 최소선 갱신과 CI 결과 해석은 [백엔드 테스트와 커버리지 검증](guides/TESTING.md#커버리지-게이트-실행)을 따른다.

| 범위 | Windows PowerShell | macOS·Linux |
| --- | --- | --- |
| H2 리포트와 빠른 게이트 | `.\gradlew.bat jacocoTestReport jacocoTestCoverageVerification` | `./gradlew jacocoTestReport jacocoTestCoverageVerification` |
| H2·PostgreSQL 합산 정본 게이트 | `.\gradlew.bat jacocoAllTestReport jacocoAllTestCoverageVerification` | `./gradlew jacocoAllTestReport jacocoAllTestCoverageVerification` |
| 패키지 규칙 대상 확인 | `.\gradlew.bat verifyCoverageRuleTargets` | `./gradlew verifyCoverageRuleTargets` |

미커버 분기의 위치는 HTML 리포트에서 확인한다.

```text
build/reports/jacoco/test/html/index.html
build/reports/jacoco/jacocoAllTestReport/html/index.html
```

## Java 컨벤션 확인

Java 포맷은 Spotless가 실행하는 네이버 Java 코딩 컨벤션의 Eclipse Formatter 프로필을 사용한다. 블록 들여쓰기는 너비 4의 탭, 한 줄 최대 길이는 120자다.

`conventionCheck`는 Spotless와 main, test, postgresTest source set의 Checkstyle을 함께 실행한다. Checkstyle은 프로젝트에서 선택한 네이밍·선언·중괄호 규칙만 검사하며 세부 목록과 예외는 [`config/checkstyle`](../config/checkstyle/README.md)을 따른다.

포맷 위반을 수정한 뒤에는 diff를 검토하고 `conventionCheck`를 다시 실행한다. clone마다 한 번 필요한 pre-commit hook 활성화 절차는 [Java 컨벤션과 Git hook 설정](guides/CODE_FORMATTING.md)을 따른다.

## 문서 링크 확인

정본 문서는 서로를 상대 링크로 참조한다. 문서를 옮기거나 제목을 바꾼 뒤에는 링크와 앵커가 남아 있는지 확인한다.

```sh
node scripts/check-doc-links.mjs
```

추적·미커밋 Markdown을 함께 검사하되 외부 링크는 제외한다. 작업 트리에 없는 경로도 검사 원본에서 빼므로 이동·삭제를 스테이징하기 전에 실행할 수 있다. destination을 못 찾은 링크는 건너뛰지 않고 `파싱 실패`로 보고한다.

검사기 자체를 고치면 회귀 테스트를 함께 실행한다.

```sh
node --test scripts/check-doc-links.test.mjs
```

두 명령을 CI의 `Docs` job이 함께 실행하므로, 문서나 검사기를 변경한 PR은 둘 다 먼저 통과시킨다.

## 백엔드 전달 테스트 계약

구현 packet과 test manifest는 작업 트리 밖의 임시 JSON으로 유지한다. packet v3는 승인된 자연어 T-ID를 보존하고, manifest는 각 T-ID를 실제 source·Gradle task·wildcard 없는 exact selector에만 연결한다. manifest 검증은 source 존재와 source set 일치에 더해 selector가 가리키는 메서드가 그 source에 실제로 선언됐는지까지 확인한다. Red 상태와 실행 결과는 manifest가 아니라 구현자의 텍스트 보고로 확인한다.

```sh
node scripts/validate-packet.mjs <packet.json>
node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree>
```

Red에서는 선택한 모든 실패를 관찰할 수 있도록 `--fail-fast`를 쓰지 않는다. Green과 full-delivery의 고정 head 최종 재실행에서만 `--fail-fast`를 사용한다. H2와 PostgreSQL selector는 task별 한 명령에 묶고 `--rerun`으로 정확히 실행한다. `--rerun`이 없으면 소스가 그대로일 때 Gradle이 Test task를 `UP-TO-DATE`로 건너뛰고 종료 코드 0을 내므로, 테스트를 한 건도 돌리지 않은 실행이 통과처럼 보인다. PostgreSQL 실행 전에는 `docker version`으로 daemon 접근을 확인한다.

selector 문법은 [Gradle 테스트 필터링](https://docs.gradle.org/current/userguide/java_testing.html)과 [TestFilter](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/testing/TestFilter.html)를 따른다.

```powershell
# Red: 첫 실패에서 중단하지 않는다.
.\gradlew.bat test `
  --tests "cloud.bamsongi.FirstTest.첫_동작" `
  --tests "cloud.bamsongi.SecondTest.둘째_동작" `
  --rerun

# Green: H2 selector를 한 번에 확인한다.
.\gradlew.bat test `
  --tests "cloud.bamsongi.FirstTest.첫_동작" `
  --tests "cloud.bamsongi.SecondTest.둘째_동작" `
  --rerun --fail-fast

# PostgreSQL Green: docker version 성공 뒤 별도 task로 확인한다.
.\gradlew.bat postgresTest `
  --tests "cloud.bamsongi.ExamplePostgresTest.경계를_검증한다" `
  --rerun --fail-fast
```

일반 TDD 루프에서는 [기본 Gradle Daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html)을 사용하고 `--no-daemon`, `--rerun-tasks`, 전체 build와 전체 coverage를 실행하지 않는다. 변경한 생산 패키지가 `gatedBranchCoverage`에 없을 때만 다음 조건부 게이트를 완료 기준에 추가한다.

```powershell
.\gradlew.bat jacocoTestReport verifyCoverageRuleTargets
```

이 명령은 `jacocoTestReport`가 `test`에 의존하므로 전체 H2 test 1회와 커버리지 구조 검사를 포함한다. 기준 실측은 약 70초이며 머신 상태에 따라 더 걸릴 수 있으므로 짧은 정적 검사로 취급하지 않는다. 로컬은 targeted 테스트와 이 조건부 전체 H2 게이트까지 책임지고, 기존 래칫 비율 회귀·PostgreSQL 합산 coverage·전체 회귀는 GitHub CI가 판정한다.

`build.gradle`의 `gatedBranchCoverage`를 바꿨다면 다음 검사를 통과시킨다. 아직 커밋하지 않은 변경은 인자 없이, 커밋한 뒤 고정한 head를 검증할 때는 `--base`로 고정한 base를 넘긴다. 인자 없이 실행하면 커밋된 변경이 빈 diff가 되어 검사가 아무것도 보지 못한다.

```sh
node scripts/validate-coverage-ratchet.mjs
node scripts/validate-coverage-ratchet.mjs --base <고정한-base-sha>
```

manifest 검증도 같다. 커밋 뒤 고정한 head를 검증할 때는 `--base`를 넘긴다. 깨끗한 worktree에서 `--base` 없이 실행하면 감사할 변경 경로가 비어 앞선 커밋의 범위 밖 변경을 놓친다.

```sh
node scripts/validate-backend-test-manifest.mjs --packet <packet.json> --manifest <manifest.json> --worktree <worktree> --base <고정한-base-sha>
```

전달 종료 시 임시 packet과 manifest는 삭제하지 않고 Private Brain의 전달 아카이브로 옮긴다. 아카이브 경로는 Private Brain 정본을 따르고 공개 파일에 적지 않으며, 이관 결과는 `archiveId`와 receipt의 packet·manifest SHA-256으로 확인한다.

## 게임 카탈로그 검수

BGG 기준 CSV와 팀 검수 JSON의 매핑·중복·필수값·품질 경고를 확인한다. 출처 manifest가 없거나 검수 상태가 승인되지 않으면 보고서만 만들고 적재 SQL은 생성하지 않는다. 메커니즘 승인 배치는 `mechanismCatalog`이 있는 manifest를 전달한다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --out build/game-catalog/draft
```

승인된 manifest로 적재 산출물을 만들 때는 다음처럼 실행한다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --manifest /path/to/approved-manifest.json \
  --out build/game-catalog/approved
```

메커니즘 승인 배치는 `upsert-games.sql`을 먼저 실행하고 `upsert-game-mechanisms.sql`을 이어서 실행한다. 현재 Issue #351 배치는 실행 후 공개 메커니즘 189개와 관계 13,263개를 확인한다. 상세 절차는 [게임 카탈로그 검수·적재](guides/GAME_CATALOG_IMPORT.md)를 따른다.

## 프롬프트 기록 확인

팀원별 최초 환경 설정과 문제 해결은 [How to 팀 프롬프트 기록 환경 설정](guides/PROMPT_LOGGING.md)을 따른다.

현재 프로세스가 팀원 이름을 인식하는지 확인한다.

Windows PowerShell:

```powershell
$env:BAMSONGI_MEMBER
```

macOS·Linux:

```sh
echo "$BAMSONGI_MEMBER"
```

Codex와 Claude Code에서는 각각 `/hooks`를 실행해 프로젝트의 `UserPromptSubmit` 훅이 보이는지 확인한다. Codex는 훅을 처음 적용하거나 변경한 뒤 다시 신뢰해야 한다.

프롬프트 로거가 직접 입력만 저장하고 내부 추천·서브에이전트 프롬프트를 제외하는지 확인한다.

```sh
node --test .bamsongi/hooks/user-prompt.test.mjs
```

저장된 프롬프트를 확인한다.

Windows PowerShell:

```powershell
Get-ChildItem `
  -LiteralPath "..\bamsongi-brain\prompts\$env:BAMSONGI_MEMBER" `
  -Filter "*.md"
```

macOS·Linux:

```sh
find "../bamsongi-brain/prompts/$BAMSONGI_MEMBER" \
  -type f \
  -name '????-??-??.md'
```

## 명령 관리 원칙

- 저장소에서 실제로 사용할 수 있는 명령만 기록한다.
- 팀이 반복해서 실행하는 짧은 명령은 이 파일에 추가한다.
- 최초 설치, 운영체제별 설정, 긴 실행 절차와 문제 해결은 `docs/guides/`에 분리하고 여기에는 링크와 반복 명령만 둔다.
- 스크립트나 Gradle task가 변경되면 같은 변경에서 이 문서도 함께 갱신한다.
