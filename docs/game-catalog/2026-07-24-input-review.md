# 2026-07-24 게임 카탈로그 입력 검수

- 상태: 검수 승인
- 검수일: 2026-07-28
- 검수자: `beyejin`
- 기준: [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)
- 입력: `games.json`, `boardgames_ranks07-24.csv`

## 입력 식별

| 역할 | 파일 | 출처·취득 방식 | 행 수 | SHA-256 |
| --- | --- | --- | ---: | --- |
| 서비스 표시 자료 | `games.json` | Albam Mate 팀원이 작성해 Slack으로 전달, 2026-07-28 02:32 KST 취득 | 2,000 | `f9adbc203023d1aa20efbfa17b8d52d8db3044ae9525d9c10704255ffcf7e321` |
| BGG 기준 스냅샷 | `boardgames_ranks07-24.csv` | BGG `boardgames_ranks_2026-07-23.zip`, 2026-07-24 17:43 KST 취득 | 179,329 | `b706d0ae3722e063f6b36b9faaf97f3533fce45605c0dfe01c347a68ea2aa56d` |

원본 파일과 생성 산출물은 저장소에 복사하지 않는다. BGG CSV는 기준 목록과 외부 식별자 검증에 사용하고, 서비스 표시 필드는 팀 제공 자료를 사용한다. 세부 출처·이용 범위는 [출처 manifest](2026-07-24-source-manifest.draft.json)에 기록한다.

## 검증 결과

아래 수치는 표의 SHA-256과 일치하는 입력을 현재 PR의 변환 도구로 실행한 결과다.

| 검사 | 결과 |
| --- | ---: |
| BGG ID·영문명·기준 순위 일치 | 2,000 / 2,000 |
| BGG 기준 스냅샷에서 찾지 못한 행 | 0건 |
| 서비스 입력 내 `bgg_id` 중복 | 0건 |
| 필수값 누락 | 0건 |
| DB 필드 길이 초과 | 0건 |
| 형식·범위를 벗어난 `complexity`·이미지 URL | 0건 |
| 포함된 BGG 확장 게임 | 0건 |
| 같은 표시 이름을 쓰는 판본·변형 후보 | 22그룹 |
| `complexity` 범위 | 1.01~4.82 |
| `complexity`·BGG 순위 Pearson 상관계수 | -0.228759 |
| 가능 인원 표현 종류 | 53개 |
| 예상 플레이 시간 표현 종류 | 155개 |

## 수용한 품질 경고

같은 판단을 개별 항목마다 반복하지 않고 경고 코드별 공통 결정으로 기록한다. 각 코드는 [출처 manifest](2026-07-24-source-manifest.draft.json)의 `acceptedWarnings`에 한 번만 포함한다.

| 경고 코드 | 근거 | 결정과 사유 |
| --- | --- | --- |
| `POSSIBLE_VERSION_COLLISION` | 동일 표시 이름을 쓰는 22그룹 | 서로 다른 `bgg_id`를 가진 항목으로 별도 판본일 가능성이 있어 현재는 모두 유지한다. 향후 BGG 상세 데이터 연동 시 판본·출시연도 등을 기준으로 재검토한다. |
| `LOW_DESCRIPTION_DIVERSITY` | 동일 구조의 간단 설명 1,040 / 2,000행(52%) | 현재 P0의 팀 제공 임시 표시 문구로 수용한다. 향후 BGG 상세 데이터 연동 시 게임별 사실에 맞는 설명으로 교체·재검토한다. |
| `LOW_DETAIL_DESCRIPTION_DIVERSITY` | 동일 구조의 상세 설명 1,881 / 2,000행(94.05%) | 현재 P0의 팀 제공 임시 표시 문구로 수용한다. 향후 BGG 상세 데이터 연동 시 게임별 승리·종료 조건으로 교체·재검토한다. |

반복 설명은 게임별 고유 설명으로 검증된 값이 아니라는 품질 한계를 유지한다. 이번 승인은 해당 한계를 인지한 상태에서 현재 입력을 P0 카탈로그로 사용하는 결정이며, BGG 상세 데이터 연동 시 교체 검수를 생략한다는 뜻이 아니다.

## 적재 산출물

승인된 manifest로 변환 도구를 다시 실행해 `quality-report.json` 상태 `ready`와 2,000행 출력을 확인했다. 생성 산출물은 저장소에 커밋하지 않는다.

| 생성 파일 | SHA-256 |
| --- | --- |
| `quality-report.json` | `099bb75a357dbc37b7ab46659372d4ca5a7eb52069808236c5e7ca9f06171332` |
| `service-catalog.json` | `2eeb3a346b364c3ee7dacac90e9f0491b3166a4b745098cdd8b7603afadbcec3` |
| `upsert-games.sql` | `d959783bfa5ed3dfc6bc7e31865c3fb568dcf8ee4d584f11c1dbfb1aa2d965b9` |

## 후속 운영

- `supported_player_count`는 게임 규칙상 플레이 가능한 인원 범위로만 사용하고 추천·최적 인원과 구분한다.
- PostgreSQL 반영 전 신규 삽입·기존 갱신·실패 롤백·반복 적재를 `postgresTest`로 검증한다.
- 새 BGG 상세 데이터를 연동할 때 판본 후보 22그룹과 반복 설명 두 경고를 다시 검수한다.
