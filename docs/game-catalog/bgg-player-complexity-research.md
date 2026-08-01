# BGG 가능 인원·복잡도 취득 및 검증 기록

- 조사·취득일: 2026-08-01
- 상태: 로컬 조사 완료, 서비스 적재 미승인
- 범위: 현재 팀 입력 2,000건의 BGG XML API2 상세 데이터
- 관련 정본: [SEARCH-01](../p1/search.md#search-01-게임-조건-검색), [ADR-0026](../adr/game/0026-p1-game-search-normalized-numeric-fields.md), [카탈로그 적재 절차](../guides/GAME_CATALOG_IMPORT.md)

## 결론

- 승인된 BGG Application Token으로 2,000개 ID를 모두 취득했고, 요청·응답 ID가 전부 일치했다.
- BGG `minplayers`·`maxplayers`는 기존 표시 문자열과 2,000건 모두 일치해 검색용 `min_players`·`max_players` 초안을 만들 수 있다.
- BGG `averageweight`는 소수 둘째 자리 `ROUND_HALF_UP`으로 정규화했다. 기존 값과 달랐던 81건을 갱신한 뒤 2,000건 모두 당시 BGG 응답과 일치했다.
- 이번 표본에는 `numweights == 0`인 게임이 없었다. 따라서 `평가 없음 → NULL` 규칙은 정본에는 유지하되 실제 무평가 응답 표본 검증은 `확인 필요`다.
- 이 결과는 로컬 기술 검증이다. 새 BGG 데이터의 저장·재가공·공개 사용 승인이나 서비스 적재 승인을 뜻하지 않는다.

## 필드 의미와 취득 경로

| 서비스 후보 값 | BGG XML 경로 | 의미 |
| --- | --- | --- |
| `min_players` | `item/minplayers@value` | 제작사 기준 가능 인원의 하한 |
| `max_players` | `item/maxplayers@value` | 제작사 기준 가능 인원의 상한 |
| `complexity` | `item/statistics/ratings/averageweight@value` | BGG 사용자 Weight 투표 평균 |
| 평가 여부 보조값 | `item/statistics/ratings/numweights@value` | Weight 투표 수 |

가능 인원은 커뮤니티의 추천·최적 인원과 다르다. Weight도 제작사가 정한 객관적 난이도가 아니라 BGG 사용자의 주관적 투표 평균이므로 화면에서는 `BGG 복잡도`처럼 출처를 드러내는 편이 정확하다.

사용한 요청 형태는 다음과 같다.

```http
GET https://boardgamegeek.com/xmlapi2/thing?id={최대 20개 BGG ID}&type=boardgame&stats=1
Authorization: Bearer {APPLICATION_TOKEN}
```

20개씩 100회 순차 요청하고 요청 사이 5초를 두었다. `202`, `429`, `500`, `503`은 재시도 대상으로 기록했다. 100개 응답은 모두 HTTP 200이었고 토큰은 manifest·XML·보고서에 저장하지 않았으며 취득 후 폐기했다.

## 로컬 배치 식별

원본과 생성 산출물은 [기존 적재 원칙](../guides/GAME_CATALOG_IMPORT.md)에 따라 저장소에 커밋하지 않는다. 아래 체크섬은 같은 로컬 배치를 식별하기 위한 기록이며, 팀 공유·서비스 사용 권한을 부여하지 않는다.

| 단계 | 행 수 | SHA-256 |
| --- | ---: | --- |
| 취득 전 `games.json` 백업 | 2,000 | `940cb04b78b1a8aeb6c4d04b6601595da0a8728c6cf62d4db0be843b44b5ce27` |
| 인원·복잡도 반영본 | 2,000 | `b4a815ff189f2a3106a656a8ed31f91ab27badd242a4b980a49e8890b62fc937` |
| 메커니즘·한국어 편집 라벨까지 반영한 최종 로컬본 | 2,000 | `efc22093ada6a32d5570d517686a12fcb18b4973d7397f10de65bf818eec81f3` |

BGG 스냅샷은 100개 XML 파일과 파일별 ID·HTTP 상태·바이트·SHA-256을 가진 로컬 manifest로 구성했다. manifest 결과는 요청 ID 2,000개, 고유 응답 ID 2,000개, 누락·초과 ID 0개다.

## 검증 결과

### 가능 인원

| 검사 | 결과 |
| --- | ---: |
| 기존 표시 문자열 파싱 실패 | 0건 |
| BGG 최소·최대 범위 오류 | 0건 |
| 기존 표시값과 BGG 범위 일치 | 2,000 / 2,000건 |
| 반영 후 `min_players >= 1`, `min_players <= max_players` 위반 | 0건 |

기존 표시 문자열과 XML 응답의 일치는 파서·ID 매핑에 대한 교차 확인이다. 두 값 모두 BGG 계열 자료이므로 독립된 사실 검증으로 세지 않는다. 독립 2차 검증이 필요하면 게임별 제작사·출판사 제품 페이지 또는 해당 판본의 공식 규칙서와 대조해야 한다.

### 복잡도

| 검사 | 결과 |
| --- | ---: |
| 기존 값과 당시 BGG 값을 둘째 자리로 반올림해 일치 | 1,919건 |
| 기존 값과 달라 갱신 | 81건 |
| 갱신 후 당시 BGG 값과 일치 | 2,000 / 2,000건 |
| `numweights` 필드 누락 | 0건 |
| `numweights == 0` | 0건 |
| 목표 외 필드 변경 | 0건 |

반올림은 이진 부동소수점이 아니라 십진수 `ROUND_HALF_UP`을 사용했다. Weight는 계속 변하는 사용자 통계이므로 취득 시각과 투표 수를 함께 관리해야 한다. 제작사 난이도나 다른 서비스 점수는 의미가 달라 독립된 같은 값으로 검증할 수 없다.

## 서비스 반영 전 확인 필요

- BGG 승인 내용이 원본 응답의 로컬 보관, 숫자 필드 매핑·반올림, `0 → NULL` 정규화와 공개 서비스 사용을 허용하는지 확인한다.
- 새 배치 manifest에 취득 시각·입력 체크섬·필드별 출처·이용 범위와 검수자를 기록한다.
- `numweights == 0`인 실제 인증 응답으로 무평가 처리 규칙을 검증한다.
- 가능 인원의 독립 검증 범위와 판본 불일치 처리 기준을 정한다.
- ADR-0026 승인 뒤 ERD·Flyway·적재 도구·PostgreSQL 검증을 별도 구현 이슈에서 함께 반영한다.

## 공식 출처

- [Using the XML API](https://boardgamegeek.com/using_the_xml_api)
- [BGG XML API2](https://boardgamegeek.com/wiki/page/BGG_XML_API2)
- [XML API Terms of Use](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)
- [BGG Game entry](https://boardgamegeek.com/wiki/page/Game_entry)
- [BGG Weight](https://boardgamegeek.com/wiki/page/weight)
