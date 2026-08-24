# 2026-08-01 BGG 상세 데이터 취득·검증 기록

- 조사·취득일: 2026-08-01
- 상태: 로컬 검증 완료, 공개 목록·서비스 적재 미승인
- 범위: 현재 팀 입력 2,000건의 BGG XML API2 상세 데이터 — 가능 인원, 복잡도, 메커니즘과 한국어 편집 라벨
- 관련 정본: [SEARCH-01 게임 조건 검색](../archive/p1/search.md#search-01-게임-조건-검색), [ADR-0026](../adr/game/0026-p1-game-search-normalized-numeric-fields.md), [ADR-0027](../adr/game/0027-controlled-game-mechanism-taxonomy-and-provenance.md), [카탈로그 적재 절차](../guides/GAME_CATALOG_IMPORT.md)

## 결론

- 승인된 BGG Application Token으로 2,000개 ID를 모두 취득했고, 요청·응답 ID가 전부 일치했다.
- BGG `minplayers`·`maxplayers`는 기존 표시 문자열과 2,000건 모두 일치해 검색용 `min_players`·`max_players` 초안을 만들 수 있다.
- BGG `averageweight`는 소수 둘째 자리 `ROUND_HALF_UP`으로 정규화했다. 기존 값과 달랐던 81건을 갱신한 뒤 2,000건 모두 당시 BGG 응답과 일치했다.
- 이번 표본에는 `numweights == 0`인 게임이 없었다. 따라서 `평가 없음 → NULL` 규칙은 정본에는 유지하되 실제 무평가 응답 표본 검증은 `확인 필요`다.
- BGG XML의 `boardgamemechanic` ID와 영문명은 원본 식별 정보로 보존한다. 한국어는 원본을 덮어쓰지 않고 별도 `name_ko` 편집 라벨 초안으로 관리하며, BGG의 공식 한국어 명칭으로 표시하지 않는다.
- 메커니즘 189개와 게임 관계 13,263건을 확인했다. 1,998개 게임은 하나 이상을 가졌고 2개 게임은 응답에 메커니즘이 없었다.
- 이번 189개 전체는 출처 후보 데이터다. ADR-0027의 서비스 공개 계약은 팀이 검수한 내부 10~20개로 시작하므로 그대로 필터 목록이나 DB 계약으로 사용하지 않는다.
- 이 결과는 로컬 기술 검증이다. 새 BGG 데이터의 저장·재가공·공개 사용 승인이나 서비스 적재 승인을 뜻하지 않는다.

## 취득 경로와 보안 조치

사용한 요청 형태는 다음과 같다.

```http
GET https://boardgamegeek.com/xmlapi2/thing?id={최대 20개 BGG ID}&type=boardgame&stats=1
Authorization: Bearer {APPLICATION_TOKEN}
```

20개씩 100회 순차 요청하고 요청 사이 5초를 두었다. `202`, `429`, `500`, `503`은 재시도 대상으로 기록했다. 100개 응답은 모두 HTTP 200이었고 토큰은 manifest·XML·보고서에 저장하지 않았으며 취득 후 폐기했다.

BGG 스냅샷은 100개 XML 파일과 파일별 ID·HTTP 상태·바이트·SHA-256을 가진 로컬 manifest로 구성했다. manifest 결과는 요청 ID 2,000개, 고유 응답 ID 2,000개, 누락·초과 ID 0건이다.

## 로컬 배치 식별

원본과 생성 산출물은 [기존 적재 원칙](../guides/GAME_CATALOG_IMPORT.md)에 따라 저장소에 커밋하지 않는다. 아래 체크섬은 같은 로컬 배치를 식별하기 위한 기록이며, 팀 공유 위치나 서비스 사용 권한을 뜻하지 않는다.

| 단계 | 항목 수 | SHA-256 |
| --- | ---: | --- |
| 취득 전 `games.json` 백업 | 게임 2,000 | `940cb04b78b1a8aeb6c4d04b6601595da0a8728c6cf62d4db0be843b44b5ce27` |
| 인원·복잡도 반영본 | 게임 2,000 | `b4a815ff189f2a3106a656a8ed31f91ab27badd242a4b980a49e8890b62fc937` |
| 영문 메커니즘 반영본 | 게임 2,000 | `c466ef66276613434fa6b1955b4648af684b19d767743360f79f1f00b0b39bdb` |
| 한국어 라벨 맵 | 메커니즘 189 | `d47bd9e671e9fda7f32a081ed8f67a08aa13c399e5d49e18cba28bde8db8b4c2` |
| 한국어 라벨까지 반영한 최종 로컬본 | 게임 2,000 | `efc22093ada6a32d5570d517686a12fcb18b4973d7397f10de65bf818eec81f3` |
| 최종 검증 보고서 | 검사 1회 | `b371e675005cf16abe8cb1b30818be3a17a472052fec247dc7c10f079252b47f` |

## 가능 인원과 복잡도

### 필드 의미와 취득 경로

| 서비스 후보 값 | BGG XML 경로 | 의미 |
| --- | --- | --- |
| `min_players` | `item/minplayers@value` | 제작사 기준 가능 인원의 하한 |
| `max_players` | `item/maxplayers@value` | 제작사 기준 가능 인원의 상한 |
| `complexity` | `item/statistics/ratings/averageweight@value` | BGG 사용자 Weight 투표 평균 |
| 평가 여부 보조값 | `item/statistics/ratings/numweights@value` | Weight 투표 수 |

가능 인원은 커뮤니티의 추천·최적 인원과 다르다. Weight도 제작사가 정한 객관적 난이도가 아니라 BGG 사용자의 주관적 투표 평균이므로 화면에서는 `BGG 복잡도`처럼 출처를 드러내는 편이 정확하다.

### 가능 인원 검증

| 검사 | 결과 |
| --- | ---: |
| 기존 표시 문자열 파싱 실패 | 0건 |
| BGG 최소·최대 범위 오류 | 0건 |
| 기존 표시값과 BGG 범위 일치 | 2,000 / 2,000건 |
| 반영 후 `min_players >= 1`, `min_players <= max_players` 위반 | 0건 |

기존 표시 문자열과 XML 응답의 일치는 파서·ID 매핑에 대한 교차 확인이다. 두 값 모두 BGG 계열 자료이므로 독립된 사실 검증으로 세지 않는다. 독립 2차 검증이 필요하면 게임별 제작사·출판사 제품 페이지 또는 해당 판본의 공식 규칙서와 대조해야 한다.

### 복잡도 검증

| 검사 | 결과 |
| --- | ---: |
| 기존 값과 당시 BGG 값을 둘째 자리로 반올림해 일치 | 1,919건 |
| 기존 값과 달라 갱신 | 81건 |
| 갱신 후 당시 BGG 값과 일치 | 2,000 / 2,000건 |
| `numweights` 필드 누락 | 0건 |
| `numweights == 0` | 0건 |
| 목표 외 필드 변경 | 0건 |

반올림은 이진 부동소수점이 아니라 십진수 `ROUND_HALF_UP`을 사용했다. Weight는 계속 변하는 사용자 통계이므로 취득 시각과 투표 수를 함께 관리해야 한다. 제작사 난이도나 다른 서비스 점수는 의미가 달라 독립된 같은 값으로 검증할 수 없다.

## 메커니즘과 한국어 편집 라벨

### 취득·검증 결과

BGG XML의 다음 링크만 게임별 원본 관계로 읽었다.

```xml
<link type="boardgamemechanic" id="2040" value="Hand Management" />
```

| 검사 | 결과 |
| --- | ---: |
| XML과 매핑한 게임 | 2,000건 |
| 메커니즘이 있는 게임 | 1,998건 |
| 메커니즘이 없는 게임 | 2건 |
| 고유 BGG 메커니즘 | 189개 |
| 게임-메커니즘 관계 | 13,263건 |
| 게임 내 중복 관계 | 0건 |
| 같은 BGG ID의 영문명 충돌 | 0건 |
| 한국어 빈 라벨·중복 라벨 | 0건 |
| 메커니즘 외 필드 변경 | 0건 |

BGG 응답에 관계가 없었던 게임은 `Timeline: Events`(`bgg_id=113401`)와 `Drop It`(`bgg_id=244916`)이다. 누락을 임의로 추정해 채우지 않는다.

### 한국어 편집 원칙

1. `name`은 BGG 영문 원본으로 유지한다.
2. `name_ko`는 한국어 사용자에게 의미가 드러나는 짧은 명사형 초안으로 작성한다.
3. 국내에 정착한 용어는 유지한다: `덱`, `드래프팅`, `레거시`, `론델`, `트릭 테이킹` 등.
4. 단순 음역보다 의미가 분명한 표현을 우선한다: `Take That` → `직접 공격`.
5. 서로 다른 BGG ID가 같은 한국어 라벨이 되지 않도록 구분한다: `Deduction` → `추리`, `Induction` → `귀납 추리`.
6. 경매·차례 순서·일꾼 놓기 계열은 공통 접두어를 사용해 함께 찾기 쉽게 한다.
7. 번역이 바뀌어도 참조가 끊기지 않도록 원천 관계는 `bgg_id`를 기준으로 추적한다.

국내 통용 용어의 단일 공식 표준은 확인하지 못했다. [보드라이프 진행방식](https://boardlife.co.kr/info/mechanisms)은 한국어 표현을 비교하는 참고 자료로만 사용하고, 게임별 BGG 관계의 근거로 사용하지 않는다. 모호한 용어는 BGG의 의미와 대표 게임을 확인한 뒤 팀 검수를 거쳐야 한다.

### 서비스 후보 구조와 경계

로컬 원천 후보의 한 항목은 다음 형태다.

```json
{
  "bgg_id": "2040",
  "name": "Hand Management",
  "name_ko": "핸드 관리"
}
```

이는 서비스 DB 구조가 아니다. ADR-0027을 채택하면 서비스는 안정적인 내부 ID·코드와 검수된 한국어 표시명을 가진 내부 목록, 게임과의 다대다 관계를 사용한다. BGG ID·영문명·확인 시점은 provenance로 남기되 외부 ID를 검색 API의 영구 계약으로 노출하지 않는다.

초기 10~20개 목록을 정할 때는 다음을 함께 확정한다.

- 사용자에게 익숙하고 충분히 넓은 분류인지
- 항목별 연결 게임 수가 필터로서 의미 있는지
- 비슷한 세부 메커니즘을 어느 내부 항목으로 묶을지
- 한국어 표시명·별칭과 최종 검수자
- 게임별 관계의 출처, 확인 날짜와 충돌 처리 결과
- BGG 상세 데이터와 한국어 편집 라벨의 저장·공개 이용 근거

## 서비스 반영 전 확인 필요

- BGG 승인 내용이 원본 응답의 로컬 보관, 숫자 필드 매핑·반올림, `0 → NULL` 정규화와 공개 서비스 사용을 허용하는지 확인한다.
- 새 배치 manifest에 취득 시각·입력 체크섬·필드별 출처·이용 범위와 검수자를 기록한다.
- `numweights == 0`인 실제 인증 응답으로 무평가 처리 규칙을 검증한다.
- 가능 인원의 독립 검증 범위와 판본 불일치 처리 기준을 정한다.
- 출처 이용 범위와 팀 검수가 끝나기 전에는 메커니즘을 공개 서비스에 적재하지 않는다.
- ADR-0026·ADR-0027 승인 뒤 ERD·Flyway·적재 도구·PostgreSQL 검증을 별도 구현 이슈에서 함께 반영한다. 이때 「한국어 편집 원칙」과 「필드 의미와 취득 경로」는 날짜 있는 조사 기록이 아니라 해당 ADR로 옮긴다.

## 확인한 출처

- [Using the XML API](https://boardgamegeek.com/using_the_xml_api)
- [BGG XML API2](https://boardgamegeek.com/wiki/page/BGG_XML_API2)
- [XML API Terms of Use](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)
- [BGG Game entry](https://boardgamegeek.com/wiki/page/Game_entry)
- [BGG Weight](https://boardgamegeek.com/wiki/page/weight)
- [BGG Board Game Mechanics](https://boardgamegeek.com/browse/boardgamemechanic): BGG 메커니즘 영문 분류 목록
- [BGG mechanism wiki](https://boardgamegeek.com/wiki/page/mechanism): 주요 메커니즘의 의미와 예시
- [보드라이프 진행방식](https://boardlife.co.kr/info/mechanisms): 국내 서비스의 한·영 표현 비교 참고
- 로컬 BGG XML 스냅샷: 게임별 `link[@type='boardgamemechanic']`의 `id`, `value`
