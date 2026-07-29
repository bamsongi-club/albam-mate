# ADR-0023: P0 Flyway 기준선을 재생성하고 인원 의미를 단계별 컬럼으로 분리

- 상태: 승인됨
- 작성일: 2026-07-28
- 결정일: 2026-07-28
- 관련: [ADR-0008](0008-flyway-database-migrations.md), [ADR-0019](../game/0019-bgg-full-catalog-staged-enrichment.md), [ERD GAMES](../../ERD.md#games), [용어집](../../../CONTEXT.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

기존 Flyway 이력은 V1에서 `recommended_player_count`를 만들고 V3에서 이를 `supported_player_count`로 이름만 바꾼다. 그러나 가능 인원, 추천 인원과 최적 인원은 서로 다른 의미이며 하나의 컬럼 이름 변경으로 표현할 관계가 아니다. P0 개발 단계에서 이 의미를 바로잡기 위해 기존 로컬·공유 검증 데이터베이스와 Flyway 이력을 폐기하고 기준선을 다시 적용하기로 했다.

이번 결정은 이미 적용된 마이그레이션을 수정하지 않는 [ADR-0008](0008-flyway-database-migrations.md)의 일반 원칙에 대한 일회성 기준선 재생성 예외다. 새 기준선이 확정된 뒤에는 ADR-0008의 전진 마이그레이션과 체크섬 불변 원칙을 다시 적용한다.

판단 기준은 다음과 같다.

- 가능·추천·최적 인원을 서로 독립된 의미로 보존할 것
- 빈 데이터베이스에서 V1부터 최종 스키마까지 재현할 수 있을 것
- 아직 출처와 계산 계약이 없는 추천·최적 인원에 가짜 값을 넣지 않을 것
- 기존 Flyway 이력을 일부만 지워 테이블과 이력이 어긋나는 상태를 만들지 않을 것
- 현재 P0 API와 카탈로그 입력 계약을 불필요하게 넓히지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 기존 V1~V3를 유지하고 V4·V5로 추천·최적 인원을 추가 | 기존 데이터베이스와 체크섬 호환성을 유지한다. | 잘못된 rename 이력이 영구적으로 남고 새 의미 구분이 기준선에 드러나지 않는다. | 제외 |
| V1 하나에 세 인원 컬럼을 모두 포함 | 신규 데이터베이스 구성이 가장 단순하다. | 추천·최적 인원의 단계적 도입 경계와 미확정 상태가 마이그레이션 이력에 드러나지 않는다. | 제외 |
| 데이터베이스를 재생성하고 V1~V3에서 인원 의미를 단계적으로 분리 | 최종 스키마와 도입 순서가 명시되고 각 단계의 보존을 검증할 수 있다. | 기존 데이터와 Flyway 이력을 폐기하고 모든 환경을 재생성해야 한다. | 선택 |

## 결정

기존 데이터베이스 스키마, 데이터와 `flyway_schema_history`를 함께 폐기하고 새 V1~V3를 빈 데이터베이스에 적용한다. `flyway_schema_history`만 삭제해 기존 테이블을 남기는 방식은 사용하지 않는다.

- V1은 P0 기본 스키마의 최종 형태를 생성한다. `games.supported_player_count VARCHAR(50) NOT NULL`과 기존 V2가 추가하던 `games.updated_at`, `participations.created_at`, `participations.updated_at`을 포함한다.
- V2는 `games.recommended_player_count VARCHAR(50) NULL`을 추가한다.
- V3는 `games.best_player_count VARCHAR(50) NULL`을 추가한다.
- 세 컬럼 사이에 rename은 없다. 가능 인원은 규칙상 범위, 추천 인원은 이용자가 추천한 범위, 최적 인원은 이용자가 가장 좋다고 평가한 범위다.
- 현재 카탈로그 입력과 API는 `supported_player_count`만 사용한다. 추천·최적 인원은 출처와 계산 계약이 승인되기 전까지 `NULL`로 유지하며 Entity와 API에 노출하지 않는다.
- 추천·최적 인원의 투표 원본과 집계 계약은 ADR-0019의 후속 결정으로 남긴다. `games`의 두 nullable 컬럼은 향후 승인된 집계 결과를 표시하기 위한 투영값이며 투표 원본의 정본이 아니다.
- 새 기준선이 저장소에 반영된 뒤에는 V1~V3를 다시 수정하지 않고 이후 변경을 V4 이상의 전진 마이그레이션으로 추가한다.

## 결과

- 얻는 것: 가능·추천·최적 인원이 별도 컬럼과 단계로 구분되고, 신규 PostgreSQL에서 각 도입 경계를 검증할 수 있다.
- 감수할 비용·위험: 이전 V1~V3를 적용한 모든 데이터베이스를 재생성해야 하며 보존하지 않은 데이터는 복구할 수 없다. 이전 체크섬을 가진 애플리케이션과 새 데이터베이스를 혼용할 수 없다.
- 후속 작업: 마이그레이션과 PostgreSQL 테스트를 재작성하고, 로컬·공유·RDS 환경은 정확한 대상을 식별한 뒤 새 기준선으로 재생성한다. 추천·최적 인원의 출처·투표·집계·API 계약은 별도로 승인한다.

## 보류 및 재검토

- 지금 하지 않는 것: 추천·최적 인원 값 적재, 투표 원본 테이블, 집계 계산식, Entity·API 응답 필드 추가
- 보류 이유: 현재 승인된 데이터는 가능 인원뿐이며 추천·최적 인원의 출처와 계산 계약이 확정되지 않았다.
- 다시 검토할 조건: 추천·최적 인원의 재사용 근거, 투표 원본과 집계 규칙, 공개 API 요구가 승인될 때

## 참고 자료

- [Flyway 버전 마이그레이션](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [Flyway 스키마 이력 테이블](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table)

## 검증

- 상태: 검증됨
- 근거: 새 V1~V3를 적용한 `SchemaValidationPostgresTest`가 PostgreSQL 18 Testcontainers에서 가능·추천·최적 인원 컬럼의 단계별 존재와 NULL 정책, 기존 값 보존을 확인한다. `./gradlew build --no-daemon`, `./gradlew postgresTest --no-daemon --stacktrace`, `node scripts/check-doc-links.mjs`가 통과했다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
