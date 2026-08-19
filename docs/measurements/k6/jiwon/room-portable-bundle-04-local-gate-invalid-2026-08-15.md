# [04/05] ROOM portable bundle local gate 중단 — INVALID (2026-08-15)

## 결론

T1 네 조합은 remote final artifact까지 `PASS`였지만, 뒤이은 9개는 bundle render의 clean-source gate에서 차단됐다. 따라서 이 묶음은 공식 매트릭스를 완주하지 못했고 성능 결과를 합산하지 않는다.

- Campaign ID: `room-k6-local-gate-2026-08-15`
- 캠페인 종료 상태: [`completed-with-limitations`](README.md)
- 측정 증거 판정: `INVALID`
- 문서 상태: [`superseded`](README.md)
- 기록 분류: `invalid-measurement-campaign` — 불완전한 중간 실행으로 성능 기준선에서 제외
- 문서 인덱스: [Jiwon k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/room-portable-bundle-04-local-gate-invalid-2026-08-15.json)
- 대체 관계: 05 최종 유효 campaign으로 대체

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 보고 구간 | UTC 2026-08-14 15:22:53~15:37:07 / KST 2026-08-15 00:22:53~00:37:07 |
| 원격 실행 source / 배포 release | `92f5f667b732a5b6b9e6cd7dff5befd13354148a` / 동일 revision |
| 계획 범위 | T1~T5 공식 25개 매트릭스 |
| runner | ROOM portable bundle → infra `run.sh room-k6`; generic `loadtest` 제외 |
| clean-source gate | bundle render 전에 tracked·일반 untracked source 변경을 차단 |
| 원자료 | 원격 실행 4개만 로컬 `build/k6/room/`에 존재; 차단 attempt와 미시도 조합에는 원격 artifact가 없음 |

## 실행 이력과 판정

캠페인은 중단 사실과 원인을 기록하고 종료되어 `completed-with-limitations`이며, 공식 매트릭스를 완주하지 못해 전체 측정 증거는 `INVALID`다.

| 구분 | 수 | 실행·artifact 상태 | 개별 판정 | 보고서 반영 |
| --- | ---: | --- | --- | --- |
| 원격 실행 | 4 | T1 네 조합, 모든 remote phase와 final artifact 존재 | 4/4 `PASS` | 전체 matrix 미완주로 기준선 제외 |
| local render gate 차단 | 9 | remote handoff 전 중단, 원격 artifact 없음 | 9/9 `INVALID` | 제외 |
| 미시도 | 12 | 실행하지 않음 | 판정 없음 | 제외 |

## 원인과 처리

Codex가 저장소 안에 자동 생성한 첨부파일 디렉터리가 untracked 상태가 됐다. remote bundle 생성기는 추적·미추적 변경이 있는 checkout을 거절하도록 설계되어 있으므로, 이는 source safety gate가 정상 동작한 결과다.

첨부파일은 삭제하지 않았다. Git의 로컬 전용 exclude에 해당 자동 첨부 디렉터리만 등록해 source와 분리했다. 이 조치는 tracked 파일이나 일반 untracked source 파일을 무시하지 않으므로, 실제 앱 변경은 계속 bundle gate에서 차단된다. clean gate를 다시 확인한 뒤 새 final campaign을 25개 전체로 재시작했다.

## 해석과 한계

- remote artifact가 없는 9개 local-render-gate attempt에는 시간·phase exit·metric·source/application 정렬·artifact digest가 없다.
- official 25개 조합 중 12개는 이 campaign에서 시도하지 않았다.
- linked manifest의 local-only artifact digest는 이후 로컬 원자료의 변경 여부만 확인하며, 이 Git 저장소만으로 원자료 bundle 내용을 독립 재구성할 수는 없다.

## 다음 측정 조건

- clean-source gate를 통과한 새 campaign에서 공식 25개 매트릭스를 처음부터 다시 실행한다.
- 일부 `PASS`를 이전 중단 campaign과 합산하지 않고, 하나의 완결된 campaign 안에서 source/release 정렬과 T5 comparison을 확인한다.

## 재현

현재 실행 절차는 [ROOM k6 실행](../../../../load-tests/k6/jiwon/README.md#실행)과 [Terraform 원격 실행 bundle](../../../../load-tests/k6/jiwon/README.md#terraform-원격-실행-bundle)을 따른다. clean-source gate를 우회하지 않고 새 campaign으로 재실행한다.

## 원자료와 teardown

원격 실행 4개와 local gate 차단 9개의 ledger·artifact 경계는 [campaign manifest](evidence/room-portable-bundle-04-local-gate-invalid-2026-08-15.json)에 있다. 원시 bundle과 실행 산출물은 로컬 `build/k6/room/`에만 보존한다.

이 중간 campaign 동안 stack은 유지했다. 완전한 official matrix와 T5 comparison gate가 통과한 뒤에만 teardown을 수행한다.
