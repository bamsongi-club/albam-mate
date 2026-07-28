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

## 배포 전에 준비할 것

1. ARM64에서 실행되는 Java 21 애플리케이션 이미지 또는 JAR
2. PostgreSQL 서비스를 포함하지 않는 운영용 Docker Compose 설정
3. HTTPS 인증서 발급·갱신과 TLS 종료 방식
4. RDS CA로 서버 인증서를 검증하는 production datasource 설정
5. 최소 권한 애플리케이션 DB 사용자와 저장소 밖 비밀 전달 방식
6. 공개 SSH 없이 EC2를 관리하고 배포하는 SSM 기반 접근
7. EC2·RDS 경보와 비용 알림의 실제 수신자 및 대응 담당자

운영 인프라를 코드로 만들 때는 VPC, subnet, route, 보안 그룹, IAM, EC2, RDS와 경보의 소유 관계를 한 변경에서 검토할 수 있어야 한다. 변경 세트를 먼저 확인하고, 예상하지 않은 public 접근, NAT Gateway, 로드 밸런서나 고가용성 리소스가 추가되지 않았는지 실행 전에 살핀다.

## 권장 배포 순서

1. 현재 브랜치와 커밋, dirty worktree를 기록하고 전체 테스트와 실행 산출물 빌드를 통과시킨다.
2. 계정의 프리 티어·크레딧·예산 알림과 서울 리전의 인스턴스·PostgreSQL 버전 가용성을 확인한다.
3. 애플리케이션 산출물의 해시를 계산하고 private 저장소에 올린다.
4. 인프라 변경 세트에서 리소스 종류, 수량, 태그, 삭제 정책과 네트워크 공개 범위를 검토한다.
5. RDS를 만든 뒤 EC2에서만 TLS 연결이 되는지 확인한다.
6. Flyway와 Hibernate `validate`가 통과한 다음 애플리케이션을 공개 HTTPS에 연결한다.
7. P0 핵심 HTTP 흐름과 재시작·복구, 경보 수신을 검증한다.
8. 일회성 환경이면 종료 직후 비용 리소스와 배포 아티팩트를 삭제하고 잔여 리소스를 다시 조회한다.

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

## 보안 확인 기준

### EC2 애플리케이션 보안 그룹

- 운영 인바운드는 HTTPS 443만 허용한다.
- SSH 22를 인터넷에 열지 않는다. 관리 접근은 SSM을 기본으로 한다.

### RDS 보안 그룹

- TCP 5432의 출발지는 IP 대역이 아니라 애플리케이션 보안 그룹 하나여야 한다.
- 개발자 IP, EC2 공인 IP와 `0.0.0.0/0`를 허용하지 않는다.
- RDS `PubliclyAccessible`은 `false`여야 한다.

### 비밀값과 TLS

- DB 비밀번호, 세션 식별자와 인증서는 Git에 넣지 않는다.
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
