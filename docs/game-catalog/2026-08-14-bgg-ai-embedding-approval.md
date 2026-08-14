# BGG 승인 데이터셋의 AI·embedding 사용 범위

- 상태: 승인됨
- 승인 확인일: 2026-08-14
- 관련: [ADR-0060](../adr/game/0060-approved-catalog-ai-embedding-scope.md), [SEARCH-04 이슈 #712](https://github.com/bamsongi-club/albam-mate/issues/712), [게임 카탈로그 검수·적재 가이드](../guides/GAME_CATALOG_IMPORT.md)

팀 승인에 따라 특정 BGG 기반 데이터셋의 서비스 적재와 해당 데이터셋을 이용한 AI 입력·embedding 생성을 모두 허용한다. 다만 이 문서는 BGG 전체 데이터에 대한 포괄 허가가 아니다. 실제 사용 가능한 범위는 각 배치의 승인 manifest가 가리키는 하나의 catalog release와 그 manifest의 allowlist로 고정한다.

## 승인 범위

| 항목 | 판정 | 적용 경계 |
| --- | --- | --- |
| 특정 데이터셋의 서비스 적재 | 승인됨 | `approved: true`, `testOnly: false`인 정확한 catalog release만 허용 |
| 승인 데이터셋의 AI 입력 | 승인됨 | manifest의 `approvedFields`와 `approvedProcessingScopes`에 포함된 필드·가공만 허용 |
| 승인 데이터셋의 embedding 생성 | 승인됨 | 승인된 `search_text` 조립 규칙과 release에 연결된 index 산출에 한정 |
| 다른 release·새 필드·새 가공 | 별도 승인 필요 | 기존 승인을 자동 승계하지 않음 |

## 배치 manifest 필수 기록

AI·embedding 산출 또는 서비스 적재를 실행하는 manifest에는 다음 값을 함께 기록한다.

- `releaseId`, `datasetId`, 입력·결과 파일의 SHA-256과 행 수
- `approved: true`, `testOnly: false`
- 필드별 출처와 `approvedFields` allowlist
- `approvedProcessingScopes`와 결정적 `search_text` 조립 규칙
- 승인 근거의 외부 참조(`approvalReferences`), 검수자와 검수 시각
- embedding을 만들 때의 model/provider, 차원, 문서 조립 규칙, index version과 산출물 SHA-256

위 값이 없거나 실제 입력·산출물과 다르면 적재와 색인 생성을 모두 차단한다. 승인 근거 원문은 저장소에 복사하지 않고, manifest의 외부 참조와 비공개 증적 보관 위치로 연결한다.

## AI·embedding 처리 규칙

1. 검색용 문서는 승인 release에서 `approvedFields`만 읽어 결정적으로 만든다. 현재 검색 명세의 후보 필드는 참고 목록일 뿐, manifest allowlist를 대신하지 않는다.
2. embedding 입력은 승인된 필드에서 만든 `search_text`로 제한한다. raw XML이나 allowlist 밖 원문은 명시적으로 승인된 processing scope가 없는 한 모델·외부 provider·검색 index로 보내지 않는다.
3. catalog embedding에는 `releaseId`, source field version, assembly rule version, model/provider, model version, index version을 연결한다. release·필드·조립 규칙·모델이 바뀌면 새 index version으로 재생성한다.
4. 사용자 query embedding은 catalog 승인과 별개의 개인정보·보존 계약으로 취급한다. query 원문·사용자 ID·세션·ROOM·채팅·프롬프트와 provider 응답 원문을 catalog index나 중앙 로그에 저장하지 않는다.
5. 공개 서비스에는 [ADR-0025](../adr/game/0025-game-catalog-public-source-attribution.md)의 BGG 출처와 `Powered by BGG` 표기를 계속 적용한다. 이미지 다운로드·변환·재호스팅과 상업적 사용은 이 승인 기록으로 새로 허용하지 않는다.

## 재승인 조건

다음 중 하나라도 바뀌면 기존 승인을 사용하지 않고 새 release manifest와 승인 근거를 만든다.

- 입력 dataset, 행 집합, source snapshot 또는 checksum
- AI·embedding에 전달하는 필드와 변환 규칙
- model/provider, 보존 기간, 저장소 또는 공개 범위
- 상업적 운영, 이미지 처리, 투표 재가공·결합 등 현재 범위 밖의 사용
