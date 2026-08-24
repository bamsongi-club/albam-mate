# ADR-0060: 승인된 카탈로그 release의 AI·embedding 처리 범위를 허용

- 상태: 승인됨
- 작성일: 2026-08-14
- 결정일: 2026-08-14
- 관련: [SEARCH-04 이슈 #712](https://github.com/bamsongi-club/albam-mate/issues/712), [BGG 승인 데이터셋의 AI·embedding 사용 범위](../../game-catalog/2026-08-14-bgg-ai-embedding-approval.md), [게임 카탈로그 검수·적재 가이드](../../guides/GAME_CATALOG_IMPORT.md), [ADR-0019](0019-bgg-full-catalog-staged-enrichment.md), [ADR-0025](0025-game-catalog-public-source-attribution.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

기존 [ADR-0019](0019-bgg-full-catalog-staged-enrichment.md)는 결정 당시 확인된 범위에서 BGG 원천의 AI·LLM 입력과 embedding 생성을 허용하지 않았다. 이후 팀은 특정 BGG 기반 데이터셋의 서비스 적재와 그 데이터셋의 AI 입력·embedding 사용을 승인했다.

이 승인을 BGG 전체 데이터에 대한 포괄 허가로 해석하면 다른 release·필드·가공·provider까지 조용히 확장될 수 있다. 반대로 기존 제한을 그대로 두면 승인된 SEARCH-04 평가와 색인 작업을 문서가 막는다. 판단 기준은 승인된 데이터셋의 재현성, 필드별 provenance, 색인 rollback 가능성, P1 개인정보·공개 범위의 보존이다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 기존 AI·LLM 제한을 그대로 유지 | 경계가 단순하다. | 이미 승인된 검색용 AI·embedding 작업까지 막는다. | 제외 |
| BGG 데이터의 AI·embedding 사용을 전면 허용 | 구현이 빠르다. | release·필드·가공·provider의 범위가 추적되지 않고 승인 범위를 과도하게 넓힌다. | 제외 |
| 승인 manifest의 release·필드·가공 allowlist에 한정 | 재현·rollback·재승인 기준을 명확히 유지한다. | manifest와 provenance를 매 배치 관리해야 한다. | **선택** |

## 결정

1. 정책적으로는 `approved: true`, `testOnly: false`인 하나의 catalog dataset release에 대해 고정 dataset profile·field provenance·실제 artifact/coverage 측정을 먼저 통과시키고, 별도 execution manifest가 release ID·dataset ID·manifest SHA-256을 참조한 경우에만 manifest가 허용한 필드와 `approvedProcessingScopes` 범위에서 AI 입력·embedding 생성을 허용한다. `prepare-game-catalog.mjs`는 dataset-only manifest의 직접 실행을 차단하고 참조 release를 검증한 뒤 기존 실행 manifest gate를 적용하지만, 실제 실행은 구체 승인 release manifest를 검증한 뒤에만 가능하며 현재 실행 가능한 release 상태는 미검증이다.
2. 검색용 embedding은 승인 필드로 만든 결정적 `search_text`에서 생성하고, release·필드 버전·조립 규칙·model/provider·index version·산출물 checksum을 함께 기록한다. raw XML과 allowlist 밖 원문은 별도 명시가 없으면 처리하지 않는다.
3. 데이터셋 승인만으로 model/provider 선택, vector 저장소, API·ERD·아키텍처 계약, 사용자 query 보존을 확정하지 않는다. 이 선택은 SEARCH-04의 별도 ADR·계약에서 검토한다.
4. 사용자 ID·세션·ROOM·채팅·prompt·query 원문과 provider 응답 원문은 catalog embedding과 중앙 로그에 넣지 않는다. 사용자 query의 일시적 embedding 처리는 개인정보·보존 계약을 별도로 따른다.
5. 공개 서비스의 BGG 출처와 `Powered by BGG` 표기는 유지한다. 이미지 다운로드·변환·재호스팅, 투표 재가공·결합과 상업적 사용은 이 ADR의 허용 범위에 포함하지 않는다.

## 결과

- 얻는 것: dataset release profile·SQL coverage 측정·실행 manifest handoff가 runner gate에 연결되어 승인된 dataset으로 SEARCH-04의 `search_text`·embedding 평가를 진행할 수 있는 기반이 생겼고, release·필드·모델 변경을 재현 가능한 index version으로 추적할 수 있다.
- 감수할 비용·위험: 배치마다 manifest allowlist와 checksum, embedding provenance를 관리해야 하며 승인되지 않은 필드가 섞이면 생성 전에 차단해야 한다.
- 후속 작업: #712의 평가 fixture를 승인 release와 manifest hash에 연결하고, lexical baseline·Dense offline PoC·hybrid 선택을 별도 품질 근거로 진행한다.

## 보류 및 재검토

- 지금 하지 않는 것: 특정 embedding model/provider/vector DB 채택, 기존 P1 API의 의미 변경, persistent vector schema 확정, 사용자 query·대화 이력 저장, BGG 전체 release 자동 처리
- 보류 이유: 이번 결정은 데이터 이용 범위의 정책 승인이지 검색 기술·API·저장 구조의 결정이 아니며, 현재 실행 가능한 구체 release manifest가 아직 등록·검증되지 않았다.
- 다시 검토할 조건: 승인 release가 바뀌거나, allowlist 밖 필드·가공·provider·공개 범위·보존 정책을 추가할 때

## 참고 자료

- [BGG 승인 데이터셋의 AI·embedding 사용 범위](../../game-catalog/2026-08-14-bgg-ai-embedding-approval.md)
- [SEARCH-04 의미 검색 평가 fixture와 판정 기준 고정](https://github.com/bamsongi-club/albam-mate/issues/712)
- [게임 카탈로그 검수·적재 가이드](../../guides/GAME_CATALOG_IMPORT.md)
- [ADR-0019: 전체 보드게임 카탈로그는 BASIC으로 확장하고 상세 정보는 단계적으로 보강](0019-bgg-full-catalog-staged-enrichment.md)
- [ADR-0025: 게임 카탈로그 출처를 전역 푸터와 공개 출처 페이지에 표시](0025-game-catalog-public-source-attribution.md)

## 검증

- 상태: 미검증
- 근거:
    - 계약: [BGG 승인 데이터셋의 AI·embedding 사용 범위](../../game-catalog/2026-08-14-bgg-ai-embedding-approval.md)가 release·필드·가공 allowlist와 재승인 조건을 정의한다.
    - 이슈: [#712](https://github.com/bamsongi-club/albam-mate/issues/712)가 고정 release 기반 평가 fixture 작업을 소유한다.
    - 구현·테스트: `catalog-dataset-release-manifest.mjs`가 고정 dataset profile·field provenance·artifact/coverage를 검증하고, `prepare-game-catalog.mjs`가 dataset release 참조를 확인한 뒤 실제 입력·service catalog·UPSERT 산출물 checksum·행 수를 생성 전에 대조한다. 관련 Node 테스트 87건이 통과했다.
- 미검증:
    - 실제 승인 manifest의 release ID·입출력 checksum·행 수·외부 승인 reference 연결
    - `prepare-game-catalog.mjs`의 승인 manifest gate 연결과 새 필드 검증은 구현·테스트했지만, 실제 승인 manifest 연결
    - AI/index runner의 `search_text`와 embedding 산출물 실제 파일 checksum·model/provider·index version 대조
    - API·ERD·아키텍처·운영 배포와 실제 품질 측정

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
