# 2026-08-20 게임명 자동 음차 보정

## 근거

- Issue: [#924](https://github.com/bamsongi-club/albam-mate/issues/924)
- 승인 코멘트: [T1~T4 승인](https://github.com/bamsongi-club/albam-mate/issues/924#issuecomment-5352476976)
- 대상: BGG XML 기반 170k catalog의 `games.name` 산출 경로

## 적용 정책

1. `추정번역(자동음차)`로 표시된 미검수 후보는 최종 `games.name`에 사용하지 않는다.
2. 검수된 후보가 있으면 기존 이름을 유지한다.
3. 미검수 후보만 있는 경우 BGG XML의 한글 `alternate`를 우선 사용한다.
4. 한글 `alternate`가 없으면 BGG XML primary 영문명을 사용한다.

후보 13,934행(고유 BGG ID 12,873개) 중 자동 음차 후보 8,731행을 확인했다. 검수된 후보가 함께 있는 중복 ID는 보정 대상에서 제외하고, 최종 7,873개 이름을 보정했다. 그중 BGG 한글 alternate는 348개, primary 영문 fallback은 7,525개이며 XML 누락은 0개다.

## 산출물

- 보정 스크립트: `scripts/game-catalog/game-name-correction.mjs`
- provenance 테스트: `scripts/game-catalog/game-name-correction.test.mjs`
- 신규 release manifest: `docs/game-catalog/catalog-dataset-release-v5.json`
- 외부 handoff ZIP: `/Users/han-yejin/Developer/bamsongi-club/albam-mate-170k/albam-mate-170k-patched-v5.zip`
- ZIP SHA-256: `46b1575c2d32a287361662f0bd7c056927bf0532b21329d0c90600792364e21d`
- provenance JSON SHA-256: `2e995ab8f0fb8137a0117bf4cc54617e6b2df6b594059b291c7dda21c86c44b3`

`01-games-full.sql`의 입력·출력은 모두 175,229행·고유 BGG ID 175,229개다. 입력 SHA-256은 `7866812e8ecd22942eccc3dee4553b49161af6297399c907b6a2953a9abb3c19`, 출력 SHA-256은 `2df918b6b2d627f1f2537690ffc18208bf47fe591c96cc0f07c70beeed817be6`이다. 170,000개 catalog ID set과 `02-metadata-full.sql` 및 관계 coverage는 유지된다.

`bgg_id=370749`에는 다음 UPDATE가 포함된다.

```sql
UPDATE games SET name = '웬디, 어른이 되렴' WHERE bgg_id = 370749;
```

기존 v4 release와 SEARCH-04 평가 산출물의 참조를 깨지 않도록 v5 manifest를 별도 추가했다. v4 artifact는 변경하지 않았다.

## 검증 결과

- `node --test scripts/game-catalog/game-name-correction.test.mjs`: 4/4 통과
- `postgresTest`의 T1~T4: 통과
- release 실측: dataset 170,000행, catalog ID·mechanism·theme·player preference coverage checksum 일치
- 로컬 PostgreSQL/API: `games` 170,005행, 관계 행 수 및 대상 내부 ID `138718` 유지, `/api/games?keyword=웬디, 어른이 되렴`에서 대상 1건 노출

이번 변경은 게임명 산출·provenance와 데이터 적재에 한정하며, 게임 목록 정렬·가중치·인기점수·필터·스키마·관계 계약은 변경하지 않는다.
