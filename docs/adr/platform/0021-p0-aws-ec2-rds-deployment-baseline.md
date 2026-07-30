# ADR-0021: P0 AWS EC2와 RDS 배포 기준선

- 상태: 승인됨
- 작성일: 2026-07-28
- 결정일: 2026-07-28
- 관련: [ADR-0002 PostgreSQL 주 데이터베이스](0002-postgresql-primary-database.md), [ADR-0009 UTC 시간 기준](0009-utc-time-standard.md), [ADR-0013 비밀번호 저장과 인증 요청 보호](../auth/0013-p0-password-storage-auth-request-protection.md), [FND-06 PostgreSQL 검증 환경](../../p0/foundation.md#fnd-06-postgresql-검증-환경)
- 대체 대상: 없음
- 후속 ADR: 없음

## 한눈에 보기

P0는 애플리케이션과 운영 데이터베이스를 서로 다른 AWS 서비스에 둔다. 사용자 요청은 EC2까지만 닿고, 데이터베이스는 EC2의 애플리케이션만 접근할 수 있는 private RDS로 운영한다.

```text
사용자
  │ HTTPS 443
  │ ← 보안 그룹 sg-app: 인터넷에서 443만 받는다
  ▼
EC2 t4g.small — public subnet, 애플리케이션
  │ PostgreSQL 5432, VPC 내부 통신
  │ ← 보안 그룹 sg-db: 출발지가 sg-app일 때만 받는다
  ▼
RDS PostgreSQL 18.4 / db.t4g.micro — private subnet, 운영 데이터
```

`sg-app`과 `sg-db`는 보안 그룹, 즉 해당 자원이 어떤 출발지에서 어떤 포트를 받을지 정하는 방화벽 규칙 묶음의 이름이다. 이 문서에서 두 이름은 계속 이 뜻으로 쓴다.

핵심은 다음 세 가지다.

- EC2를 재배포하거나 교체해도 운영 데이터는 RDS에 남는다.
- RDS는 인터넷에 공개하지 않고 EC2 애플리케이션에서만 연결한다.
- 작은 단일 구성으로 시작하고, 실제 지표에서 병목이 확인된 계층만 확장한다.

## 맥락

P0는 팀원과 외부 사용자가 접속해 실제 흐름을 확인할 수 있어야 한다. 그러나 지금까지는 PostgreSQL을 주 데이터베이스로 선택하고([ADR-0002](0002-postgresql-primary-database.md)), 로컬에서 `compose.local.yml`의 `postgres:18.4`로만 실행했다. EC2에서 애플리케이션을 어디에 실행하고 운영 데이터를 어디에 보관할지, 데이터베이스를 인터넷에 노출하지 않을 방법은 정해져 있지 않았다.

선택에는 다음 제약이 있다.

- P0는 인스턴스 한 대에서 시작할 수 있을 만큼 작다. 처음부터 여러 애플리케이션 인스턴스, 자동 확장, 고가용성 구성을 운영할 근거는 없다.
- 운영 데이터베이스도 로컬·테스트와 같은 PostgreSQL 18 계열을 사용해야 한다. 초기 운영 버전은 현재 검증 컨테이너와 같은 `18.4`로 둔다.
- AWS 프리 티어 또는 계정에 제공된 크레딧 안에서 시작하려 한다. 다만 프리 티어 적용 범위와 잔여 크레딧은 계정·가입 시점에 따라 달라지므로, "무료"를 보장된 비용 상한으로 쓰면 안 된다.
- 데이터베이스를 공개 IP로 열지 않고 애플리케이션에서만 접근하게 해야 한다.
- `t4g.small`에서 bcrypt cost 10과 해시 슬롯 4개의 측정 근거는 이미 있다([ADR-0013](../auth/0013-p0-password-storage-auth-request-protection.md)). 이 측정은 인증 해시 작업의 자원 근거일 뿐, 전체 HTTP·PostgreSQL 경로 또는 서비스 처리량을 검증한 결과는 아니다.

이번 결정의 판단 기준은 다음과 같다.

- 운영 구조를 설명하기 쉬울 것
- 데이터베이스 노출을 최소화할 것
- 현재 PostgreSQL·UTC 계약과 맞을 것
- 초기 비용과 운영 부담을 낮출 것
- 실제 측정 결과가 생기면 그때 확장할 수 있을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| EC2 `t4g.small` + RDS PostgreSQL `db.t4g.micro` | 애플리케이션과 데이터베이스의 책임을 분리한다. RDS가 백업·스냅샷·복구 경로를 제공하고, DB를 VPC 안에 숨길 수 있다. EC2 `t4g.small`은 기존 bcrypt 측정 환경과 맞는다. | 애플리케이션과 DB 두 서비스를 연결·모니터링해야 한다. RDS `db.t4g.micro`는 작은 시작 용량이므로 측정에 따라 증설해야 한다. | 선택 |
| EC2 한 대에서 애플리케이션과 PostgreSQL 컨테이너를 함께 실행 | 로컬 Compose와 가장 비슷하고 처음 만들기는 단순하다. | EC2 장애가 곧 애플리케이션과 운영 데이터베이스의 동시 장애가 된다. 백업·복구·패치·디스크 관리 책임이 팀에 남고 DB를 안전하게 격리하기 어렵다. | 제외 |
| EC2 `t4g.small` + RDS PostgreSQL `db.t4g.small` | 데이터베이스의 초기 CPU·메모리 여유가 더 크다. | 현재 "프리 티어 또는 크레딧 안에서 시작"이라는 비용 원칙에 맞지 않을 수 있다. 실제 병목 근거 없이 DB 크기만 먼저 키운다. | 제외 |
| ECS·EKS, 다중 EC2 또는 RDS Multi-AZ로 시작 | 장애 격리와 확장 경로가 넓어진다. | 배포 파이프라인, 네트워크, 비용, 세션 공유와 운영 절차가 P0보다 먼저 복잡해진다. 현재 수요·장애 목표·부하 측정이 없다. | 제외 |

## 결정

P0 운영 환경은 AWS에서 다음 기준으로 시작한다.

1. 애플리케이션은 Amazon Linux 2023 ARM64의 EC2 `t4g.small` 한 대에서 실행한다. 배포 단위는 Docker Compose이며, **운영 Compose에는 PostgreSQL 컨테이너를 넣지 않는다.**
2. 운영 데이터베이스는 Amazon RDS for PostgreSQL을 다음 사양으로 만든다.

   | 항목 | 값 |
   | --- | --- |
   | 엔진 버전 | PostgreSQL `18.4` |
   | 인스턴스 클래스 | `db.t4g.micro` |
   | 스토리지 | General Purpose SSD `gp3` 20 GiB |
   | 가용 영역 구성 | Single-AZ(한 가용 영역에만 둔다) |
   | 스토리지 암호화 | 켠다 |
   | 자동 백업 보존 기간 | 7일 |

3. EC2는 외부 HTTPS 요청을 받는 public subnet에 두고, RDS는 DB subnet group의 private subnet 두 개에 둔다. RDS의 `Publicly accessible`은 `No`로 둔다.
4. 애플리케이션 보안 그룹(`sg-app`)은 인터넷에서 HTTPS 443만 받는다. DB 보안 그룹(`sg-db`)의 PostgreSQL 5432 인바운드 출발지는 IP 대역이 아니라 `sg-app`으로만 제한한다. 공개 SSH 22와 공개 RDS 접근은 허용하지 않는다.
5. 운영 애플리케이션은 RDS 엔드포인트로 연결하고, DB 사용자·비밀번호·TLS 루트 인증서는 저장소 밖의 배포 비밀값으로 제공한다. Flyway 마이그레이션, Hibernate `validate`, PostgreSQL 연결 시간대 UTC는 로컬·테스트와 같은 계약을 유지한다.
6. 비용은 프리 티어·크레딧 적용 여부를 AWS 콘솔에서 생성 전에 확인한다. 그리고 알림 세 가지를 설정한다. 프리 티어 사용량이 무료 한도의 85%에 닿을 때, 이번 달 사용량이 무료 한도를 넘을 것으로 예측될 때, 월 비용이 1 USD를 넘거나 넘을 것으로 예측될 때다. 이 알림은 상황을 알려줄 뿐 비용을 자동으로 멈추지 않으므로, 초과 시 사람이 원인을 확인하고 조치한다.

이 ADR은 운영 기반의 선택만 정한다. HTTPS 인증서와 리버스 프록시의 구체적 구현, 실제 Compose 파일, CI/CD, DNS, 운영 DB 사용자 생성은 배포 구현 작업에서 정하고 검증한다.

### 로컬과 운영의 관계

DB를 시작하고 연결하는 방법은 다르지만, 애플리케이션이 기대하는 데이터베이스 계약은 같아야 한다.

| 구분 | 로컬 개발 | P0 운영 |
| --- | --- | --- |
| 데이터베이스 | `compose.local.yml`의 `postgres:18.4` 컨테이너 | RDS for PostgreSQL 18.4 |
| 접근 범위 | 개발 PC의 `127.0.0.1` | VPC 내부의 `sg-app`만 |
| 스키마 적용 | 애플리케이션의 Flyway | 같은 Flyway 마이그레이션 |
| 스키마 검증 | Hibernate `validate` | Hibernate `validate` |
| 시간대 | PostgreSQL·JDBC UTC | PostgreSQL·JDBC UTC |
| 비밀값 | 로컬 `.env` | 저장소 밖의 배포 비밀값 |

`compose.local.yml`은 로컬 DB 전용이다. 운영 서버에서 이 파일로 PostgreSQL까지 함께 실행하면 애플리케이션과 운영 데이터를 분리한다는 결정에 어긋난다.

## 결과

- 얻는 것: 애플리케이션과 데이터베이스가 분리되어 EC2 재시작·교체가 운영 데이터를 직접 지우지 않는다. RDS는 인터넷에 노출되지 않고, 로컬·테스트와 PostgreSQL 18.4 및 Flyway 계약을 맞춘다. P0 운영 구성을 한 장의 기준으로 설명할 수 있다.
- 감수할 비용·위험: 애플리케이션 EC2와 RDS 모두 단일 인스턴스이므로 각각 장애 지점이 된다. RDS 백업은 복구 가능성을 높이지만 무중단 서비스나 자동 장애 조치를 보장하지 않는다. 프리 티어·크레딧을 넘으면 과금된다. `t4g` 같은 T 계열 인스턴스에서는 다른 이유로도 과금될 수 있다. 이 계열은 평소 다 쓰지 않은 CPU 성능을 크레딧으로 쌓아 뒀다가 부하가 몰릴 때 꺼내 쓰는 방식인데, 쌓아 둔 크레딧을 모두 소진하고도 계속 기준 성능 이상을 쓰면 그 초과분이 잉여 크레딧 요금으로 청구된다.
- 후속 작업: ARM64 이미지와 운영 Compose·프로필을 구현하고, RDS TLS 연결과 비밀 주입을 구성한다. 나머지 구현 항목과 실제 배포 후 확인할 항목은 아래 「후속 배포 작업」 절에 정리한다.

## 보류 및 재검토

- 지금 하지 않는 것:
  - NAT Gateway
  - Application Load Balancer
  - RDS Multi-AZ
  - 읽기 복제본
  - RDS Proxy
  - Redis 기반 세션 공유
  - 자동 수평 확장
- 보류 이유: P0는 애플리케이션 한 대와 DB 한 대의 실측 결과가 없고, 위 구성은 비용과 운영 대상만 늘린다. 특히 여러 애플리케이션 인스턴스는 서버 세션 공유 방식을 별도로 결정해야 한다.
- 다시 검토할 조건:
  - `CPUSurplusCreditsCharged > 0`, 즉 위에서 설명한 잉여 크레딧 요금이 실제로 청구되기 시작하거나, EC2 CPU 평균이 15분 동안 70% 이상인 상황이 일주일 안에 두 번 발생할 때
  - EC2 메모리 사용률이 15분 동안 80% 이상일 때
  - RDS CPU 평균이 15분 동안 70% 이상이거나, 남은 스토리지가 20% 미만이거나, DB 연결이 애플리케이션 풀 상한에 15분 동안 근접할 때
  - 사용자에게 5xx 오류나 요청 시간 초과가 발생할 때
  - 다중 애플리케이션 인스턴스, 무중단 배포 또는 더 높은 가용성이 실제 요구가 될 때

경보가 한 번 발생했다고 자동 증설하지 않는다. 먼저 EC2·RDS·애플리케이션 지표와 쿼리를 확인하고, 병목이 확인된 계층만 조정한다. 다중 EC2가 필요하면 세션 저장·공유 방식을 후속 ADR로 먼저 정한다.

## 후속 배포 작업

ADR이 승인됐다는 사실만으로 배포가 완료된 것은 아니다. 아래 항목을 실제 배포 작업에서 구현하고, 확인 결과를 이 ADR의 검증 근거로 남긴다.

### 구현할 것

1. ARM64에서 실행되는 애플리케이션 이미지와 PostgreSQL 서비스를 포함하지 않는 운영용 Docker Compose 설정
2. HTTPS 인증서 발급·갱신과 TLS 종료 방식
3. RDS TLS 인증서 검증을 포함한 production datasource 설정
4. 운영 DB 사용자·비밀번호·TLS 인증서를 저장소 밖에서 전달하는 방식
5. 공개 SSH 없이 EC2를 관리하고 배포하는 접근 방식. 아래 「검증」 절의 임시 테스트는 SSM으로 접속했으나, 운영에서 무엇을 쓸지는 이 작업에서 정한다.
6. CloudWatch 경보와 비용 알림의 실제 수신자 및 대응 절차

### 배포 후 확인할 것

일부 항목은 아래 「검증」 절의 임시 스택에서 이미 한 번 확인했다. 다만 그 스택은 외부 인바운드를 모두 닫은 환경이었으므로, 이미 확인한 항목도 실제 배포 환경에서 다시 확인해야 한다.

| 확인할 것 | 임시 스택에서 | 실제 배포에서 |
| --- | --- | --- |
| EC2 ARM64에서 애플리케이션 컨테이너가 시작된다 | 확인함 | 다시 확인 |
| 그 애플리케이션에 HTTPS로 접근된다 | 확인하지 않음(외부 인바운드를 닫아 둠) | 확인 |
| Flyway가 RDS PostgreSQL 18.4에 마이그레이션을 적용하고 Hibernate `validate`가 통과한다 | 확인함 | 다시 확인 |
| 회원가입·로그인의 실제 HTTP 흐름이 RDS까지 연결된다 | 확인함 | 다시 확인 |
| 방 생성·참가의 실제 HTTP 흐름이 RDS까지 연결된다 | 확인하지 않음 | 확인 |
| EC2 밖에서는 RDS 5432 연결이 실패하고 EC2 애플리케이션에서만 성공한다 | 보안 그룹 규칙 구성만 확인함 | 실제 연결 실패까지 확인 |
| EC2를 재시작해도 데이터가 남는다 | 애플리케이션 컨테이너 재시작까지만 확인함 | EC2 인스턴스 재시작으로 확인 |
| RDS 스냅샷 또는 자동 백업으로 복구할 수 있다 | 확인하지 않음 | 확인 |
| 비용·성능 경보가 지정한 수신자에게 도착한다 | 경보 5개 생성까지만 확인함 | 실제 수신까지 확인 |

## 참고 자료

- [Amazon RDS for PostgreSQL 릴리스 일정](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-release-calendar.html)
- [Amazon RDS Free Tier](https://aws.amazon.com/rds/free/)
- [Amazon RDS private access](https://docs.aws.amazon.com/AmazonRDS/latest/gettingstartedguide/security-public-private.html)
- [Amazon RDS DB instance storage](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Storage.html)
- [AWS Free Tier usage 추적](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/tracking-free-tier-usage.html)
- [AWS Budgets](https://docs.aws.amazon.com/cost-management/latest/userguide/budgets-managing-costs.html)
- [T4g Unlimited 모드](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-performance-instances-unlimited-mode.html)
- [Amazon RDS CloudWatch 지표](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-metrics.html)

## 검증

- 상태: 미검증
- 근거:
    - 구현:
        - 2026-07-28 서울 리전의 일회성 환경에서 EC2 `t4g.small` ARM64와 RDS PostgreSQL `18.4` `db.t4g.micro`를 연결했다.
        - RDS는 비공개·암호화·Single-AZ·gp3 20 GiB·백업 7일이었고, DB 보안 그룹은 애플리케이션 보안 그룹에서 오는 5432만 허용했다.
    - 테스트:
        - RDS CA 검증 TLS, 당시 Flyway V1~V3, Hibernate `validate`, 공개 조회, 회원가입·로그인·보호 프로필 조회와 애플리케이션 컨테이너 재시작 뒤 데이터 지속을 확인했다.
- 미검증:
    - [ADR-0023](0023-p0-flyway-baseline-reset-player-count-stages.md)이 V1~V3 기준선을 재생성하므로 이 마이그레이션 근거는 새 기준선에서 다시 검증해야 한다.
    - 임시 테스트는 외부 인바운드를 모두 닫은 채, 인스턴스에 포트를 열지 않고 접속하는 AWS Systems Manager Session Manager(SSM)로 수행했다.
    - 따라서 인터넷 HTTPS 443과 인증서 갱신, 비루트 배포 역할, 전용 애플리케이션 DB 사용자, EC2 재시작, RDS 백업 복구, 메모리 경보와 비용 알림 수신은 아직 검증되지 않았다.
    - 이 항목까지 확인해야 결정 전체를 `검증됨`으로 바꾼다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
