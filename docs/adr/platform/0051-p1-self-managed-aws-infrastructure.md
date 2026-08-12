# ADR-0051: P1 저비용 4 EC2 자체 운영 인프라와 Nginx 진입점

- 상태: 승인됨
- 작성일: 2026-08-06
- 결정일: 2026-08-06
- 관련: [P1 실행 환경과 공용 인프라](../../P1-spec.md#실행-환경과-공용-인프라), [다중 인스턴스 실행](../../ARCHITECTURE.md#다중-인스턴스-실행), [P1 AWS 실행 설계](../../guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md), [ADR-0021 P0 AWS EC2와 RDS 배포 기준선](0021-p0-aws-ec2-rds-deployment-baseline.md), [ADR-0038 다중 인스턴스 공용 세션과 스케줄 조정](0038-multi-instance-session-and-scheduler-coordination.md)
- 대체 대상: [ADR-0038](0038-multi-instance-session-and-scheduler-coordination.md)의 `production` AWS 토폴로지와 운영 리소스 선택(ALB·ASG·RDS·Redis 제품)
- 후속 ADR: 없음

## 맥락

승인된 [ADR-0021](0021-p0-aws-ec2-rds-deployment-baseline.md)은 P0 데이터를 비공개 RDS PostgreSQL에 두고, P1 정본과 [ADR-0038](0038-multi-instance-session-and-scheduler-coordination.md)은 ALB 뒤 여러 애플리케이션 인스턴스가 공용 RDS PostgreSQL·Redis를 쓰는 목표 구성을 기록한다. 두 기록 모두 실제 배포·측정이 끝났다는 뜻은 아니다.

2026-08-06 논의와 이후 검토에서 P1 검증 환경을 다음 구성으로 선택했다.

| 역할 | 초기 사양 | 실행 내용 |
| --- | --- | --- |
| App1 EC2 | `t4g.micro` | web(Nginx) 컨테이너 + Spring 컨테이너, 유일한 외부 진입점 |
| App2 EC2 | `t4g.micro` | Spring 컨테이너 |
| PostgreSQL EC2 | `t4g.micro` | `postgres:18.4` 컨테이너 + 암호화 EBS |
| Redis EC2 | `t4g.micro` | `redis:8.4-alpine` 컨테이너 + AOF + 암호화 EBS |

함께 선택한 경계는 네 가지다. ALB·ASG 기반 자동 확장·상태 기반 자동 대체를 쓰지 않고, 네 EC2를 public subnet과 Public IPv4에 두어 NAT Gateway 비용을 피하며, 인터넷 인바운드는 App1의 `80`만 기본으로 열고 인증서와 Nginx TLS 설정을 준비한 뒤 선택적으로 `443`을 열며, 확장은 측정된 병목을 근거로 해당 역할만 수동으로 한다. 단순 시연에서는 Spring 한 대 구성도 가능하다는 안이 함께 나왔으나 교차 인스턴스 검증에는 쓰지 않는다.

이렇게 고른 이유는 세 가지다.

- **전부 `t4g.micro`로 시작한다.** 대안은 분산 대상인 Spring EC2만 `t4g.small`로 두는 안이었고 근거는 프리티어 월 750시간이었다. 그러나 P1 검증 목표는 환경을 여유 있게 굴리는 것이 아니라 어느 역할이 먼저 한계에 닿는지 보는 것이다. 메모리를 미리 키우면 병목이 드러나는 부하 수준도 올라가 검증 기간 안에 한계를 못 볼 수 있다. 벡터 검색처럼 PostgreSQL 메모리를 크게 쓰는 기능도 아직 없어 데이터 서비스를 먼저 키울 근거가 없다.
- **PostgreSQL·Redis도 Docker로 실행한다.** 네 역할의 실행 단위를 컨테이너로 맞춰야 이후 오케스트레이션으로 옮길 때 같은 이미지와 기동 방식을 그대로 쓴다. 호스트에 직접 설치하면 볼륨을 키우거나 인스턴스를 교체할 때마다 설치와 설정 복원을 되풀이해야 하고, Redis는 배포마다 설정 파일을 다시 맞춰야 한다.
- **ALB 대신 App1의 Nginx를 진입점으로 쓴다.** 월 고정비를 줄이기 위해서다. 대가로 App1 장애가 곧 전체 외부 진입점 장애가 되는 단일 장애 지점이 생긴다.

부하 검증의 성공 기준도 정리했다. 처리량 숫자 하나로 "이 정도면 견딘다"를 일반화하지 않는다. 세션을 유지한 채 WebSocket을 열고 채팅을 보내는 부하와 단순 조회 부하는 요청 수가 같아도 자원 소비가 다르기 때문이다. 대신 실제 사용 흐름을 재현한 시나리오에서 부하를 점진적으로 올리며 먼저 실패하는 역할과 그 시점의 지표를 기록한다.

이 선택에는 두 가지 책임이 따라온다. 관리형 데이터 서비스를 자체 운영으로 바꾸면 백업·복구·패치·TLS·디스크·장애 대응이 팀 몫이 된다. Terraform을 써도 AWS 리소스가 GCP 리소스로 자동 변환되지 않으므로 공급자별 모듈과 상태 파일이 필요하고, 인프라 저장소를 분리해도 상태 파일·실행 계획·비밀값을 따로 보호하지 않으면 보안 경계가 되지 않는다.

호스트 설정은 Terraform과 분리한 Ansible playbook으로 반복 적용한다. 논의에서 나온 Terraform output 기반 inventory 흐름은 채택하되, 현재 보안 경계에 맞춰 Public IPv4와 SSH key 대신 EC2 instance ID와 Systems Manager를 사용한다. 이 선택은 SSH 22를 열지 않는 운영 접근 원칙과 일치한다.

현재 구현과의 차이도 남아 있다. `compose.production.yml`과 `nginx.production.conf`는 한 호스트의 Spring만 대상으로 하므로, 다중 upstream과 App2 host port 게시는 별도 구현이 필요하다.

판단 기준은 다음과 같다.

- P1 검증 환경을 다른 팀원 AWS 계정에서도 재현할 수 있을 것
- Spring 두 대가 공용 PostgreSQL·Redis를 쓰는 현재 P1 애플리케이션 계약을 유지할 것
- 네 역할을 모두 `t4g.micro`로 시작하고 역할별 병목 근거가 있을 때만 확장할 것
- 부하 성공 기준을 단일 처리량 숫자가 아니라 실제 사용 흐름 시나리오와 역할별 실패 지점으로 정의할 것
- 운영 데이터를 컨테이너와 EC2 루트 볼륨의 수명에서 분리할 것
- 공개 SSH, 장기 Access Key와 저장소의 평문 비밀값을 쓰지 않을 것
- 단일 PostgreSQL·Redis와 App1 단일 진입점의 장애 위험을 고가용성 구성으로 과장하지 않을 것
- P1 검증 종료 뒤 환경과 과금 리소스를 확인 가능하게 제거할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 기존 P1 관리형 구성 | 자동 백업·복구와 관리형 장애 대응을 활용하고 데이터 서비스 운영 부담이 작다. | 관리형 서비스 비용이 발생하고 자체 운영 학습 범위가 줄어든다. | 기존 승인 기준 유지 |
| ALB + `t4g.small` Spring 2대 + `t4g.micro` 데이터 2대 | 분산 대상인 Spring에 먼저 메모리 여유를 주고 프리티어 월 750시간을 활용한다. | ALB 고정비가 생기고, Spring 메모리가 넉넉해 검증 기간에 한계 지점을 관찰하지 못할 수 있다. | 제외 |
| ALB + `t4g.micro` EC2 4대 | 관리형 진입점의 상태 확인과 대상 분리를 활용한다. | 비용 최소화 목표에 비해 ALB 고정비가 크고 현재 web 컨테이너의 TLS 종료 경계를 다시 설계해야 한다. | Nginx 안으로 대체 |
| App1 Nginx + `t4g.micro` EC2 4대 | 별도 로드 밸런서 없이 교차 인스턴스 흐름을 재현하고 역할별 병목을 같은 초기 조건에서 관찰한다. | App1이 Nginx와 Spring 자원을 함께 쓰고 App1 장애 시 전체 진입점이 멈춘다. PostgreSQL·Redis도 단일 장애 지점이며 운영 책임은 팀에 있다. | 선택 |
| EC2 한 대에 Spring·PostgreSQL·Redis 동거 | 구성과 비용이 가장 작고 로컬 Compose와 유사하다. | 한 대 장애가 전체 장애가 되고 교차 인스턴스 검증 목표를 충족하지 못한다. | 제외 |
| ECS·EKS와 관리형 데이터 서비스 | 순차 배포와 오케스트레이션 확장 경로가 넓다. | P1 검증에 필요한 범위보다 IAM·네트워크·스케줄러 운영이 복잡하다. | 제외 |
| 클라우드마다 수동으로 VM·네트워크 생성 | 처음 한 번은 Terraform 학습 없이 만들 수 있다. | 계정 이동 때 설정 누락을 검증하기 어렵고 삭제 대상과 비용 소유권이 불명확해진다. | 제외 |

관리형 서비스의 비용 배수는 선택 근거로 확정하지 않는다. 실제 계정·리전·인스턴스 유형·스토리지·가동 시간과 Public IPv4·EBS·S3·CloudWatch를 포함해 다시 계산한다.

## 결정

EC2 네 대의 역할·초기 사양, App1 Nginx 진입점, ALB·자동 확장 제외, public subnet, 네트워크·보안·데이터 운영과 Terraform 상태 관리 경계를 P1 AWS 검증 환경의 기준으로 채택한다.

1. App1의 Nginx가 HTTPS와 WebSocket Upgrade를 처리하고 App1·App2 Spring에 요청을 분산한다. 교차 인스턴스 분산을 확인할 수 있도록 upstream 응답 헤더나 접근 로그에 실제 대상을 남긴다.
2. Terraform은 애플리케이션 EC2 두 대를 서로 다른 AZ에 고정 생성하고 ASG 기반 자동 확장·상태 기반 자동 대체 정책은 쓰지 않는다. 운영자가 `terraform apply`를 실행하면 구성 변경이나 실제 리소스 유실에 따라 인스턴스가 교체·재생성될 수 있다. App1은 web과 Spring 컨테이너를, App2는 Spring 컨테이너만 실행한다. 프런트엔드 전용 EC2나 별도 S3·CloudFront 배포는 P1 검증 범위에서 쓰지 않는다.
3. JVM 최대 heap의 초기값은 `-Xmx256m`로 둔다. 측정 결과가 아니라 `t4g.micro`의 1GiB를 web 컨테이너·운영 에이전트와 나눠 쓰기 위한 출발점이므로, P1 부하에서 GC·OOM 지표와 컨테이너 메모리 한도를 함께 확인해 조정한다.
4. PostgreSQL `18.4`는 `t4g.micro` EC2 한 대의 Docker 컨테이너로 실행하고 암호화한 별도 EBS에 데이터를 저장한다.
5. Redis `8.4-alpine`은 `t4g.micro` EC2 한 대의 Docker 컨테이너로 실행하고 AOF와 암호화한 별도 EBS를 쓴다. Redis는 Spring Session, 전송 제한과 Pub/Sub 신호만 소유하고 업무 데이터 정본은 PostgreSQL에 둔다.
6. 네 EC2는 public subnet과 Public IPv4를 쓰고 NAT Gateway는 만들지 않는다. 공개 인바운드는 App1 Nginx의 TCP `80`만 기본 허용하고, 유효한 인증서와 TLS 설정을 준비한 경우에만 TCP `443`을 추가한다. App2의 Spring 포트, PostgreSQL `5432`, Redis `6379`는 애플리케이션 보안 그룹에서만 허용한다. SSH는 열지 않고 운영 접속은 SSM Session Manager를 쓴다.
7. 애플리케이션은 private IP를 직접 저장하지 않고 private DNS 이름으로 PostgreSQL·Redis에 연결한다.
8. Terraform은 AWS용 모듈과 계정별 원격 상태 파일로 네트워크, EC2, EBS, IAM, private DNS와 Ansible SSM 임시 전송 버킷을 관리하고 EC2 instance ID 기반 inventory를 출력한다. cloud-init은 최초 부팅의 SSM 준비와 데이터 EBS 마운트만 담당하며, Ansible은 SSM으로 접속해 Docker와 공통 호스트 설정을 반복 적용한다. Terraform `apply`에서 Ansible을 자동 호출하지 않고 각 단계의 검토·실패 경계를 분리한다. ALB·NAT Gateway, 애플리케이션 이미지 빌드, Flyway 스키마와 테스트 데이터는 Terraform이 관리하지 않는다. `t4g`는 ARM64이므로 빌드 파이프라인이 `linux/arm64`를 지원해야 한다.
9. 각 계정의 Terraform 상태 파일은 versioning·encryption·public access block과 lockfile을 적용한 S3 backend에 분리한다. 실제 비밀값, 상태 파일, 실행 계획과 개인 tfvars는 Git에 저장하지 않는다.
10. 다른 클라우드로 이동할 때에는 provider별 모듈과 상태 파일을 새로 만들고 공통 Docker 이미지와 역할별 입력·출력 계약만 재사용한다.
11. 이 구성을 P1의 최종 용량이나 고가용성 운영 구성으로 부르지 않는다. 역할별 CPU·메모리·디스크·연결 지표와 실패 증상을 기록하고 병목이 확인된 역할만 단계적으로 조정한다. App2 장애 시 Nginx의 실패 대상 제외 동작과 기존 연결 영향을 검증하고, App1 장애는 전체 외부 진입점 장애로 기록한다. 모든 EC2와 데이터 서비스 복구는 수동이다.
12. 병목 해소 수단을 인스턴스 확장으로만 한정하지 않는다. 실패 원인이 사양이 아니라 애플리케이션 동작으로 확인되면 사양을 올리는 대신 처리 방식을 바꾸는 선택지도 평가한다. 방 동시성 제어의 낙관적 잠금 충돌과 재시도가 지연을 만드는 경우가 그 예다. 어느 쪽이든 측정값과 판단 근거를 남기고, 애플리케이션 변경은 해당 도메인의 별도 작업으로 분리한다.

[ADR-0021](0021-p0-aws-ec2-rds-deployment-baseline.md)은 완료된 P0의 EC2·RDS 기준선이므로 대체하지 않는다. 이 ADR은 [ADR-0038](0038-multi-instance-session-and-scheduler-coordination.md)의 `production` 설명 중 ALB·ASG·RDS와 운영 Redis 제품 선택만 대체한다. Spring Session Redis, PostgreSQL ShedLock, Redis fallback 금지와 다중 인스턴스 애플리케이션 계약은 계속 ADR-0038을 따른다.

## 결과

- 얻는 것:
    - P1 검증 환경을 선언형 코드로 다른 팀원 AWS 계정에 같은 순서로 재현할 수 있다.
    - Spring 두 대의 공용 세션, 채팅 fan-out·catch-up, ShedLock과 알림 relay를 실제 네트워크에서 검증할 수 있다.
    - 컨테이너와 EC2 루트 볼륨을 교체해도 별도 EBS와 백업으로 복구할 경계를 마련한다.
- 감수할 비용·위험:
    - App1 Nginx가 단일 외부 진입점이므로 App1 장애 시 App2가 정상이어도 서비스에 접근할 수 없다.
    - PostgreSQL·Redis도 단일 장애 지점이며 자동 장애 조치가 없다. OS·Docker·DB·Redis의 보안 업데이트, TLS, 용량, 백업과 복구는 팀이 직접 운영한다.
    - public subnet과 Public IPv4는 NAT Gateway 비용을 피하는 대신 공격 표면과 IPv4 비용을 남긴다. 보안 그룹을 잘못 열면 데이터 서비스가 인터넷에 노출된다.
    - Ansible SSM 연결은 제어 노드의 AWS 권한과 Session Manager plugin, 명령·파일 전송용 임시 S3 버킷에 의존한다. 중단된 실행이 남긴 임시 객체는 공개 차단·암호화·1일 만료 정책으로 제한한다.
    - 네 역할이 모두 `t4g.micro`이므로 JVM 메모리 부족, CPU credit 고갈, PostgreSQL 연결·디스크 병목 또는 Redis 메모리 부족이 먼저 나타날 수 있다.
    - Terraform `apply` 권한과 상태 파일 접근을 잘못 관리하면 인프라 삭제나 비밀 노출로 이어진다.
- 후속 작업:
    - 별도 인프라 저장소의 Terraform 모듈과 cloud-init, SSM 기반 Ansible 호스트 준비를 검증하고 역할별 Compose 배포는 후속으로 구현한다.
    - RDS CA·엔드포인트를 전제로 한 production profile·Compose·환경 예시를 자체 운영 PostgreSQL TLS·private DNS 계약으로 바꾼다.
    - 운영 Nginx에 App1·App2 upstream, WebSocket Upgrade, timeout, 실패 대상 제외와 upstream 식별 로그를 구현하고, App2 Spring을 host `8080`에 게시해 애플리케이션 보안 그룹 안에서만 접근되게 한다.
    - App1 Nginx TLS 인증서의 발급·저장·갱신, 상태 확인 엔드포인트, Redis 인증·TLS와 Flyway 실행 주체를 현재 구현에 맞게 확정한다.
    - 부하 생성 도구를 확정한다. k6, JMeter, 반복 요청 스크립트가 후보로 나왔고 아직 하나로 정하지 않았다.
    - 부하 시나리오와 성공 기준은 이 ADR이나 인프라 실행안이 아니라 도메인 측정 가이드가 소유한다. 실시간 경로는 채팅 측정 가이드를 새로 만들어 맡기고, 인프라 쪽은 그 시나리오 실행 시의 역할별 자원 지표만 기록한다.
    - P1 부하·장애 검증에서 App2 장애, PostgreSQL·Redis 재시작, DB 백업 복원을 확인하고 역할별 CPU·CPU credit·메모리·디스크·연결 수·지연·실패 증상을 같은 시간축으로 기록한다.
    - 최초 병목을 확인한 뒤 해당 역할만 한 단계씩 확장하고 같은 시나리오를 재실행한다. 다음 인스턴스 유형은 측정 결과로 정한다.
    - 실제 계정·리전·가동 시간으로 비용 한도와 환경 유지 기간을 확정한다.

## 보류 및 재검토

- 지금 하지 않는 것: PostgreSQL streaming replica·자동 장애 조치, Redis Sentinel·Cluster, Multi-Region, ECS·EKS, Kafka, 클라우드 간 동일 상태 파일 재사용
- 보류 이유: P1 목표는 교차 인스턴스 동작, 재현 가능한 배포와 초기 병목 확인이며 데이터 서비스의 무중단 운영이 아니다.
- 다시 검토할 조건:
    - P1 이후 실제 사용자를 위한 상시 서비스로 운영할 때
    - PostgreSQL·Redis 장애 허용 시간이 수동 복구 시간보다 짧아질 때
    - 패치·백업·복구 운영 부담이 관리형 서비스 비용보다 커질 때
    - 실제 비용 계산에서 자체 운영 EC2 구성이 목표 한도를 넘을 때
    - AWS 외 클라우드 배포가 실제 일정과 담당자를 가진 작업으로 확정될 때

## 참고 자료

- [P1 AWS 저비용 4 EC2 인프라 실행안](../../guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md)
- [Terraform S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3)
- [Terraform 민감 데이터 관리](https://developer.hashicorp.com/terraform/language/manage-sensitive-data)
- [Terraform provider별 resource 구성](https://developer.hashicorp.com/terraform/language/resources/configure)
- [Amazon EC2 user data](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/user-data.html)
- [Amazon EBS volume 사용](https://docs.aws.amazon.com/ebs/latest/userguide/ebs-using-volumes.html)
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [Ansible `amazon.aws.aws_ssm` connection](https://docs.ansible.com/projects/ansible/latest/collections/amazon/aws/aws_ssm_connection.html)

## 검증

- 상태: 미검증
- 근거:
    - 구현: 인프라 저장소의 [커밋 `126ee87`](https://github.com/bamsongi-club/albam-mate-infra/commit/126ee878aa30dbb2e532886f9276f056ff66d7cb)에 고정 EC2 4대, App1 Elastic IP, public subnet, 보안 그룹, 별도 데이터 EBS, private DNS, SSM inventory와 Ansible 호스트 준비를 구현했다.
    - 테스트:
        - 2026-08-06에 bootstrap과 P1 root module의 `terraform validate`, 전체 `terraform fmt -check`를 통과했다.
        - [2026-08-11 인증·알림 AWS 용량 측정](../../measurements/k6/jiho/auth-notification-capacity-2026-08-11.md)은 서울 리전의 임시 스택에 App 2대·PostgreSQL 1대·Redis 1대를 모두 `t4g.micro`로 배포하고 외부 web 진입점으로 인증·알림 계약과 fan-out을 실행했다. App은 CPU credit `standard`, JVM `-Xmx256m`, container 512MiB로 고정했고 image digest·release SHA·실효 설정을 Run별로 확인했다.
        - 같은 측정의 알림 0.5×·1× 혼합 부하에서 App container memory 95.94~99.55%, host available memory 32.2~45.4MiB와 Java cgroup OOM을 관측했다. PostgreSQL CPU는 최대 12.20%, Redis는 5.43%였고 waiting lock과 최종 처리 가능 backlog는 0이어서, 네 역할을 같은 초기 사양에서 시작해 먼저 드러나는 병목을 측정한다는 경계에서 App memory를 최초 직접 병목으로 확인했다. 두 혼합 Run은 측정 완결성 조건을 충족하지 못한 `INVALID`이므로 P1 용량 경계로 읽지 않는다.
- 미검증:
    - 2026-08-11 측정은 실제 AWS 스택 생성과 실행을 확인했지만, 해당 인프라 worktree가 dirty 상태였으므로 정확한 runner 변경은 Run별 `infra-dirty.patch` 지문에 의존한다. 계정·리전·fixture·image digest를 다른 팀원 AWS 계정에서 독립 재현하지는 않았다.
    - Ansible `--syntax-check`·`--check`와 실제 SSM 연결·playbook 실행을 확인하지 않았다.
    - 자체 운영 PostgreSQL TLS·백업 복구와 Redis AOF 복구를 실행하지 않았다.
    - 실제 AWS 환경의 두 Spring 인스턴스에서 교차 세션·WebSocket·Scheduler, App2 장애 시 실패 대상 제외와 기존 연결 영향을 확인하지 않았다. relay는 정상 fan-out 범위만 실제 AWS에서 검증했고 지속 혼합 부하 용량 경계는 미확정이다.
    - 이번 Run의 AWS 계정·서울 리전은 고정했지만 재현 대상 계정·EBS 용량, 역할별 확장 기준과 전체 비용을 확정하지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
