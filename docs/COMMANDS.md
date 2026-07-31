# Albam Mate Commands

이 문서는 팀이 반복해서 사용하는 프로젝트 명령의 기준 문서다. 별도 안내가 없다면 모든 명령은 `albam-mate` 저장소 루트에서 실행한다.

일회성 설정과 긴 문제 해결 절차는 이 파일에 복제하지 않고 관련 가이드로 연결한다.

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
| 커버리지 규칙 대상 확인 | `.\gradlew.bat verifyCoverageRuleTargets` | `./gradlew verifyCoverageRuleTargets` |

저장소에는 운영 데이터소스 연결값이 없어 PostgreSQL 설정 없는 `bootRun`은 데이터소스 자동 설정에서 실패한다. H2를 쓰는 `test`와 `build`에는 PostgreSQL이 필요 없다.

## 로컬 PostgreSQL 개발 환경

### macOS·Linux

저장소 루트에서 예시 환경 파일을 복사한다. `.env`는 로컬 연결값을 담으므로 Git에 포함하지 않는다.

```sh
cp -n .env.example .env
```

PostgreSQL을 시작하고 health check 결과를 확인한다.

```sh
docker compose -f compose.local.yml up -d
docker compose -f compose.local.yml ps
```

애플리케이션은 `local` 프로필을 명시하고 `.env`의 로컬 DB 변수를 프로세스에 주입해 실행한다. `.env`에는 프로필 활성화 값을 넣지 않아 H2 `test`와 PostgreSQL `postgresTest` 실행에 영향을 주지 않는다.

```sh
set -a
. ./.env
set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

local 프로필은 식별 가능한 게임 중심·사람 중심 시드 모임을 각각 30개 준비한다.
재기동하면 같은 시드 모임만 미래 시각·공개 가능 상태로 갱신하고 수동 사용자·모임·참여 데이터는 보존한다.

애플리케이션을 종료한 뒤 PostgreSQL 컨테이너만 중지한다. named volume의 개발 데이터는 유지된다.

```sh
docker compose -f compose.local.yml down
```

로컬 데이터를 명시적으로 초기화할 때만 volume까지 삭제한다.

```sh
docker compose -f compose.local.yml down --volumes
```

### Windows PowerShell

저장소 루트에서 `.env`가 없을 때만 예시 환경 파일을 복사한다. 이미 있는 `.env`는 덮어쓰지 않는다.

```powershell
if (-not (Test-Path -LiteralPath .env)) {
  Copy-Item -LiteralPath .env.example -Destination .env
}
```

PostgreSQL을 시작하고 health check 결과를 확인한다.

```powershell
docker compose --env-file .env -f compose.local.yml up -d
docker compose --env-file .env -f compose.local.yml ps
```

Docker Compose의 정규화 설정에서 PostgreSQL 연결값을 읽어 현재 프로세스의 `ALBAM_MATE_LOCAL_*` 환경변수로 주입한다. 구성 JSON은 변수에만 캡처해 비밀번호를 출력하지 않고, 같은 PowerShell 창에서 애플리케이션을 실행한다.

```powershell
$composeConfigLines = @(
  docker compose --env-file .env -f compose.local.yml config --format json
)
if ($LASTEXITCODE -ne 0) {
  throw "docker compose config failed with exit code $LASTEXITCODE"
}
$composeConfig = ConvertFrom-Json -InputObject ($composeConfigLines -join [Environment]::NewLine)
$postgres = $composeConfig.services.postgres
$postgresPort = $postgres.ports[0]

$environmentValues = [ordered]@{
  ALBAM_MATE_LOCAL_DB_HOST = [string]$postgresPort.host_ip
  ALBAM_MATE_LOCAL_DB_PORT = [string]$postgresPort.published
  ALBAM_MATE_LOCAL_DB_NAME = [string]$postgres.environment.POSTGRES_DB
  ALBAM_MATE_LOCAL_DB_USER = [string]$postgres.environment.POSTGRES_USER
  ALBAM_MATE_LOCAL_DB_PASSWORD = [string]$postgres.environment.POSTGRES_PASSWORD
}
$missingEnvironmentKeys = @(
  foreach ($entry in $environmentValues.GetEnumerator()) {
    if ([string]::IsNullOrEmpty($entry.Value)) {
      $entry.Key
    }
  }
)
if ($missingEnvironmentKeys.Count -gt 0) {
  throw "Missing required ALBAM environment variables: $($missingEnvironmentKeys -join ', ')"
}
foreach ($entry in $environmentValues.GetEnumerator()) {
  [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process)
}

.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

애플리케이션을 `Ctrl+C`로 종료한 뒤 PostgreSQL 컨테이너만 중지한다. named volume의 개발 데이터는 유지된다.

```powershell
docker compose --env-file .env -f compose.local.yml down
```

로컬 데이터를 명시적으로 초기화할 때만 volume까지 삭제한다.

```powershell
docker compose --env-file .env -f compose.local.yml down --volumes
```

## 운영 Compose

운영 호스트의 최초 준비와 보안 기준은 [P0 AWS 운영 인프라 기준](guides/AWS_P0_INFRASTRUCTURE.md#운영-compose-준비와-실행)을 따른다. 실제 비밀값은 저장소 밖 `/etc/albam-mate/production.env`, TLS 인증서는 `/etc/albam-mate/tls`, RDS CA 번들은 `/etc/albam-mate/rds-ca-bundle.pem`에 둔다.

배포 파이프라인은 백엔드와 웹 이미지를 같은 40자리 Git SHA 태그로 private registry에 게시한다. 웹 운영 이미지는 TLS 설정을 포함하는 `frontend/Dockerfile.production`으로 빌드한다. 운영 호스트의 Compose에는 source build fallback이 없다.

`ALBAM_MATE_RELEASE`에는 40자리 소문자 Git SHA만 허용된다. `latest`, 축약 SHA 또는 길이가 다른 값을 넣으면 두 운영 이미지의 진입점이 시작을 거부하고 `up --wait`가 실패한다.

```sh
export ALBAM_MATE_RELEASE="$(git rev-parse HEAD)"
docker buildx build --platform linux/arm64 --tag "${ALBAM_MATE_IMAGE_NAMESPACE}/backend:${ALBAM_MATE_RELEASE}" --push .
docker buildx build --platform linux/arm64 --file frontend/Dockerfile.production --tag "${ALBAM_MATE_IMAGE_NAMESPACE}/web:${ALBAM_MATE_RELEASE}" --push frontend
```

초기 준비가 끝난 운영 호스트에서는 다음 한 명령으로 두 이미지를 받고, 백엔드와 HTTPS 웹 프록시를 시작하고, 두 health check가 통과할 때까지 기다린다.

```sh
docker compose --env-file /etc/albam-mate/production.env -f compose.production.yml up -d --wait
```

환경 파일 내용을 출력하지 않고 Compose 설정을 검증하고, 실행 상태와 최근 로그를 확인한다.

```sh
docker compose --env-file /etc/albam-mate/production.env -f compose.production.yml config --quiet
docker compose --env-file /etc/albam-mate/production.env -f compose.production.yml ps
docker compose --env-file /etc/albam-mate/production.env -f compose.production.yml logs --tail 200
```

롤백은 `production.env`의 `ALBAM_MATE_RELEASE`를 이전에 검증된 Git SHA로 바꾸고 같은 `up -d --wait` 명령을 다시 실행한다. 같은 릴리스 값은 두 서비스의 목표 이미지 태그를 하나의 Compose 설정으로 묶을 뿐이며, `up -d --wait`는 변경된 서비스 컨테이너를 각각 중지·재생성한 뒤 실행·health 상태만 기다리는 명령이라 원자적 전환이나 실패 시 자동 복구를 보장하지 않는다. 예를 들어 spring 재생성 뒤 web 재생성이 실패하면 새 spring과 이전 web이 함께 남을 수 있다. 명령 실행 뒤에는 서비스별 실제 이미지를 확인한다.

```sh
docker compose --env-file /etc/albam-mate/production.env -f compose.production.yml images
```

목표 릴리스와 다른 서비스가 있으면 원인을 해소하거나 `ALBAM_MATE_RELEASE`를 다시 이전 검증된 Git SHA로 맞춘 뒤 같은 `up -d --wait` 명령을 재실행해 두 서비스를 같은 릴리스로 맞춘다.

운영 Compose에는 PostgreSQL과 더미 데이터 적재 작업이 없다. RDS의 게임 카탈로그는 재기동해도 유지되며, 최초 2,000개 카탈로그 적재·교체는 [게임 카탈로그 검수·적재](guides/GAME_CATALOG_IMPORT.md)의 승인된 `UPSERT` 절차로 한 번 수행한다. 전달받은 `games.json`이나 `games.sql`을 컨테이너 시작 때마다 자동 실행하지 않는다.

컨테이너만 내릴 때는 다음 명령을 사용한다. 외부 RDS의 데이터는 이 명령의 대상이 아니다.

```sh
docker compose --env-file /etc/albam-mate/production.env -f compose.production.yml down
```

Docker가 실행 가능한 검증 환경에서는 승인된 T1~T8을 각각 같은 검증기로 재현한다. 각 실행은 고유한 테스트 컨테이너·네트워크·임시 디렉터리만 만들고 종료 시 정리하며, 기존 로컬 Compose 프로젝트와 볼륨은 변경하지 않는다.

```sh
for test_id in T1 T2 T3 T4 T5 T6 T7 T8; do
  node scripts/verify-docker-deployment.mjs "$test_id" || exit 1
done
```

Windows PowerShell:

```powershell
foreach ($testId in 1..8) {
  node scripts/verify-docker-deployment.mjs "T$testId"
  if ($LASTEXITCODE -ne 0) {
    throw "Docker deployment contract failed: T$testId"
  }
}
```

## PostgreSQL 마이그레이션 검증

[ADR-0023](adr/platform/0023-p0-flyway-baseline-reset-player-count-stages.md)의 일회성 기준선 재생성 뒤에는 다음 규칙을 지킨다.

- 이전 V1~V3를 적용한 데이터베이스를 재사용하지 않는다.
- 로컬 데이터는 명시적으로 승인한 경우에만 위의 `down --volumes` 명령으로 초기화한다.
- 공유·RDS 환경은 정확한 대상을 확인한 뒤 별도로 재생성한다.
- 기존 테이블을 남기고 `flyway_schema_history`만 삭제하면 안 된다.

`postgresTest`는 Testcontainers가 PostgreSQL 18.4 컨테이너(`postgres:18.4`)를
일회성으로 시작해 빈 데이터베이스에 Flyway 마이그레이션과 Hibernate
`ddl-auto=validate`를 적용한다. H2 기반 `test`와 별도 태스크이므로 일반적인
`test`·`build` 실행에는 Docker가 필요하지 않다.

```sh
./gradlew postgresTest --no-daemon --stacktrace
```

Docker 데몬이 없거나 Testcontainers가 컨테이너를 시작하지 못하면 테스트
결과가 아니라 실행 환경 제약으로 기록한다. CI에서는 `build` 뒤에
`postgresTest`를 명시적으로 실행한다.

## 분기 커버리지 확인

커버리지는 라인이 아니라 분기를 기준으로 본다. 라인 커버리지는 조건식의 한쪽만
실행해도 올라가므로 경계 조건과 예외 경로가 검증되지 않은 채 높게 나온다.

`jacocoTestCoverageVerification`은 H2 `test`만 보는 Docker 없는 `check`·`build` 게이트다.
`jacocoAllTestCoverageVerification`은 `test`·`postgresTest`를 합산하고 둘에 의존하는 정본 게이트다.

```sh
./gradlew jacocoAllTestReport jacocoAllTestCoverageVerification
```

각 게이트는 담당 Test 태스크의 exec만 사용한다. `build/jacoco`의 exec를 모두 읽으면 실행하지 않은 suite의 이전 결과가 테스트 변경·삭제 뒤에도 분기를 덮어 거짓 통과한다.

CI는 정본 게이트로 판정하며 `-x jacocoTestCoverageVerification`으로 test 전용 게이트를 제외한다. 그렇지 않으면 `build`가 H2 결과로 먼저 판정해 PostgreSQL 검증 범위의 변경을 정본 판정 전에 막는다.

최소선은 도입 시점의 실측값을 바닥으로 고정한 값이므로 목표치가 아니라 회귀
방지선이다. 올리는 변경은 그대로 반영하고, 내리는 변경은 이유를 PR에 남긴다.
대상 패키지와 값은 `build.gradle`의 `gatedBranchCoverage`가 정본이며 두 게이트가
같은 규칙을 공유한다.

분기 최소선과 함께 전체 라인 최소선을 보조로 둔다. 분기 커버리지는 조건문이 없는
코드를 세지 않으므로, 조건문 없는 서비스·매핑 코드가 테스트 없이 들어오면 분기
규칙만으로는 잡히지 않는다. 라인 최소선은 전체에만 두며 패키지 단위로는 두지 않는다.

이 게이트는 미검증 코드가 없다는 보장이 아니라 검증 수준이 내려가지 않는다는 보장이다.
비율이 최소선 이상으로 유지되는 범위의 미검증 추가는 통과한다.

`verifyCoverageRuleTargets`는 규칙 대상이 리포트에 없거나 분기 10개 이상인 패키지에 최소선이 없으면 실패한다. 두 게이트가 의존하므로 단독 실행 때도 검사된다. 패키지를 옮기거나 만들면 같은 변경에서 `gatedBranchCoverage`를 갱신하고 새 항목은 실측값을 0.01 단위로 내려 적는다.

미커버 분기의 위치는 HTML 리포트에서 확인한다.

```text
build/reports/jacoco/test/html/index.html
build/reports/jacoco/jacocoAllTestReport/html/index.html
```

CI는 합산 리포트의 전체 분기·라인 비율을 job summary에 남기고, HTML과 XML을
`jacoco-coverage-<run attempt>` artifact로 14일간 보관한다. 게이트가 실패했을 때도
리포트가 생성된 단계까지 진행됐다면 같은 artifact에서 미커버 위치를 확인한다.

Docker가 없으면 정본 게이트를 실행할 수 없다. 이때는 `check`의 test 전용 게이트로
확인하고, 실행하지 못한 범위를 보고에 명시한다.

## Java 컨벤션 확인

Java 포맷은 Spotless가 실행하는 네이버 Java 코딩 컨벤션의 Eclipse Formatter
프로필을 사용한다. 블록 들여쓰기는 너비 4의 탭, 한 줄 최대 길이는 120자다.

`conventionCheck`는 Spotless와 main, test, postgresTest source set의 Checkstyle을
함께 실행한다. Checkstyle은 프로젝트에서 선택한 네이밍·선언·중괄호 규칙만
검사하며 세부 목록과 예외는 [`config/checkstyle`](../config/checkstyle/README.md)을
따른다.

포맷 위반을 수정한 뒤에는 diff를 검토하고 `conventionCheck`를 다시 실행한다.
clone마다 한 번 필요한 pre-commit hook 활성화 절차는
[Java 컨벤션과 Git hook 설정](guides/CODE_FORMATTING.md)을 따른다.

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

## 게임 카탈로그 검수

BGG 기준 CSV와 팀 검수 JSON의 매핑·중복·필수값·품질 경고를 확인한다.
출처 manifest가 없거나 검수 상태가 승인되지 않으면 보고서만 만들고 적재 SQL은
생성하지 않는다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --out build/game-catalog/draft
```

테스트와 승인 후 적재 절차는 [게임 카탈로그 검수·적재](guides/GAME_CATALOG_IMPORT.md)를 따른다.

## 프롬프트 기록 확인

팀원별 최초 환경 설정은 [How to 팀 프롬프트 기록 환경 설정](guides/PROMPT_LOGGING.md)을 따른다.

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

프롬프트 로거의 저장·필터링 동작을 확인한다.

```sh
node --test .bamsongi/hooks/user-prompt.test.mjs
```

이 테스트는 직접 입력한 프롬프트만 저장하고 Codex 내부 추천 및 서브에이전트 프롬프트는 제외하는지 확인한다.

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
- 팀이 반복해서 실행하는 명령은 이 파일에 추가한다.
- 최초 설치, 운영체제별 설정, 문제 해결처럼 설명이 긴 절차는 `docs/guides/`에 분리하고 여기에는 링크와 반복 확인 명령만 둔다.
- 스크립트나 Gradle task가 변경되면 같은 변경에서 이 문서도 함께 갱신한다.
