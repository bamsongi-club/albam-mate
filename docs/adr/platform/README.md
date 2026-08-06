# Platform ADR

애플리케이션 구조, Java·Spring, 데이터베이스, 시간과 공통 식별자처럼 여러 도메인에 걸치는 백엔드 기반 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0001](0001-java-21-spring-boot-4-baseline.md) | Java 21과 Spring Boot 4를 백엔드 기준선으로 채택 | 승인됨 | 2026-07-23 | 검증됨 |
| [0002](0002-postgresql-primary-database.md) | PostgreSQL을 주 데이터베이스로 채택 | 승인됨 | 2026-07-23 | 검증됨 |
| [0006](0006-p0-bigint-identity-ids.md) | P0 내부 식별자에 BIGINT 자동 증가 키를 사용 | 승인됨 | 2026-07-24 | 검증됨 |
| [0007](0007-domain-centered-modular-monolith.md) | 도메인 중심 모듈러 모놀리스를 채택 | 승인됨 | 2026-07-24 | 검증됨 |
| [0008](0008-flyway-database-migrations.md) | Flyway SQL 마이그레이션으로 데이터베이스 스키마를 관리 | 승인됨 | 2026-07-24 | 검증됨 |
| [0009](0009-utc-time-standard.md) | 시스템 기준 시각을 UTC로 통일 | 승인됨 | 2026-07-24 | 미검증 |
| [0010](0010-h2-postgresql-test-boundary.md) | H2와 PostgreSQL의 테스트 검증 경계를 분리 | 승인됨 | 2026-07-24 | 검증됨 |
| [0016](0016-p0-update-api-http-method.md) | P0 수정 API의 HTTP 메서드를 PATCH로 통일 | 대체됨 | 2026-07-27 | 미검증 |
| [0017](0017-test-coverage-branch-ratchet.md) | 테스트 커버리지를 분기 기준으로 측정하고 회귀 방지선으로 운영 | 승인됨 | 2026-07-28 | 검증됨 |
| [0021](0021-p0-aws-ec2-rds-deployment-baseline.md) | P0 AWS EC2와 RDS 배포 기준선 | 승인됨 | 2026-07-28 | 미검증 |
| [0022](0022-p0-update-api-http-method-and-finish-idempotency.md) | P0 수정 API에 PATCH를 유지하고 방 종료 명령을 멱등 처리 | 대체됨 | 2026-07-28 | 검증됨 |
| [0023](0023-p0-flyway-baseline-reset-player-count-stages.md) | P0 Flyway 기준선을 재생성하고 인원 의미를 단계별 컬럼으로 분리 | 승인됨 | 2026-07-28 | 검증됨 |
| [0024](0024-naver-java-format-and-project-checkstyle.md) | 네이버 Java 포맷과 프로젝트 Checkstyle로 컨벤션을 자동화 | 승인됨 | 2026-07-31 | 검증됨 |
| [0038](0038-multi-instance-session-and-scheduler-coordination.md) | 다중 인스턴스의 공용 세션과 스케줄 실행을 공유 인프라로 조정 | 승인됨 | 2026-08-01 | 미검증 |
| [0047](0047-http-method-and-target-state-idempotency.md) | 수정·관계 설정 API의 HTTP 메서드와 방 종료 멱등성을 확정 | 승인됨 | 2026-08-04 | 미검증 |
| [0051](0051-p1-self-managed-aws-infrastructure.md) | P1 자체 운영 PostgreSQL·Redis 도입과 초기 용량 검증 제안 | 제안됨 | 미정 | 미검증 |
