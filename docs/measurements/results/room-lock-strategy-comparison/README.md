# ROOM-LOCK-CMP 결과 보존

이 디렉터리는 Issue #786의 timeboxed T1 결과를 판단 가능한 크기로 보존한다. 코드 구현 검증용 가짜 JSON, placeholder 수치, 서로 다른 환경의 결과를 넣지 않는다.

## 최종 branch tree 보존 대상

- `campaign-plan.json`: 후보 SHA, 실행 조건, 포함·제외 run 목록
- `campaign-report.json`: 정규화 metric, candidate provenance, C의 T7 `FAIL`과 runner artifact `INVALID`, teardown 결과
- `decision-report.md`: A/B/C 수치의 뜻과 A 선택 근거

full raw bundle 371파일과 `raw-digests.json`은 PR 검토 범위를 줄이기 위해 최종 branch tree에서 제외한다. `campaign-plan.json`과 `campaign-report.json`의 `archivedPath`는 측정 시점 archive의 원래 경로이며, 현재 tree의 파일 경로가 아니다. 측정 시점에는 non-secret raw 371파일의 SHA-256과 바이트 크기를 source bundle과 대조했다.

`FAIL`과 `INVALID`의 판정과 실패 사유는 `campaign-report.json`과 `decision-report.md`에 유지한다. teardown과 잔여 AWS resource 결과는 run의 성능 status와 별도 운영 게이트 artifact로 연결한다.

## 2026-08-20 timeboxed 결과

이번 결과는 T1·constant-mixed·c8·8 req/s·60초의 A/B 4회 실행과 C fail-fast 원자료로 한정한다. 기계 생성 `campaign-report.json`은 winner를 자동으로 만들지 않지만, 사람이 읽는 `decision-report.md`는 이 범위의 근거로 A를 생산 적용 전략으로 선택한다. #787은 그 선택을 ADR로 공식화하며, 이 결과 자체가 후보 PR 병합을 승인하지는 않는다.

- `campaign-plan.json`: 사용자 승인 4회 범위, 후보 SHA, 포함·제외 실행 목록
- `campaign-report.json`: A/B의 제한된 T1 metric, C의 T7 1차 `FAIL`과 runner artifact `INVALID` 부가 상태, teardown 결과
- `decision-report.md`: A/B/C 수치의 분모·metric 정의·해석·결정 범위와 A 선택 근거를 설명하는 사람이 읽는 의사결정 보고서
full raw bundle은 이 결과 tree에 배치하지 않는다. C p4의 `resource-signals.json` 누락은 보정하거나 삭제하지 않으며, runner `INVALID`와 실제 5xx `FAIL`의 구분은 두 report에 명시한다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-20` · 폐기 조건 `786 결과가 후속 측정 저장소로 이전될 때`
