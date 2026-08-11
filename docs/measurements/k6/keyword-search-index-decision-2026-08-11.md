# 키워드 검색 인덱스 판단 (2026-08-11)

## 결론

170,000건 전체 카탈로그를 복원한 AWS 스택에서, 임시 `pg_trgm` GIN 인덱스를 정식 Flyway 마이그레이션으로 도입하지 않기로 했다.

- PostgreSQL 실행 계획은 GIN을 사용해 빨라졌지만, 동일한 API 부하에서는 p95가 개선되지 않았다.
- 표준 credit `t4g.micro`에서는 30 VU의 2글자 키워드 검색만으로 PostgreSQL CPU credit이 소진되고 p95 3.29초가 관측됐다.
- 이 결과는 검색어 최소 길이 정책을 의미하지 않는다. 현재 공개 검색 계약은 그대로 두며, 병목을 분리한 뒤 같은 계약으로 다시 측정한다.

## 측정 범위

| 항목 | 값 |
| --- | --- |
| 대상 흐름 | 공개 `GET /api/games?keyword=...&page=0&size=20` |
| 릴리스 | `6a19713e053235b28fc075f1c1e7ad6351fda538` |
| 리전 | `ap-northeast-2` |
| 앱 / PostgreSQL / Redis | 각 `t4g.micro` |
| 부하 발생기 | `c7g.large`에서 공개 HTTPS로 직접 요청 |
| fixture | `games` 170,000건과 category/theme/mechanism/player-preference 관계를 포함한 복원 덤프 |
| 인증 | 비로그인 공개 검색. `JSESSIONID`를 만들지 않았다 |
| 시나리오 | `02-game-keyword.js`, 10 → 30 VU, 30 VU 유지 3분 |

`08-game-realistic.js` 혼합 피크 시나리오는 최신 release 배포를 우선해 이 실행에서 시작하지 않았다. 따라서 이 문서는 전체 서비스 또는 로그인 사용자의 용량을 말하지 않는다.

## 결과

### 표준 credit 기준선

인덱스가 없는 상태에서 각 VU가 게임명에서 뽑은 2글자 키워드를 검색했다.

| DB credit | 요청 수 | 실제 RPS | API p95 | HTTP 실패 | 판정 |
| --- | ---: | ---: | ---: | ---: | --- |
| standard | 1,963 | 6.09 | 3,286.1 ms | 0% | p95 기준 실패 |

CloudWatch에서 PostgreSQL CPU는 약 90~99%까지 올랐고, CPU credit balance는 5.71에서 0.007까지 떨어졌다. 이 실행은 `t4g.micro` 표준 credit의 지속 성능 한계를 보여 주지만, 임시 GIN 전후 비교의 기준으로 쓰지 않았다.

### 고정 키워드의 대칭 비교

표준 credit이 이미 소진된 뒤에는 두 상태를 공정하게 비교하기 위해 PostgreSQL만 일시적으로 `unlimited` credit으로 전환했다. 인스턴스 유형은 계속 `t4g.micro`였으며, 이 결과는 표준 credit 용량 수치가 아니라 인덱스 판단 보조 근거다.

모든 VU가 같은 6글자 이상 게임명 `누스피요르드`를 검색하도록 고정했다.

| DB 상태 | 요청 수 | 실제 RPS | API 평균 | API p95 | HTTP 실패 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 임시 GIN 없음 | 2,630 | 8.75 | 1,750.2 ms | 3,057.7 ms | 0% |
| 임시 GIN 적용 | 2,275 | 7.56 | 2,177.1 ms | 6,178.9 ms | 0% |

임시 GIN은 동일한 API 측정에서 개선을 만들지 못했다. 따라서 SQL 실행 계획만으로 정식 인덱스 도입을 확정하지 않는다.

## 실행 계획과 해석

직접 SQL probe에서 `lower(name) LIKE '%dragonmaster%'` 조건은 인덱스가 없을 때 `Parallel Seq Scan`으로 약 134.924 ms, 임시 GIN이 있을 때 `Bitmap Index Scan`으로 약 0.765 ms였다. 이는 인덱스가 해당 predicate를 지원한다는 사실만 보인다.

그러나 실제 API에는 content/count 조회, 정렬, 연결 대기와 인프라 자원이 함께 포함된다. 위 대칭 비교에서 p95가 오히려 높아졌으므로, 현재 병목을 PostgreSQL predicate 하나로 단정하거나 GIN migration을 만들지 않는다.

## 증거와 이전 기록의 관계

원시 k6 결과는 커밋하지 않았다. 아래는 원본 summary의 SHA-256이다.

| 실행 | summary 파일명 | SHA-256 |
| --- | --- | --- |
| 표준 credit 기준선 | `summary-20260811T060211Z.json` | `473a2a857094cdcbf63ce9e38926286e5142c3bf72c221c23d43c70840a81139` |
| 고정 키워드·GIN 없음 | `summary-20260811T064656Z.json` | `033402a97ec561788c8316dc3ee3614e07710183d5fbc6c9576cdc0bc41e25d9` |
| 고정 키워드·GIN 적용 | `summary-20260811T065407Z.json` | `689a84f9201e0e3cde5332f50640a38d0028d989f18f221e098df7e68b21c328` |

[키워드 검색 AWS 용량·인덱스 측정](keyword-search-capacity-2026-08-11.md)은 `zombicide` 고정 검색어와 게임 단일 fixture에서 수행한 별도 측정이다. 그 문서의 640 VU 통과 수치를 이 문서의 전체 카탈로그 공개 검색 용량으로 일반화하지 않는다.

다음 재측정은 새 표준-credit 스택에서 `08-game-realistic.js`를 별도로 실행하고, Nginx upstream·Spring HikariCP·PostgreSQL 연결 수를 같은 시간 창에 수집한 뒤 진행한다.
