# ROOM 잠금 전략 비교 측정

## 목적

Issue #786에서 #785가 고정한 A/B/C 후보를 같은 AWS 환경·release 설정·fixture로 paired/crossover 측정한다. 결과는 T1·T2 잠금 전략을 생산에 적용할지 판단하기 위한 증거이며, 이 작업 자체가 생산 코드 병합이나 전략 ADR을 수행하지는 않는다.

세 후보는 기존 PR #791·#792·#793의 SHA를 그대로 사용한다. 후보 PR을 이 branch에 합치거나 후보 코드를 수정하지 않는다.

## 2026-08-20 timeboxed 실행 기록

최초 계획은 T1·T2 전체 matrix와 회귀를 포함한 600회 campaign이었다. 사용자 승인에 따라 이번 실행은 잠금 전략 결정을 위한 최소 증거로 축소했다.

- `T1`·`constant-mixed`·`constant-arrival-rate`·c8·8 req/s·60초만 실행했다.
- A와 B는 각각 완결 PASS 4회를 보존했다. 다섯 번째 반복을 맞추기 위한 재측정은 하지 않는다.
- C p4는 유효 provenance에서 5xx·server failure·contract failure가 각각 4건 발생해 T7의 1차 분류가 `FAIL`이다. nonzero k6 종료 뒤 resource signal이 빠져 runner 최종 상태는 `INVALID`로 남지만, 두 상태를 함께 보존하고 C 성능 순위에는 넣지 않는다.
- 실행 전 중단한 A p3, C p1·p2 bundle도 제외 사유와 함께 보존한다.

실제 실행 목록·candidate SHA·정규화 metric은 [campaign plan](results/room-lock-strategy-comparison/campaign-plan.json), [campaign report](results/room-lock-strategy-comparison/campaign-report.json), [의사결정 보고서](results/room-lock-strategy-comparison/decision-report.md)에 있다. full raw bundle은 PR 검토 범위를 줄이기 위해 최종 branch tree에서 제외했고, 두 JSON의 `archivedPath`는 측정 시점의 원래 archive 위치를 뜻한다. 기계 생성 campaign report는 winner를 자동으로 만들지 않지만, 의사결정 보고서는 이 timeboxed 증거로 A를 생산 적용 전략으로 선택한다. #787은 그 선택을 ADR로 공식화하며, #786은 후보 코드 병합을 수행하지 않는다.

## 현재 구현

- `load-tests/k6/jiwon/tools/room-lock-comparison.mjs`: 후보 순서가 섞인 campaign plan, c16·constant-arrival-rate·mixed fixture, comparison bundle, provenance와 PASS/FAIL/INVALID 집계
- `load-tests/k6/jiwon/tests/room-lock-comparison.test.mjs`: matrix·fixture·bundle scenario 계약 회귀
- [실행 계약](room-lock-strategy-comparison-contract.md): matrix, metric, tail-latency gate, 판정과 운영 게이트
- [결과 보존 규칙](results/room-lock-strategy-comparison/README.md): timeboxed 결과와 raw archive 제외 범위

기존 T1~T5 script와 portable bundle source는 읽기 전용으로 사용한다. 786 전용 bundle의 `tools/fixture.mjs`는 새 comparison 도구 source를 bundle 안에서 실행하도록 생성되며, infra의 기존 `room-k6` transport가 그 bundle을 검증·전송한다.

## 실행 순서

### 1. 후보 SHA 고정

실행 전에 #785의 승인된 candidate SHA를 별도 JSON에 보존한다. 예시는 형식만 보여 주며 실제 SHA는 실행 시점의 승인 artifact를 사용한다.

```json
{
  "A": "<40자리 A candidate SHA>",
  "B": "<40자리 B candidate SHA>",
  "C": "<40자리 C candidate SHA>"
}
```

후보별 앱 checkout HEAD, bundle `sourceRevision`, infra `RELEASE_SHA`, 배포 image revision이 해당 후보 SHA와 일치해야 한다.

### 2. campaign plan 생성

```powershell
node load-tests/k6/jiwon/tools/room-lock-comparison.mjs plan `
  --campaign-id room-lock-20260820 `
  --candidates-file .run/room-lock-candidates.json `
  --seed room-lock-20260820 `
  --output build/k6/room-lock/campaign-plan.json
```

plan은 최초 설계상 480개 핵심 실행과 120개 회귀·배경 실행을 포함한다. T5는 public/host/participant와 scale 1/10을 각각 별도 portable run으로 실행한다. 각 핵심 paired run은 같은 condition·동시성·반복 번호를 공유하고 후보 실행 순서만 seed 기반으로 섞는다. 이번 timeboxed 결과는 이 full-plan generator를 실행한 결과가 아니라, 실제 생성된 bundle을 `campaign-plan.json`에 4회 범위로 고정해 보존한 결과다.

### 3. 후보별 comparison bundle 생성

비교 controller는 786 branch에서 실행하고, `--app-root`에는 해당 후보 SHA의 clean checkout을 지정한다. 후보 checkout에는 비교 도구를 추가하지 않는다. controller는 후보 checkout의 ROOM runtime만 bundle에 복사하고, infra가 요구하는 `build/k6/room/**` 아래에 결과를 만든다. 이 생성 경로 외의 후보 checkout 변경은 거절한다.

```powershell
$env:ROOM_K6_FIXTURE_PASSWORD_HASH = '<실행 환경 전용 fixture hash>'
node load-tests/k6/jiwon/tools/room-lock-comparison.mjs render-bundle `
  --scenario t1 `
  --run-id <plan의 runId> `
  --candidate A `
  --candidate-sha <A candidate SHA> `
  --condition constant-mixed `
  --concurrency 16 `
  --source-sha <A candidate SHA> `
  --app-root <A candidate checkout>

node load-tests/k6/jiwon/tools/room-lock-comparison.mjs validate `
  --for-execution `
  --bundle build/k6/room/<run-id>/<fixture-id>
```

`render-bundle`는 candidate SHA와 source SHA가 다르면 중단한다. bundle에는 prepare SQL, resource query, fixture plan, execution options, generated k6 scenario, immutable SHA-256이 들어간다.

### 4. AWS 실행

AWS `apply`, 앱 배포/release, `room-k6` 실행은 각각 실행 직전 명시 승인을 확인한다. Terraform state 변경에는 root credential을 사용하지 않고 전용 SSO/profile 또는 role을 사용한다.

```bash
./run.sh room-k6 ../albam-mate/build/k6/room/<run-id>/<fixture-id>
```

실행 후 bundle에 `run-manifest.json`, `k6-summary.json`, snapshots, diagnosis, `resource-signals.json`, `infra-execution.json`, `final-result.json`이 있어야 한다. 786은 AWS apply/run을 자동으로 시작하지 않는다.

### 5. 회귀와 결과 집계

T3·T4·T5는 후보 checkout에서 portable bundle을 읽기 전용으로 생성·실행하고, 결과를 같은 campaign의 회귀 gate로 연결한다. 모든 결과가 보존된 뒤 후보 checkout root를 함께 지정하여 campaign report를 만든다.

```powershell
node load-tests/k6/jiwon/tools/room-lock-comparison.mjs aggregate-campaign `
  --plan build/k6/room-lock/campaign-plan.json `
  --candidate-roots-file .run/room-lock-candidate-roots.json `
  --output docs/measurements/results/room-lock-strategy-comparison/campaign-report.json
```

campaign report는 유효·제외 candidate와 정규화 metric을 남기며 winner를 자동으로 만들지 않는다. 사람이 읽는 의사결정 보고서는 report와 측정 시점에 검증한 evidence를 근거로 A를 선택하고, #787이 이를 ADR로 공식화한다. full raw archive는 최종 branch tree에서 제외했지만, 이번 timeboxed report는 A/B 4회와 C T7 FAIL을 정직하게 기록하며 full 5회 gate를 통과했다고 주장하지 않는다.

## 금지 범위

- #791·#792·#793 candidate PR 수정·rebase·merge
- 생산 코드 또는 기존 T1~T5 script 의미 변경
- 서로 다른 시점·환경의 절대 수치 비교
- FAIL/INVALID 결과를 순위에 포함
- AWS teardown 없이 campaign 완료 보고

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-20` · 폐기 조건 `후속 잠금 전략 비교 정본으로 이전될 때`
