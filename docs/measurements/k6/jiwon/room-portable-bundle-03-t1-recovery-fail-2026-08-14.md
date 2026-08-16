# [03/05] ROOM portable bundle T1 recovery smoke — FAIL (2026-08-14)

| 항목 | 값 |
| --- | --- |
| 문서 상태 | [`superseded`](README.md) |
| Campaign 상태 | [`completed-with-limitations`](README.md) |
| 기록 분류 | `invalid-measurement-campaign` — 원인은 확인했지만 성능 기준선에서는 제외 |
| 실행 경로 | ROOM portable bundle → `run.sh room-k6` |
| 조건 | T1 / stress / hot / concurrency 2 |
| 시간 범위 | UTC 2026-08-14 14:45:19–14:48:46 / KST 2026-08-14 23:45:19–23:48:46 |
| 근거 식별자 | [비식별 canonical campaign manifest](evidence/room-portable-bundle-03-t1-recovery-fail-2026-08-14.json) |
| 비밀정보 경계 | 비밀번호·credential-derived hash·토큰·세션·CSRF·URL·실제 fixture/resource 식별자는 기록하지 않음. source/artifact 무결성 식별값은 linked manifest에만 보존 |

## 결론

snapshot SQL 수정 뒤 첫 T1 smoke는 raw final status가 `FAIL`이었다. prepare, resource query, before/after snapshot은 모두 정상 종료했지만 k6 exit는 `99`였고 after diagnosis가 실패했다. 요청 10건 중 성공 5건, 예상 밖 4xx 5건, server failure 0건, contract failure 5건이 관측됐다. 이 측정은 성능 수치로 승격하지 않는다.

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

## 결과 처리

실패 시점에는 원인 수정·재측정을 위해 stack을 유지했다. 이 문서는 실패 원인을 보존하는 기록이며, teardown 결과는 최종 유효 campaign 문서에만 기록한다.
