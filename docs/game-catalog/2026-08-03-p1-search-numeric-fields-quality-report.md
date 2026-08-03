# 2026-08-03 P1 검색 수치 품질 보고서

## 실행 입력과 재현 명령

`$ALBAM_CATALOG_INPUT_DIR/games.json`(2,000건, SHA-256 `efc22093ada6a32d5570d517686a12fcb18b4973d7397f10de65bf818eec81f3`)과 `$ALBAM_CATALOG_INPUT_DIR/boardgames_ranks07-24.csv`(SHA-256 `b706d0ae3722e063f6b36b9faaf97f3533fce45605c0dfe01c347a68ea2aa56d`)를 사용했다. 원본과 생성된 `service-catalog.json`, `upsert-games.sql`은 저장소에 넣지 않는다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games "$ALBAM_CATALOG_INPUT_DIR/games.json" \
  --ranks "$ALBAM_CATALOG_INPUT_DIR/boardgames_ranks07-24.csv" \
  --manifest docs/game-catalog/2026-08-03-p1-search-source-manifest.draft.json \
  --out /tmp/albam-mate-issue-293-quality
```

현재 manifest는 서비스 적재·데이터 검수가 `pending`인 초안이고, 입력에는 `NON_POSITIVE_VALUE` 4건이 있다. 따라서 위 재현 명령은 종료 코드 `1`과 `blocked` `quality-report.json`만 만들며 `service-catalog.json`과 `upsert-games.sql`은 만들지 않는다. manifest의 `toolCommit`은 검색 수치 변환 코드를 포함한 고정 commit `36957e01008613d468f22d6bf010744e10347f98`이며, 해당 commit의 도구와 이 manifest로 아래 집계를 재현했다.

## 검색 수치 정규화 결과

| 필드 | 전체 | 유효 | 누락 | 제외 | NULL 정규화 | 제외 사유 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 가능 인원 (`min_players`, `max_players`) | 2,000 | 2,000 | 0 | 0 | 0 | 없음 |
| 플레이 시간 (`min_play_time_minutes`, `max_play_time_minutes`) | 2,000 | 1,996 | 0 | 4 | 0 | `NON_POSITIVE_VALUE` 4건 (`0분` 3건, `90~0분` 1건) |
| 복잡도 (`complexity`) | 2,000 | 2,000 | 0 | 0 | 0 | 없음 |

인원·시간은 `N명`·`N~M명`, `N분`·`N~M분`만 PostgreSQL `INTEGER` 범위(`1`~`2147483647`)의 양의 정수로 적재한다. 비어 있는 표시값만 검색 수치 쌍을 `NULL`로 둘 수 있다. 비어 있지 않은 해석 불가·0·음수·역전·JavaScript safe integer 또는 PostgreSQL `INTEGER` 범위 초과값은 사유를 집계한 품질 오류로 전체 배치를 차단하며 JSON·SQL 산출물을 만들지 않는다. 복잡도 `0.00`은 평가 없음으로 `NULL`이며, `1.00`~`5.00`만 유지한다.

## 기존 카탈로그 검수 결과

BGG ID·영문명·순위 일치, 필수값, 길이, 이미지 URL, 복잡도 형식 검사는 각각 2,000건에서 위반이 없었다. 플레이 시간의 `NON_POSITIVE_VALUE` 4건은 검수 승인과 무관하게 전체 배치를 차단하는 오류다. 기존 판본 충돌 35그룹과 설명 다양성 경고 2건도 별도 검수 대상이다. 따라서 이 실행의 품질 보고서는 `blocked`이며, 실제 적재 산출물을 만들거나 사용하지 않는다.
