# ROOM-LOCK-CMP 결과 보존

이 디렉터리는 Issue #786의 실제 paired/crossover 실행 결과만 보존한다. 코드 구현 검증용 가짜 JSON, placeholder 수치, 서로 다른 환경의 결과를 넣지 않는다.

## 보존 대상

각 run bundle은 다음 원자료를 그대로 보존한다.

- `manifest.json`, `fixture-plan.json`, `private/prepare-provenance.json`
- `prepare.sql`, `resource-query.sql`, `execution-options.json`
- `fixture.json`, before/after snapshot과 diagnosis
- `run-manifest.json`, `infra-execution.json`
- `k6-summary.json`, `k6-console.log`, `resource-signals.json`
- `final-result.json`

campaign 단위 report는 `campaign-report.json`으로 보존하며, candidate SHA·pair ID·sequence·포함/제외 사유·정규화 metric을 연결한다.

## 결과 배치

```text
room-lock-strategy-comparison/
├─ README.md
├─ campaign-plan.json
├─ campaign-report.json
├─ t1/
│  └─ <condition>/<concurrency>/<pair-id>/<candidate>/...
├─ t2/
│  └─ <condition>/<concurrency>/<pair-id>/<candidate>/...
└─ regressions/
   ├─ t3/
   ├─ t4/
   └─ t5/
```

`FAIL`과 `INVALID` run도 삭제하지 않는다. `INVALID`은 순위에서 제외하지만 provenance·raw artifact·digest·실패 사유를 함께 남긴다. teardown과 잔여 AWS resource 결과는 run의 성능 status와 별도 운영 게이트 artifact로 연결한다.

## 2026-08-20 timeboxed 결과

이번 결과는 T1·constant-mixed·c8·8 req/s·60초의 A/B 4회 실행과 C fail-fast 원자료로 한정한다. 전체 600회 matrix의 결과가 아니며 winner·최종 잠금 전략·ADR을 만들지 않는다.

- `campaign-plan.json`: 사용자 승인 4회 범위, 후보 SHA, 포함·제외 실행 목록
- `campaign-report.json`: A/B의 제한된 T1 metric, C의 T7 1차 `FAIL`과 runner artifact `INVALID` 부가 상태, teardown 결과
- `decision-report.md`: A/B/C 수치의 분모·metric 정의·해석·결정 가능/불가능 범위를 설명하는 사람이 읽는 의사결정 보고서
- `raw/`: 완결·중단 bundle 13개의 non-secret 원자료 371개 파일을 원본 구조로 보존한 경로
- `raw-digests.json`: source 397개 파일의 SHA-256, Git 보관 371개 파일의 원본 대조 결과, 제외한 26개 credential-derived fixture artifact의 SHA-256·크기·사유

`prepare.sql`과 `private/prepare-provenance.json`은 fixture bcrypt hash를 포함하므로 Git raw archive에는 넣지 않는다. 측정 수치·C의 실패 근거와 무관한 이 값은 source SHA-256 증거만 남긴다. 나머지 보관 파일은 AWS teardown 직전에 source bundle과 archive bundle의 SHA-256 일치를 확인했다. C p4의 `resource-signals.json` 누락은 보정하거나 삭제하지 않는다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-20` · 폐기 조건 `786 결과가 후속 측정 저장소로 이전될 때`
