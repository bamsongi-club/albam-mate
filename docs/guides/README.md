# 프로젝트 가이드

이 디렉터리는 최초 설정, 운영 준비, 데이터 적재와 문제 해결처럼 설명이 긴 절차를 모은다. 반복해서 실행하는 명령은 [COMMANDS](../COMMANDS.md), 제품·구조·저장 계약과 기술 결정은 각 정본 문서를 따른다. 이 인덱스는 가이드를 찾는 경로만 소유하며 상세 절차나 설정값을 반복하지 않는다.

## 가이드 찾기

| 가이드 | 용도 | 상태 | 소유 경계 |
| --- | --- | --- | --- |
| [How to 로컬 개발 환경 실행](LOCAL_DEVELOPMENT.md) | `.env` 준비, 전체 스택과 PostgreSQL 전용 Compose 실행, 운영체제별 호스트 개발 | 사용 가능 | 로컬 실행·종료·문제 해결 절차를 설명한다. 서비스·포트의 실제 설정은 [`compose.local.yml`](../../compose.local.yml), 반복 명령은 [COMMANDS](../COMMANDS.md#로컬-개발)가 소유한다. |
| [How to 백엔드 테스트와 커버리지 검증](TESTING.md) | H2·PostgreSQL 테스트, JaCoCo 게이트 실행과 실패 해석 | 사용 가능 | 실행·결과 해석 절차를 설명한다. 테스트 배치 규칙은 [테스트 작업 안내](../../src/test/AGENTS.md), 결정 근거는 [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md)과 [ADR-0017](../adr/platform/0017-test-coverage-branch-ratchet.md), 실제 태스크·최소선은 [`build.gradle`](../../build.gradle)이 소유한다. |
| [P0 AWS 운영 인프라 기준](AWS_P0_INFRASTRUCTURE.md) | P0 AWS 배포 구성, 준비·배포·검증 순서와 보안 확인 | 구현·배포 전 기준 | 실행 절차와 확인 항목을 설명한다. 구성 선택과 변경 근거는 [ADR-0021](../adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md)이 소유한다. |
| [P1 AWS 저비용 4 EC2 인프라 실행안](AWS_MULTI_INSTANCE_INFRASTRUCTURE.md) | App1 Nginx 단일 진입점과 네 `t4g.micro` EC2를 Terraform으로 만들고 역할별 병목을 측정하는 절차 | 승인·부분 검증 | 임시 AWS 4 EC2 배포와 인증·알림 측정에서 확인한 범위, 독립 재현·복구·교차 인스턴스·지속 용량의 미검증 범위를 구분한다. 기술 선택과 기존 기준의 부분 대체 범위는 [ADR-0051](../adr/platform/0051-p1-self-managed-aws-infrastructure.md)이 소유한다. |
| [develop P1 자동 CD 가이드](CD_DEPLOYMENT.md) | `develop` 병합 뒤 동일 SHA의 CI·이미지·migrator·App2→App1 롤아웃과 앱 rollback 경계 | 승인·구현 전 | 실행·실패 처리·증거 경계를 설명한다. 기술 선택은 [ADR-0084](../adr/platform/0084-github-actions-develop-p1-continuous-deployment.md)이 소유하며, workflow·OIDC·migrator·실제 AWS 실행은 아직 구현·검증되지 않았다. |
| [Java 컨벤션과 Git hook 설정](CODE_FORMATTING.md) | clone별 Git hook 설치와 Java 포맷·Checkstyle 수동 실행 | 사용 가능 | 설정 절차를 설명한다. 코드 작성 규칙은 [CONVENTIONS](../CONVENTIONS.md), 실제 설정은 [formatter](../../config/formatter/README.md)와 [checkstyle](../../config/checkstyle/README.md)이 소유한다. |
| [게임 카탈로그 검수·적재](GAME_CATALOG_IMPORT.md) | 게임 데이터 검수, 출처 기록, 적재 산출물 생성과 PostgreSQL 반영 | 사용 가능 | 검수·적재 절차를 설명한다. 데이터 선택과 트랜잭션 원칙은 [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)가 소유한다. |
| [P1 검색 성능 측정](P1_SEARCH_PERFORMANCE.md) | 게임·방 검색의 PostgreSQL 기준선, 후보 인덱스와 재측정 조건 | 사용 가능 | 고정 fixture·EXPLAIN 수집과 인덱스 채택 근거를 설명한다. 검색 의미는 [P1 검색](../archive/p1/search.md), 저장 구조는 [ERD](../ERD.md)가 소유한다. |
| [알림 Outbox 운영 런북](NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본) | 알림 relay 수치·측정, 실패 복구·폐기와 보존 데이터 정리 | P1 계약 | 현재 운영 파라미터와 실행·판정 절차를 소유한다. 실행 가능 여부는 [P1 기능 종료 상태의 `NOTI-01`](../archive/p1/README.md#기능별-종료-상태), 결정 근거는 [ADR-0040](../adr/notification/0040-postgresql-notification-relay-recovery-retention.md), 저장 계약은 [ERD의 P1 알림 저장 계약](../ERD.md#p1-알림-저장-계약)을 따른다. |
| [P2 운영 관측 런북](MONITORING_OPERATIONS.md) | metric·log 허용 목록, alarm 대응, 전체 스택 계획 종료·재기동과 증거 계약 | 계약 정본·OPS-01 구현·AWS 실측 | inventory·수집 경계·계층별 생존·상태 전이·IAM·실패 복구와 `OPS-01-AC1`~`AC7` 실측 경계를 소유한다. 기능 완료 기준은 [P2 운영 관측](../p2/monitoring.md), 화면·등급 정책은 [운영 대시보드](../p2/dashboard.md), 반복 실행 명령은 [COMMANDS](../COMMANDS.md#운영-compose)를 따른다. |
| [How to 팀 프롬프트 기록 환경 설정](PROMPT_LOGGING.md) | Codex·Claude Code 프롬프트 기록 훅의 팀원별 최초 설정과 문제 해결 | 사용 가능 | 환경 설정과 확인 절차를 설명한다. 반복 확인 명령은 [COMMANDS](../COMMANDS.md#프롬프트-기록-확인)를 따른다. |

`사용 가능`, `구현·배포 전 기준`, `승인·구현 전`과 `계약 정본·구현 전`은 현재 실행 상태를 나타낸다. `승인·구현 전`은 팀이 정책을 채택했지만 workflow·인프라·운영 증거가 아직 없다는 뜻이다. `계약 정본·OPS-01 구현·AWS 실측`은 `OPS-01`의 생산 코드·자동 검증·임시 AWS 배포·측정·철거를 마쳤다는 뜻이며 상시 운영 배포나 다른 `OPS` 기능 완료를 뜻하지 않는다. `승인·부분 검증`은 임시 AWS 배포와 일부 부하 실행 증거가 있지만 독립 재현·복구·교차 인스턴스·지속 용량 경계는 확인하지 않았음을 뜻한다. `P1 계약`은 도입 단계만 나타내며 현재 생산 코드·검증·배포 여부는 연결된 P1 기능 종료 상태로 판정한다.

## 유지 규칙

- 팀이 반복해서 실행하는 짧은 명령은 [COMMANDS](../COMMANDS.md)에 두고 이 디렉터리에 복제하지 않는다.
- 되돌리기 어렵거나 논쟁적인 선택과 변경 이유는 [ADR](../adr/README.md)에 두고 가이드에는 실행·확인 절차만 둔다.
- 가이드를 추가하거나 이름·실행 가능 상태를 바꾸면 이 인덱스를 같은 변경에서 갱신한다.

> 문서 관리: 소유자 `밤송이클럽 개발·운영 팀` · 최종 검증일 `2026-08-18` · 폐기 조건 `최초 설정·운영 가이드의 대표 인덱스가 승인된 다른 문서로 대체될 때`
