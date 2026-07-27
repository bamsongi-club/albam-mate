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
| 코드 포맷 검사 | `.\gradlew.bat spotlessCheck` | `./gradlew spotlessCheck` |
| 코드 포맷 자동 수정 | `.\gradlew.bat spotlessApply` | `./gradlew spotlessApply` |
| 커버리지 리포트 | `.\gradlew.bat jacocoTestReport` | `./gradlew jacocoTestReport` |
| 커버리지 리포트 (통합 포함) | `.\gradlew.bat jacocoAllTestReport` | `./gradlew jacocoAllTestReport` |
| 분기 커버리지 검사 | `.\gradlew.bat jacocoTestCoverageVerification` | `./gradlew jacocoTestCoverageVerification` |
| 분기 커버리지 검사 (통합 포함) | `.\gradlew.bat jacocoAllTestCoverageVerification` | `./gradlew jacocoAllTestCoverageVerification` |
| 커버리지 규칙 대상 확인 | `.\gradlew.bat verifyCoverageRuleTargets` | `./gradlew verifyCoverageRuleTargets` |

현재 저장소에는 운영용 데이터소스 연결값이 포함되어 있지 않다. `bootRun`은 PostgreSQL 연결 설정이 없으면 데이터소스 자동 설정 단계에서 실패한다. 테스트는 H2 인메모리 데이터베이스를 사용하므로 별도의 PostgreSQL 없이 `test`와 `build`를 실행할 수 있다.

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

Docker Compose가 `.env`를 해석한 정규화된 설정 모델에서 PostgreSQL 연결값을 읽어 Compose와 애플리케이션이 동일한 값을 사용하도록 `ALBAM_MATE_LOCAL_*` 환경변수로 현재 프로세스에 주입한다. 구성 JSON은 변수에만 캡처하므로 비밀번호를 터미널에 출력하지 않는다. 이후 같은 PowerShell 창에서 애플리케이션을 실행한다.

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

## PostgreSQL 마이그레이션 검증

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

게이트는 두 단계다. `jacocoTestCoverageVerification`은 H2 `test` 결과만 보므로
Docker 없이 실행되며 `check`와 `build`에 포함된다. `jacocoAllTestCoverageVerification`은
`test`와 `postgresTest`를 합산하는 정본 게이트이며 두 테스트에 모두 의존한다.

```sh
./gradlew jacocoAllTestReport jacocoAllTestCoverageVerification
```

각 태스크는 담당 Test 태스크의 exec 파일만 사용한다. `build/jacoco` 디렉터리의 exec를
모두 읽으면 이번 실행에서 돌리지 않은 suite의 이전 결과가 남아 함께 집계되고, 테스트를
바꾸거나 지워도 과거 데이터가 분기를 덮어 거짓 통과한다.

CI는 정본 게이트로 판정하고 `-x jacocoTestCoverageVerification`으로 test 전용 게이트를
제외한다. 제외하지 않으면 `build`가 `test` 결과만으로 먼저 판정해, PostgreSQL 통합
테스트가 담당하는 범위에 의존하는 변경이 정본 판정에 닿기 전에 막힌다.

최소선은 도입 시점의 실측값을 바닥으로 고정한 값이므로 목표치가 아니라 회귀
방지선이다. 올리는 변경은 그대로 반영하고, 내리는 변경은 이유를 PR에 남긴다.
대상 패키지와 값은 `build.gradle`의 `gatedBranchCoverage`가 정본이며 두 게이트가
같은 규칙을 공유한다.

`verifyCoverageRuleTargets`는 최소선 목록이 실제 패키지 구조와 어긋나는 두 경우를
실패로 만든다. 규칙 대상이 리포트에 없으면 규칙이 아무 패키지에도 적용되지 않은 채
통과하고, 분기 10개 이상인 패키지에 최소선이 없으면 전체 최소선만 적용되어 새 모듈이
낮은 커버리지로 들어와도 통과한다. 패키지를 옮기거나 새로 만들었다면 같은 변경에서
`gatedBranchCoverage`를 갱신하고, 새 항목은 실측값을 0.01 단위로 내려 적는다.

미커버 분기의 위치는 HTML 리포트에서 확인한다.

```text
build/reports/jacoco/test/html/index.html
build/reports/jacoco/jacocoAllTestReport/html/index.html
```

Docker가 없으면 정본 게이트를 실행할 수 없다. 이때는 `check`의 test 전용 게이트로
확인하고, 실행하지 못한 범위를 보고에 명시한다.

## 코드 포맷 확인

Java 포맷은 Google Java Format의 AOSP 스타일을 사용한다. 기본 Google 스타일과 달리 블록 들여쓰기가 4칸이다.

포맷 위반을 수정한 뒤에는 diff를 검토하고 `spotlessCheck`를 다시 실행한다. clone마다 한 번 필요한 pre-commit hook 활성화 절차는 [코드 포맷과 Git hook 설정](guides/CODE_FORMATTING.md)을 따른다.

## 문서 링크 확인

정본 문서는 서로를 상대 링크로 참조한다. 문서를 옮기거나 제목을 바꾼 뒤에는 링크와 앵커가 남아 있는지 확인한다.

```sh
node scripts/check-doc-links.mjs
```

추적 중인 Markdown과 아직 커밋하지 않은 Markdown을 함께 검사하고 외부 링크는 확인하지 않는다. 작업 트리에 없는 경로는 검사 원본에서 빼므로 이동·삭제를 스테이징하기 전에도 실행할 수 있다. destination을 뽑지 못한 링크는 건너뛰지 않고 `파싱 실패`로 보고한다.

검사기 자체를 고치면 회귀 테스트를 함께 실행한다.

```sh
node --test scripts/check-doc-links.test.mjs
```

두 명령을 CI의 `Docs` job이 함께 실행하므로, 문서나 검사기를 변경한 PR은 둘 다 먼저 통과시킨다.

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
