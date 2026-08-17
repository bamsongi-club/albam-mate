# Game ADR

게임 목록의 출처, 적재·갱신과 검색 경계에 관한 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0011](0011-bgg-manual-catalog-snapshot.md) | P0 게임 카탈로그를 검수된 BGG 수동 스냅샷으로 관리 | 대체됨 | 2026-07-24 | 미검증 |
| [0014](0014-bgg-curated-service-catalog.md) | BGG 수집 데이터를 팀 검수 서비스 카탈로그로 재가공 | 대체됨 | 2026-07-24 | 미검증 |
| [0015](0015-bgg-baseline-team-collected-game-list.md) | BGG 기준 스냅샷과 팀 수집 자료로 서비스 게임 목록 구성 | 승인됨 | 2026-07-26 | 검증됨 |
| [0018](0018-expansion-type-and-relations.md) | P0 이후 단독 플레이 가능 여부와 확장 관계를 분리해 관리 | 승인됨 | 2026-08-03 | 미검증 |
| [0019](0019-bgg-full-catalog-staged-enrichment.md) | 전체 보드게임 카탈로그는 BASIC으로 확장하고 상세 정보는 단계적으로 보강 | 승인됨 | 2026-08-03 | 미검증 |
| [0025](0025-game-catalog-public-source-attribution.md) | 게임 카탈로그 출처를 전역 푸터와 공개 출처 페이지에 표시 | 승인됨 | 2026-07-31 | 미검증 |
| [0026](0026-p1-game-search-normalized-numeric-fields.md) | 게임 인원·시간 표시값과 검색 수치를 분리 | 승인됨 | 2026-08-03 | 검증됨 |
| [0027](0027-controlled-game-mechanism-taxonomy-and-provenance.md) | 게임 메커니즘을 검수된 내부 목록과 관계로 관리 | 대체됨 | 2026-08-03 | 미검증 |
| [0028](0028-explicit-user-played-game-state.md) | 사용자가 표시한 해 본 게임만 관계로 저장 | 승인됨 | 2026-08-03 | 검증됨 |
| [0048](0048-full-reviewed-game-mechanism-catalog.md) | 검수된 메커니즘 189개 전체를 안정적인 내부 목록으로 공개 | 승인됨 | 2026-08-04 | 검증됨 |
| [0050](0050-game-metadata-catalog-and-filters.md) | 17만 게임 메타데이터를 관계로 관리하고 상세 필터를 제공 | 승인됨 | 2026-08-05 | 검증됨 |
| [0057](0057-game-catalog-operational-import-strategy.md) | 게임 카탈로그 운영 적재는 승인 청크 UPSERT로 시작하고 증분 파이프라인은 후속으로 설계 | 승인됨 | 2026-08-13 | 미검증 |
| [0058](0058-external-ranking-and-popularity-sort.md) | 외부·내부 원천을 결합한 게임 인기 점수와 기본 정렬 | 승인됨 | 2026-08-14 | 미검증 |
| [0060](0060-approved-catalog-ai-embedding-scope.md) | 승인된 카탈로그 release의 AI·embedding 처리 범위를 허용 | 승인됨 | 2026-08-14 | 미검증 |
| [0066](0066-search-quality-corpus-and-full-catalog-scale-corpus-split.md) | 검색 품질 corpus와 대규모 catalog 성능 검증 corpus를 분리 | 제안됨 | 미정 | 미검증 |
