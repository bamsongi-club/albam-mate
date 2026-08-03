# 2026-08-03 P1 검색 수치 품질 보고서

## 실행 입력과 승인

`games.p1-search-time-corrected-2026-08-03.json`(2,000건, SHA-256 `c8800eaf0f4e276722162f1371d72cab08ae6ac440e730c2567321300c4a9cf6`)과 `boardgames_ranks07-24.csv`(179,329건, SHA-256 `b706d0ae3722e063f6b36b9faaf97f3533fce45605c0dfe01c347a68ea2aa56d`)를 사용했다. 검수자 `beyejin`이 2026-08-03 07:41:08 UTC에 [Issue #293 승인 코멘트](https://github.com/bamsongi-club/albam-mate/issues/293#issuecomment-5163626054)로 보정 배치와 품질 경고 수용 범위를 승인했다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games "$ALBAM_CATALOG_INPUT_DIR/games.p1-search-time-corrected-2026-08-03.json" \
  --ranks "$ALBAM_CATALOG_INPUT_DIR/boardgames_ranks07-24.csv" \
  --manifest docs/game-catalog/2026-08-03-p1-search-source-manifest.json \
  --out /tmp/albam-mate-issue-293-quality
```

manifest의 `toolCommit`은 기호를 보존하는 판본 충돌 판정과 플레이 시간 보정을 포함한 고정 commit `896b37396b5bc6d11a866519b8fccb3971c3fb94`다. 위 입력과 도구로 다시 실행한 `quality-report.json`은 오류 0건의 `ready` 상태이며 서비스 카탈로그 2,000건과 UPSERT SQL을 생성했다. 원본·보정 JSON과 생성 산출물은 저장소에 넣지 않는다.

## 검색 수치 정규화 결과

| 필드 | 전체 | 유효 | 누락 | 제외 | NULL 정규화 | 제외 사유 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 가능 인원 (`min_players`, `max_players`) | 2,000 | 2,000 | 0 | 0 | 0 | 없음 |
| 플레이 시간 (`min_play_time_minutes`, `max_play_time_minutes`) | 2,000 | 1,997 | 3 | 0 | 3 | `정보 없음` 3건을 `NULL/NULL`로 정규화 |
| 복잡도 (`complexity`) | 2,000 | 2,000 | 0 | 0 | 0 | 없음 |

`90분`은 `90/90`으로 적재하고 `정보 없음`은 표시 문자열을 유지한 채 검색 수치만 `NULL/NULL`로 둔다. 그 밖의 해석 불가·0·음수·역전·JavaScript safe integer 또는 PostgreSQL `INTEGER` 범위 초과값은 품질 오류로 전체 배치를 차단한다. 복잡도 `0.00`은 평가 없음으로 `NULL`이며 `1.00`~`5.00`만 유지한다.

## 카탈로그 검수와 수용 경고

BGG ID·영문명·순위 일치, 중복 BGG ID, 필수값, 길이, 이미지 URL, 복잡도 형식 검사는 각각 2,000건에서 위반이 없었다. 판본 충돌 비교에서 제목 의미 기호를 보존한 결과 `POSSIBLE_VERSION_COLLISION`은 필드 기준 33그룹으로 집계됐다. 이는 서로 다른 BGG ID를 병합한다는 뜻이 아니라 판본·번역명 개선 후보를 남기는 경고다.

간단 설명의 최다 템플릿 비율은 52.00%(1,040/2,000), 상세 설명은 94.05%(1,881/2,000)다. 검수자는 이번 배치에서 `POSSIBLE_VERSION_COLLISION`, `LOW_DESCRIPTION_DIVERSITY`, `LOW_DETAIL_DESCRIPTION_DIVERSITY`를 수용했다.

## 생성 산출물

| 산출물 | 결과 | SHA-256 |
| --- | ---: | --- |
| `quality-report.json` | `ready`, 오류 0건 | `785499090647349a370c7801e28b0446458459bbb20ed627491a1f4cbbbd386e` |
| `service-catalog.json` | 2,000건 | `25ee1249f0af6340d357a8da96ccc8f493c31b829beaef90eafa7ed6b764b6f3` |
| `upsert-games.sql` | 결정적 UPSERT | `3c4c1af2178164b60c344a4775b55beb2b4effc8a58101440bbbfaa8c77d7dce` |
