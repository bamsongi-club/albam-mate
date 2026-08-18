# Albam Mate Commands

이 문서는 저장소 루트에서 반복해서 실행하는 짧은 명령의 기준 문서다. 최초 설정, 긴 측정 절차와 문제 해결은 [프로젝트 가이드](guides/README.md) 또는 아래 [특수 절차 찾아가기](#특수-절차-찾아가기)를 따른다.

## 개발 환경 확인

### 첫 90초 — 첫 Green

Java 21과 저장소의 Gradle Wrapper를 사용한다. H2 기반 `test`에는 Docker와 외부 PostgreSQL이 필요하지 않다.

Windows PowerShell:

```powershell
java --version
.\gradlew.bat test
```

macOS·Linux:

```sh
java --version
./gradlew test
```

`BUILD SUCCESSFUL` 또는 명령의 성공 종료를 확인하면 첫 검증이 끝난다. Java 버전이나 테스트 실패를 해석해야 하면 [백엔드 테스트 가이드](guides/TESTING.md#빠른-h2-테스트-실행)를 먼저 본다.

## 자주 쓰는 명령

| 작업 | Windows PowerShell | macOS·Linux |
| --- | --- | --- |
| H2 전체 테스트 | `.\gradlew.bat test` | `./gradlew test` |
| 빌드 | `.\gradlew.bat build` | `./gradlew build` |
| PostgreSQL 테스트 | `.\gradlew.bat postgresTest --no-daemon --stacktrace` | `./gradlew postgresTest --no-daemon --stacktrace` |
| 빠른 커버리지 게이트 | `.\gradlew.bat jacocoTestReport jacocoTestCoverageVerification` | `./gradlew jacocoTestReport jacocoTestCoverageVerification` |
| Java 컨벤션 검사 | `.\gradlew.bat conventionCheck` | `./gradlew conventionCheck` |
| 문서 링크 검사 | `node scripts/docs/check-doc-links.mjs` | `node scripts/docs/check-doc-links.mjs` |
| 프론트엔드 테스트 | `Set-Location frontend; npm.cmd test` | `cd frontend && npm test` |
| 프론트엔드 빌드 | `Set-Location frontend; npm.cmd run build` | `cd frontend && npm run build` |
| 로컬 전체 스택 시작 | `docker compose --env-file .env -f compose.local.yml up -d --build --wait` | 동일 |
| 로컬 전체 스택 종료 | `docker compose --env-file .env -f compose.local.yml down` | 동일 |

애플리케이션에는 Java 21이 필요하다. 문서 검사는 Node.js 20 이상, 프론트엔드는 Node.js 20.19 이상 또는 22.12 이상을 사용한다. Gradle은 별도 설치본 대신 저장소 Wrapper를 사용한다.

PostgreSQL 설정 없는 단독 `bootRun`은 데이터소스 자동 설정에서 실패하며, Redis·프록시·다중 인스턴스 경계도 검증하지 못한다. 기본 애플리케이션 실행은 아래 로컬 Compose 경로를 사용한다.

## 로컬 개발

최초 한 번의 `.env` 준비와 포트 변경, 데이터 초기화는 [로컬 개발 환경 실행](guides/LOCAL_DEVELOPMENT.md)을 따른다. `.env`가 준비된 뒤에는 다음 명령만 반복한다.

```sh
docker compose --env-file .env -f compose.local.yml config --quiet
docker compose --env-file .env -f compose.local.yml up -d --build --wait
docker compose --env-file .env -f compose.local.yml ps
docker compose --env-file .env -f compose.local.yml logs --tail 200
docker compose --env-file .env -f compose.local.yml down
```

기본 웹 주소는 `http://localhost:5173`이다. `/api`와 WebSocket도 같은 프록시를 통과한다. `down`은 PostgreSQL named volume을 보존하며, 데이터를 지우는 `down --volumes`는 정확한 대상을 확인하고 초기화를 승인한 경우에만 사용한다.

## 테스트와 커버리지

| 검증 범위 | 명령 | 상세 해석 |
| --- | --- | --- |
| 빠른 H2 회귀 | 운영체제에 맞는 Wrapper로 `test` | [빠른 H2 테스트](guides/TESTING.md#빠른-h2-테스트-실행) |
| PostgreSQL 계약 | 먼저 `docker version`, 이후 `postgresTest --no-daemon --stacktrace` | [PostgreSQL 검증](guides/TESTING.md#postgresql-검증-실행) |
| H2 커버리지 | `jacocoTestReport jacocoTestCoverageVerification` | [커버리지 게이트](guides/TESTING.md#커버리지-게이트-실행) |
| H2·PostgreSQL 합산 커버리지 | `jacocoAllTestReport jacocoAllTestCoverageVerification` | [정본 커버리지와 CI 판정](guides/TESTING.md#ci-판정) |

### PostgreSQL 마이그레이션 검증

`postgresTest`는 빈 PostgreSQL 18.4 컨테이너에 Flyway를 적용하고 Hibernate 스키마와 PostgreSQL 전용 계약을 확인한다. 실행·실패 해석은 [PostgreSQL 검증 가이드](guides/TESTING.md#postgresql-검증-실행)를 따른다.

### 분기 커버리지 확인

빠른 H2 게이트와 H2·PostgreSQL 합산 정본 게이트의 책임, 최소선 변경과 CI 해석은 [커버리지 가이드](guides/TESTING.md#커버리지-게이트-실행)를 따른다.

대상 테스트만 실행할 때는 wildcard가 없는 클래스·메서드 selector와 `--rerun`을 사용한다. Red에서는 모든 실패를 보기 위해 `--fail-fast`를 쓰지 않고, Green에서만 사용한다.

```powershell
.\gradlew.bat test `
  --tests "cloud.bamsongi.albammate.architecture.ModuleArchitectureTest.업무_모듈_간_순환_의존이_없다" `
  --rerun --fail-fast
```

테스트 배치와 selector 증거 규칙은 [일반 테스트 작업 안내](../src/test/AGENTS.md)와 [PostgreSQL 테스트 작업 안내](../src/postgresTest/AGENTS.md)를 따른다.

## 코드와 문서 품질

Java 포맷·Checkstyle을 함께 확인한다.

```sh
./gradlew conventionCheck
```

Windows에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용한다. 자동 포맷이 필요하면 운영체제에 맞는 Wrapper로 `spotlessApply`를 한 번 실행하고 diff를 확인한다. clone별 Git hook 설정은 [Java 컨벤션과 Git hook 설정](guides/CODE_FORMATTING.md)을 따른다.

Markdown 링크와 검사기 회귀를 확인한다.

```sh
node scripts/docs/check-doc-links.mjs
node --test scripts/docs/check-doc-links.test.mjs
node scripts/docs/check-monitoring-contract.mjs
node --test scripts/docs/check-monitoring-contract.test.mjs
```

## 운영 Compose

운영 호스트 준비, 설정 검증, 배포·롤백 명령은 [P1 AWS 다중 인스턴스 실행안](guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md)이 소유한다. 이 문서에는 운영 비밀값이나 호스트별 긴 절차를 복제하지 않는다.

P2 운영 관측의 기능 완료 기준은 [운영 관측 명세](p2/monitoring.md), 화면·경고 정책은 [대시보드 정책](p2/dashboard.md), metric·log 허용 목록과 계획 종료·재기동 계약은 [P2 운영 관측 런북](guides/MONITORING_OPERATIONS.md), 전송 경계는 [ADR-0071](adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md)·[ADR-0059](adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)을 따른다.

상태 전이 명령은 [`albam-mate-infra`](https://github.com/bamsongi-club/albam-mate-infra/tree/47bd0ba1a8cb97b13694ff492bf365f0cfee66d7) checkout에서 실행한다. 실제 `stackId`, UTC 종료 시각과 대표 alarm 이름을 넣어야 하며, 상태 변경 명령은 정확한 `--confirm-stack-id` 없이는 실패한다.

```sh
./run.sh ops status
./run.sh ops initialize-active --confirm-stack-id <stack-id>
./run.sh ops plan-stop --planned-until <YYYY-MM-DDTHH:MM:SSZ> --confirm-stack-id <stack-id>
./run.sh ops extend --planned-until <YYYY-MM-DDTHH:MM:SSZ> --confirm-stack-id <stack-id>
./run.sh ops restart --confirm-stack-id <stack-id>
./run.sh ops recover --confirm-stack-id <stack-id>
./run.sh ops verify-alert-cycle --alarm-name <alarm-name> --confirm-stack-id <stack-id>
./run.sh down
```

`down`은 `PLANNED_STOP`과 초과 schedule이 없을 때만 P1을 철거하고, 성공한 destroy 뒤 동적 운영 상태를 정리한다. Object Lock receipt와 bootstrap state·lock·ECR 자원은 감사와 다음 실행을 위해 남긴다.

## 프롬프트 기록 확인

반복 확인과 문제 해결은 [팀 프롬프트 기록 환경 설정](guides/PROMPT_LOGGING.md)을 따른다. 프롬프트 로거의 회귀 검사는 `node --test .bamsongi/hooks/user-prompt.test.mjs`로 실행한다.

## 특수 절차 찾아가기

긴 명령, fixture 준비, 상태 초기화와 결과 해석은 이 문서에 복제하지 않는다.

| 작업 | 기준 문서 |
| --- | --- |
| 로컬 `.env`, 로그, 종료와 데이터 초기화 | [로컬 개발 환경 실행](guides/LOCAL_DEVELOPMENT.md) |
| PostgreSQL 실패·커버리지 최소선 해석 | [백엔드 테스트와 커버리지 검증](guides/TESTING.md) |
| 운영 Compose와 P1 AWS 검증 환경 | [P1 AWS 다중 인스턴스 실행안](guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md) |
| P2 metric·log·대시보드·경고·계획 종료 계약 | [P2 운영 관측 명세](p2/monitoring.md), [대시보드 정책](p2/dashboard.md), [운영 관측 런북](guides/MONITORING_OPERATIONS.md) |
| 인증·알림 k6 계약과 용량 측정 | [인증·알림 k6 README](../load-tests/k6/jiho/README.md), [Jiho 측정 문서](measurements/k6/jiho/README.md) |
| ROOM-09 기준선 실행과 보고서 재생성 | [ROOM-09 일괄 처리 기준선](measurements/room-09-bounded-processing-baseline.md#재현-명령) |
| 게임 카탈로그 fixture·검수·적재 | [게임 카탈로그 검수·적재](guides/GAME_CATALOG_IMPORT.md) |
| 팀 프롬프트 기록 환경 | [팀 프롬프트 기록 환경 설정](guides/PROMPT_LOGGING.md) |
| 백엔드 변경의 빠른·전체 전달 경로 | [협업 개발의 전달 경로](CONVENTIONS.md#백엔드-변경-전달-경로) |

## 명령 관리 원칙

- 실제 저장소에서 반복 실행하며 짧게 끝나는 명령만 이 파일에 둔다.
- 최초 설치, 운영체제별 설정, fixture 준비, 배포·측정·장애 대응은 해당 가이드가 소유한다.
- 스크립트나 Gradle task가 바뀌면 같은 변경에서 이 문서의 핫패스와 링크를 확인한다.
- 명령이 성공했다는 사실을 배포·운영·용량 검증 완료로 확대하지 않는다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-12` · 폐기 조건 `반복 명령이 단일 task runner의 생성형 도움말로 완전히 대체될 때`
