# How to 로컬 개발 환경 실행

이 문서는 `compose.local.yml`로 Albam Mate의 기본 로컬·상시 데모·P1 검증 환경을 실행하는 방법을 설명한다. 이 구성은 로컬 프록시, Spring 애플리케이션 두 대, PostgreSQL과 Redis를 함께 실행한다. 짧은 반복 명령만 필요하면 [프로젝트 명령](../COMMANDS.md#로컬-개발)을 사용한다.

## 준비 사항

- Docker Engine과 Docker Compose가 필요하다.
- 모든 명령은 저장소 루트에서 실행한다.
- 기본 로컬 실행은 단일 Spring 인스턴스가 아니라 다중 인스턴스 Compose를 사용한다.

`compose.local.yml`은 다음 서비스를 실행한다.

| 서비스 | 역할 |
| --- | --- |
| `postgres` | 로컬 애플리케이션 데이터와 Flyway schema를 제공한다. |
| `redis` | Spring Session, 채팅 Pub/Sub과 전송 제한 상태를 제공한다. |
| `spring-1`, `spring-2` | `local` 프로필로 실행되는 두 애플리케이션 인스턴스다. |
| `proxy` | 두 Spring 인스턴스에 HTTP·WebSocket 요청을 분산하고 프런트엔드를 제공한다. |

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

PostgreSQL, Redis, Spring 두 대와 프록시 이미지를 현재 소스에서 빌드하고 모든 health check가 통과할 때까지 기다린다.

```sh
docker compose --env-file .env -f compose.local.yml up -d --build --wait
docker compose --env-file .env -f compose.local.yml ps
```

기본 웹 주소와 API 주소는 `http://localhost:5173`와 `http://localhost:5173/api`다. 실제 프록시·PostgreSQL·Redis 호스트 포트는 `.env`의 `ALBAM_MATE_LOCAL_PROXY_PORT`, `ALBAM_MATE_LOCAL_DB_PORT`, `ALBAM_MATE_LOCAL_REDIS_PORT`를 따른다. Spring 인스턴스는 프록시 뒤에서만 접근하며, `/api`와 WebSocket Upgrade 모두 프록시를 통과한다.

소스가 변경되면 같은 `up -d --build --wait` 명령으로 이미지를 다시 빌드한다. 최근 로그는 다음 명령으로 확인한다.

```sh
docker compose --env-file .env -f compose.local.yml logs --tail 200
```

Compose가 실행 중인 상태에서 프록시를 통과하는 세션·교차 인스턴스 검증은 다음 명령으로 실행한다.

```sh
JAVA_TOOL_OPTIONS='-Dissue471.localProxy=true' ./gradlew postgresTest --tests "cloud.bamsongi.albammate.global.security.session.LocalMultiProxyRuntimePostgresTest" --rerun --fail-fast --no-daemon --stacktrace
```

## 의존성만 실행

애플리케이션을 시작하지 않고 PostgreSQL과 Redis 클라이언트 작업만 확인할 때 사용한다. 이 명령은 기본 local 런타임의 다중 인스턴스 검증을 대체하지 않는다.

```sh
docker compose --env-file .env -f compose.local.yml up -d --wait postgres redis
docker compose --env-file .env -f compose.local.yml ps postgres redis
docker compose --env-file .env -f compose.local.yml stop postgres redis
```

## 종료와 데이터 초기화

전체 스택을 내릴 때는 다음 명령을 사용한다. named volume의 PostgreSQL 개발 데이터는 유지된다.

```sh
docker compose --env-file .env -f compose.local.yml down
```

로컬 데이터를 명시적으로 초기화할 때만 모든 로컬 Compose 서비스를 내리고 named volume까지 삭제한다. 이 명령은 로컬 PostgreSQL 개발 데이터와 Redis 컨테이너를 초기화한다.

```sh
docker compose --env-file .env -f compose.local.yml down --volumes
```

## 확인과 문제 해결

- `docker compose ... ps`에서 `postgres`, `redis`, `spring-1`, `spring-2`, `proxy`가 모두 `healthy`인지 확인한다.
- 포트가 이미 사용 중이면 기존 local Compose를 먼저 종료하고, `.env`의 `ALBAM_MATE_LOCAL_*_PORT` 값을 확인한다.
- `local`은 Redis가 필요한 프로필이므로 Redis 없이 Spring 한 대만 실행해 P1 검증을 완료한 것으로 보지 않는다.
- H2 기반 `test`와 `build`에는 PostgreSQL이나 Docker가 필요하지 않다. 테스트 실행 경계는 [백엔드 테스트와 커버리지 검증](TESTING.md)을 따른다.
- `.env`의 필수 키가 없으면 Compose 정규화 검사가 실패한다. [.env.example](../../.env.example)과 비교해 누락된 키를 채운다.

## 관련 문서

- 반복 실행 명령: [프로젝트 명령](../COMMANDS.md#로컬-개발)
- 실행 프로필 결정: [ADR-0052](../adr/platform/0052-local-profile-multi-instance-default.md)
- 로컬 Compose 정의: [compose.local.yml](../../compose.local.yml)
- local 프로필 설정: [application-local.yml](../../src/main/resources/application-local.yml)
- 프런트엔드 작업 안내: [frontend/AGENTS.md](../../frontend/AGENTS.md#실행과-산출물)
