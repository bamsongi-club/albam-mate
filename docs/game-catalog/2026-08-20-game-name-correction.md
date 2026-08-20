# 2026-08-20 게임명 자동 음차 보정

## 근거

- Issue: [#924](https://github.com/bamsongi-club/albam-mate/issues/924)
- 승인 코멘트: [T1~T4 승인](https://github.com/bamsongi-club/albam-mate/issues/924#issuecomment-5352476976)
- 대상: BGG XML 기반 170k catalog의 `games.name` 산출 경로

## 적용 정책

1. `추정번역(자동음차)`로 표시된 미검수 후보는 최종 `games.name`에 사용하지 않는다.
2. 검수 완료(`Y`)이고 비어 있지 않은 `nameKo`는 현재 SQL 값과 무관하게 최종 이름으로 사용한다.
3. 같은 `bgg_id`에 서로 다른 검수 완료 한글명이 있으면 어느 하나도 자동 선택하지 않고 실패한다. 같은 이름의 중복만 허용한다.
4. 미검수 자동 음차 후보만 BGG XML의 한글 `alternate`, 없으면 BGG XML primary 영문명 순으로 보정한다. candidate CSV의 `nameEn`은 fallback으로 사용하지 않는다.
5. raw XML manifest는 필수이며 전체 SHA-256이 승인 snapshot `b7aa4731c5480a434b915921cb8f7f6d6a616a007b87239bff0452b80764f524`와 같아야 한다. 사용한 XML 파일은 manifest의 `requestIds`, `responseIds`, `bytes`, `sha256`과 대조해 provenance에 남긴다.

## 입력과 현재 상태

기준 v4 `01-games-full.sql`은 SHA-256 `7866812e8ecd22942eccc3dee4553b49161af6297399c907b6a2953a9abb3c19`, 175,229행·고유 `bgg_id` 175,229개다.

현재 후보 입력에는 `bgg_id=327266`의 상충하는 검수 완료 한글명이 있습니다.

- `네덜란드 저항군: 오렌지는 승리하리라!`
- `더치 레지스탕스: 오렌지가 승리하리라!`

따라서 사람의 최종 선택 전에는 새 handoff를 생성하지 않는다. 기존 `albam-mate-170k-patched-v5.zip`은 이 fail-closed 경계 이전의 후보 파일이므로 release·승인 산출물로 사용하지 않는다. 새 출력 SHA-256이나 coverage 결과도 선언하지 않는다.

## release 경계

기존 v4 release와 SEARCH-04 평가 산출물의 참조는 유지한다. v5 `catalog-dataset-release` manifest는 추가하지 않았으며, 실제 보정 산출물은 상충 후보의 사람 검수, 전체 재생성, release 실측, 별도 사람 승인이 모두 끝난 뒤에만 release로 취급한다.

## 검증 결과

- `node --test --test-name-pattern='^T[1-4]:' scripts/game-catalog/game-name-correction.test.mjs`: 8/8 통과. 검수명 우선·상충 검수명 차단·XML 한글 alternate·XML primary fail-closed·provenance를 검증한다.
- `postgresTest`의 exact T1~T4 selector: 4/4 통과. 적재, API 노출, 행 수·관계·내부 ID·checksum/provenance 보존을 검증한다.
- 실제 후보 입력의 `bgg_id=327266` 상충은 새 handoff 재생성을 차단하는 기대 동작이다.

이번 변경은 게임명 산출·provenance와 데이터 적재에 한정하며, 게임 목록 정렬·가중치·인기점수·필터·스키마·관계 계약은 변경하지 않는다.
