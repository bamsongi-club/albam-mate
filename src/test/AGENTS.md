# 일반 테스트 작업 안내

이 파일은 `src/test/**` 공통 테스트 규약의 정본이며 [PostgreSQL 테스트](../postgresTest/AGENTS.md)에도 적용된다.

## 작성과 검증

- 재사용 fixture는 해당 source set의 `java/.../<domain>/fixture`, 전용 fixture는 해당 테스트 가까이에 두며 `src/main`에는 두지 않는다.
- 현재 시각에 의존하는 테스트는 `Clock.fixed(...)`를 사용하고 fixture의 시각도 고정값으로 둔다.
- 생산 코드 패키지를 이동·추가하거나 커버리지 최소선을 조정하면 [결과 해석과 최소선 갱신](../../docs/guides/TESTING.md#결과-해석과-최소선-갱신) 절차를 따른다.
- 새 업무 모듈을 추가하면 `ModuleArchitectureTest`의 모듈 목록과 허용 의존 방향을 함께 갱신한다.

## source set 배치

| source set | 배치 기준 |
| --- | --- |
| `src/test` | 단위 테스트, Spring context smoke test, MVC slice, 표준 JPA 매핑·조회와 H2로 검증 가능한 서비스 흐름 |
| `src/postgresTest` | Flyway, PostgreSQL 전용 SQL·타입·함수·인덱스·제약, 잠금·격리·동시성과 H2 차이의 회귀 경로 |

H2 테스트가 있어도 PostgreSQL 고유 동작에 의존하면 `src/postgresTest` 검증을 함께 둔다. H2 통과를 위해 운영 SQL이나 마이그레이션을 단순화하지 않는다.

## 이름

테스트 메서드명은 `방을_생성하면_모집중_상태가_된다()`처럼 행동과 결과를 한국어로 드러내고, 같은 의미를 반복하는 `@DisplayName`은 사용하지 않는다.

테스트 클래스명은 검증 역할이 드러나는 접미사(`Test`, `IntegrationTest`, `HttpIntegrationTest`, `PostgresTest` 등)를 사용하고, 별도 구분이 필요 없으면 `Test`를 기본으로 한다.

## 근거와 명령

- source set 경계의 결정 근거: [ADR-0010](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md)
- 분기 커버리지와 패키지 변경 규칙: [ADR-0017](../../docs/adr/platform/0017-test-coverage-branch-ratchet.md)
- 실행 명령: [프로젝트 명령](../../docs/COMMANDS.md)
