# 게임 목록 relation·complex 후보 비교 결과

- 상태: success
- fixture: game-list-170005-observed-2026-08-19 (170,005 games)
- fixture manifest SHA-256: `58263d92f6f1f39f7cf3619f9f7666cf9d48c6f420b59606116a1e353f6000eb`
- runner file SHA-256: `a96476d2d354a561e418853805b60fe3dbd425f9e3da93c338e7bb8ab6b8ac09`
- 선정 후보: V1
- SQL/EXPLAIN evidence gate: 24 provenance capture, 24 SQL capture, 24 slowest EXPLAIN, 8 relation·complex content plan, 4 theme index candidate plan, base exact count 부재=PASS

## Scenario median (네 batch 가운데 두 p95의 산술평균)

| scenario | V0 p50 / p95 / max | V1 p50 / p95 / max | V2 p50 / p95 / max | V3 p50 / p95 / max |
| --- | --- | --- | --- | --- |
| base | 32.610ms / 86.175ms / 98.275ms | 28.368ms / 52.948ms / 73.931ms | 32.901ms / 55.704ms / 110.224ms | 36.861ms / 67.528ms / 75.949ms |
| keyword | 19.793ms / 32.194ms / 35.004ms | 16.540ms / 32.934ms / 41.087ms | 21.247ms / 36.029ms / 38.747ms | 24.634ms / 59.173ms / 62.369ms |
| player-count | 22.901ms / 48.659ms / 85.571ms | 21.636ms / 39.131ms / 105.580ms | 24.266ms / 34.931ms / 41.412ms | 30.565ms / 68.287ms / 81.864ms |
| relation-theme-mechanism | 398.485ms / 554.692ms / 628.872ms | 232.526ms / 288.132ms / 310.185ms | 78.879ms / 120.298ms / 130.079ms | 104.940ms / 158.754ms / 185.515ms |
| complex | 273.064ms / 509.353ms / 596.344ms | 168.778ms / 255.566ms / 297.577ms | 90.462ms / 187.785ms / 547.927ms | 123.346ms / 675.846ms / 837.736ms |
| flags-upcoming-exact | 33.665ms / 68.215ms / 83.328ms | 18.246ms / 28.638ms / 31.416ms | 18.618ms / 27.977ms / 32.259ms | 20.234ms / 39.286ms / 43.533ms |

## Gate

| variant | six-scenario 5% no-regression | relation improved | complex improved | candidate |
| --- | --- | --- | --- | --- |
| V1 | PASS | PASS | PASS | PASS |
| V2 | FAIL | PASS | PASS | FAIL |
- V2 keyword: 36.029ms > 33.804ms (V0 32.194ms × 1.05)
| V3 | FAIL | PASS | FAIL | FAIL |
- V3 keyword: 59.173ms > 33.804ms (V0 32.194ms × 1.05)
- V3 player-count: 68.287ms > 51.092ms (V0 48.659ms × 1.05)
- V3 complex: 675.846ms > 534.821ms (V0 509.353ms × 1.05)

## Raw artifacts

| variant | round | artifact | server commit |
| --- | ---: | --- | --- |
| V0 | 1 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r1.json` | `cecbe1017a38b90b7be8e472ee16c5d809918b90` |
| V0 | 2 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r2.json` | `cecbe1017a38b90b7be8e472ee16c5d809918b90` |
| V0 | 3 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r3.json` | `cecbe1017a38b90b7be8e472ee16c5d809918b90` |
| V0 | 4 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r4.json` | `cecbe1017a38b90b7be8e472ee16c5d809918b90` |
| V1 | 1 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r1.json` | `2502d57e8c468929598e66a080442a4e2a5e412a` |
| V1 | 2 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r2.json` | `2502d57e8c468929598e66a080442a4e2a5e412a` |
| V1 | 3 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r3.json` | `2502d57e8c468929598e66a080442a4e2a5e412a` |
| V1 | 4 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r4.json` | `2502d57e8c468929598e66a080442a4e2a5e412a` |
| V2 | 1 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r1.json` | `725a04197b81fffa284b77f415c64be280a897cb` |
| V2 | 2 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r2.json` | `725a04197b81fffa284b77f415c64be280a897cb` |
| V2 | 3 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r3.json` | `725a04197b81fffa284b77f415c64be280a897cb` |
| V2 | 4 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r4.json` | `725a04197b81fffa284b77f415c64be280a897cb` |
| V3 | 1 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r1.json` | `4d34d74d1adff1eb010a52962acbf3de425df58a` |
| V3 | 2 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r2.json` | `4d34d74d1adff1eb010a52962acbf3de425df58a` |
| V3 | 3 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r3.json` | `4d34d74d1adff1eb010a52962acbf3de425df58a` |
| V3 | 4 | `docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r4.json` | `4d34d74d1adff1eb010a52962acbf3de425df58a` |
