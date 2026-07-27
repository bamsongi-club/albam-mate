# 2026-07-24 게임 카탈로그 입력 검수

- 상태: 적재 차단
- 검수일: 2026-07-27
- 기준: [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)
- 입력: `games.json`, `boardgames_ranks07-24.csv`

## 입력 식별

| 역할 | 파일 | 행 수 | SHA-256 |
| --- | --- | ---: | --- |
| 서비스 표시 자료 | `games.json` | 2,000 | `5deb7f9922065bc55aaca9a7cad2356fadd241d554b1812b45cd60fb9c714a02` |
| BGG 기준 스냅샷 | `boardgames_ranks07-24.csv` | 179,329 | `b706d0ae3722e063f6b36b9faaf97f3533fce45605c0dfe01c347a68ea2aa56d` |

실제 출처, 취득 방식·시각과 이용 조건은 아직 확인되지 않았다. 원본 파일은 저장소에 복사하지 않았다.

## 로컬 초안 검증 결과

아래 수치는 표의 SHA-256과 일치하는 로컬 파일을 현재 PR의 변환 도구로 실행한 결과다. 원본 취득 경로와 이용 조건이 아직 없어 동일 자료를 다시 취득할 수 있다는 증거 또는 적재 승인을 뜻하지 않는다.

- 차단 `quality-report.json` SHA-256: `7d4f086e15e3f340d27adc2e55c2a3c4f049c09862938141bfb427f5fe8301f2`

| 검사 | 결과 |
| --- | ---: |
| BGG ID·영문명·기준 순위 일치 | 2,000 / 2,000 |
| 서비스 입력 내 `bgg_id` 중복 | 0건 |
| 필수값 누락 | 0건 |
| DB 필드 길이 초과 | 0건 |
| 형식·범위를 벗어난 `complexity`·이미지 URL | 0건 |
| 포함된 BGG 확장 게임 | 0건 |
| 같은 표시 이름을 쓰는 판본·변형 후보 | 22그룹 |
| `complexity`·BGG 순위 Pearson 상관계수 | -0.941233 |
| 가능 인원 표현 종류 | 53개 |
| 예상 플레이 시간 표현 종류 | 155개 |

입력의 구조와 BGG 스냅샷 매핑은 적재 가능한 형태지만, 내용 품질은 승인할 수 없다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --out build/game-catalog/2026-07-24-draft
```

## 적재 차단 사유

- `complexity` 2,000개가 3.13~4.20에만 분포하고 BGG 순위와 Pearson 상관계수 `-0.9412`를 보인다. 순위 인접 행 사이에서 복잡도 상승은 0회, 하락은 100회, 동일은 1,899회로 실제 BGG `averageweight`가 아니라 순위 기반 생성값으로 판단된다.
- `supported_player_count`는 게임 규칙상 플레이 가능한 범위로만 취급한다. 추천·최적 인원과는 구분되며 실제 값의 출처와 정확성은 아직 승인되지 않았다.
- 이름·영문명·연도·숫자를 정규화하면 1,040행의 간단 설명이 같은 전략 게임 문구다.
- 이름·영문명·연도·숫자를 정규화하면 1,881행의 상세 설명이 같은 승리·종료 조건 문구다.
- 서로 다른 `bgg_id`가 같은 표시 이름을 쓰는 22그룹은 판본·변형 매핑을 사람이 확인해야 한다.
- 두 입력의 실제 출처와 필드별 이용 조건이 기록되지 않았다.

## 다음 검수

1. [출처 manifest](2026-07-24-source-manifest.draft.json)의 출처·취득 시각·이용 조건 `TODO`를 실제 근거로 교체한다.
2. BGG의 실제 `averageweight`와 가능 인원 값의 출처를 확인하고, 추천·최적 인원은 별도 취득 근거가 준비되기 전까지 추가하지 않는다.
3. 판본·변형 후보와 반복 문구의 사실성을 검수하고 잘못된 행을 수정하거나 제외한다.
4. 검수자가 남은 경고를 확인한 경우에만 `acceptedWarnings`에 해당 코드를 기록하고 `review.status`를 `approved`로 바꾼다.
5. 변환 도구를 다시 실행해 `service-catalog.json`, `upsert-games.sql`과 최종 체크섬을 생성한다.
6. PostgreSQL에서 신규 삽입·기존 갱신·실패 롤백·반복 적재를 검증한 뒤 공유 환경에 반영한다.
