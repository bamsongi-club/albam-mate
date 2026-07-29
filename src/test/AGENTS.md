# 일반 테스트 작업 안내

이 파일은 H2 기반 `src/test/**`의 source set 배치 맥락만 다룬다. 공통 작성 규칙과 클래스 접미사의 정본은 [프로젝트 컨벤션의 테스트 절](../../docs/CONVENTIONS.md#테스트)이다.

## source set 배치

| source set | 배치 기준 |
| --- | --- |
| `src/test` | 단위 테스트, Spring context smoke test, MVC slice, 표준 JPA 매핑·조회와 H2로 검증 가능한 서비스 흐름 |
| `src/postgresTest` | Flyway, PostgreSQL 전용 SQL·타입·함수·인덱스·제약, 잠금·격리·동시성과 H2 차이의 회귀 경로 |

하나의 기능에 H2 테스트가 있어도 결과가 PostgreSQL 고유 동작에 의존하면 `src/postgresTest` 검증을 함께 둔다. H2를 통과시키기 위해 운영 SQL이나 마이그레이션을 별도로 단순화하지 않는다.

## 관련 정본과 검증

- source set 경계의 결정 근거: [ADR-0010](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md)
- 분기 커버리지와 패키지 변경 규칙: [ADR-0017](../../docs/adr/platform/0017-test-coverage-branch-ratchet.md)
- 고정 시각 fixture 규칙: [프로젝트 컨벤션의 시간 처리 절](../../docs/CONVENTIONS.md#시간-처리)
- 실행 명령: [프로젝트 명령](../../docs/COMMANDS.md)
