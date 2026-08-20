# #867 게임 목록 동시 부하·자원 검증 결과

- 실행일: 2026-08-20
- 상태: 완료 — 로컬 격리 환경의 최종 후보 검증이며 production SLO·용량 승인으로 해석하지 않는다.
- 선택 후보: V1(query shape)
- 서버 commit: `853d1eeee384783372649a1b258c3f816d24e835`
- runner commit: `22916f36027af4187b64eebdee69a2fc67cf388f`
- runner source clean: `true`
- fixture: `game-list-170005-local-2026-08-19-frozen` (`games` 170,005건)
- fixture manifest SHA-256: `cc91b435cbac389fb9c77e0b08a241d6383d953cc361f58ebff19cc9baecc120`
- k6: `v2.1.0`
- Docker Server: `29.4.3`
- 실행 호스트: macOS arm64, 10 CPU

## 결론

VU 2·4·8의 세 단계에서 HTTP 오류·timeout으로 집계되는 `http_req_failed`는 모두 `0%`, Slice 응답 계약 check는 모두 `100%`, App 2대와 PostgreSQL의 재시작은 `0회`였다. VU 4까지 처리량이 증가했지만 VU 8에서는 p95가 `494.930ms`, p99가 `1,220.537ms`로 늘어 로컬 환경의 tail latency 증가가 관찰됐다. 이 결과만으로 운영 환경의 안전한 동시 사용자 수나 SLO를 정하지 않는다.

## 실행 조건과 토폴로지

isolated PostgreSQL에 170,005건 fixture를 복원하고 현재 애플리케이션 image를 Flyway와 함께 기동했다. proxy 뒤에 App1·App2를 두고, Redis는 애플리케이션 보조 컨테이너로 분리했다. k6는 `/api/games`의 기본 목록·relation(theme/mechanism)·complex filter를 약 1/3씩 요청했다. 각 응답은 HTTP 200, `content` 배열, `page`·`size`·`hasNext`, 정확한 Slice 키(`content`, `hasNext`, `page`, `size`)를 확인했다.

각 단계는 constant VU로 20초 실행했고 자원 수집 설정 간격은 1초였다. Docker stats와 `pg_stat_activity` 호출이 동기식인 runner 특성상 실제 표본은 단계별 6개였으며, 시작·종료 표본을 포함한다.

```bash
node scripts/measurements/game-list-concurrency.mjs \
  --base-url http://127.0.0.1:5174 \
  --server-commit 853d1eeee384783372649a1b258c3f816d24e835 \
  --app1-container albam-mate-867-spring-1 \
  --app2-container albam-mate-867-spring-2 \
  --postgres-container albam-mate-867-postgres \
  --levels 2,4,8 \
  --duration 20s \
  --sample-interval-ms 1000 \
  --output build/k6/game-list-867/concurrency.json
```

## HTTP 결과

`error/timeout`은 k6 표준 `http_req_failed` 기준이며, `checks`는 Slice 응답 계약 check 기준이다.

| 동시 VU | 요청 수 | 처리량 | p50 | p95 | p99 | max | error/timeout | checks |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 353 | 17.430 req/s | 59.662ms | 137.129ms | 248.917ms | 371.407ms | 0% (0) | 100% |
| 4 | 575 | 28.629 req/s | 84.192ms | 209.543ms | 280.810ms | 340.129ms | 0% (0) | 100% |
| 8 | 613 | 30.327 req/s | 199.416ms | 494.930ms | 1,220.537ms | 1,741.920ms | 0% (0) | 100% |

VU 8은 VU 4보다 처리량이 소폭 증가했지만 p95는 약 2.36배, p99는 약 4.35배 증가했다. 이는 이 로컬 Docker 호스트에서 동시성이 올라갈 때 tail latency가 악화되는 관찰값이며, production capacity threshold가 아니다.

## App·PostgreSQL 자원

CPU는 Docker stats가 보고한 컨테이너 CPU percentage라서 PostgreSQL처럼 multi-core를 사용하는 컨테이너는 100%를 넘을 수 있다. memory는 사용량 MiB의 단계별 최댓값이다. DB connection은 `pg_stat_activity`의 단계별 최댓값이다.

| 동시 VU | App1 CPU | App2 CPU | PostgreSQL CPU | App1 memory | App2 memory | PostgreSQL memory | DB total / active | restart |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 10.32% | 10.46% | 110.05% | 691.4MiB | 702.7MiB | 251.4MiB | 29 / 3 | 0 |
| 4 | 11.20% | 11.45% | 157.73% | 693.4MiB | 704.4MiB | 254.1MiB | 29 / 4 | 0 |
| 8 | 17.06% | 16.03% | 185.96% | 695.4MiB | 707.6MiB | 258.7MiB | 29 / 8 | 0 |

메모리 사용량과 전체 connection 수는 단계가 올라가도 큰 변화가 없었고, active connection은 VU와 함께 3→4→8로 증가했다. `wait_event IS NOT NULL` 표본은 단계별 28이었지만 PostgreSQL idle client session도 포함하므로, 이를 DB query queue 길이로 해석하지 않는다.

## 보존·한계

최종 결과 JSON과 단계별 k6 summary/log는 로컬 `build/k6/game-list-867/`에 생성했고 Git에는 커밋하지 않는다. runner는 App image OCI revision과 서버 commit, fixture manifest SHA-256, runner/k6 파일 SHA-256을 함께 검증했다.

이번 측정은 동일한 macOS Docker Desktop 호스트에서 warm-cache에 가까운 격리 환경으로 수행했다. production 네트워크, 외부 observability, 실제 App/DB 인스턴스 크기, 장시간 soak, 장애 주입은 포함하지 않는다. 따라서 V1 선택은 [후보 비교 결과](../../results/game-list-740/game-list-867-2026-08-19.md)의 순차 p95 gate로 확정하고, 본 문서는 동시 부하에서 오류·자원·tail latency를 확인한 보조 증거로 사용한다.
