# ADR-0048: 검수된 메커니즘 189개 전체를 안정적인 내부 목록으로 공개

- 상태: 승인됨
- 작성일: 2026-08-04
- 결정일: 2026-08-04
- 관련: [Issue #351](https://github.com/bamsongi-club/albam-mate/issues/351), [SEARCH-01](../../p1/search.md#search-01-게임-조건-검색), [게임 카탈로그 취득 기록](../../game-catalog/2026-08-01-bgg-detail-acquisition.md)
- 대체 대상: [ADR-0027](0027-controlled-game-mechanism-taxonomy-and-provenance.md)
- 후속 ADR: 없음

**결정 요약:** 정확한 승인 입력에서 검수한 BGG 메커니즘 189개 전체를 내부 코드와 공개 상태로 통제하고, 게임과 다대다 관계로 연결해 `SEARCH-01`에 제공한다.

## 맥락

[ADR-0027](0027-controlled-game-mechanism-taxonomy-and-provenance.md)은 익숙한 10~20개로 시작하도록 결정했지만, 이후 승인 입력 `games.p1-search-time-corrected-2026-08-03.json`에서 게임 2,000개, 고유 메커니즘 189개와 게임 관계 13,263개를 확인했다. 고급 목록에서 전체 검수 항목을 검색·선택하려면 소수 목록 제한을 유지할 수 없다.

외부 이름을 그대로 API 식별자로 사용하면 표시명 수정이 요청 계약을 깨뜨린다. 반대로 전체 후보를 검수 상태 없이 자동 공개하면 출처·번역·관계 오류가 서비스 검색에 바로 반영된다. 따라서 189개 공개 확대와 기존 내부 통제 원칙을 함께 유지해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 10~20개만 공개 | 초기 검수·표시가 단순하다. | 승인된 전체 목록을 고급 검색에 제공하지 못한다. | 제외 |
| BGG 이름·ID 189개를 그대로 공개 | 변환이 작다. | 외부 식별자·표시명과 내부 API 계약이 결합되고 검수 상태를 통제하기 어렵다. | 제외 |
| 189개를 내부 코드·공개 상태·검수 근거와 함께 관리 | 전체 검색을 제공하면서 표시명 변경과 신규 항목 공개를 통제한다. | 목록·관계 적재와 검수 증거를 계속 관리해야 한다. | 선택 |

## 결정

- 정확한 SHA-256이 승인된 현재 입력의 메커니즘 189개와 게임 관계 13,263개를 `SEARCH-01` 공개 범위로 채택한다.
- 메커니즘은 자동 증가 내부 ID, BGG 메커니즘 ID, 안정적인 내부 `code`, 변경 가능한 한국어·영문 표시명, 공개 여부와 검수 근거를 분리해 저장한다.
- `code`는 현재 BGG 영문명을 ASCII `UPPER_SNAKE_CASE`로 정규화한다. 현재 189개에는 충돌이 없으며 코드 의미는 표시명이 바뀌어도 변경하지 않는다.
- 같은 게임과 메커니즘 관계는 하나만 저장한다. 기존 `Game.tag`는 현재 표시 의미를 유지한다.
- 현재 189개는 검수 후 공개하고, 이후 새 항목은 기본 비공개로 적재해 사람이 출처·표시명·관계를 검수한 뒤에만 공개한다.
- 대표 항목은 `featured_order` 1~8로 핸드 관리, 주사위 굴림, 셋 컬렉션, 협력 게임, 타일 놓기, 조립 보드, 솔로/솔로테어 게임, 일꾼 놓기 순서를 사용한다. 나머지는 대표 순서가 없다.
- 공개 선택지 API는 안정적인 코드, 한국어명, BGG 영문명과 대표 순서만 반환한다. 대표 항목을 순서대로 먼저 반환하고 나머지는 한국어명·코드 오름차순으로 반환한다.
- 반복한 메커니즘 조건 안에서는 OR, 다른 게임 필터와는 AND로 결합한다. 존재하지 않거나 비공개인 코드는 검증 오류다.
- 입력 checksum, BGG 원본 ID·영문명·내부 코드·한국어명 매핑, 검수자·검수일·출처와 승인 범위는 manifest와 품질 보고서에 남긴다. 원본 JSON·CSV와 생성 SQL은 저장소에 커밋하지 않는다.

## 결과

- 얻는 것: 현재 검수 목록 전체를 한영 표시명과 안정적인 코드로 검색하면서 새 항목의 자동 공개를 막는다.
- 감수할 비용·위험: 외부 분류 변경과 새 관계가 생길 때마다 manifest·표시명·관계 검수를 반복해야 한다.
- 후속 작업: 전진 Flyway, 결정적 카탈로그 산출 도구, 선택지 API와 단일·다중 검색을 구현하고 PostgreSQL에서 중복·공개 경계를 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: 테마 필터, 메커니즘 기반 추천, 사용자 지정 정렬, 패싯별 결과 건수와 미검수 자동 공개
- 보류 이유: #351은 검수된 메커니즘 선택지와 게임 검색에 한정한다.
- 다시 검토할 조건: 출처 계약이나 내부 코드 의미를 바꿔야 하거나, 전체 카탈로그 확장으로 현재 관계 적재 방식의 비용이 측정될 때

## 참고 자료

- [Issue #351](https://github.com/bamsongi-club/albam-mate/issues/351)
- [게임 카탈로그 취득·검증 기록](../../game-catalog/2026-08-01-bgg-detail-acquisition.md)
- [P1 검색용 출처 manifest](../../game-catalog/2026-08-03-p1-search-source-manifest.json)

## 검증

- 상태: 검증됨
- 근거:
    - 구현: `V12__create_game_mechanism_schema.sql`, `GameMechanismController`, `GameMechanismQueryService`, `GameListSearchCriteria`, `mechanism-catalog.mjs`
    - 계약: `docs/API.md`, `docs/ERD.md`, `docs/P1-spec.md`, `docs/p1/search.md`, 메커니즘 source manifest·품질 보고서
    - 테스트: `prepare-game-catalog.test.mjs`, `GameMechanismHttpIntegrationTest`, `GameListFilterPostgresTest`, `GameCatalogImportPostgresTest`

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
