# ADR-0086: develop 기준 P2 GitHub Actions 연속 배포 경계

- 상태: 승인됨
- 작성일: 2026-08-21
- 결정일: 2026-08-21
- 관련: [#961](https://github.com/bamsongi-club/albam-mate/issues/961), [ADR-0008](0008-flyway-database-migrations.md), [ADR-0084](0084-github-actions-develop-p1-continuous-deployment.md), [P2 운영 관측·배포 복구](../../p2/monitoring.md#ops-06-develop-p2-연속-배포와-앱-복구), [develop P2 자동 CD 가이드](../../guides/CD_DEPLOYMENT.md)
- 대체 대상: [ADR-0084](0084-github-actions-develop-p1-continuous-deployment.md) (P1 `develop` CD의 환경·대상·실행·복구 경계 전체)
- 후속 ADR: 없음

## 맥락

P1은 종료돼 archive로 동결됐고 현재 제품 단계는 P2다. ADR-0084는 P1 네 EC2 기준선에서 `develop` 자동 CD의 경계를 승인했지만, 현재 인프라의 `stacks/aws/p1`은 성능 측정용 `perf` namespace와 수동 `run.sh` bundle 흐름을 사용한다. 이 흐름을 현재 P2 배포 대상으로 재사용하면 임시 성능 stack, P2 release와 배포 권한·last-known-good 상태가 섞인다.

P2는 `develop`에서 성공한 CI 결과를 같은 SHA의 ARM64 backend·web image와 함께 P2 전용 App1·App2에 반영해야 한다. CI 완료 순서는 commit 순서와 다를 수 있고, GitHub concurrency의 대기 run 교체는 오래된 완료 이벤트가 최신 성공 후보를 밀어내게 할 수 있다. 또한 `workflow_run` 안의 기본 SHA·ref는 배포 대상 SHA가 아니므로, CI가 검증한 immutable SHA를 잃지 않게 source 선택과 실제 deploy 직렬화를 분리해야 한다.

두 Spring 인스턴스가 기동할 때 Flyway를 실행하면 App1·App2가 잠시 다른 release로 공존하는 rollout에서 migration 주체가 중복된다. 앱 전체를 non-web으로 실행하는 방식도 scheduler, runner, component scan의 부수효과를 막지 못한다. 한편 GitHub deploy role이 host env, DB secret, 임의 `AWS-RunShellScript`, S3 bundle 또는 GitHub token을 가지면 최소 권한 경계가 사라진다.

이번 결정은 다음 기준을 만족해야 한다.

- 성공한 `CI Gate`가 검증한 같은 40자리 SHA와 backend·web immutable digest만 P2에 배포할 것
- P2 대상은 P1/perf 기준선과 state·namespace·instance selector를 분리하고, Terraform apply는 CD workflow 밖에 둘 것
- GitHub Actions는 OIDC의 두 최소 권한 role만 사용하고, secret·SSH·임의 shell command에 접근하지 못하게 할 것
- 현재 P2 release와 일치하는 수동 bootstrap LKG가 없으면 어떤 상태 변경도 시작하지 않을 것
- migrator 실패와 앱 실패를 구분하고, 앱 rollback은 지원하되 DB automatic rollback을 약속하지 않을 것
- 배포 검증 account의 password·cookie·CSRF·env 원문을 GitHub, SSM 출력, Parameter Store receipt에 남기지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| P1/perf stack과 `run.sh`를 P2 CD runner로 재사용 | 기존 Terraform·Ansible을 바로 호출할 수 있어 보인다. | `perf` namespace와 P2 release가 섞이고, control-node archive/S3 권한을 GitHub deploy role에 부여해야 한다. | 제외 |
| P2 전용 stack·고정 runner를 새로 둠 | 대상·state·Parameter·IAM 경계를 고정하고 host-local secret을 유지한다. | Terraform·Ansible과 runner contract를 추가해야 한다. | 선택 |
| branch tip을 다시 읽어 최신 commit만 배포 | 구현이 단순하다. | 아직 성공하지 않은 또는 실패한 commit 때문에 마지막 CI-success SHA를 건너뛴다. | 제외 |
| 최신 성공 CI run만 source 후보로 선택한 뒤 deploy를 직렬화 | 마지막 성공 release를 보존하고 오래된 완료 이벤트가 대기열을 교체하지 못한다. | CI run 조회·정적 검증이 필요하다. | 선택 |
| GitHub role에 `AWS-RunShellScript`·secret/S3 권한 부여 | 한 workflow에서 배포 bundle과 host command를 처리하기 쉽다. | arbitrary root 실행과 secret·artifact 접근으로 권한 경계가 무너진다. | 제외 |
| backend image에 같은 SHA Compose asset을 넣고 고정 host runner만 추출 | digest와 Compose asset을 함께 검증하고 기존 host-local env를 재사용한다. | Dockerfile·ignore·runner 검증이 추가된다. | 선택 |
| 일반 App1·App2 기동 시 Flyway 유지 | 새 runner가 필요 없다. | 두 앱이 migration을 실행해 순서·실패 주체가 불명확하다. | 제외 |
| App2 one-shot migrator 후 App2→App1 배포 | DB 접속 경계와 migration 횟수를 분리하고 기존 앱을 먼저 유지할 수 있다. | expand 호환성과 전용 launch policy를 구현·검증해야 한다. | 선택 |
| 자동 DB rollback/PITR | 앱과 schema를 함께 복구하는 것처럼 보인다. | 데이터 보정·외부 부수효과·이미 성공한 migration을 안전하게 역순 처리할 수 없고 복구 훈련도 별도다. | 제외 |

## 결정

### P2 대상과 immutable release

- CD 대상은 P1/perf 기준선과 별도의 고정 P2 stack이다. P2 Terraform state, instance selector와 `/albam-mate/p2/` Parameter namespace는 perf stack과 공유하지 않는다. Terraform plan/apply, 초기 대상 생성, DNS/TLS 입력은 운영자 수동 절차이며 workflow가 실행하지 않는다.
- `CI` workflow의 same-repository `develop` push가 성공했고 terminal `CI Gate`를 통과한 `workflow_run.head_sha`만 release source다. CD는 `github.sha`, `github.ref`, PR head, 배포 시 다시 읽은 branch tip을 source input으로 사용하지 않는다.
- concurrency 밖의 source gate는 같은 workflow·repository·`develop` push의 더 높은 `run_number`인 성공 CI가 있는 경우에만 source를 stale로 건너뛴다. 아직 running이거나 실패한 더 최신 commit은 마지막 성공 source를 막지 않는다. 실제 deploy job만 `p2-deploy` 단일 직렬 group과 `cancel-in-progress: false`를 사용한다.
- backend·web은 같은 40자리 SHA, OCI revision label, `linux/arm64` immutable tag/digest로 게시한다. 같은 SHA의 재실행은 tag를 덮어쓰지 않고 platform·revision을 검증한 뒤 기존 digest를 재사용한다. host rollout도 pulled RepoDigest와 입력 digest의 일치를 확인한다.

### 권한, asset 전달과 P2 host runner

- GitHub Actions는 OIDC로 image-publish role과 deploy role을 분리한다. image-publish role은 backend·web ECR repository의 push에만, deploy role은 ECR read, P2 deploy target contract read, `/albam-mate/p2/last-known-good` read/write, 고정 P2 SSM document의 `SendCommand`와 결과 조회에만 접근한다.
- deploy role은 secret·KMS decrypt·`GetParametersByPath`·S3·Terraform/IAM 변경·SSH·`AWS-RunShellScript` 또는 임의 command document를 허용하지 않는다. custom SSM document는 operation, 40자리 SHA, backend/web digest만 허용하고 P2 host의 고정 runner만 실행한다.
- backend image에는 같은 SHA의 Compose asset을 포함한다. P2 host runner는 exact backend digest를 pull·검증한 뒤 asset을 추출하고, host에 이미 있는 `/etc/albam-mate/app1.env` 또는 `/etc/albam-mate/app2.env`를 local process에서만 조합한다. GitHub runner와 deploy role은 env 파일을 읽지 않는다.
- 인증 smoke credential은 App1 host의 root 소유 `0600` `/etc/albam-mate/deployment-verification.env`에 사전 배치한다. 일반 사용자 계정만 사용하며, runner는 파일 누락·권한 오류·login/read-only smoke 실패를 fail-closed로 처리한다. file content, password, session, CSRF는 stdout, SSM, GitHub summary와 Parameter에 남기지 않는다.

### LKG, migrator와 rollout

- `/albam-mate/p2/last-known-good`은 secret이 아닌 release manifest다. 최초 값은 Terraform이 빈 값으로 만들지 않으며, 운영자가 현재 P2 App1·App2의 동일 SHA·digest, health/upstream과 authenticated smoke를 확인한 뒤 수동으로 기록한다. manifest가 없거나 두 앱과 일치하지 않으면 migrator·app replacement·LKG write를 시작하지 않는다.
- App2에서만 전용 non-web Flyway migrator가 `validate` 후 `migrate`를 정확히 한 번 실행하고 종료한다. 일반 App1·App2 Compose는 Flyway를 명시적으로 비활성화한다. migrator는 최소 datasource/Flyway context만 열며 scheduler·web·일반 component scan을 열지 않는다.
- migrator가 성공한 뒤 App2, App1 순서로 새 digest를 적용한다. 각 단계에서 container health, release SHA, App1이 관찰하는 upstream을 확인하고 마지막에 App1의 CSRF→login→`GET /api/users/me` read-only smoke를 수행한다.
- migrator 실패 시 기존 App1·App2를 유지한다. App2 실패 시 App2만 LKG로 복귀한다. App1 또는 final smoke 실패 시 App1→App2 순서로 LKG 앱만 복귀하고 health·release를 다시 확인한다. DB migration, backup/PITR, data correction은 자동 rollback하지 않는다. 일반 migration은 구·신 앱이 함께 동작하는 expand 변경만 허용하고 contract 변경은 별도 release로 분리한다.

### 증거와 가용성 표현

- workflow와 runner는 source SHA, CI URL, backend·web digest, role name, P2 target/SSM command ID, 단계별 health/upstream/smoke 판정, LKG version과 failure phase만 남긴다. secret 원문·credential·token·cookie·CSRF·env는 어떤 receipt에도 남기지 않는다.
- P2 App1의 단일 ingress와 App2/App1 container 재생성 때문에 짧은 HTTP retry·WebSocket reconnect가 발생할 수 있다. 이 결정은 zero-downtime, blue/green, ALB traffic cutover, 자동 failover를 보장하지 않는다.

## 결과

- 얻는 것:
    - 성공한 CI, image digest, P2 target, migration과 rollback을 한 immutable release 단위로 추적한다.
    - perf 측정 stack과 P2 CD state·권한을 분리하고 deploy role의 secret·arbitrary command 접근을 막는다.
    - 마지막 성공 SHA를 배포하되 실행 중 배포를 취소하지 않아 migration/app 상태를 판정할 수 있다.
    - normal app Flyway 실행을 한 번의 App2 migrator로 옮겨 migration 실패와 app failure의 책임을 구분한다.
- 감수할 비용·위험:
    - 두 저장소의 workflow, Dockerfile, Terraform·Ansible runner, IAM, static/integration test를 함께 유지해야 한다.
    - LKG bootstrap, P2 account/region/network/DNS 입력과 verification account 파일은 실제 운영자가 별도로 준비해야 한다.
    - migration lock·실행시간과 App1 교체의 짧은 연결 중단은 release별로 검토해야 한다.
- 후속 작업:
    - `albam-mate`와 `albam-mate-infra`에서 #961의 T1~T7을 구현·자동 검증한다.
    - 운영자가 P2 Terraform apply, host bootstrap, LKG 최초 기록과 실제 deploy/rollback receipt를 별도 수행한다.

## 보류 및 재검토

- 지금 하지 않는 것: `main`/다른 환경 자동 배포, Terraform apply, P1/perf 재사용, DB automatic rollback, backup/PITR, k6 자동 실행, zero-downtime/blue-green/ALB cutover, autoscaling과 24시간 on-call.
- 다시 검토할 조건: P2 외 환경을 추가할 때, P2 target/ingress topology가 바뀔 때, backup/PITR 복구 훈련이 검증될 때, expand 호환성만으로 처리하기 어려운 schema/data migration이 필요할 때, App1 무중단 목표가 승인될 때.

## 참고 자료

- [#961](https://github.com/bamsongi-club/albam-mate/issues/961)
- [ADR-0008: Flyway SQL 마이그레이션](0008-flyway-database-migrations.md)
- [ADR-0084: 역사적 P1 CD 경계](0084-github-actions-develop-p1-continuous-deployment.md)
- [GitHub Actions workflow_run](https://docs.github.com/actions/using-workflows/events-that-trigger-workflows#workflow_run)
- [GitHub Actions concurrency](https://docs.github.com/actions/writing-workflows/choosing-what-your-workflow-does/control-the-concurrency-of-workflows-and-jobs)
- [GitHub Actions OIDC](https://docs.github.com/actions/concepts/security/openid-connect)

## 검증

- 상태: 코드 구현·자동 검증 완료, 실제 P2 AWS 실행 미검증
- 근거:
    - 계약:
        - [#961](https://github.com/bamsongi-club/albam-mate/issues/961)과 `OPS-06`은 P2 target 분리, CI-success SHA 선택, immutable image, OIDC/SSM, LKG, migrator, rollout, app rollback과 receipt 경계를 고정한다.
- 구현·자동 검증:
    - app CD workflow, backend image Compose asset, Flyway migrator와 일반 앱 Flyway override
    - P2 Terraform/Ansible OIDC role, custom SSM document, host runner와 LKG fail-closed 경계
- 실제 실행 미검증:
    - 실제 P2 Terraform apply, verification account file, LKG bootstrap, deploy·rollback·receipt 실측

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
