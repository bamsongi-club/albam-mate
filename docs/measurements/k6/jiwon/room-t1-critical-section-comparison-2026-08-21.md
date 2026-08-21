# ROOM T1 참가 취소·자동 승격 critical section 비교 — PASS (2026-08-21)

## 결론

[Issue #781의 승인된 T1~T5 계약](https://github.com/bamsongi-club/albam-mate/issues/781#issuecomment-5361820883)에 따라 V0·V1·V2를 `T1 / stress / hot / c8 / 5 rounds` 조건에서 각각 세 번, 총 아홉 번 실행했다. 아홉 Run은 모두 provenance·무결성·진단 gate를 통과한 `PASS`이며, 이 문서의 수치 계산에는 `reportDisposition=included`인 아홉 Run만 사용했다.

T5의 주 지표인 성공 응답 p95 중앙값은 V0 `233.240ms`, V1 `457.929ms`, V2 `548.845ms`였다. V1과 V2는 각각 두 Run에서 V0보다 낮은 p95를 보였지만, 어느 후보도 V0 대비 최소 20% 개선을 충족하지 못했다. T5 산식을 기계적으로 적용한 결과는 `RETAIN_V0`이지만, 이 보고서는 최종 production 후보를 선택하거나 PR #947·#960의 병합·닫기를 결정하지 않는다. 이 결과는 운영 SLO·용량·특정 SQL 비용의 결론으로 확대하지 않는다.

## 계약과 실행 범위

| 항목 | 값 |
| --- | --- |
| Campaign ID | `room-t1-critical-section-comparison-2026-08-21` |
| Issue / 결정 ADR | [#781](https://github.com/bamsongi-club/albam-mate/issues/781) / [ADR-0086](../../../adr/participation/0086-room-t1-participation-cancel-critical-section-selection.md) |
| 조건 | `T1 / stress / hot / c8 / 5 rounds` |
| 반복 | variant별 3회, 총 9회 |
| 실행 구간 | UTC `2026-08-20T22:55:23Z`–`2026-08-20T23:56:16Z` |
| 대상 환경 | `aws-room-k6` |
| 실행 경로 | portable bundle → `albam-mate-infra/run.sh room-k6` |
| 주 지표 | `room_request_duration{outcome:success}` p95의 variant별 3회 중앙값 |
| Guardrail | 성공 건수, 허용된 `ROOM_CONCURRENT_MODIFICATION` 409, `resource-signals.http.rps` |

T2·T4의 저장 불변식·교차 경합·rollback은 AWS 조건을 추가하지 않고 후보 PR의 H2·PostgreSQL 테스트와 CI로 확인한다. p99와 aggregate query/transaction·CPU·Hikari·lock 신호는 이 campaign에서 진단값으로만 보존한다.

## 입력 source와 fixture 호환 patch

원격 fixture는 `rooms.region` 제약 때문에 기존 `ROOM-K6` 값으로는 준비 단계에서 실패했다. 세 variant의 알고리즘 차이를 섞지 않기 위해 각 original commit을 direct parent로 하는 같은 fixture-only commit을 하나씩 만들었다. 세 diff의 stable patch-id는 모두 `012423435125b5674f572f432d54b186bf4b9212`이며, 변경 파일은 두 개뿐이다.

| variant | original SHA | derived SHA | derived tree SHA | direct parent 확인 |
| --- | --- | --- | --- | --- |
| V0 | `d3ea2a9ca9c972c9fdbcd8800d9c63de0240f9cc` | `43b074570fc7de25bacb3f972868b2c86bfd1839` | `c920cf2b90c162ceda88eb3291a818f2c8505fb1` | `43b0745^ = d3ea2a9` |
| V1 | `e4d104a5ef6f2c8fa06b144acc6b0a44d89be076` | `b8504236619d78824e5ee85c2e4588f39d736b89` | `93db82959f68fabe388fce7aab2af35afda2dc81` | `b850423^ = e4d104a` |
| V2 | `3c6aa743e1371af23962de142ee4b37e1df136fc` | `f08c59e4934c764ef187639a2935e41414b03280` | `daa6fa5a568ec4518bdc379e33c2d68ff6d0fc11` | `f08c59e^ = 3c6aa74` |

| 파일 | original blob | derived blob | 변경 |
| --- | --- | --- | --- |
| `load-tests/k6/jiwon/tools/fixture-model.mjs` | `f351d94495ca47fc7d72c1d0c01025a1ab7e2af8` | `810bbc453692f2accd33e15358f332828cb72e51` | `ROOM-K6` → `홍대` |
| `load-tests/k6/jiwon/tests/fixture-model.test.mjs` | `76406d0f00a360bfd12dbf12a1054e4c2aeefe3e` | `c5ecf56dd7947516b11e4905cb112619848a680c` | `홍대` 포함·`ROOM-K6` 부재 assertion 2개 추가 |

정규화 diff는 다음과 같다. original SHA를 checkout한 뒤 이 두 파일만 같은 diff가 되도록 적용하면 표의 derived tree SHA와 비교할 수 있다.

```diff
diff --git a/load-tests/k6/jiwon/tests/fixture-model.test.mjs b/load-tests/k6/jiwon/tests/fixture-model.test.mjs
@@ -357,6 +357,8 @@ test('fixture SQL은 필수 users timestamp와 정확한 ID 기반 cleanup을
   assert.equal(plan.schemaVersion, 2);
   assert.equal(fixture.schemaVersion, 2);
   assert.match(prepareSql, /pg_advisory_xact_lock/);
+  assert.match(prepareSql, /'ALL_LEVELS',\s*false,\s*'홍대',/);
+  assert.doesNotMatch(prepareSql, /'ALL_LEVELS',\s*false,\s*'ROOM-K6',/);
diff --git a/load-tests/k6/jiwon/tools/fixture-model.mjs b/load-tests/k6/jiwon/tools/fixture-model.mjs
@@ -573,7 +573,7 @@ VALUES
         ${sqlLiteral(ownershipDescription)},
         'ALL_LEVELS',
         false,
-        'ROOM-K6',
+        '홍대',
```

각 derived worktree에서 아래 selector를 실행해 3/3 통과했다.

```text
node --test --test-name-pattern="fixture SQL은 필수 users timestamp와 정확한 ID 기반 cleanup을 만들고 prefix 전체 삭제를 쓰지 않는다" load-tests/k6/jiwon/tests/fixture-model.test.mjs
```

## 유효성 gate

| gate | 확인 결과 | 판정 |
| --- | --- | --- |
| 입력 정합성 | 9/9에서 manifest `sourceDirty=false`, `sourceRevision=run-manifest.sourceSha=infra applicationRevision` | PASS |
| bundle 무결성 | 9/9에서 fixture·summary·console SHA-256이 run-manifest와 일치 | PASS |
| 실행 완료 | 9/9 `COMPLETED`, `completed=true`, k6 exit code `0` | PASS |
| 진단·최종 결과 | 9/9 before/after diagnosis, final result, resource signals가 모두 `PASS` | PASS |
| infra 단계 | 9/9 prepare/resource query/before/k6/after phase exit code 모두 `0` | PASS |
| teardown | Terraform 전용 리소스 94개 destroy 후 state empty 확인 | PASS |

## T5 비교 결과

`허용 409`은 `ROOM_CONCURRENT_MODIFICATION`으로 분류된 `room_concurrent_failures`의 합계다. RPS는 `resource-signals.http.rps`의 세 Run 중앙값이며, statement-level 비용 지표가 아니다.

| variant | 성공 p95 3회 (ms) | p95 중앙값 (ms) | V0 대비 | 성공 / 허용 409 | HTTP RPS 중앙값 | V0보다 낮은 p95 Run | T5 기계적 결과 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| V0 | 613.387 / 233.240 / 190.070 | 233.240 | 기준선 | 48 / 72 | 0.657089 | - | 유지 |
| V1 | 457.929 / 625.617 / 150.593 | 457.929 | 96.3% 악화 | 45 / 75 | 0.663642 | 2/3 | 실패: p95·성공·409 |
| V2 | 548.845 / 567.947 / 179.045 | 548.845 | 135.3% 악화 | 48 / 72 | 0.663784 | 2/3 | 실패: p95 |

V1과 V2는 모두 9/9 artifact validity를 통과했지만 T5의 **기계적 채택 조건**에는 통과하지 못했다. `PASS`는 Run의 실행·artifact 유효성이고, 후보 채택이나 PR lifecycle 결정과 같은 뜻이 아니다.

## 진단 신호

아래는 동일 UTC window에서 수집한 aggregate resource signal이다. query·transaction 값은 background를 포함하는 집계이므로 V1·V2의 특정 statement 비용 개선이나 production 선택 근거로 사용하지 않았다. 각 Run의 non-secret resource signal SHA-256은 evidence에 보존한다.

| variant | query calls 합계 | query time 합계 (ms) | transaction duration 범위 (ms) | 최대 lock wait | 최대 Hikari pending | PostgreSQL CPU 범위 (%) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| V0 | 2,664 | 11,535.425 | 6.187–32.116 | 7 | 0 | 5.12–7.49 |
| V1 | 2,853 | 11,967.684 | 7.409–46.336 | 5 | 1 | 5.60–8.56 |
| V2 | 2,603 | 11,789.387 | 7.246–41.506 | 5 | 0 | 5.40–13.01 |

## 반복별 요약

상세 non-secret SHA-256과 상태는 [campaign evidence](evidence/room-t1-critical-section-comparison-2026-08-21.json)에 보존한다.

| variant | Run | 성공 p95 (ms) | 성공 / 허용 409 | HTTP RPS | 판정 |
| --- | --- | ---: | ---: | ---: | --- |
| V0 | r01 | 613.387 | 16 / 24 | 0.657089 | PASS |
| V0 | r02 | 233.240 | 16 / 24 | 0.648488 | PASS |
| V0 | r03 | 190.070 | 16 / 24 | 0.664250 | PASS |
| V1 | r01 | 457.929 | 15 / 25 | 0.657402 | PASS |
| V1 | r02 | 625.617 | 15 / 25 | 0.663642 | PASS |
| V1 | r03 | 150.593 | 15 / 25 | 0.663983 | PASS |
| V2 | r01 | 548.845 | 15 / 25 | 0.657148 | PASS |
| V2 | r02 | 567.947 | 18 / 22 | 0.663784 | PASS |
| V2 | r03 | 179.045 | 15 / 25 | 0.663984 | PASS |

## 원자료 경계와 재현

원시 bundle은 `build/k6/room/<run-id>/<fixture-id>/`에 local-only로 보존한다. `k6-summary.json`에는 CSRF/session 값이 포함될 수 있으므로 Git에 커밋하지 않는다. 이 report와 evidence에는 비밀이 아닌 source·tree·artifact SHA-256, 집계값, 상태만 보존하며 URL·실제 fixture/resource ID·원시 SQL·로그는 남기지 않는다.

따라서 저장소만으로 원시 bundle 내용을 독립 재검증할 수는 없지만, 이 문서의 original SHA·정규화 fixture patch·derived tree SHA와 evidence의 run별 digest를 통해 측정 입력과 보존된 원자료의 변경 여부를 감사할 수 있다. 원자료가 복구 불가능하거나 evidence digest와 맞지 않으면 이 결과를 선택 근거로 사용하지 않는다.

검증 절차와 원시 artifact 식별값은 [campaign evidence](evidence/room-t1-critical-section-comparison-2026-08-21.json)를 정본으로 따른다.
