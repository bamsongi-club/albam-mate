# 키워드 검색 인덱스 판단 (2026-08-11)

## 결론

170,000건 전체 카탈로그를 복원한 AWS 스택에서 같은 release·fixture·시나리오·고정 키워드로 임시 `pg_trgm` GIN 인덱스 OFF·ON을 다시 측정했다.

- 검색 단독 `02-game-keyword.js`는 p95가 **3,034.1 ms → 9.7 ms**, p99가 **3,350 ms → 10.8 ms**로 줄어 모든 threshold를 통과했다.
- 현실 혼합 `08-game-realistic.js`는 인덱스 적용 뒤에도 전체 p95 **12,748.7 ms**, 키워드 p95 **11,284.2 ms**로 실패했다. HTTP 실패는 두 상태 모두 0%였다.
- 따라서 현재 substring 검색에는 GIN을 도입하기로 결정한다. GAME 혼합 부하의 지연은 GIN 도입을 막는 조건으로 묶지 않고, 다른 조회 경로와 앱·DB 자원 중 정확한 병목 지점을 찾는 후속 과제로 분리한다. 이 PR은 #551의 1차 측정 범위에 따라 Flyway 마이그레이션을 직접 추가하지 않으며, 정식 마이그레이션과 PostgreSQL 회귀 검증은 확정된 후속 구현 범위로 넘긴다.

검색어 길이를 제한하는 제품 정책은 도입하지 않는다. 측정 뒤 임시 인덱스와 AWS 리소스는 모두 삭제했다.

## 재현 조건

| 항목 | 값 |
| --- | --- |
| benchmark | `pr591-20260811-v1` |
| release | `6a19713e053235b28fc075f1c1e7ad6351fda538` |
| fixture | `catalog-170k-base-pre-full-localization` |
| fixture SHA-256 | `9b216fc3dddb60af78c338552c9f85d0764a5899380d968351e43fed32f2dcff` |
| 시나리오 묶음 SHA-256 | `e3e3c02f0c7f726c959b213aab8be5c3225891a39a8d093d25aaae8cd23944b1` |
| 검색어 | `누스피요르드` (1건 일치) |
| 인증 / profile | 비로그인 / `load` |
| k6 | `1.3.0`, Linux arm64 |
| 리전 | `ap-northeast-2` |
| 앱 / PostgreSQL / Redis | 각 `t4g.micro` |
| 부하 발생기 | `c7g.large` |
| PostgreSQL credit | 두 상태 모두 `unlimited` |

fixture에는 `games` 170,000건과 category 8건, theme 85건, mechanism 196건 및 해당 관계·선호 데이터를 복원했다. 정확한 테이블별 건수와 환경 해시는 [검증 증거 JSON](evidence/keyword-search-index-comparison-2026-08-11.json)에 보존했다.

비교 manifest는 두 상태의 release, fixture, 시나리오, 검색어, profile, 인증, k6 버전이 다르면 실행을 거부한다. summary와 console 로그의 SHA-256도 상태별로 기록했다.

## SQL 실행 계획

동일한 `lower(name) LIKE '%누스피요르드%'` 조건을 직접 측정했다.

| DB 상태 | 계획 | page 실행 | count 실행 |
| --- | --- | ---: | ---: |
| GIN 없음 | `Parallel Seq Scan` | 125.413 ms | 108.746 ms |
| 임시 GIN | `Bitmap Index Scan` → `Bitmap Heap Scan` | 0.228 ms | 0.174 ms |

임시 인덱스 크기는 17 MB였다. 이 결과는 predicate가 GIN을 사용한다는 근거이며, API 전체 병목 판단은 아래 k6 결과를 함께 본다.

## k6 결과

### 검색 단독 `02-game-keyword.js`

10 → 30 VU, 30 VU 3분 유지, 총 5분 구간이다.

| DB 상태 | 요청 수 | 실제 RPS | 평균 | p95 | p99 | HTTP 실패 | 판정 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| GIN 없음 | 2,637 | 8.77 | 1,728.7 ms | 3,034.1 ms | 3,350 ms | 0% | 실패 |
| 임시 GIN | 7,096 | 23.65 | 6.7 ms | 9.7 ms | 10.8 ms | 0% | 통과 |

GIN 적용으로 p95는 약 99.7% 감소했고 실제 처리율은 약 169.7% 증가했다. 두 상태 모두 status check 100%와 HTTP 실패율 0%를 기록했으므로 개선은 오류 응답 증가로 만든 결과가 아니다.

### 현실 혼합 `08-game-realistic.js`

목록·키워드·복합 필터·관계 필터·예정 모임·상세 조회를 10 → 30 → 50 → 100 VU로 13분간 혼합했다.

| DB 상태 | 요청 수 | 실제 RPS | 전체 평균 | 전체 p95 | 전체 p99 | 키워드 p95 | HTTP 실패 | 판정 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| GIN 없음 | 7,482 | 9.57 | 5,514.3 ms | 12,477.8 ms | 14,530 ms | 12,405.2 ms | 0% | 실패 |
| 임시 GIN | 8,699 | 11.14 | 4,577.6 ms | 12,748.7 ms | 14,480 ms | 11,284.2 ms | 0% | 실패 |

적용군은 키워드 p95와 평균 처리율이 일부 개선됐지만 전체 p95는 개선되지 않았다. 목록·복합 필터·관계 필터·예정 모임·상세 조회도 모두 p95 11초 이상이어서 혼합 부하에서는 키워드 predicate 외 병목이 지배적이다.

## 자원 관찰과 한계

| DB 상태 | PostgreSQL CPU 평균 / 최대 | credit balance 최소 | surplus credit 최대 |
| --- | ---: | ---: | ---: |
| GIN 없음 | 68.9% / 99.4% | 0 | 20.01 |
| 임시 GIN | 57.9% / 99.3% | 0 | 46.36 |

- PostgreSQL은 두 상태 모두 `unlimited`였으므로 credit balance 0에서 baseline 성능으로 제한되지는 않았다. 다만 OFF 다음 ON 순서로 실행해 surplus 누적 상태는 같지 않다.
- 종료 직후 앱 컨테이너 메모리는 약 83~85%였다. HikariCP·Nginx upstream·`pg_stat_activity`를 전체 구간의 시계열로 수집하지 못해 혼합 부하의 정확한 병목 위치는 확정하지 않는다.
- 비로그인 측정이므로 로그인 개인화 경로 용량으로 일반화하지 않는다.
- `08`은 `load` profile 결과다. `soak`, `spike`, `stress` 용량은 측정하지 않았다.

## 결정과 후속 측정

1. 현재 이름 substring 검색에는 `lower(name) gin_trgm_ops` GIN을 도입한다.
2. #551의 1차 측정 PR에는 마이그레이션을 추가하지 않고, 전진 Flyway 마이그레이션·ERD·PostgreSQL 회귀 검증을 후속 구현 범위로 확정한다.
3. 혼합 부하는 `08`의 목록·복합·관계·예정 모임·상세 쿼리를 분리 측정하고 HikariCP·Nginx·PostgreSQL 시계열을 같은 창에 수집해 정확한 병목 지점을 찾은 뒤 별도로 개선한다.
4. 과거 manifest 도입 전 결과는 참고 기록으로만 유지하고 현재 판단에는 `pr591-20260811-v1` 쌍을 사용한다.

## 증거와 정리

[검증 증거 JSON](evidence/keyword-search-index-comparison-2026-08-11.json)은 실제 IP·계정 식별자를 제거하고 다음을 보존한다.

- release·fixture·시나리오·실행 환경 불변값
- SQL 계획과 k6 주요 지표
- 원시 summary·console·manifest·CloudWatch 파일의 SHA-256
- 인프라 철거 검증 결과

측정 뒤 임시 GIN 인덱스를 제거하고 Terraform 계획의 94개 리소스를 삭제했다. 최종 확인 결과 Terraform state, 활성 EC2, EBS 볼륨, Elastic IP, VPC, 보안 그룹, 서브넷, 전송 S3 버킷, 스택 전용 SSM 파라미터는 모두 0개다.

[키워드 검색 AWS 용량·인덱스 측정](keyword-search-capacity-2026-08-11.md)은 별도 단일 fixture와 다른 부하 구성의 과거 측정이다. 그 문서의 640 VU 통과 수치를 전체 카탈로그 혼합 용량으로 일반화하지 않는다.
