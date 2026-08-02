# How to 로컬 개발 환경 실행

이 문서는 `compose.local.yml`로 Albam Mate를 로컬에서 실행하는 두 가지 방법을 설명한다. 자주 반복하는 짧은 명령만 필요하면 [프로젝트 명령](../COMMANDS.md#로컬-개발)을 사용한다.

## 준비 사항

- 전체 스택 실행에는 Docker Engine과 Docker Compose가 필요하다.
- Spring을 호스트에서 실행하려면 Java 21이 필요하다.
- 프론트엔드를 호스트에서 실행하려면 [프론트엔드 작업 안내](../../frontend/AGENTS.md#실행과-산출물)의 Node.js 버전과 명령을 따른다.
- 모든 명령은 저장소 루트에서 실행한다.

`compose.local.yml`은 다음 두 방식을 지원한다.

| 방식 | 실행 위치 | 적합한 작업 |
| --- | --- | --- |
| 전체 스택 | PostgreSQL, Spring, 웹을 모두 컨테이너에서 실행 | 현재 소스로 화면과 API를 한 번에 확인 |
| 호스트 개발 | PostgreSQL만 컨테이너에서 실행하고 Spring 또는 웹은 호스트에서 실행 | 코드 변경을 빠르게 반복 |

두 방식은 기본적으로 같은 포트를 사용하므로 동시에 실행하지 않는다.

## 환경 파일 준비

`.env`는 로컬 연결값과 공개 포트를 담으며 Git에 포함하지 않는다. 저장소 루트에 `.env`가 없을 때만 예시 파일을 복사한다.

macOS·Linux:

```sh
cp -n .env.example .env
```

Windows PowerShell:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
  Copy-Item -LiteralPath .env.example -Destination .env
}
```

필수 환경변수가 채워졌는지 값을 출력하지 않고 확인한다.

```sh
docker compose --env-file .env -f compose.local.yml config --quiet
```

## 전체 스택 실행

PostgreSQL, Spring, 웹 이미지를 현재 소스에서 빌드하고 모든 health check가 통과할 때까지 기다린다.

```sh
docker compose --env-file .env -f compose.local.yml up -d --build --wait
docker compose --env-file .env -f compose.local.yml ps
```

기본 웹 주소는 `http://localhost:5173`이고 Spring API의 기본 직접 접근 주소는 `http://localhost:8080/api`다. 실제 포트는 `.env`의 `ALBAM_MATE_LOCAL_WEB_PORT`와 `ALBAM_MATE_LOCAL_SPRING_PORT`를 따른다. 웹의 `/api` 요청은 Compose 내부의 Spring 서비스로 전달된다.

소스가 변경되면 같은 `up -d --build --wait` 명령으로 이미지를 다시 빌드한다. 최근 로그는 다음 명령으로 확인한다.

```sh
docker compose --env-file .env -f compose.local.yml logs --tail 200
```

## PostgreSQL만 Compose로 실행

PostgreSQL 서비스만 시작하고 health check 결과를 확인한다.

```sh
docker compose --env-file .env -f compose.local.yml up -d --wait postgres
docker compose --env-file .env -f compose.local.yml ps postgres
```

Spring은 `local` 프로필을 명시하고 `.env`의 데이터베이스 연결값을 현재 프로세스에 주입해 실행한다. `.env`에는 프로필 활성화 값을 넣지 않아 H2 `test`와 PostgreSQL `postgresTest`의 실행 경계를 유지한다.

### macOS·Linux에서 Spring 실행

```sh
set -a
. ./.env
set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

### Windows PowerShell에서 Spring 실행

Docker Compose의 정규화 설정에서 PostgreSQL 연결값을 읽어 현재 프로세스의 `ALBAM_MATE_LOCAL_*` 환경변수로 주입한다. 구성 JSON은 변수에만 보관해 비밀번호를 출력하지 않는다. 같은 PowerShell 창에서 애플리케이션을 실행한다.

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

local 프로필은 식별 가능한 게임 중심·사람 중심 시드 모임을 각각 30개 준비한다. 재기동하면 같은 시드 모임만 미래 시각·공개 가능 상태로 갱신하고 수동 사용자·모임·참여 데이터는 보존한다.

## 종료와 데이터 초기화

전체 스택을 내릴 때는 다음 명령을 사용한다. named volume의 PostgreSQL 개발 데이터는 유지된다.

```sh
docker compose --env-file .env -f compose.local.yml down
```

Spring을 `Ctrl+C`로 종료한 뒤 PostgreSQL 컨테이너만 중지할 때도 데이터는 유지된다.

```sh
docker compose --env-file .env -f compose.local.yml stop postgres
```

로컬 데이터를 명시적으로 초기화할 때만 모든 로컬 Compose 서비스를 내리고 named volume까지 삭제한다. 이 명령은 로컬 PostgreSQL 개발 데이터를 복구 없이 제거한다.

```sh
docker compose --env-file .env -f compose.local.yml down --volumes
```

## 확인과 문제 해결

- `docker compose ... ps`에서 시작한 서비스가 `healthy`인지 확인한다.
- 포트가 이미 사용 중이면 다른 로컬 실행 방식을 먼저 종료한다. 두 방식의 기본 포트는 같다.
- PostgreSQL 설정 없이 `bootRun`만 실행하면 데이터소스 자동 설정에서 실패한다. 위 호스트 실행 절차로 `local` 프로필과 연결값을 함께 전달한다.
- H2 기반 `test`와 `build`에는 PostgreSQL이나 Docker가 필요하지 않다. 테스트 실행 경계는 [백엔드 테스트와 커버리지 검증](TESTING.md)을 따른다.
- `.env`의 필수 키가 없으면 Compose 정규화 검사가 실패한다. [.env.example](../../.env.example)과 비교해 누락된 키를 채운다.

## 관련 문서

- 반복 실행 명령: [프로젝트 명령](../COMMANDS.md#로컬-개발)
- 로컬 Compose 정의: [compose.local.yml](../../compose.local.yml)
- local 프로필 설정: [application-local.yml](../../src/main/resources/application-local.yml)
- 프론트엔드 호스트 실행: [프론트엔드 작업 안내](../../frontend/AGENTS.md#실행과-산출물)
