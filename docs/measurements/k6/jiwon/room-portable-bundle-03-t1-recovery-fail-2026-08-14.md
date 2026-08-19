# [03/05] ROOM portable bundle T1 recovery smoke — FAIL (2026-08-14)

## 결론

snapshot SQL 수정 뒤 첫 T1 smoke는 `FAIL`이었고, 세션 격리 수정 뒤 같은 조건의 새 Run은 `PASS`했다. 실패 원인과 복구는 확인했지만 두 Run의 source revision이 다르고 각각 한 번만 실행했으므로 성능 개선이나 용량 기준선으로 사용하지 않는다.

- Campaign ID: `room-k6-t1-recovery-smoke-2026-08-14`
- 캠페인 종료 상태: [`completed-with-limitations`](README.md)
- 측정 증거 판정: `FAIL` 후 recovery `PASS`
- 문서 상태: [`superseded`](README.md)
- 기록 분류: `invalid-measurement-campaign` — 원인 진단에는 포함, 성능 기준선에서는 제외
- 문서 인덱스: [Jiwon k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/room-portable-bundle-03-t1-recovery-fail-2026-08-14.json)
- 대체 관계: 05 최종 유효 campaign 전 recovery 이력

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 전체 실행 구간 | UTC 2026-08-14 14:45:19~15:21:05 / KST 2026-08-14 23:45:19~2026-08-15 00:21:05 |
| 시나리오 | T1 / stress / hot / concurrency 2 / 5 rounds |
| 실패 source / 배포 release | `c76b38cb086ea796ad62c139df15b9c1a38a2168` / 동일 revision |
| recovery source / 배포 release | `92f5f667b732a5b6b9e6cd7dff5befd13354148a` / 동일 revision |
| runner | ROOM portable bundle → infra `run.sh room-k6`; generic `loadtest` 제외 |
| 원자료 | 로컬 `build/k6/room/`만 보존; 비밀값·실환경 URL·실제 fixture/resource 식별자는 Git에 기록하지 않음 |

## 실행 이력과 판정

두 Run은 실패 원인과 수정 후 복구를 판단하는 증거에는 포함했지만 성능 기준선에서는 모두 제외했다.

| 역할 | UTC 실행 구간 | k6 / after diagnosis | 성공/요청 | 예상 밖 4xx | contract failure | 판정 |
| --- | --- | --- | ---: | ---: | ---: | --- |
| 실패 진단 | 14:45:19~14:48:46 | `99` / `FAIL` | 5/10 | 5 | 5 | `FAIL` |
| 복구 확인 | 15:17:36~15:21:05 | `0` / `PASS` | 10/10 | 0 | 0 | `PASS` |

실패 Run은 prepare, resource query, before/after snapshot을 정상 종료했지만 k6 exit가 `99`였고 after diagnosis가 실패했다. server failure는 두 Run 모두 0건이었다.

## 원인과 수정

setup이 두 fixture 계정을 준비할 때 k6의 VU 기본 CookieJar를 공유했다. 두 번째 로그인에서 첫 번째 세션이 교체되어 한 actor가 매 round 인증되지 않은 요청을 냈다. 사후 DB snapshot도 한 actor만 취소되고 다른 actor가 계속 ACTIVE인 패턴을 보였다.

- `preparedSessions()`와 `sessionFor()`가 계정별 `new http.CookieJar()`를 사용하도록 수정했다.
- 기본 CookieJar 사용을 막는 source-contract 회귀 테스트를 추가했다.
- k6 summary의 custom counter가 최상위 `count`로 나오는 현재 형식도 사후 진단에서 인식하도록 보정했다. 이 보정은 4xx의 원인은 아니지만 잘못된 5xx/metric 부족 진단을 막는다.

수정 뒤 같은 T1 smoke를 새 bundle로 다시 실행해 요청 10건·성공 10건, 예상 밖 4xx 0건, server failure 0건, contract failure 0건 및 모든 원격 phase `0`을 확인했다.

## 해석과 한계

- 이 campaign은 단일 T1 조건만 다뤘으며 공식 25개 매트릭스를 실행하지 않았다.
- 실패 Run과 recovery Run은 서로 다른 source revision에서 각각 한 번 실행했으므로 지연시간·RPS 개선 비교나 락 전략의 근거로 사용하지 않는다.
- linked manifest의 local-only artifact digest는 이후 로컬 원자료의 변경 여부만 확인하며, 이 Git 저장소만으로 원자료 bundle 내용을 독립 재구성할 수는 없다.

## 다음 측정 조건

- recovery source와 배포 release가 정렬된 clean bundle로 공식 25개 매트릭스를 처음부터 다시 실행한다.
- 성능 비교가 필요하면 같은 source·환경·profile에서 조건별 반복 횟수와 수집 지표를 먼저 고정한다.

## 재현

현재 실행 절차는 [ROOM k6 실행](../../../../load-tests/k6/jiwon/README.md#실행)과 [Terraform 원격 실행 bundle](../../../../load-tests/k6/jiwon/README.md#terraform-원격-실행-bundle)을 따른다. 당시 실패와 recovery의 정확한 revision·Run ledger는 manifest에서 확인한다.

## 원자료와 teardown

두 Run의 revision, phase, metric과 artifact 무결성 식별값은 [campaign manifest](evidence/room-portable-bundle-03-t1-recovery-fail-2026-08-14.json)에 있다. 원시 bundle과 실행 산출물은 로컬 `build/k6/room/`에만 보존한다.

실패 시점에는 원인 수정·재측정을 위해 stack을 유지했다. 이 문서는 실패 원인을 보존하는 기록이며, teardown 결과는 최종 유효 campaign 문서에만 기록한다.
