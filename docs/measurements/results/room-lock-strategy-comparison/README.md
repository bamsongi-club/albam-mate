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

현재 이 디렉터리에는 AWS campaign 결과가 없다. 결과가 생성되기 전까지 PASS/FAIL 수치나 winner를 작성하지 않는다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-20` · 폐기 조건 `786 결과가 후속 측정 저장소로 이전될 때`
