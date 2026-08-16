# [04/05] ROOM portable bundle local gate 중단 — INVALID (2026-08-15)

| 항목 | 값 |
| --- | --- |
| Campaign 상태 정본 | [campaign 상태 인덱스](README.md) |
| 기록 분류 | `invalid-measurement-campaign` — 불완전한 중간 실행으로 성능 기준선에서 제외 |
| 실행 경로 | ROOM portable bundle → `run.sh room-k6` |
| 시간 범위 | UTC 2026-08-14 15:22:53–15:37:07 / KST 2026-08-15 00:22:53–00:37:07 |
| 근거 식별자 | [비식별 canonical campaign manifest](evidence/room-portable-bundle-04-local-gate-invalid-2026-08-15.json) |
| 비밀정보 경계 | 비밀번호·credential-derived hash·토큰·세션·CSRF·URL·실제 fixture/resource 식별자는 기록하지 않음. source/artifact 무결성 식별값은 linked manifest에만 보존 |

## 결론

T1 네 조합은 remote final artifact까지 `PASS`였지만, 뒤이은 9개는 bundle render의 clean-source gate에서 차단됐다. 따라서 이 묶음은 공식 매트릭스를 완주하지 못했고 성능 결과를 합산하지 않는다.

## 원인과 처리

Codex가 저장소 안에 자동 생성한 첨부파일 디렉터리가 untracked 상태가 됐다. remote bundle 생성기는 추적·미추적 변경이 있는 checkout을 거절하도록 설계되어 있으므로, 이는 source safety gate가 정상 동작한 결과다.

첨부파일은 삭제하지 않았다. Git의 로컬 전용 exclude에 해당 자동 첨부 디렉터리만 등록해 source와 분리했다. 이 조치는 tracked 파일이나 일반 untracked source 파일을 무시하지 않으므로, 실제 앱 변경은 계속 bundle gate에서 차단된다. clean gate를 다시 확인한 뒤 새 final campaign을 25개 전체로 재시작했다.

## 해석과 한계

- remote artifact가 없는 9개 local-render-gate attempt에는 시간·phase exit·metric·source/application 정렬·artifact digest가 없다.
- official 25개 조합 중 12개는 이 campaign에서 시도하지 않았다.
- linked manifest의 local-only artifact digest는 이후 로컬 원자료의 변경 여부만 확인하며, 이 Git 저장소만으로 원자료 bundle 내용을 독립 재구성할 수는 없다.

## 결과 처리

이 중간 campaign 동안 stack은 유지했다. 완전한 official matrix와 T5 comparison gate가 통과한 뒤에만 teardown을 수행한다.
