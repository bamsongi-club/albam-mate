# 게임명 키워드 검색 AWS 용량·인덱스 측정 (2026-08-11)

## 결론

170,000건 `games` fixture에서 `GET /api/games?keyword=zombicide&page=0&size=20`를 측정했다.

- 임시 `pg_trgm` GIN 인덱스는 같은 1 VU 조건의 p95를 **343.8 ms에서 23.8 ms**로 낮췄다. `lower(name) LIKE '%...%'` 검색에는 정식 Flyway 마이그레이션 후보로 제안할 근거가 있다.
- 인덱스를 적용한 상태에서 **640 VU / 614.2 RPS**는 2분 동안 HTTP 실패 없이 통과했다.
- **1,280 VU**에서는 실제 처리량이 **135.2 RPS**로 무너지고, p95 **2,339.9 ms**, HTTP 요청 실패 **14.10%**가 발생했다. 부하 종료 직후 같은 HTTPS 요청도 10초 안에 응답하지 못했다.
- 따라서 이 경로의 관측 경계는 **640 VU 통과, 1,280 VU 실패**다. 두 수준 사이의 정확한 임계값은 이번 실행에서 확정하지 않았다.

이 문서는 인덱스·운영 용량·생산 설정을 바꾸지 않는다. 임시 인덱스와 AWS 스택은 측정 뒤 삭제했다.

## 측정 범위와 제외 범위

| 항목 | 내용 |
| --- | --- |
| 대상 API | `GET /api/games?keyword=zombicide&page=0&size=20` |
| 검색어 | `zombicide` (9자, fixture에서 154건 일치) |
| 요청 경로 | 부하 발생기 EC2 → 공개 HTTPS → App A Nginx → App A/B → PostgreSQL |
| 포함 | 목록 페이지 조회, 검색 조건, 이름 정렬과 페이징에 따른 조회·count 경로 |
| 제외 | 다른 API, 로그인/인증, 쓰기 경로, Redis 효과, 전체 제품 혼합 트래픽, 장시간 soak test |

이 fixture에는 `games`와 한국어 이름 보조 데이터 170,000건만 적재했다. 관계 메타데이터는 handoff SQL의 대상 불일치로 적재하지 않았지만, 이 측정의 이름 검색 조건은 `games.name`만 사용하므로 검색 경로의 의미에는 영향을 주지 않는다. 다만 전체 카탈로그 데이터가 적재된 운영 환경의 절대 수치로 해석하지 않는다.

## 고정 환경과 측정 방법

| 항목 | 값 |
| --- | --- |
| 기준 애플리케이션 SHA | `6a19713e053235b28fc075f1c1e7ad6351fda538` |
| 리전 | `ap-northeast-2` (서울) |
| 앱 / PostgreSQL / Redis | 각 `t4g.micro` |
| 부하 발생기 | `c7g.large` |
| PostgreSQL 데이터 | 170,000 `games`, DB 약 502 MB |
| 부하 도구 | k6 `1.3.0` |
| 실행 방식 | `constant-vus`, 단계당 2분, VU마다 요청 후 1초 대기 |
| 판정 기준 | p95 < 500 ms, p99 < 1,000 ms, HTTP 요청 실패율 < 1% |

PostgreSQL은 데이터 적재 후 `t4g.micro`에서만 부하를 받았다. 적재 과정에서 사용된 일시적인 `t4g.medium`에는 부하 요청을 보내지 않았다.

## 인덱스 전후 비교

애플리케이션의 검색 조건은 다음과 같다.

```sql
WHERE lower(name) LIKE '%zombicide%'
ORDER BY name ASC, id ASC
LIMIT 20
```

비교용으로만 아래 인덱스를 만들었고, Flyway 파일이나 생산 스키마에는 반영하지 않았다.

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_perf_games_name_lower_trgm
  ON games USING gin (lower(name) gin_trgm_ops);
ANALYZE games;
```

| DB 상태 | PostgreSQL 실행 계획 | API p95 / p99 | HTTP 실패 | 관찰 |
| --- | --- | ---: | ---: | --- |
| 인덱스 없음 | `Parallel Seq Scan` | 343.8 / 397.2 ms | 0% | page 약 119.9 ms, count 약 111.1 ms |
| 임시 GIN 인덱스 | `Bitmap Index Scan` → `Bitmap Heap Scan` | 23.8 / 33.0 ms | 0% | page 약 1.5 ms, count 약 1.2 ms |

`war`처럼 8,488건이 일치하는 넓은 검색어도 임시 인덱스를 사용했고, page 실행 시간은 약 29.6 ms였다. 이 결과는 특정 검색어 길이를 제품 정책으로 정하는 근거가 아니며, `lower(name) LIKE '%...%'`라는 현재 쿼리 형태에 대한 인덱스 효과만 말한다.

## 인덱스 적용 후 용량 램프

아래 RPS는 고정 VU가 실제로 처리한 초당 요청 수다. 부하가 밀리면 VU가 timeout을 기다리므로, 구성한 VU보다 실제 RPS가 낮아질 수 있다.

| VU | 실제 RPS | p95 | p99 | HTTP 실패 | 판정 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 0.99 | 23.8 ms | 33.0 ms | 0% | 통과 |
| 3 | 2.95 | 24.0 ms | 29.8 ms | 0% | 통과 |
| 5 | 4.90 | 19.2 ms | 32.3 ms | 0% | 통과 |
| 10 | 9.85 | 18.7 ms | 36.6 ms | 0% | 통과 |
| 20 | 19.68 | 21.8 ms | 48.4 ms | 0% | 통과 |
| 40 | 39.42 | 19.4 ms | 38.8 ms | 0% | 통과 |
| 80 | 78.73 | 19.8 ms | 48.2 ms | 0% | 통과 |
| 160 | 157.35 | 21.0 ms | 31.0 ms | 0% | 통과 |
| 320 | 312.11 | 25.1 ms | 44.1 ms | 0% | 통과 |
| 640 | 614.16 | 42.8 ms | 312.6 ms | 0% | 통과 |
| 1,280 | 135.24 | 2,339.9 ms | 3,538.5 ms | 14.10% | 실패 |

1,280 VU에서는 k6 표준 `http_req_failed`가 request timeout을 14.10%로 기록했다. 당시 보조 실패 지표는 timeout 응답의 빈 본문을 JSON으로 읽는 처리 문제로 실패를 누락했으므로, 이 행의 실패율은 표준 HTTP 지표만 정본으로 사용한다. 보정 뒤 실행한 960 VU는 직전 과부하의 회복 전 `setup()`이 60초 timeout으로 끝나 실제 요청을 만들지 못했으므로 표에서 제외했다.

CloudWatch에서 1,280 VU 구간의 앱·DB CPU가 단순히 지속 포화된 모습은 확인하지 못했다. 따라서 이번 결과만으로 병목을 PostgreSQL CPU, 앱 CPU, Nginx 연결 대기열 또는 네트워크 중 하나로 단정하지 않는다. 확인된 사실은 공개 HTTPS 경로에서 timeout과 처리량 붕괴가 생겼다는 점이다.

## 해석과 후속 결정

1. `pg_trgm` GIN 인덱스는 현재 게임명 substring 검색의 후보다. 정식 반영은 승인 뒤 Flyway 마이그레이션, PostgreSQL 통합 테스트, 같은 부하 계약의 재측정으로 진행한다.
2. 현재 안전 수치는 전체 서비스 용량이 아니라 이 검색 경로에서 관찰한 마지막 통과 단계인 **640 VU / 614.2 RPS / 2분**이다.
3. 정확한 한계가 필요하면 새 스택에서 회복 확인을 먼저 하고 `800`, `960`, `1,120` VU를 독립 실행한다. VU 기반 결과와 함께 constant-arrival-rate로 목표 RPS를 고정해 부하 발생기 대기와 대상 시스템 처리량을 분리한다.
4. 과부하 시 원인을 확정하려면 Nginx 연결·upstream 상태, Spring/HikariCP 대기열, PostgreSQL `pg_stat_activity`와 연결 수를 같은 시간 창에 수집한다.

## 원본 요약 근거와 정리

원본 k6 summary JSON은 측정용 인프라 작업 폴더의 `.run/results`에 보존했다. 아래 SHA-256은 그 원본 파일 바이트 기준이다.

| 결과 | 파일 | SHA-256 |
| --- | --- | --- |
| 인덱스 없음 1 VU | `summary-20260811T023817Z.json` | `6ac6a21be356f68c38645943ad79673786828337bd950ff80b3a288e0774d9f9` |
| 인덱스 적용 1 VU | `summary-20260811T024421Z.json` | `ff3cb34b8e48fdd7ee87ef36b840874dda73a8962a3bc5ba16c109bee89257b1` |
| 마지막 통과 640 VU | `summary-20260811T031908Z.json` | `fd49959c7b79d5025aaf1ed37742c7e91952a5dbc8fca0c5b256410db9de54b7` |
| 첫 실패 1,280 VU | `summary-20260811T032306Z.json` | `e61dbfc91d99befe6b012f8b61ec8bd07ce35b1e7032752b15fb222cded73408` |

측정 뒤 Terraform이 테스트 리소스 94개를 삭제했다. Terraform state와 실행 EC2·EBS 볼륨·Elastic IP가 모두 비어 있음을 확인했으므로, 임시 인덱스와 fixture를 포함한 AWS 테스트 환경은 남아 있지 않다.
