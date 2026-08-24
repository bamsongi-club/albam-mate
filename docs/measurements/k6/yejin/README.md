# Yejin k6 측정 문서

게임 키워드 검색과 혼합 조회 k6 측정 결과 및 인덱스 판단 근거를 관리한다. 공통 보존 규칙은 [상위 README](../README.md)를 따른다.

| 구분 | 문서 | 근거 |
| --- | --- | --- |
| 키워드 검색 용량 측정 | [키워드 검색 AWS 용량·인덱스 측정](keyword-search-capacity-2026-08-11.md) | 문서 내 실행 조건과 결과 |
| 인덱스 A/B 판단 | [키워드 검색 인덱스 판단](keyword-search-index-decision-2026-08-11.md) | [검증 증거 JSON](evidence/keyword-search-index-comparison-2026-08-11.json) |
| #867 게임 목록 동시 부하·자원 검증 | [게임 목록 동시 부하·자원 검증](game-list-concurrency-2026-08-20.md) | VU 2/4/8 HTTP·App·PostgreSQL 측정 JSON |
