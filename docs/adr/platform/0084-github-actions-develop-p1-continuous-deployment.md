# ADR-0084: develop 기준 P1 GitHub Actions 연속 배포 경계

- 상태: 승인됨
- 작성일: 2026-08-20
- 결정일: 2026-08-20
- 관련: [#933](https://github.com/bamsongi-club/albam-mate/issues/933), [ADR-0008](0008-flyway-database-migrations.md), [ADR-0051](0051-p1-self-managed-aws-infrastructure.md), [P1 AWS 저비용 4 EC2 인프라 실행안](../../guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md), [CD 배포 가이드](../../guides/CD_DEPLOYMENT.md)
- 대체 대상: [ADR-0008](0008-flyway-database-migrations.md) (P1 production App1·App2의 Flyway 실행 위치 범위)
- 후속 ADR: 없음

## 맥락

P1은 App1 Nginx 단일 진입점, App1·App2 Spring 두 대, PostgreSQL·Redis 네 EC2로 구성한다. 현재 가이드는 같은 40자리 Git SHA의 ARM64 backend·web 이미지를 ECR에 수동으로 게시하고 App2 뒤 App1을 배포하는 절차를 기록한다. GitHub Actions의 기존 `CI Gate`는 테스트 결과를 집계하지만, 배포 워크플로·OIDC 권한·실행 직렬화·실패 복귀는 구현돼 있지 않다.

`develop`에 기능 브랜치를 병합하면 검증을 통과한 정확히 그 변경을 P1 EC2에 반영해야 한다. 반면 `main`은 현재 자동 배포 대상으로 삼지 않는다. P1의 App1은 유일한 외부 진입점이고 Nginx와 Spring이 같은 Compose 단위이므로, App1 교체 중의 짧은 HTTP 재시도와 WebSocket 재연결은 허용하되 무중단 배포라고 과장할 수 없다.

운영 프로필의 App1·App2 Spring은 현재 각각 기동할 때 Flyway를 실행한다. 두 인스턴스가 서로 다른 버전으로 잠시 공존하는 배포에서는 스키마 적용 주체가 둘이 될 수 있다. [ADR-0008](0008-flyway-database-migrations.md)은 다중 인스턴스 배포가 필요해지면 Flyway 실행 위치를 별도 작업으로 옮길 수 있게 열어 두었지만, 실행·rollback 정책은 정하지 않았다.

2026-08-20 팀 합의로 P1 `develop` 자동 CD와 이 문서의 실행·복구 경계를 채택했다. 이 ADR은 [ADR-0008](0008-flyway-database-migrations.md)의 전체 결정을 폐기하지 않고, P1 production App1·App2의 Flyway 실행 위치 범위만 후속 결정으로 구체화한다. P1 App1·App2 배포 대상에서는 부하 테스트를 수행하지 않고, HTTP·WebSocket·k6 측정은 별도 EC2 환경에서 진행하므로 그 측정 환경의 배포·원복은 이 ADR의 CD 소유 범위에 포함하지 않는다.

판단 기준은 다음과 같다.

- `CI Gate`를 통과한 하나의 immutable SHA·image digest만 P1에 배포할 것
- 정적 AWS 접근 키 없이 GitHub Actions의 분리된 최소 권한 OIDC role과 SSM으로만 P1 image 게시·배포를 수행할 것
- 동시에 들어온 `develop` 변경이 서로 다른 이미지·마이그레이션·롤백 상태를 만들지 않게 할 것
- migration 실패가 기존 앱 컨테이너 교체나 자동 DB rollback으로 이어지지 않게 할 것
- 새 앱 실패 시 구 앱이 확장된 스키마와 계속 호환되는 범위에서 자동 복귀할 것
- 현재 P1의 단일 진입점·백업 미검증·별도 EC2 부하 측정 경계를 자동 CD 완료로 오인하지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 수동 이미지 게시·수동 SSM 배포 유지 | 초기 구현이 없다. | 배포 대상 SHA, 반복 순서와 실패 복구가 사람의 기억에 의존한다. | 제외 |
| `develop` CI 성공 뒤 OIDC·SSM으로 자동 배포 | 병합된 정확한 SHA, 실행 이력과 배포 권한을 한 흐름으로 묶을 수 있다. | 배포 role·migrator·health/smoke·롤백을 구현·검증해야 한다. | 선택 |
| `main`까지 같은 환경에 자동 배포 | 흐름이 단순해 보인다. | P1 검증 환경과 이후 운영 환경의 승인·관측 경계를 섞는다. | 제외 |
| 각 Spring 시작 시 Flyway 실행 유지 | 별도 runner가 없다. | 다중 인스턴스가 스키마 변경의 실행 주체가 되고 배포 순서·실패 책임이 불명확하다. | 제외 |
| 전용 1회 migrator 후 앱 순차 배포 | 마이그레이션 실행 횟수와 실패 위치를 분리하고, 기존 앱을 건드리기 전에 실패를 멈출 수 있다. | 구·신 앱이 같은 확장 스키마와 호환돼야 하며 별도 runner를 구현해야 한다. | 선택 |
| DB snapshot/PITR로 자동 schema rollback | 앱과 DB를 함께 되돌릴 수 있어 보인다. | 성공한 데이터 변경·외부 부수효과를 안전하게 되돌린다는 보장이 없고 P1 backup/restore도 검증되지 않았다. | 제외 |
| Blue/green 또는 ALB 전환 | 트래픽을 별도 슬롯으로 바꿀 수 있다. | App1 이중화·새 슬롯·트래픽 전환과 검증이 필요하다. 현재 P1 범위를 크게 바꾼다. | 보류 |

## 결정

### 트리거와 immutable 릴리스

- `develop`에 병합된 커밋의 `CI Gate`가 성공한 경우에만 그 **동일한 40자리 Git SHA**를 P1 배포 후보로 삼는다. PR head, 재조회한 branch tip, 다른 SHA의 backend·web 조합을 배포 입력으로 쓰지 않는다.
- backend·web은 모두 `linux/arm64` 이미지로 만들고 같은 SHA tag와 OCI revision label을 사용한다. ECR에서 실제 digest를 기록해 배포·health·rollback의 기준으로 삼는다. 변경 불가 tag를 덮어쓰지 않는다.
- `main` push는 P1 자동 배포를 시작하지 않는다. P1 외 환경의 배포 여부·승인 흐름은 이 ADR 범위 밖이다.

### 권한과 실행 직렬화

- CD workflow는 GitHub Actions OIDC로 발급받은 짧은 수명의 두 role만 사용한다. 정적 AWS access key, private key, SSH 접속은 저장소 secret이나 runner에 두지 않는다.
- image-publish role은 허용된 ECR backend·web 저장소에 새 immutable image를 게시하는 권한만 가진다. SSM 명령, Terraform apply, IAM 변경, 비밀값 조회 권한을 가지지 않는다.
- deploy role은 게시된 image의 ECR 읽기, P1 대상 SSM 명령·결과 조회와 고정된 비밀이 아닌 배포 상태 Parameter Store 경로의 읽기·쓰기 권한만 가진다. ECR image 쓰기, Terraform apply, IAM 변경, DB·애플리케이션 비밀값 조회 범위를 배포 권한에 섞지 않는다.
- 두 role의 trust policy는 이 저장소·허용된 workflow·`develop` 배포로 subject를 제한한다.
- P1 배포는 환경별 하나의 직렬 실행만 허용한다. 실행 중인 배포는 취소하지 않고 끝까지 성공·실패를 판정한다. 뒤에 들어온 `develop` 커밋은 실행 중인 배포가 끝난 뒤 최신 SHA 하나만 이어서 배포한다. CI의 취소 정책을 배포 단계에 그대로 재사용하지 않는다.

### Flyway와 롤아웃

- 앱 배포 전, 새 backend image를 사용하는 전용 one-shot migrator가 `validate`와 `migrate`를 **정확히 한 번** 실행하고 종료한다. App1·App2 일반 기동에서는 Flyway를 실행하지 않는다. 이 전환은 migrator와 앱 설정이 함께 구현·검증된 뒤에만 적용한다.
- migrator는 외부 GitHub runner나 PostgreSQL 노드가 아니라 **App2 EC2**에서 실행한다. deploy role이 App2를 대상으로 SSM `SendCommand`를 호출하고, App2의 `/etc/albam-mate/app2.env`를 로컬에서 읽는 새 backend image 작업으로 DB에 접속한다. `sg-postgres`의 `sg-app` 허용 경계를 재사용하며, deploy role은 env 파일이나 DB secret을 조회하지 않는다. SSM 명령 인자·표준 출력·배포 기록에도 secret을 남기지 않는다.
- production 프로필의 `flyway.enabled: true`는 migrator에 유지한다. 일반 App1·App2 기동의 Flyway 비활성화는 후속 구현에서 명시적인 앱별 override와 함께 적용하며, 이 문서만으로 먼저 끄지 않는다.
- 일반 릴리스의 migration은 구·신 앱이 모두 읽고 쓸 수 있는 **expand** 변경으로 제한한다. 새 테이블·새 nullable/default 컬럼·호환되는 index처럼 이전 앱을 깨지 않는 변경은 이 단계에 둔다. 데이터 형식 전환은 필요하면 feature flag 또는 dual read/write를 먼저 배포한다.
- 컬럼·테이블 삭제, rename, `NOT NULL` 강화, 호환되지 않는 데이터 재작성 같은 **contract** 변경은 구 앱이 P1에서 0개가 된 뒤 최소 한 릴리스를 분리해 실행한다. 이때도 별도 복구 절차와 lock·실행시간 검토가 필요하다.
- migrator가 실패하면 App1·App2의 기존 컨테이너를 교체하지 않고 새 릴리스만 실패시킨다. 다만 Flyway는 앞선 versioned migration이 성공한 뒤 뒤 파일에서 실패하면 이미 성공한 스키마 변경을 남길 수 있으므로, 실패가 “DB 무변경”을 뜻하지 않는다. 이 때문에 expand 호환성은 필수다.
- migrator가 성공하면 App2 새 image의 health와 App1에서 보이는 upstream 응답·release SHA를 확인한 뒤 App1을 새 image로 교체한다. 마지막으로 App1·App2 각각의 release SHA와 기능 smoke를 확인한다. Nginx 자체 `/healthz` 200만으로 upstream 정상 배포를 판정하지 않는다.
- migration 성공 뒤 새 앱 health 또는 smoke가 실패하면 이번 run에서 변경한 앱을 **같은** last-known-good image SHA로 자동 복귀시킨다. App2만 바뀐 상태에서 App2가 실패하면 App2만 복귀한다. App1까지 바뀐 뒤 App1 또는 최종 smoke가 실패하면 외부 진입점을 먼저 복원하도록 App1, App2 순서로 모두 복귀시키고 두 인스턴스의 SHA·health를 다시 확인한다. 성공한 DB migration은 자동 rollback하지 않는다. expand 규칙을 지킨 구 앱은 확장된 schema와 계속 동작해야 한다.

### 배포 상태와 최초 bootstrap

- last-known-good의 정본은 비밀을 저장하지 않는 SSM Parameter Store의 `/albam-mate/p1/last-known-good` manifest다. manifest에는 source SHA, backend·web digest, App1·App2에서 검증한 release SHA, 마지막 health/smoke 성공 시각과 Parameter Store version을 기록한다. 노드의 `ALBAM_MATE_RELEASE` 현재값은 bootstrap·rollback의 정본이 아니다.
- 자동 CD를 활성화하기 전에 운영자가 현재 App1·App2의 동일 release SHA·digest, health/upstream과 기능 smoke를 확인해 manifest를 한 번 bootstrap한다. Parameter가 없거나 두 앱의 SHA·digest가 다르면 migrator·앱 교체·SSM 상태 변경을 시작하지 않는다.
- 배포 성공 후 App1·App2의 health·upstream·기능 smoke와 digest를 모두 확인한 뒤에만 새 manifest를 한 번에 기록한다. rollback은 이 manifest의 동일한 last-known-good SHA·digest를 사용한다.

### P1 가용성 표현

- App2→App1 순서는 유지한다. App1 Nginx와 Spring의 동시 교체, App2 재생성, WebSocket 연결 종료로 인한 짧은 HTTP 재시도·WebSocket 재연결은 P1에서 허용한다.
- 이 결정은 zero-downtime, health 기반 트래픽 제외, 자동 장애 조치, 자동 DB 복구를 보장하지 않는다. 이를 요구하면 App1 이중화 또는 안정적 ingress와 명시적 traffic cutover를 포함한 후속 ADR로 다시 결정한다.

## 결과

- 얻는 것:
    - 병합·CI·이미지·P1 배포·rollback이 하나의 SHA와 digest로 추적된다.
    - migration 실패와 새 앱 실패를 분리해, 기존 앱을 먼저 내리지 않고 실패를 처리할 수 있다.
    - DB를 되돌릴 수 있다는 잘못된 가정 없이 expand/contract로 앱 rollback 가능 범위를 명확히 한다.
    - App2의 기존 네트워크·env 경계에서 migrator를 실행하고, LKG bootstrap 상태를 별도 기록할 수 있다.
- 감수할 비용·위험:
    - OIDC trust policy, ECR/SSM 최소 권한, deployment concurrency, one-shot migrator와 앱 Flyway 설정을 구현·운영해야 한다.
    - migration SQL의 lock·실행시간은 기존 서비스 요청에 영향을 줄 수 있으므로 별도 검토가 필요하다.
    - App1 단일 진입점과 현재 Nginx 구성 때문에 짧은 연결 중단이 발생할 수 있다.
- 후속 작업:
    - `albam-mate`와 `albam-mate-infra`에 배포 workflow·OIDC role·SSM 배포·migrator·앱별 Flyway 비활성화·health/smoke·last-known-good rollback을 구현한다.
    - 비밀이 아닌 LKG Parameter Store 경로의 IAM·bootstrap 절차를 구현한다.
    - P1 실제 배포에서 SHA/digest, migrator 결과, App2/App1 health, smoke와 rollback 결과를 보존한다.

## 보류 및 재검토

- 지금 하지 않는 것: `main` 자동 배포, Terraform apply, k6 자동 실행, DB 자동 rollback, backup/PITR 완료 판정, ALB·App1 이중화·blue/green 전환.
- 보류 이유: 이 ADR은 P1 애플리케이션 릴리스의 경계만 고정한다. 인프라 변경과 복구·가용성 보장은 별도 권한·토폴로지·실측 근거가 필요하다.
- 다시 검토할 조건: P1 외 환경을 자동 배포할 때, App1 무중단 목표가 생길 때, backup/PITR 복원 훈련이 검증될 때, migration lock·데이터 변환이 expand/contract 절차만으로 다루기 어려울 때.

## 참고 자료

- [#933](https://github.com/bamsongi-club/albam-mate/issues/933)
- [ADR-0008: Flyway SQL 마이그레이션](0008-flyway-database-migrations.md)
- [ADR-0051: P1 저비용 4 EC2](0051-p1-self-managed-aws-infrastructure.md)
- [GitHub Actions OIDC](https://docs.github.com/actions/concepts/security/openid-connect)
- [Flyway migration transaction handling](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)

## 검증

- 상태: 미검증
- 근거:
    - 계약:
        - [#933](https://github.com/bamsongi-club/albam-mate/issues/933)와 이 ADR은 P1 `develop` 자동 CD의 트리거·권한·직렬화·migration·앱 rollback 경계를 고정한다.
- 미검증:
    - GitHub Actions CD workflow, OIDC trust/권한, ECR publish와 SSM 실행 구현
    - App2 one-shot migrator와 App1·App2 Flyway 비활성화, expand/contract 검증
    - LKG Parameter Store bootstrap과 IAM 검증
    - 실제 P1의 App2→App1 rollout, SHA/digest 확인, health/smoke, 앱 자동 rollback과 연결 중단 범위

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
