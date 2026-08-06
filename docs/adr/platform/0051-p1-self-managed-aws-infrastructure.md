# ADR-0051: P1 자체 운영 PostgreSQL·Redis 도입과 초기 용량 검증 제안

- 상태: 제안됨
- 작성일: 2026-08-06
- 결정일: 미정
- 관련: [P1 실행 환경과 공용 인프라](../../P1-spec.md#실행-환경과-공용-인프라), [다중 인스턴스 실행](../../ARCHITECTURE.md#다중-인스턴스-실행), [P1 AWS 실행 설계](../../guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md), [ADR-0021 P0 AWS EC2와 RDS 배포 기준선](0021-p0-aws-ec2-rds-deployment-baseline.md), [ADR-0038 다중 인스턴스 공용 세션과 스케줄 조정](0038-multi-instance-session-and-scheduler-coordination.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

현재 승인된 [ADR-0021](0021-p0-aws-ec2-rds-deployment-baseline.md)은 P0 운영 데이터를 비공개 RDS PostgreSQL에 두고 애플리케이션 EC2와 데이터베이스 수명을 분리한다.

P1 정본과 [ADR-0038](0038-multi-instance-session-and-scheduler-coordination.md)은 ALB·ASG 애플리케이션 인스턴스가 공용 RDS PostgreSQL과 Redis를 사용하는 목표 운영 구성을 기록한다.

이 목표는 아직 실제 운영 배포나 측정이 완료됐다는 뜻이 아니다.

2026-08-06 논의에서는 다음 내용이 직접 제시됐다.

- RDS·ElastiCache 대신 EC2의 Docker 컨테이너로 PostgreSQL과 Redis를 운영한다.
- 초기에는 Spring EC2 두 대, PostgreSQL EC2 한 대, Redis EC2 한 대를 기본으로 하고 단순 시연에서는 Spring 한 대도 가능하다는 안이 제시됐다.
- Terraform으로 같은 구성을 팀원 계정에 다시 만들고, 인프라 코드는 애플리케이션과 다른 저장소에서 관리한다.
- 이후 P1 기준선은 Spring 두 대를 유지한 EC2 네 대 구성으로 구체화됐다. 네 EC2는 모두 `t4g.micro`로 시작하고, P1 부하와 장애 시나리오에서 역할별 병목을 측정한 뒤 병목이 확인된 자원만 단계적으로 확장한다.

ALB·ASG는 위 논의에서 직접 제시된 내용이 아니라 현재 P1 목표 운영 구성에서 이어받았다. 비공개 서브넷, NAT Gateway, 별도 EBS, SSM, 백업과 관측 구성은 운영 안전 요구를 만족시키기 위해 이 ADR에서 추가한 설계 제안이다.

P1에서 Spring 두 대의 교차 인스턴스 세션·채팅·스케줄 동작을 검증하려면 이 보강 설계와 현재 구현 사이의 차이도 별도 작업으로 해소해야 한다.

관리형 데이터 서비스를 자체 운영 EC2로 바꾸면 단순한 엔드포인트 변경을 넘어 백업·복구·패치·TLS·디스크·장애 대응 책임이 팀으로 이동한다.

Terraform을 사용해도 AWS 리소스를 GCP 리소스로 자동 변환할 수 없으므로 공급자별 모듈과 상태 파일이 필요하다. 별도 인프라 저장소도 상태 파일·실행 계획·비밀값을 보호하지 않으면 보안 경계가 되지 않는다.

판단 기준은 다음과 같다.

- P1 검증 환경을 다른 팀원 AWS 계정에서도 재현할 수 있을 것
- Spring 두 대가 공용 PostgreSQL·Redis를 사용하는 현재 P1 애플리케이션 계약을 유지할 것
- Spring 두 대, PostgreSQL 한 대, Redis 한 대를 모두 `t4g.micro`로 시작하고 역할별 병목 근거가 있을 때만 확장할 것
- 운영 데이터를 컨테이너와 EC2 루트 볼륨의 수명에서 분리할 것
- 공개 SSH, 장기 Access Key와 저장소의 평문 비밀값을 사용하지 않을 것
- 단일 PostgreSQL·Redis의 장애 위험을 고가용성 운영 구성으로 과장하지 않을 것
- P1 검증 종료 뒤 환경과 과금 리소스를 확인 가능하게 제거할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| ALB·ASG + RDS PostgreSQL + ElastiCache | 자동 백업·복구와 관리형 장애 대응을 활용하고 데이터 서비스 운영 부담이 작다. | 계정·리전·사양에 따른 관리형 서비스 비용이 발생하고 자체 운영 학습 범위가 줄어든다. | 기존 승인 기준 유지 |
| ALB + `t4g.micro` Spring EC2 2대 + `t4g.micro` PostgreSQL EC2 1대 + `t4g.micro` Redis EC2 1대 | P1 교차 인스턴스 흐름을 유지하면서 Docker·Terraform으로 계정별 재현 범위를 넓히고 역할별 병목을 같은 초기 조건에서 관찰한다. | PostgreSQL·Redis가 단일 장애 지점이며 작은 메모리와 버스트 CPU의 한계가 서비스별로 다르게 나타날 수 있다. 백업·복구·패치·TLS·관측 책임도 팀이 맡는다. | 제안 선택 |
| Spring·PostgreSQL·Redis를 EC2 한 대에서 함께 실행 | 구성과 비용이 가장 작고 로컬 Compose와 유사하다. | EC2 한 대 장애가 애플리케이션·DB·Redis 전체 장애가 되고 P1 교차 인스턴스 검증 목표를 충족하지 못한다. | 제외 |
| ECS·EKS와 관리형 데이터 서비스 | 순차 배포와 오케스트레이션 확장 경로가 넓다. | P1 검증에 필요한 범위보다 IAM·네트워크·스케줄러 운영이 복잡하다. | 제외 |
| 클라우드마다 수동으로 VM과 네트워크 생성 | 처음 한 번은 Terraform 학습 없이 만들 수 있다. | 계정 이동 때 설정 누락과 구성 차이를 검증하기 어렵고 삭제 대상과 비용 소유권이 불명확해진다. | 제외 |

관리형 서비스의 구체적인 비용 배수는 선택 근거로 확정하지 않는다. 실제 계정·리전·인스턴스 유형·스토리지·가동 시간과 ALB·NAT·IPv4·S3·CloudWatch를 포함한 조건으로 다시 계산한다.

## 결정

이 ADR이 승인되면 P1 검증 환경은 다음과 같이 구성한다.

1. Internet-facing ALB가 HTTPS와 WebSocket Upgrade를 처리하고 비공개 서브넷의 Spring EC2 두 대에 요청을 분산한다.
2. Spring은 동일한 Git SHA 이미지를 사용하는 Launch Template와 ASG `desired=2`로 실행한다. 두 인스턴스 모두 `t4g.micro`로 시작하고 JVM 최대 heap은 `-Xmx256m`를 초기값으로 둔다. 컨테이너 메모리 한도와 OOM 동작은 P1 부하에서 함께 검증한다.
3. PostgreSQL `18.4`는 `t4g.micro` EC2 한 대의 Docker 컨테이너로 실행하고 암호화한 별도 EBS에 데이터를 저장한다.
4. Redis `8.4-alpine`은 `t4g.micro` EC2 한 대의 Docker 컨테이너로 실행하고 AOF와 암호화한 별도 EBS를 사용한다. Redis는 Spring Session, 전송 제한과 Pub/Sub 신호만 소유하며 업무 데이터 정본은 PostgreSQL로 유지한다.
5. PostgreSQL과 Redis는 공인 IP와 공개 인바운드를 갖지 않는다. Spring 보안 그룹에서 각 서비스 포트로만 접근하고 운영 접속은 SSM Session Manager를 사용한다.
6. 애플리케이션은 생성된 private IP를 직접 저장하지 않고 private DNS 이름으로 PostgreSQL·Redis에 연결한다.
7. Terraform은 AWS용 모듈과 계정별 원격 상태 파일로 네트워크, ALB, EC2, EBS, IAM, DNS, 백업과 관측 리소스를 관리한다. 애플리케이션 이미지 빌드, Flyway 스키마와 테스트 데이터는 관리하지 않는다. `t4g`의 ARM64 환경에서 실행할 Docker 이미지는 애플리케이션 빌드 파이프라인이 `linux/arm64`를 지원해야 한다.
8. 각 계정의 Terraform 상태 파일은 versioning·encryption·public access block과 lockfile을 적용한 S3 backend에 분리한다. 실제 비밀값, 상태 파일, 실행 계획과 개인 tfvars는 Git에 저장하지 않는다.
9. AWS에서 다른 클라우드로 이동할 때에는 provider별 모듈과 상태 파일을 새로 만들고 공통 Docker 이미지와 역할별 입력·출력 계약만 재사용한다.
10. 이 구성을 P1의 최종 용량이나 고가용성 운영 구성으로 부르지 않는다. 역할별 CPU·메모리·디스크·연결 지표와 실패 증상을 기록하고, 병목이 확인된 역할의 인스턴스 유형·개수·EBS만 단계적으로 조정한다. Spring 한 대 장애는 ALB·ASG로 복구할 수 있지만 단일 PostgreSQL·Redis 장애는 수동 복구 대상이다.

이 제안이 승인되기 전까지 [ADR-0021](0021-p0-aws-ec2-rds-deployment-baseline.md)과 [ADR-0038](0038-multi-instance-session-and-scheduler-coordination.md)의 상태·대체 관계·결정 본문은 변경하지 않는다.

승인할 때에는 ADR-0021의 현재 운영 배포 범위와 ADR-0038의 운영 리소스 선택 중 어떤 범위를 대체하는지 명시하고 각 ADR 인덱스를 함께 갱신한다.

## 결과

- 얻는 것:
    - P1 검증 환경을 선언형 코드와 같은 검증 순서로 다른 팀원 AWS 계정에 재현할 수 있다.
    - Spring 두 대의 공용 세션, 채팅 fan-out·catch-up, ShedLock과 알림 relay를 실제 네트워크에서 검증할 수 있다.
    - PostgreSQL·Redis 컨테이너와 EC2 루트 볼륨을 교체해도 별도 EBS와 백업으로 복구할 경계를 마련한다.
- 감수할 비용·위험:
    - PostgreSQL·Redis가 단일 장애 지점이며 자동 장애 조치를 제공하지 않는다.
    - OS·Docker·DB·Redis의 보안 업데이트, TLS, 용량, 백업과 복구를 팀이 직접 운영해야 한다.
    - 단일 NAT Gateway와 단일 데이터 노드는 P1 검증 비용을 줄이는 대신 AZ 장애 복원력을 제공하지 않는다.
    - 네 역할을 모두 `t4g.micro`로 시작하므로 Spring JVM 메모리 부족, CPU credit 고갈, PostgreSQL 연결·디스크 병목 또는 Redis 메모리 부족이 먼저 나타날 수 있다.
    - Terraform `apply` 권한과 상태 파일 접근을 잘못 관리하면 인프라 삭제나 비밀 노출로 이어질 수 있다.
- 후속 작업:
    - 별도 인프라 저장소에서 Terraform 모듈, cloud-init과 역할별 Compose를 구현한다.
    - RDS CA와 엔드포인트를 전제로 한 production profile·Compose·환경 예시를 자체 운영 PostgreSQL TLS·private DNS 계약으로 변경한다.
    - ALB 대상 프로토콜, 상태 확인 엔드포인트, Redis 인증·TLS와 Flyway 실행 주체를 현재 구현에 맞게 확정한다.
    - P1 부하·장애 검증에서 Spring 한 대 장애, PostgreSQL·Redis 재시작, DB 백업 복원을 확인한다.
    - 역할별 CPU, CPU credit, 메모리, 디스크, 연결 수, 지연 시간과 실패 증상을 같은 시간축으로 기록한다.
    - 최초 병목을 확인한 뒤 해당 역할만 한 단계씩 확장하고 같은 시나리오를 재실행한다. 다음 인스턴스 유형은 측정 결과를 근거로 정한다.
    - 실제 계정·리전·가동 시간으로 비용 한도와 환경 유지 기간을 확정한다.

## 보류 및 재검토

- 지금 하지 않는 것: PostgreSQL streaming replica·자동 장애 조치, Redis Sentinel·Cluster, Multi-Region, ECS·EKS, Kafka, 클라우드 간 동일 상태 파일 재사용
- 보류 이유: P1 목표는 교차 인스턴스 애플리케이션 동작, 재현 가능한 배포와 초기 병목 확인이며 데이터 서비스의 무중단 운영이 아니다.
- 다시 검토할 조건:
    - P1 이후 실제 사용자를 위한 상시 서비스로 운영할 때
    - PostgreSQL·Redis 장애 허용 시간이 수동 복구 시간보다 짧아질 때
    - 패치·백업·복구 운영 부담이 관리형 서비스 비용보다 커질 때
    - 실제 비용 계산에서 자체 운영 EC2 구성이 목표 한도를 넘을 때
    - AWS 외 클라우드 배포가 실제 일정과 담당자를 가진 작업으로 확정될 때

## 참고 자료

- [P1 AWS 자체 운영 인프라 제안 실행안](../../guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md)
- [Terraform S3 backend](https://developer.hashicorp.com/terraform/language/backend/s3)
- [Terraform 민감 데이터 관리](https://developer.hashicorp.com/terraform/language/manage-sensitive-data)
- [Terraform provider별 resource 구성](https://developer.hashicorp.com/terraform/language/resources/configure)
- [Amazon EC2 user data](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/user-data.html)
- [Amazon EBS volume 사용](https://docs.aws.amazon.com/ebs/latest/userguide/ebs-using-volumes.html)
- [AWS Systems Manager Session Manager](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 팀이 이 제안을 승인하지 않았고 Terraform 구현·`plan`·`apply` 근거가 없다.
    - 자체 운영 PostgreSQL TLS·백업 복구와 Redis AOF 복구를 실행하지 않았다.
    - 실제 AWS 환경에서 두 Spring 인스턴스의 교차 세션·WebSocket·Scheduler·relay 동작을 확인하지 않았다.
    - AWS 계정·리전·EBS 용량, 역할별 확장 기준과 전체 비용을 확정하지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
