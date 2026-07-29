# 일반 테스트 작업 안내

이 파일은 공통 테스트 규약의 정본이다. `src/test/**`에 직접 적용하며, [PostgreSQL 테스트](../postgresTest/AGENTS.md)도 여기의 공통 규약을 따른다.

## 작성과 검증

- 단위 테스트는 JUnit 5와 Mockito를 사용하고 given-when-then 흐름이 드러나게 작성한다.
- Service 단위 테스트는 자기 모듈의 Repository와 외부 의존성을 목킹한다.
- Controller 테스트는 HTTP 상태, 요청 검증, 인증 경계와 응답 계약을 확인한다.
- `@SpringBootTest`는 전체 Spring 구성이 필요한 통합 경로에만 사용한다.
- 새 Service와 Controller에는 성공 경로와 핵심 실패 경로 테스트를 함께 작성한다.
- 테스트에서 Spring TestContext fixture를 주입할 때만 `@Autowired`를 허용한다.
- 여러 테스트에서 재사용하는 fixture는 해당 source set의 `java/.../<domain>/fixture` 패키지에 두고, 한 테스트에서만 쓰는 fixture는 그 테스트 가까이에 둔다. 테스트 편의를 위한 fixture를 `src/main`에 두지 않는다.
- 현재 시각에 의존하는 테스트는 `Clock.fixed(...)`를 사용하고 fixture의 시각도 고정값으로 둔다.
- 생산 코드의 패키지를 옮기거나 새로 만들면 `build.gradle`의 `gatedBranchCoverage` 대상과 실측 최소선이 함께 유효한지 확인하고 필요한 변경을 같은 작업에 포함한다.
- 모듈이 둘 이상 구현되면 순환 의존과 다른 모듈 내부 패키지 접근을 구조 테스트로 검사한다.

## source set 배치

| source set | 배치 기준 |
| --- | --- |
| `src/test` | 단위 테스트, Spring context smoke test, MVC slice, 표준 JPA 매핑·조회와 H2로 검증 가능한 서비스 흐름 |
| `src/postgresTest` | Flyway, PostgreSQL 전용 SQL·타입·함수·인덱스·제약, 잠금·격리·동시성과 H2 차이의 회귀 경로 |

하나의 기능에 H2 테스트가 있어도 결과가 PostgreSQL 고유 동작에 의존하면 `src/postgresTest` 검증을 함께 둔다. H2를 통과시키기 위해 운영 SQL이나 마이그레이션을 별도로 단순화하지 않는다.

## 이름

테스트 메서드명은 `방을_생성하면_모집중_상태가_된다()`처럼 행동과 결과를 한국어로 드러내고, 같은 의미를 반복하는 `@DisplayName`은 사용하지 않는다.

테스트 클래스명은 검증 역할이 드러나는 가장 구체적인 접미사를 사용한다. 별도 역할을 구분할 필요가 없는 한 `Test`를 기본으로 한다.

| 형태 | 사용 기준 |
| --- | --- |
| `{Target}Test` | 한 대상의 동작을 검증하는 기본 테스트. 단위 테스트, MVC slice와 Repository 테스트를 포함한다. |
| `{Target}UnitTest` | 같은 대상의 다른 범위 테스트와 구분해야 하는 Mockito 기반 격리 테스트 |
| `{Scenario}IntegrationTest` | H2와 Spring context에서 여러 실제 구성요소의 협력을 검증하는 테스트 |
| `{Scenario}HttpIntegrationTest` | 전체 Spring context와 MockMvc로 HTTP 경계를 검증하는 테스트 |
| `{Scenario}RealHttpIntegrationTest` | 임의 포트의 실제 서버와 HTTP client를 연결해 검증하는 테스트 |
| `{Scope}PersistenceTest` | 여러 Entity의 매핑, 관계와 영속성 계약을 함께 검증하는 테스트 |
| `{Scope}PostgresTest` | `postgresTest` source set에서 실제 PostgreSQL 고유 계약을 검증하는 테스트 |
| `{ApplicationName}ApplicationTests` | Spring Boot 애플리케이션 context 기동 smoke test |

## 근거와 명령

- source set 경계의 결정 근거: [ADR-0010](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md)
- 분기 커버리지와 패키지 변경 규칙: [ADR-0017](../../docs/adr/platform/0017-test-coverage-branch-ratchet.md)
- 실행 명령: [프로젝트 명령](../../docs/COMMANDS.md)
