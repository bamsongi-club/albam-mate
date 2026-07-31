# P0 AWS 운영 인프라 기준

이 문서는 P0를 AWS에 배포할 때 구성 요소가 어디에 놓이고, 구현과 운영에서 무엇을 확인해야 하는지 설명한다. 선택의 근거와 변경 규칙은 [ADR-0021](../adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md)이 정본이다.

## 한눈에 보기

```text
사용자
  │ HTTPS 443
  ▼
EC2 t4g.small / Amazon Linux 2023 ARM64
  │ Docker Compose 애플리케이션
  │ PostgreSQL 5432 / TLS / VPC 내부 통신
  ▼
RDS PostgreSQL 18.4 / db.t4g.micro
private DB subnet group / 외부 공개 없음
```

운영 데이터베이스는 EC2의 PostgreSQL 컨테이너가 아니라 RDS에 둔다. EC2를 다시 배포해도 데이터가 애플리케이션 인스턴스와 함께 사라지지 않게 하고, DB 접근은 EC2 보안 그룹에서만 허용한다.

## 확정된 시작 구성

| 대상 | 시작값 | 이유 |
| --- | --- | --- |
| 애플리케이션 호스트 | EC2 `t4g.small`, Amazon Linux 2023 ARM64, 한 대 | P0의 작은 시작 규모와 기존 ARM64 측정 환경에 맞춘다. |
| 실행 단위 | Docker Compose의 애플리케이션 서비스 | 배포 단위를 단순하게 유지하며 운영 Compose에는 PostgreSQL을 넣지 않는다. |
| 데이터베이스 | RDS PostgreSQL `18.4`, `db.t4g.micro` | 로컬·통합 테스트와 같은 PostgreSQL 18.4 계약을 유지한다. |
| DB 저장소 | 암호화된 gp3 20 GiB, 백업 7일, Single-AZ | 최소 복구 경로를 두되 P0에서 Multi-AZ 비용은 추가하지 않는다. |
| 네트워크 | EC2 public subnet, RDS private subnet 두 개 | 사용자 요청 대상과 데이터 저장소의 공개 범위를 분리한다. |
| DB 접근 | TCP 5432 출발지를 EC2 애플리케이션 보안 그룹으로 제한 | 개발자 IP나 `0.0.0.0/0`로 DB를 열지 않는다. |

## 로컬 Compose와 AWS 배포의 관계

환경마다 DB를 시작하는 주체는 다르지만 애플리케이션 계약은 같다.

| 구분 | 로컬 | AWS |
| --- | --- | --- |
| DB 실행 주체 | [compose.local.yml](../../compose.local.yml)의 `postgres:18.4` | RDS PostgreSQL 18.4 |
| DB 접속 범위 | 개발 PC의 `127.0.0.1` | VPC 안의 애플리케이션 보안 그룹 |
| 스키마 | Flyway 마이그레이션 | 같은 Flyway 마이그레이션 |
| 스키마 검증 | Hibernate `validate` | Hibernate `validate` |
| 시간대 | PostgreSQL·JDBC UTC | PostgreSQL·JDBC UTC |
| 비밀값 | 로컬 `.env` | 저장소 밖의 배포 비밀값 |

운영용 Compose는 애플리케이션 서비스만 정의한다. 애플리케이션 이미지나 JAR와 AWS RDS CA 번들을 읽기 전용으로 제공하고, 데이터소스 값은 저장소 밖의 배포 비밀값에서 받는다.

## P0 수동 배포 계약

P0의 첫 운영 배포는 CI/CD보다 수동 배포를 먼저 검증한다. ECR 없이 SSM으로 EC2에 접속해 배포할 전체 커밋 SHA를 checkout하고, 그 소스에서 Spring·Vite 이미지를 빌드한다. 두 이미지에는 같은 커밋의 앞 12자리 SHA를 태그로 사용한다.

- 배포자는 EC2에서 임의 브랜치 최신 상태를 빌드하지 않고 검증한 전체 커밋 SHA를 checkout한다.
- 현재 배포 SHA와 직전 배포 SHA를 운영 기록에 남긴다.
- 애플리케이션 롤백은 직전 SHA를 다시 checkout·빌드하는 방식으로 시작한다.
- Flyway는 전진 마이그레이션을 사용한다. 새 마이그레이션이 이전 애플리케이션과 호환되지 않으면 애플리케이션만 이전 SHA로 되돌리지 않고 별도 복구 결정을 한다.
- GitHub Actions 자동 배포와 ECR은 수동 배포 검증 뒤의 후속 범위다.

### 운영 설정과 비밀 전달

운영 설정의 원본은 AWS Systems Manager Parameter Store의 `/albam-mate/prod/` 경로다. EC2 인스턴스 역할은 이 경로의 조회와 비밀번호 복호화에 필요한 최소 권한만 가진다.

| 값 | Parameter Store 또는 파일 | 보안 경계 |
| --- | --- | --- |
| RDS endpoint | `/albam-mate/prod/db/host` `String` | 저장소 밖 운영 설정 |
| RDS port | `/albam-mate/prod/db/port` `String` | 기본값은 `5432`지만 배포값을 명시 |
| DB 이름 | `/albam-mate/prod/db/name` `String` | 애플리케이션 전용 DB |
| DB 사용자 | `/albam-mate/prod/db/username` `String` | RDS 마스터 사용 금지 |
| DB 비밀번호 | `/albam-mate/prod/db/password` `SecureString` | 전용 KMS 키 또는 승인된 계정 기본 키로 암호화 |
| 공개 호스트명 | `/albam-mate/prod/web/host` `String` | 인증서의 SAN과 일치 |
| RDS CA 번들 | `/etc/albam-mate/rds/global-bundle.pem` | AWS 공개 인증서다. 출처·버전을 확인하고 읽기 전용 마운트 |
| HTTPS 공개 인증서 | `/etc/albam-mate/tls/fullchain.pem` | 비밀값이 아니며 발급·갱신 주체를 운영 기록에 남김 |
| HTTPS 개인키 | `/etc/albam-mate/tls/privkey.pem` | 비밀 파일이며 저장소·이미지·Parameter Store 평문에 저장 금지 |

EC2의 배포 절차는 Parameter Store 값을 표준 출력이나 명령 인자로 노출하지 않고 `/etc/albam-mate/prod.env`에 기록한다. 파일 소유자는 `root`, 권한은 `0600`으로 두고 운영 Compose에만 `--env-file`로 전달한다. 컨테이너 환경 계약은 다음 이름을 사용한다.

- `SPRING_PROFILES_ACTIVE=prod`
- `ALBAM_MATE_PROD_DB_HOST`
- `ALBAM_MATE_PROD_DB_PORT`
- `ALBAM_MATE_PROD_DB_NAME`
- `ALBAM_MATE_PROD_DB_USER`
- `ALBAM_MATE_PROD_DB_PASSWORD`
- `ALBAM_MATE_PROD_RDS_CA_PATH`
- `ALBAM_MATE_PUBLIC_HOST`
- `ALBAM_MATE_TLS_CERT_PATH`
- `ALBAM_MATE_TLS_PRIVATE_KEY_PATH`

Parameter Store 값이 바뀌면 운영 환경 파일을 다시 생성하고 영향받는 컨테이너를 재생성한 뒤 실제 HTTP 흐름을 확인한다. GitHub Actions를 후속 도입하더라도 장기 AWS access key와 운영 DB 비밀번호를 Actions secret에 복제하지 않는다. GitHub OIDC로 짧은 AWS 자격을 받고 SSM 배포 명령만 요청하며, 런타임 비밀의 원본은 Parameter Store로 유지한다.

`GetParametersByPath` 권한은 허용한 상위 경로의 하위 값을 함께 조회할 수 있으므로 EC2 역할에 `/albam-mate/prod/`보다 넓은 경로를 허용하지 않는다. 비밀번호 자동 교체·교체 이력 감사·다중 리전 복제가 필요해지면 Parameter Store에 자체 절차를 덧붙이지 않고 Secrets Manager 전환을 재검토한다.

### 운영 DB 사용자

RDS 마스터 계정은 초기 DB와 권한 준비에만 사용하고 애플리케이션에 주입하지 않는다. P0는 지정한 애플리케이션 DB·스키마 안에서 Flyway와 런타임 SQL에 필요한 권한만 가진 전용 사용자 하나로 시작한다. 이 사용자에게 `rds_superuser`, 다른 데이터베이스 접근 또는 운영에 불필요한 역할 생성 권한을 부여하지 않는다.

Flyway 실행 사용자와 런타임 사용자를 분리할 필요가 생기면 권한·비밀번호·기동 순서가 함께 바뀌므로 운영 가이드와 production datasource 계약을 먼저 갱신한다.

## 배포 전에 준비할 것

1. ARM64에서 실행되는 Java 21 애플리케이션 이미지 또는 JAR
2. PostgreSQL 서비스를 포함하지 않는 운영용 Docker Compose 설정
3. 운영 호스트명과 `/etc/albam-mate/tls/`에 제공할 HTTPS 인증서·개인키 및 갱신 담당자
4. RDS CA로 서버 인증서를 검증하는 production datasource와 위 환경 변수 계약
5. 최소 권한 애플리케이션 DB 사용자와 `/albam-mate/prod/` Parameter Store 값
6. 공개 SSH 없이 EC2를 관리하고 배포하는 SSM 기반 접근
7. EC2·RDS 경보와 비용 알림의 실제 수신자 및 대응 담당자

운영 IaC 변경은 VPC·subnet·route·보안 그룹·IAM·EC2·RDS·경보의 소유 관계를 함께 검토할 수 있어야 한다. 실행 전 변경 세트에서 예상하지 않은 public 접근·NAT Gateway·로드 밸런서·고가용성 리소스가 없는지 확인한다.

## 권장 배포 순서

1. 현재 브랜치와 커밋, dirty worktree를 기록하고 전체 테스트와 실행 산출물 빌드를 통과시킨다.
2. 계정의 프리 티어·크레딧·예산 알림과 서울 리전의 인스턴스·PostgreSQL 버전 가용성을 확인한다.
3. 인프라 변경 세트에서 리소스 종류, 수량, 태그, 삭제 정책과 네트워크 공개 범위를 검토한다.
4. RDS와 전용 애플리케이션 사용자를 만든 뒤 Parameter Store 값과 RDS CA·HTTPS 인증서 파일을 준비한다.
5. SSM으로 EC2에 접속해 검증한 전체 커밋 SHA를 checkout하고 Parameter Store에서 `/etc/albam-mate/prod.env`를 생성한다.
6. 운영 Compose의 정규화 설정을 값 출력 없이 검사하고 같은 SHA 태그로 Spring·Vite 이미지를 빌드·시작한다.
7. EC2에서만 RDS TLS 연결이 되고 Flyway와 Hibernate `validate`가 통과한 다음 공개 HTTPS를 연결한다.
8. P0 핵심 HTTP 흐름과 컨테이너 재생성 뒤 데이터 지속을 검증하고 현재·직전 배포 SHA를 기록한다.
9. 백업 복구·EC2 재부팅·경보 수신처럼 이번 배포에서 수행하지 않은 항목은 완료로 표시하지 않는다.
10. 일회성 환경의 삭제·정리는 배포와 별도로 명시적 승인을 받은 뒤 수행한다. 실행 직전에 정확한 대상을 다시 확인하고, 삭제 후 잔여 비용 리소스와 배포 아티팩트를 조회한 결과까지 확인해야 완료다.

## 배포 후 확인할 것

| 확인 영역 | 완료 기준 |
| --- | --- |
| 애플리케이션 | ARM64 EC2에서 컨테이너가 시작되고 HTTPS로 응답한다. |
| 데이터베이스 | RDS PostgreSQL 18.4에 Flyway가 적용되고 Hibernate `validate`가 통과한다. |
| 기능 | 회원가입·로그인·방 생성·참가의 실제 HTTP 흐름이 RDS까지 연결된다. |
| 네트워크 | 외부에서는 5432 연결이 실패하고 애플리케이션 보안 그룹에서만 성공한다. |
| 지속성 | EC2를 재시작해도 데이터가 남는다. |
| 복구 | RDS 스냅샷 또는 자동 백업에서 복구하고 애플리케이션 연결을 확인한다. |
| 보안 | 최소 권한 AWS 역할과 전용 DB 사용자를 사용하며 비밀값이 저장소·로그에 없다. |
| 관측 | 성능·비용 경보가 지정한 수신자에게 도착하고 담당자가 대응 절차를 안다. |

확인 결과가 일부만 충족되면 ADR-0021은 `미검증`으로 유지한다. 구현이 존재한다는 사실과 운영 결정 전체가 검증됐다는 판정을 구분한다.
경보 수신 담당자 지정과 실제 수신 테스트 중 하나라도 확인되지 않으면 운영 검증은 완료되지 않는다.

## 보안 확인 기준

### EC2 애플리케이션 보안 그룹

- 운영 인바운드는 HTTPS 443만 허용한다.
- SSH 22를 인터넷에 열지 않는다. 관리 접근은 SSM을 기본으로 한다.

### RDS 보안 그룹

- TCP 5432의 출발지는 IP 대역이 아니라 애플리케이션 보안 그룹 하나여야 한다.
- 개발자 IP, EC2 공인 IP와 `0.0.0.0/0`를 허용하지 않는다.
- RDS `PubliclyAccessible`은 `false`여야 한다.

### 비밀값과 TLS

- DB 비밀번호, 세션 식별자와 TLS 개인키는 Git·이미지·로그·명령 이력에 넣지 않는다.
- RDS CA와 HTTPS 공개 인증서는 비밀이 아니다. 출처·버전·갱신 책임을 확인하고 저장소 밖에서 읽기 전용으로 제공한다.
- 운영 `.env` 파일을 개발용 `.env.example`에서 만들지 않는다. Parameter Store에서 생성한 `/etc/albam-mate/prod.env`만 사용한다.
- JDBC는 RDS CA로 서버 인증서를 검증하고 `sslmode=verify-full`을 사용한다.
- 운영에서는 RDS 마스터 계정 대신 필요한 스키마 권한만 가진 애플리케이션 사용자를 만든다.

## 비용과 경보

프리 티어와 크레딧은 계정·가입 시점에 따라 달라지며 무료를 보장하는 스위치가 아니다. 생성 직전에 계정의 Free Tier 플랜과 잔여 크레딧을 확인하고, EC2·RDS 인스턴스 시간 외에도 공인 IPv4, EBS·RDS 저장소, Secrets Manager와 CloudWatch 경보 비용을 함께 본다.

운영 기준 경보는 다음 임계치에서 시작하고 실제 지표에 따라 조정한다.

- EC2 CPU 평균 70% 이상이 15분 지속
- EC2 `CPUSurplusCreditsCharged`가 0보다 큼
- RDS CPU 평균 70% 이상이 15분 지속
- RDS 남은 저장소가 4 GiB 미만으로 15분 지속
- RDS 연결 수가 8 이상으로 15분 지속

Free Tier 85%, 100% 예측 초과와 월 1 USD 실제·예측 비용 알림을 담당자 이메일에 연결한다. 경보와 예산 알림은 비용을 자동으로 중지하지 않으므로, 수신 뒤 누가 원인을 확인하고 리소스를 중지·축소할지도 정한다.

경보가 한 번 발생했다고 바로 증설하지 않는다. EC2·RDS·애플리케이션 지표와 쿼리를 함께 보고 병목이 확인된 계층만 조정한다. 여러 EC2가 필요해지면 서버 세션 공유 방식을 후속 ADR로 먼저 정한다.

## 관련 문서

- [ADR-0021: P0 AWS EC2와 RDS 배포 기준선](../adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md)
- [ADR-0002: PostgreSQL을 주 데이터베이스로 채택](../adr/platform/0002-postgresql-primary-database.md)
- [ADR-0009: 시스템 기준 시각을 UTC로 통일](../adr/platform/0009-utc-time-standard.md)
- [프로젝트 명령](../COMMANDS.md)
- [AWS Systems Manager Parameter Store](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)
- [Parameter Store IAM 접근 제한](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-paramstore-access.html)
- [GitHub Actions에서 AWS OIDC 구성](https://docs.github.com/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)
