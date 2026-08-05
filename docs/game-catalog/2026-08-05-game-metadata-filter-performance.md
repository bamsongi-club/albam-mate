# 17만 게임 메타데이터 필터 성능 측정

- fixture SHA-256: `87f5795a6d3e29d6375fffb61cf255a4c5aeabceb9339a992187cf26c4acf321`
- fixture rows: 170,000
- 관계: `performanceFixtureRelations=true`. 카테고리는 실제 rank CSV의 양수 rank만 조인했고, 테마·추천/베스트·메커니즘은 성능 전용 고정 seed입니다. 운영 artifact로 사용하지 않습니다.
- 측정: PostgreSQL Testcontainers, cache·추가 검색 인덱스 없음

```sh
JAVA_TOOL_OPTIONS='-Dissue420.fixture=/Users/han-yejin/Downloads/albam-mate-search-perf-170k/games-170k.performance.json -Dissue420.fixtureManifest=/Users/han-yejin/Downloads/albam-mate-search-perf-170k/source-manifest.performance.json -Dissue420.rankCsv=/Users/han-yejin/Downloads/boardgames_ranks07-24.csv -Dissue420.performanceReport=/var/folders/v9/g612x8h14tbf3_946mnk5f1h0000gn/T/albam-mate-issue-420-performance-report.json' ./gradlew postgresPerformanceTest --tests 'cloud.bamsongi.albammate.game.GameMetadataSearchPerformancePostgresTest.십칠만건_fixture에서_대표조합의_결과_전체건수_실행계획과_시간을_기록한다' --rerun --fail-fast
```

| 조합 | total | page IDs | elapsed ms | 실행 계획 핵심 |
|---|---:|---:|---:|---|
| no-filter | 170,000 | 10 | 95 | `EXPLAIN ANALYZE BUFFERS FORMAT JSON` 기록 |
| category-single | 3,266 | 10 | 26 | 상관 EXISTS |
| category-or | 6,423 | 10 | 47 | 상관 EXISTS + IN |
| theme-any-single | 84,838 | 10 | 561 | 상관 EXISTS |
| theme-any-multiple | 113,279 | 10 | 674 | 상관 EXISTS + IN |
| theme-all-multiple | 28,345 | 10 | 2,231 | countDistinct 상관 subquery |
| compound | 835 | 10 | 87 | 카테고리·인원·메커니즘·테마 AND |

각 조합의 전체 `EXPLAIN ANALYZE BUFFERS FORMAT JSON`과 page IDs는 저장소 밖 성능 report에만 둡니다. 측정은 역방향 검색 인덱스나 cache 도입 근거를 만들지 않았습니다.
