# 첨부 v4 게임 설명 품질 재측정

## 범위와 입력

2026-08-19에 사용자 제공 `albam-mate-170k-patched-v4.zip` 내부 `01-games-full.sql`을 PostgreSQL에 실행하지 않고 읽기 전용으로 파싱했다.

재측정은 저장소의 `scripts/game-catalog/parse-game-catalog-sql.mjs`를 사용했다. 이 parser는 SQL을 실행하거나 전체 행을 메모리에 보관하지 않고, `INSERT INTO games`의 column list를 기준으로 표준 SQL 문자열·`NULL`·여러 statement를 streaming 해석한다. 문자열 내부의 escaped quote, comma, 괄호, multiline, backslash와 `ON CONFLICT` tail은 회귀 테스트로 고정했다.

| 항목 | 결과 |
| --- | --- |
| SQL SHA-256 | `7866812e8ecd22942eccc3dee4553b49161af6297399c907b6a2953a9abb3c19` |
| `INSERT INTO games` 문 수 | 40 |
| SQL 행 수 | 175,234 |
| 고유 `bgg_id` 수 | 175,234 |
| 최대 `bgg_id` | 990,005 |
| 전달 README의 기대 행 수 | 170,005 |

따라서 이 SQL은 README가 설명하는 `170,005`건 기준 catalog와 행 수가 일치하지 않는다. 이 작업에서는 SQL 실행·DB 갱신·기존 데이터 삭제를 수행하지 않았다.

## 설명 필드 분포

분류기는 `description-language-v2`를 사용했다. 완전한 영문 문장 segment가 한국어 본문과 함께 있거나, 일반 영문 구문이 한국어 조사와 연결된 경우 `mixed`로 분류한다. 반대로 강한 Title Case 게임명·고유명사, 약어, 숫자·기호가 포함된 Latin span은 허용 목록으로 제거한 뒤 한국어 본문이면 `korean`으로 분류한다. 문자가 없으면 `other`, 빈 값은 `missing`이다. 따라서 아래 수치는 기존 v1 비율 기준 측정값과 직접 비교하지 않는다.

| 필드 | `korean` | `english` | `mixed` | `other` | `missing` |
| --- | ---: | ---: | ---: | ---: | ---: |
| `description` | 215 | 93,057 | 81,842 | 120 | 0 |
| `detail_description` | 55 | 13,591 | 161,587 | 1 | 0 |

행 단위로 합치면 `mixed`는 161,594건, `english`·`other`를 포함한 미번역 행은 93,177건, 두 필드가 모두 `korean`인 행은 47건이다.

parser 결과는 SQL 행 175,234건, 고유 `bgg_id` 175,234건, 중복 ID 행 0건, `INSERT` 40개와 일치했다. SQL SHA-256은 `7866812e8ecd22942eccc3dee4553b49161af6297399c907b6a2953a9abb3c19`이다.

## provenance 판단

SQL에는 최종 문자열과 기본 게임 필드만 있고 설명 필드별 `source`, `sourceVersion`, `processing`, `status`, `reviewedBy`, `reviewedAt`가 없다. 따라서 혼합 문장의 생성 경로는 이 전달본만으로 확정할 수 없으며, 해당 값을 승인된 한국어 설명으로 간주하거나 자동 번역 결과로 재사용하지 않는다.

현재 파이프라인은 다음을 적재 전 필수 조건으로 둔다.

- `mixed` 설명은 `MIXED_DESCRIPTION_BLOCKED`로 항상 차단한다.
- 영문 원문을 유지하거나 한국어 설명을 보정하려면 필드별 승인 provenance를 요구한다.
- provenance가 없는 번역·재작성 입력은 SQL export와 release manifest 검증에서 차단한다.
