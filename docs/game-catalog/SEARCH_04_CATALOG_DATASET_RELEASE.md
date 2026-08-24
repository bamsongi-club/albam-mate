# SEARCH-04 catalog dataset release 계약

## 목적

SEARCH-04의 평가 fixture가 참조할 게임 카탈로그를 하나의 재현 가능한 dataset release로 고정한다.

이 계약은 **catalog dataset 자체의 승인**만 소유한다. `search_text` 조립, embedding provider/model, dimensions, index version, vector 저장소와 embedding 산출물은 후속 Search/Embedding Execution 승인에서 별도로 확정한다. Catalog release 승인이 특정 model/provider 선택을 의미하지 않는다.

## 승인 대상

- 기준 데이터셋: `bgg-catalog-170k`
- 기준 근거: [#621의 최종 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/621#issuecomment-5278598331)로 확인한 raw BGG snapshot 170,000건
- 전달 artifact 실행 순서: `01 → 02` (`01-games-full.sql` → `02-metadata-full.sql`)
- [#621 최종 판정](https://github.com/bamsongi-club/albam-mate/issues/621#issuecomment-5278598331)에서 확인된 보정: 확장판 5,229건 원래 ID 복구, `game_player_preferences` `bool_or` 정책 확정, bgg_id 26~40 이름 오매핑 수정

`#621` 본문에는 초기 보류 문구가 남아 있으므로, `bool_or` 확정 근거는 본문이 아니라 위 최종 승인 댓글과 [이슈 종료 댓글](https://github.com/bamsongi-club/albam-mate/issues/621#issuecomment-5279057012)이다. 이 링크는 정책·검수 근거를 가리키며, 실행 가능한 catalog release manifest가 저장소에 등록됐다는 뜻은 아니다.

`games` 운영 테이블의 최종 행 수 170,005와 BGG release의 170,000 ID 집합은 구분한다. SEARCH-04의 `datasetId=bgg-catalog-170k`는 BGG snapshot의 170,000 ID 집합을 가리킨다.

## manifest 계약

`catalog-dataset-release` schemaVersion 1은 다음 값을 필수로 가진다.

- `kind`: `catalog-dataset-release`
- `releaseId`: 이 release를 식별하는 불변 ID
- `datasetId`: `bgg-catalog-170k`
- `fieldVersion`: SEARCH-04가 참조할 catalog 필드 버전
- `approved: true`, `testOnly: false`
- `approval.reviewedBy`, `approval.reviewedAt`, `approval.references`
- `sourceSnapshot`: `source=BGG XML`, 고정 `batchId`, `rows=170000`, 원본 source manifest SHA-256
- `approvedFields`: SEARCH-04 평가에서 데이터 근거로 사용할 수 있는 필드 allowlist
- `fieldProvenance`: 각 `approvedFields`의 공개 여부·처리 허용 여부·원본 컬럼·source version·source manifest SHA-256
- `dataset.rows`: 정확히 고정된 catalog row 수
- `dataset.sha256`: canonical dataset 파일의 SHA-256
- `dataset.idSetSha256`: 정렬한 BGG ID 집합의 SHA-256
- `artifacts.01`, `02`: 각 승인 artifact의 `path`, `sha256`, `bytes`, `status=approved`
- `coverage.catalogIds`, `mechanismRelations`, `themeRelations`, `playerPreferences`: 각 coverage 행 수·SHA-256·canonical serialization version

schemaVersion 1의 고정 profile은 `datasetId=bgg-catalog-170k`, `fieldVersion=catalog-fields-v1`, `sourceSnapshot.batchId=bgg-xml-basic-170k-2026-08-10`과 `sourceSnapshot.manifestSha256`를 코드에서 함께 검증한다. `dataset.rows`와 coverage의 기대 행 수는 각각 `170000`, `428488`, `461973`, `263463`이며, 이 값만 적는 것으로는 승인되지 않는다. 각 필드 provenance의 source column·source version·공개·처리 허용·source manifest hash가 trusted profile과 일치해야 한다.

artifact의 `path`는 상대 경로이며 `..`, 절대 경로, 빈 segment를 사용할 수 없다. key별 고정 basename(`01`은 `01-games-full.sql`, `02`는 `02-metadata-full.sql`)과 일치하고, 실제 측정 시 artifact root의 realpath 안에 있는 서로 다른 regular file이어야 한다. 따라서 같은 파일을 여러 key로 선언하거나 symlink로 root 밖 파일을 가리키는 manifest는 차단한다.

실제 값은 원본 artifact를 직접 읽어 계산한 값만 허용한다. 사람이 추정한 checksum·행 수나 테스트용 값을 승인 release에 기록하지 않는다.

## 검증

구조 검증:

```bash
node --test scripts/game-catalog/catalog-dataset-release-manifest.test.mjs
node --test scripts/game-catalog/catalog-dataset-release-measurement.test.mjs
node --test scripts/game-catalog/prepare-game-catalog.test.mjs
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
- `01~02` artifact SHA-256
- `01~02` artifact byte size
- dataset ID 집합에서 SQL의 mechanism/theme/player relation을 실제로 추출한 coverage 행 수·canonical SHA-256·serialization version

## 승인 절차

1. [#621 최종 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/621#issuecomment-5278598331)과 170,000 BGG snapshot, `01~02` 전달 artifact를 release 후보로 고정한다.
2. dataset/artifact의 실제 row count·SHA-256·ID set hash·bytes를 계산한다.
3. 해당 값을 manifest에 기록하고 `approved: true`, `testOnly: false`로 확정한다.
4. GitHub에 release ID, dataset ID, checksum, 검증 결과와 승인 범위를 남기고 그 URL을 `approval.references`에 기록한다.
5. validator가 실제 파일과 manifest를 대조해 성공해야 release를 승인 상태로 취급한다.
6. SEARCH-04 fixture manifest의 `catalog.releaseId`, `datasetSha256`, `rowCount`, `manifestReference`, `releaseStatus=approved`를 이 release에 연결한다.

## Embedding Execution과의 경계

이 manifest는 **dataset release manifest**이므로 `prepare-game-catalog.mjs --manifest`에 직접 전달하지 않는다. runner는 `kind=catalog-dataset-release`를 실행 manifest가 아닌 dataset manifest로 판정해 차단한다. 검색용 적재·`search_text`·embedding 실행은 별도의 execution manifest가 다음처럼 dataset release를 ID와 SHA-256으로 참조해야 한다.

```json
{
  "datasetRelease": {
    "manifestPath": "catalog-dataset-release.json",
    "releaseId": "bgg-catalog-2026-08-16-v1",
    "datasetId": "bgg-catalog-170k",
    "manifestSha256": "<dataset-release-manifest-sha256>"
  }
}
```

`prepare-game-catalog.mjs`는 execution manifest와 같은 디렉터리를 기준으로 안전한 상대 경로·realpath를 확인하고, 참조 manifest의 schema/profile과 파일 SHA-256·`releaseId`·`datasetId`를 먼저 검증한다. 그 뒤 기존 `validateApprovedReleaseManifest`를 실행해 `approvedProcessingScopes`, `search_text`, embedding 및 실제 입력·산출물 checksum을 별도로 검증한다. dataset release 승인만으로 embedding 실행이나 구체 release의 실행 승인이 생기지는 않는다.

Catalog Dataset Release 승인에는 다음을 요구하지 않는다.

- embedding provider/model/modelVersion
- embedding dimensions
- indexVersion
- embedding output checksum
- vector DB 선택

이 값은 승인된 catalog release를 입력으로 실제 Dense PoC를 진행할 때 별도 실행 manifest/ADR에서 검증한다. Dataset release의 변경, allowlist 필드 변경 또는 ID 집합 변경이 발생하면 새 catalog release 승인을 요구한다.
