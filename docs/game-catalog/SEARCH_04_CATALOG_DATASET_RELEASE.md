# SEARCH-04 catalog dataset release 계약

## 목적

SEARCH-04의 평가 fixture가 참조할 게임 카탈로그를 하나의 재현 가능한 dataset release로 고정한다.

이 계약은 **catalog dataset 자체의 승인**만 소유한다. `search_text` 조립, embedding provider/model, dimensions, index version, vector 저장소와 embedding 산출물은 후속 Search/Embedding Execution 승인에서 별도로 확정한다. Catalog release 승인이 특정 model/provider 선택을 의미하지 않는다.

## 승인 대상

- 기준 데이터셋: `bgg-catalog-170k`
- 기준 근거: #621에서 최종 검증한 raw BGG snapshot 170,000건
- 전달 artifact 실행 순서: `01 → 01b → 02 → 03 → 04 → 05 → 06 → 07`
- #621에서 확인된 보정: 확장판 5,229건 원래 ID 복구, `game_player_preferences` `bool_or` 정책 확정, bgg_id 26~40 이름 오매핑 수정

`games` 운영 테이블의 최종 행 수 170,005와 BGG release의 170,000 ID 집합은 구분한다. SEARCH-04의 `datasetId=bgg-catalog-170k`는 BGG snapshot의 170,000 ID 집합을 가리킨다.

## manifest 계약

`catalog-dataset-release` schemaVersion 1은 다음 값을 필수로 가진다.

- `releaseId`: 이 release를 식별하는 불변 ID
- `datasetId`: `bgg-catalog-170k`
- `fieldVersion`: SEARCH-04가 참조할 catalog 필드 버전
- `approved: true`, `testOnly: false`
- `approval.reviewedBy`, `approval.reviewedAt`, `approval.references`
- `approvedFields`: SEARCH-04 평가에서 데이터 근거로 사용할 수 있는 필드 allowlist
- `dataset.rows`: 정확히 고정된 catalog row 수
- `dataset.sha256`: canonical dataset 파일의 SHA-256
- `dataset.idSetSha256`: 정렬한 BGG ID 집합의 SHA-256
- `artifacts.01`, `01b`, `02` ... `07`: 각 승인 artifact의 `path`, `sha256`, `bytes`, `status=approved`
- `coverage.catalogIds`, `mechanismRelations`, `themeRelations`, `playerPreferences`: 각 coverage 행 수와 SHA-256

실제 값은 원본 artifact를 직접 읽어 계산한 값만 허용한다. 사람이 추정한 checksum·행 수나 테스트용 값을 승인 release에 기록하지 않는다.

## 검증

구조 검증:

```bash
node --test scripts/game-catalog/catalog-dataset-release-manifest.test.mjs
```

실제 dataset/artifact 대조:

```bash
node scripts/game-catalog/measure-catalog-dataset-release.mjs \
  --manifest /path/to/catalog-dataset-release.json \
  --dataset /path/to/bgg-catalog-170k.json \
  --artifacts-root /path/to/albam-mate-170k-patched
```

검증기는 다음이 하나라도 다르면 실패한다.

- dataset row count
- dataset SHA-256
- BGG ID 집합 SHA-256
- `01~07` artifact SHA-256
- `01~07` artifact byte size

## 승인 절차

1. #621 최종 170,000 BGG snapshot과 `01~07` 전달 artifact를 release 후보로 고정한다.
2. dataset/artifact의 실제 row count·SHA-256·ID set hash·bytes를 계산한다.
3. 해당 값을 manifest에 기록하고 `approved: true`, `testOnly: false`로 확정한다.
4. GitHub에 release ID, dataset ID, checksum, 검증 결과와 승인 범위를 남기고 그 URL을 `approval.references`에 기록한다.
5. validator가 실제 파일과 manifest를 대조해 성공해야 release를 승인 상태로 취급한다.
6. SEARCH-04 fixture manifest의 `catalog.releaseId`, `datasetSha256`, `rowCount`, `manifestReference`, `releaseStatus=approved`를 이 release에 연결한다.

## Embedding Execution과의 경계

Catalog Dataset Release 승인에는 다음을 요구하지 않는다.

- embedding provider/model/modelVersion
- embedding dimensions
- indexVersion
- embedding output checksum
- vector DB 선택

이 값은 승인된 catalog release를 입력으로 실제 Dense PoC를 진행할 때 별도 실행 manifest/ADR에서 검증한다. Dataset release의 변경, allowlist 필드 변경 또는 ID 집합 변경이 발생하면 새 catalog release 승인을 요구한다.
