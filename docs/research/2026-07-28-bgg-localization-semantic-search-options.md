# BGG 한국어화·의미 검색 제안 검증

- 조사일: 2026-07-28
- 검토 범위: 전체 약 17.9만 건 `BASIC` 적재, 기존 2천 건 pilot, BGG 순위 상위 1만 건 상세·한국어화·임베딩, 지표 충족 후 3만 건 확대
- 상태: 공식 공개 문서 검토 완료, BGG 개별 승인 문구와 기술 선택은 미확정

> 이 문서는 팀 논의를 위한 기술 조사다. BGG의 개별 승인·회신 원문을 검토한 법률 판단이 아니며, ADR 승인이나 구현 완료를 뜻하지 않는다.

## 결론

제안한 단계적 구조는 기술적으로 가능하지만, **BGG 원문을 번역하거나 임베딩 모델에 보내는 단계는 현재 바로 구현하면 안 된다.** BGG 표준 XML API 조건은 API 데이터 수정을 금지하고, 일반 이용약관은 BGG 사이트를 AI·LLM 시스템의 데이터로 사용하는 것을 금지한다. 번역은 수정에 해당할 가능성이 높고 임베딩은 AI 시스템의 입력이므로, 두 작업은 **해당 이용을 명시적으로 허용하는 BGG의 서면 근거를 확인한 뒤** 진행해야 한다. 이는 공식 문구에 근거한 보수적 해석이며, 개별 승인 메일이 예외를 부여했는지는 아직 확인하지 않았다. [BGG XML API 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use), [BGG 일반 이용약관](https://boardgamegeek.com/terms)

권리 조건이 해결된다는 전제에서는 다음 순서가 적절하다.

1. 전체 CSV 약 17.9만 건은 식별 가능한 `BASIC` 카탈로그의 기준 스냅샷으로 보존한다.
2. 기존 2천 건으로 수집·한국어화·검색 품질과 비용을 먼저 검증한다.
3. BGG 순위 상위 1만 건만 상세 수집·한국어화·임베딩한다.
4. 품질·성능·비용 지표를 통과하면 3만 건으로 확대한다.

벡터 저장소는 아직 정하지 않는다. 현재 PostgreSQL을 그대로 활용할 수 있는 `pgvector`가 첫 검증 후보지만, 1만 건 실제 데이터의 검색 품질과 p95 응답 시간, 인덱스 크기·재생성 시간을 측정하기 전에는 선택으로 확정할 근거가 없다.

## 1. BGG 수집 경계

### 전체 CSV와 XML API의 역할이 다르다

BGG 전체 CSV는 모든 게임의 **이름, ID, 순위, 평균 평점**을 담으며, BGG는 전체 이름·순위가 필요할 때 CSV를 선호 경로로 안내한다. 인원수·시간·카테고리·메커니즘·설명까지 들어 있는 전체 상세 CSV는 공식 문서에서 확인되지 않았다. 따라서 전체 `BASIC` 목록은 CSV로 만들고, 상세 보강은 `thing` API로 별도 수집하는 구조가 필요하다. CSV도 라이선스상 XML API 데이터로 취급된다. [BGG XML API2의 CSV 안내](https://boardgamegeek.com/wiki/page/bgg_xml_api2)

표준 XML API 라이선스는 엄격한 비상업적 목적의 복제·표시만 허용한다. 공식 사용 안내는 승인된 애플리케이션과 Bearer token을 요구하고, 사용량 한도는 라이선스에 따라 달라질 수 있다고 설명한다. 수익화하거나 용도가 바뀌면 현재 비상업 승인을 그대로 적용하지 말고 라이선스를 다시 확인해야 한다. [BGG XML API 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use), [BGG XML API 사용 안내](https://boardgamegeek.com/using_the_xml_api)

`thing` 요청은 한 번에 최대 20개 ID를 받을 수 있다. BGG는 요청이 잦으면 500 또는 503을 반환할 수 있고, 현재는 요청 사이 약 5초가 충분해 보인다고 안내하지만 이를 고정 보장 한도로 표현하지 않는다. 별도 사용 안내도 정확한 한도를 아직 정하는 중이며, 서버에서 요청하고 결과를 캐시하며 호출 수를 최소화하라고 권고한다. [BGG XML API2의 Rate Limit과 Thing](https://boardgamegeek.com/wiki/page/bgg_xml_api2), [BGG XML API 사용 안내](https://boardgamegeek.com/using_the_xml_api)

따라서 애플리케이션 요청 중 BGG를 직접 호출하지 않고 다음 배치 경계를 둔다.

- 전체 CSV와 XML 원문 응답을 수정하지 않은 스냅샷으로 보관한다.
- `source_url`, 취득 시각, 행·ID 수, SHA-256, 사용한 애플리케이션과 약관 확인일을 manifest에 기록한다.
- 상세 수집기는 최대 20 ID 단위, 속도 제한, 재시도·중단 후 재개, 응답 캐시를 지원한다.
- 정제 DB는 `bgg_id`로 반복 실행 가능한 `UPSERT`를 하되, 원본 저장소와 분리한다.
- 공개 사용에는 BGG 출처와 링크된 `Powered by BGG` 로고가 필요하다. [BGG XML API 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)

### 상위 1만 건의 의미

상위 1만 건은 “알밤메이트가 높게 평가한 게임”이 아니라 **초기 상세 보강 대상을 정하는 BGG 기반 운영 기준**이다. BGG 순위는 취득 시점과 출처를 함께 기록하고 Albam Mate 사용자 리뷰와 섞지 않는다. 지표를 통과한 뒤 3만 건으로 늘리며, 17.9만 건 전체 상세 수집·번역·임베딩은 이번 제안에 포함하지 않는다.

## 2. 한국어화와 provenance

권리 조건이 해결되면 DeepL API는 기술적 후보가 될 수 있다. 공식 번역 API는 요청의 `source_lang`, `target_lang`, `model_type`, `glossary_id`를 받을 수 있고, 응답에 번역문, 감지한 원문 언어, 실제 사용 모델 유형과 과금 문자를 반환할 수 있다. 요청 크기는 최대 128 KiB이며 여러 입력의 결과는 요청 순서대로 반환된다. [DeepL Translate API](https://developers.deepl.com/api-reference/translate/request-translation), [DeepL 사용량·제한](https://developers.deepl.com/docs/resources/usage-limits)

한국어 번역은 원문을 덮어쓰지 않고 별도 파생 데이터로 저장한다.

| 구분 | 최소 기록 |
| --- | --- |
| 원문 | `bgg_id`, 원문 종류, 원문 또는 원문 위치, `source_hash`, BGG 취득 배치 |
| 요청 | provider, endpoint, 요청 시각, 원문·대상 언어, 요청 모델 유형, glossary ID, 변환 규칙 버전 |
| 응답 | 번역문, 감지 언어, 사용 모델 유형, 성공·실패 상태 |
| 검수 | 검수 상태, 검수자, 검수 시각, 수정 이유 |

DeepL 응답은 모델 **유형**을 알려 주지만 고정된 모델 snapshot 버전까지 제공한다고 공식 문서에서 확인되지는 않았다. 그러므로 같은 요청의 완전한 재현을 가정하지 말고 원문 hash와 실제 응답을 함께 보존해야 한다. 공급자를 OpenAI 등으로 바꾸더라도 `provider + model_id + source_hash + 변환 규칙 버전`을 새 버전으로 추가하고 기존 결과를 조용히 덮어쓰지 않는다.

## 3. 임베딩과 하이브리드 검색

임베딩도 번역과 마찬가지로 BGG의 AI 이용 허용이 확인되기 전에는 생성하지 않는다. 허용된 입력으로 진행할 때는 `game_id`, 입력 필드·언어, `source_hash`, provider, model ID, 차원, 생성 시각을 기록한다. 원문·번역·태그가 바뀌거나 모델이 바뀌면 새 임베딩 버전을 만들고, 검색 전환 후 이전 버전을 정리한다.

자연어 의미 검색만으로는 게임명·고유 메커니즘 같은 정확한 단어를 놓칠 수 있으므로 **키워드 검색과 벡터 검색을 결합한 하이브리드 검색**을 검증한다. 합성 가중치는 감으로 확정하지 않고 팀이 만든 대표 질의와 정답 후보로 비교한다.

| 후보 | 공식 문서에서 확인되는 기능 | Albam Mate의 운영 경계 |
| --- | --- | --- |
| PostgreSQL `pgvector` | 기본 exact 검색, HNSW·IVFFlat 근사 인덱스, PostgreSQL full-text search와 RRF를 조합한 하이브리드 검색을 지원한다. [pgvector 공식 README](https://github.com/pgvector/pgvector) | 기존 게임 DB와 ID·필터·백업을 함께 관리할 수 있다. 대신 인덱스 튜닝·모니터링·DB 부하는 팀 책임이다. **우선 검증 후보일 뿐 미확정**이다. |
| Pinecone | serverless index가 자동 확장되며, dense+sparse 단일 인덱스 또는 분리 인덱스 방식의 하이브리드 검색을 제공한다. 검색 반영은 eventual consistency다. [Pinecone hybrid search](https://docs.pinecone.io/guides/search/hybrid-search), [Pinecone search overview](https://docs.pinecone.io/guides/search/search-overview), [Pinecone serverless 운영](https://docs.pinecone.io/guides/index-data/implement-multitenancy) | 인프라 운영은 줄지만 외부 서비스 비용·한도와 PostgreSQL 원본에서 파생 인덱스로 동기화·재구축하는 절차가 필요하다. |
| Milvus | Lite·Standalone·Distributed 배포를 제공하고 dense·sparse·hybrid 검색을 지원한다. 공식 가이드는 Lite를 수백만 벡터 이하, Standalone을 최대 1억 벡터 규모의 후보로 설명한다. [Milvus 배포 옵션](https://milvus.io/docs/install-overview.md), [Milvus hybrid search](https://milvus.io/docs/hybrid_search_with_milvus.md) | 1만→3만 건 규모만으로 별도 서버나 Kubernetes 운영을 정당화할 수 없다. 자가 운영 요구나 확장 지표가 생길 때 재검토한다. |

세 제품의 공식 기능만으로 우열을 확정하지 않는다. 동일한 임베딩·질의·필터로 `Recall@K` 또는 `nDCG@K`, p95 응답 시간, 인덱스 크기·빌드 시간, 갱신 지연, 월 비용을 측정한 뒤 선택한다. 벡터 저장소는 어디를 택하든 PostgreSQL의 게임 원본을 대신하는 정본이 아니라 **삭제 후 다시 만들 수 있는 파생 인덱스**로 둔다.

## 4. BGG 평점과 사용자 리뷰 분리

BGG 전체 CSV에 평균 평점이 포함되므로 “BGG 평점을 버린다”는 말을 원본 파일에서 열을 삭제한다는 뜻으로 사용하면 원본 재현성이 깨진다. 대신 다음처럼 경계를 둔다.

- 허용된 보관 범위 안에서 원본 CSV는 평균 평점을 포함한 채 변경 없이 보존한다. BGG 공식 공개 문서에서는 별도의 보관 기간을 확인하지 못했으므로 개별 승인 조건을 확인한다.
- 서비스 카탈로그·검색 인덱스·API에는 BGG 평균 평점을 Albam Mate 사용자 리뷰의 평점 필드로 옮기거나 노출하지 않는다.
- `BGG rank`는 초기 1만 건 선정의 provenance로만 기록하며 사용자 리뷰의 정렬·평가 기준에는 사용하지 않는다.
- 현재 범위에서는 별도의 자체 지수나 종합 점수를 만들지 않고 사용자 리뷰만 관리한다.
- 리뷰의 필드, 정렬과 노출 방식은 제품 명세에서 정하며 이번 조사에서는 확정하지 않는다.

즉, BGG 원본의 보존과 BGG 평점의 서비스 비노출은 동시에 가능하다. 원본은 감사·재처리를 위한 입력이고, 사용자 리뷰는 Albam Mate에서 별도로 생성되는 서비스 데이터다.

## 구현 전 확인 항목

- [ ] BGG 개별 승인 원문에 번역, AI 번역 서비스 전송, 임베딩 생성·저장, 원본 보관, 공개 범위가 명시되어 있는가
- [ ] 허용되지 않았다면 BGG에 서면 예외를 받거나, 독립 작성·별도 라이선스 텍스트만 한국어화·임베딩하는가
- [ ] 2천 건 pilot의 API 성공률·소요 시간·번역 품질·비용·검색 평가 기준을 합의했는가
- [ ] 1만→3만 확대 통과 기준을 수치로 정했는가
- [ ] 같은 데이터로 `pgvector` 우선 benchmark를 수행하고 외부 벡터 DB 도입 조건을 기록했는가
- [ ] BGG 평균 평점·순위와 Albam Mate 사용자 리뷰의 저장·노출 필드가 분리되어 있는가

## 미확정 사항

- 개별 BGG 승인 메일이 표준 약관의 수정·AI 이용 금지에 대한 예외를 주는지
- BGG 원본과 캐시의 허용 보관 기간·삭제 의무
- 번역 provider와 모델, glossary, 사람 검수 범위와 예산
- 임베딩 입력 문서 구성, 모델·차원, 하이브리드 합성 방식
- `pgvector`, Pinecone, Milvus 중 실제 운영 기술
- 사용자 리뷰의 필드, 정렬과 노출 방식
