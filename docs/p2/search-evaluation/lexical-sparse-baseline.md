# SEARCH-04 Lexical·Sparse baseline 실행 규약

이 문서는 #866의 offline 후보 실행 규약을 소유합니다. 공개 API·프론트엔드·DB schema/migration·전체 catalog backfill은 이 실행에 포함하지 않습니다.

## 고정 입력

- `docs/p2/search-evaluation/manifest.json`의 `development-seed` profile
- 승인된 Top 1,000 `quality-corpus.json`
- [입력 descriptor](lexical-sparse-baseline-input.json)에 고정한 PR #861 POC manifest와 `search-text.json` 외부 artifact

실행기는 descriptor·POC manifest·search-text artifact 전체 파일 SHA-256과 logical `games` SHA-256, dataset `releaseId`·`datasetId`·manifest SHA-256, Top 1,000 membership을 모두 대조합니다. 입력이 바뀌면 실행을 거절합니다.

## 실행

```bash
node scripts/search-evaluation/lexical-sparse-baseline.mjs \
  --mode lexical \
  --manifest docs/p2/search-evaluation/manifest.json \
  --input-descriptor docs/p2/search-evaluation/lexical-sparse-baseline-input.json \
  --poc-manifest /path/to/poc-search-text-manifest.json \
  --search-text /path/to/search-text.json \
  --out /path/to/lexical-results.json

node scripts/search-evaluation/lexical-sparse-baseline.mjs \
  --mode sparse \
  --manifest docs/p2/search-evaluation/manifest.json \
  --input-descriptor docs/p2/search-evaluation/lexical-sparse-baseline-input.json \
  --poc-manifest /path/to/poc-search-text-manifest.json \
  --search-text /path/to/search-text.json \
  --out /path/to/sparse-results.json
```

stdout의 `resultSha256`와 외부 결과 파일을 함께 보존합니다. 동일한 manifest·corpus·search-text·mode는 byte-equivalent 결과와 동일 checksum을 만들어야 합니다.

## 후보 규칙

### Lexical

- `게임명`(PR #861의 name·alias 조립 결과)과 `영문명`만 사용합니다.
- query와 이름을 Unicode `NFKC`·소문자·공백으로 정규화하고, 숫자가 포함된 토큰과 인원·시간·난이도 조건 토큰은 relevance 신호에서 제외합니다.
- exact phrase, phrase 포함, 전체 token 일치, token overlap을 고정 점수로 계산합니다.
- 동점은 정규화된 게임명, `gameId` 오름차순으로 정렬합니다.

### Sparse

- `메커니즘`·`카테고리`·`테마` field의 승인된 값만 사용합니다.
- query에 구조화 값의 정규화 token sequence가 나타날 때만 점수를 더합니다. 고정 가중치는 mechanism 3, category 2, theme 1입니다.
- 설명·detail description·이름·영문명은 Sparse relevance 신호로 사용하지 않습니다.
- 동점 정렬은 Lexical과 같습니다.

두 mode 모두 결과는 `queryId`를 key로 하고 `{ rankedGameIds, hardFilterViolationGameIds }`를 value로 하는 공통 결과 형식을 사용합니다. 점수 자체는 결과 파일에 노출하지 않습니다.

## Hard filter 경계

점수 계산이 끝난 뒤 pinned quality corpus의 metadata에 P1 hard filter를 적용합니다.

- `minPlayers`: `member.maxPlayers >= minPlayers`
- `maxPlayers`: `member.minPlayers <= maxPlayers`
- `maxPlayTimeMinutes`: `member.maxPlayTimeMinutes <= maxPlayTimeMinutes`

hard filter 조건은 lexical·Sparse 점수에 들어가지 않으며, 필터링된 후보는 결과에 포함하지 않습니다. 따라서 결과의 `hardFilterViolationGameIds`는 비어 있어야 합니다.

이 결과는 #868 Dense 후보와 같은 query ID·ranked game ID·hard-filter 결과 형식으로 비교할 수 있지만, Development Seed의 provisional quality 결과를 최종 검색 방식 승인이나 production 품질 승인으로 해석하지 않습니다.
