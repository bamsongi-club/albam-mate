# 게임 카탈로그 검수·적재

이 절차는 [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)의 단일 BGG 기준 스냅샷, 출처 기록, 선검증과 트랜잭션 단위 `UPSERT` 규칙을 실행한다. 원본과 생성 산출물은 저장소에 커밋하지 않고, 출처 manifest와 검수 기록만 보관한다.

## 1. 초안 검수

manifest 없이 실행해 파일 체크섬, BGG 매핑과 품질 경고를 먼저 확인한다. 검수가 끝나기 전에는 `quality-report.json`만 생성되고 종료 코드는 실패다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --out build/game-catalog/2026-07-24-draft
```

## 2. 출처·검수 기록

[manifest 초안](../game-catalog/2026-07-24-source-manifest.draft.json)에 다음 근거를 기록한다.

- 각 파일의 실제 출처, 취득 방식·시각과 이용 조건
- 필드별 입력 출처와 갱신 규칙
- `selectionRules`의 선택·제외 규칙과 `versionRules`의 본판·확장·변형 구분 규칙
- `selection`의 원본 후보 행 수·포함 행 수·제외 건수와 각 제외 항목의 식별자·사유
- 변환 도구가 포함된 전체 Git commit SHA
- 검수일·검수자
- 사람이 확인하고 수용한 품질 경고 코드

`TODO`, 체크섬 불일치, 미승인 상태 또는 미수용 경고가 하나라도 있으면 적재 산출물을 만들지 않는다.

`selection`은 다음 정합성을 만족해야 한다.

- `candidateRows = includedRows + excludedRows`
- `excludedRows`와 `exclusions.length`가 같고, 각 항목에 `identifier`와 `reason`이 있어야 한다.
- `includedRows`가 실제 `service-catalog.json` 행 수와 같아야 한다.

이 정보는 `quality-report.json`에도 그대로 기록되어 원본 후보에서 최종 적재 행까지의 차이를 추적한다.

## 3. 적재 산출물 생성

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --manifest docs/game-catalog/2026-07-24-source-manifest.draft.json \
  --out build/game-catalog/2026-07-24-approved
```

성공하면 아래 파일을 만든다.

- `quality-report.json`: 입력·결과 행 수, 체크섬, 오류와 승인된 경고
- `service-catalog.json`: 내부 `id`를 제외하고 `bgg_id`로 정규화한 서비스 데이터
- `upsert-games.sql`: 한 트랜잭션에서 실행하는 `bgg_id` 기준 `UPSERT`

`upsert-games.sql`은 기존 내부 `id`와 `created_at`을 유지하며, 새 입력에서 빠진 기존 게임을 삭제하지 않는다.

## 4. PostgreSQL 적재

검수 보고서의 상태가 `ready`일 때만 대상 데이터베이스를 명시해 실행한다.

```sh
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file build/game-catalog/2026-07-24-approved/upsert-games.sql
```

어느 행에서든 실패하면 `COMMIT`에 도달하지 않아 전체 배치가 롤백된다. 적재 전후 행 수, 기존 `bgg_id`의 내부 `id` 유지와 반복 실행 결과를 확인한다.

## 검증 명령

```sh
node --test scripts/game-catalog/prepare-game-catalog.test.mjs
./gradlew postgresTest --no-daemon --stacktrace
```

`postgresTest`는 Docker가 필요하다. Docker 데몬이 없어서 Testcontainers가 시작되지 못한 경우에는 테스트 실패가 아니라 실행 환경 제약으로 별도 기록한다.
