# MATCH-01 응답 완료 baseline 결과

- 측정 실행 SHA: `91d0fd9c8ccf51e073223e069aee5f98571ce228`
- private sidecar: `response-completion-91d0fd9c8ccf51e073223e069aee5f98571ce228-private-sidecar.json`
- materialized sidecar SHA-256: `46d36cb0d41c195aaacbd0ba771ff11fc1db9bc7db50da92f9a3d7c9addff529`
- artifact: `response-completion-91d0fd9c8ccf51e073223e069aee5f98571ce228.json`
- artifact SHA-256: `fd464d2b77c776afd23b7e4f4e1fd0d9ebc7c693893d941121d7ee940817ecf2`
- 판정: `RESPONSE_BASELINE_ACCEPTED`

| 시나리오 | round | p50 (ns) | p95 (ns) | p99 (ns) | 처리량 (req/s) | retry (total/max) | lock wait (sampled/raw ns) | 실패율 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ACCEPT_NON_TERMINAL | 1 | 1577555100 | 2149993800 | 2311582700 | 388.508 | 0/0 | 0/0 | 0.000000 |
| ACCEPT_NON_TERMINAL | 2 | 1283557800 | 1815024400 | 1939722000 | 472.193 | 0/0 | 0/0 | 0.000000 |
| ACCEPT_NON_TERMINAL | 3 | 1251955000 | 1696142900 | 1803915300 | 503.892 | 0/0 | 0/0 | 0.000000 |
| ACCEPT_FINAL | 1 | 1275940400 | 1917314800 | 2118087100 | 437.469 | 0/0 | 0/0 | 0.000000 |
| ACCEPT_FINAL | 2 | 1235268500 | 1851388700 | 1991376700 | 459.574 | 0/0 | 0/0 | 0.000000 |
| ACCEPT_FINAL | 3 | 1236236800 | 1890754000 | 1982921300 | 455.381 | 0/0 | 1938307200/1938307200 | 0.000000 |
| REQUEUE | 1 | 774840000 | 1180824700 | 1319526800 | 678.009 | 0/0 | 0/0 | 0.000000 |
| REQUEUE | 2 | 762464600 | 1167287000 | 1291086800 | 688.433 | 0/0 | 0/0 | 0.000000 |
| REQUEUE | 3 | 752718500 | 1150434300 | 1276283000 | 699.851 | 0/0 | 0/0 | 0.000000 |
| CANCEL | 1 | 750441600 | 1187779600 | 1335346700 | 674.802 | 0/0 | 0/0 | 0.000000 |
| CANCEL | 2 | 754646000 | 1173040400 | 1323154200 | 689.243 | 0/0 | 0/0 | 0.000000 |
| CANCEL | 3 | 742022900 | 1146154100 | 1268315500 | 697.708 | 0/0 | 0/0 | 0.000000 |

| 시나리오 | 세 measured round p95 중앙값 (ns) | 세 measured round p95 최댓값 (ns) |
| --- | ---: | ---: |
| ACCEPT_NON_TERMINAL | 1815024400 | 2149993800 |
| ACCEPT_FINAL | 1890754000 | 1917314800 |
| REQUEUE | 1167287000 | 1180824700 |
| CANCEL | 1173040400 | 1187779600 |

각 measured round는 1,000개 비식별 raw sample과 fixture/DB 통계/lock-wait 관측을 보존한다. 이 결과는 운영 SLO 또는 후보 선점 baseline 통과를 의미하지 않는다.
