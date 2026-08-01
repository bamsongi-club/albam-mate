# 프로젝트 가이드

이 디렉터리는 최초 설정, 운영 준비, 데이터 적재와 문제 해결처럼 설명이 긴 절차를 모은다. 반복해서 실행하는 명령은 [COMMANDS](../COMMANDS.md), 제품·구조·저장 계약과 기술 결정은 각 정본 문서를 따른다. 이 인덱스는 가이드를 찾는 경로만 소유하며 상세 절차나 설정값을 반복하지 않는다.

## 가이드 찾기

| 가이드 | 용도 | 상태 | 소유 경계 |
| --- | --- | --- | --- |
| [P0 AWS 운영 인프라 기준](AWS_P0_INFRASTRUCTURE.md) | P0 AWS 배포 구성, 준비·배포·검증 순서와 보안 확인 | 구현·배포 전 기준 | 실행 절차와 확인 항목을 설명한다. 구성 선택과 변경 근거는 [ADR-0021](../adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md)이 소유한다. |
| [Java 컨벤션과 Git hook 설정](CODE_FORMATTING.md) | clone별 Git hook 설치와 Java 포맷·Checkstyle 수동 실행 | 사용 가능 | 설정 절차를 설명한다. 코드 작성 규칙은 [CONVENTIONS](../CONVENTIONS.md), 실제 설정은 [formatter](../../config/formatter/README.md)와 [checkstyle](../../config/checkstyle/README.md)이 소유한다. |
| [게임 카탈로그 검수·적재](GAME_CATALOG_IMPORT.md) | 게임 데이터 검수, 출처 기록, 적재 산출물 생성과 PostgreSQL 반영 | 사용 가능 | 검수·적재 절차를 설명한다. 데이터 선택과 트랜잭션 원칙은 [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)가 소유한다. |
| [알림 Outbox 운영 런북](NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본) | 알림 relay 수치·측정, 실패 복구·폐기와 보존 데이터 정리 | P1 계약 | 현재 운영 파라미터와 실행·판정 절차를 소유한다. 실행 가능 여부는 [P1 기능 상태 정본의 `NOTI-01`](../p1/README.md#기능별-현재-상태), 결정 근거는 [ADR-0040](../adr/notification/0040-postgresql-notification-relay-recovery-retention.md), 저장 계약은 [ERD의 P1 알림 저장 계약](../ERD.md#p1-알림-저장-계약)을 따른다. |
| [How to 팀 프롬프트 기록 환경 설정](PROMPT_LOGGING.md) | Codex·Claude Code 프롬프트 기록 훅의 팀원별 최초 설정과 문제 해결 | 사용 가능 | 환경 설정과 확인 절차를 설명한다. 반복 확인 명령은 [COMMANDS](../COMMANDS.md#프롬프트-기록-확인)를 따른다. |

`사용 가능`과 `구현·배포 전 기준`은 현재 실행 상태를 나타낸다. `P1 계약`은 도입 단계만 나타내며 현재 생산 코드·검증·배포 여부는 연결된 P1 기능 상태 정본으로 판정한다.

## 유지 규칙

- 팀이 반복해서 실행하는 짧은 명령은 [COMMANDS](../COMMANDS.md)에 두고 이 디렉터리에 복제하지 않는다.
- 되돌리기 어렵거나 논쟁적인 선택과 변경 이유는 [ADR](../adr/README.md)에 두고 가이드에는 실행·확인 절차만 둔다.
- 가이드를 추가하거나 이름·실행 가능 상태를 바꾸면 이 인덱스를 같은 변경에서 갱신한다.
