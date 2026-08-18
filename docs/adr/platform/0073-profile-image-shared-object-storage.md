# ADR-0073: 프로필 이미지 저장소를 다중 인스턴스 공용 객체 스토리지(S3)로 전환

- 상태: 제안됨
- 작성일: 2026-08-18
- 결정일: 미정
- 관련: [ADR-0038 다중 인스턴스 공용 세션과 스케줄 실행](0038-multi-instance-session-and-scheduler-coordination.md), [ADR-0051 P1 저비용 4 EC2 자체 운영 인프라](0051-p1-self-managed-aws-infrastructure.md), [GitHub Issue #727](https://github.com/bamsongi-club/albam-mate/issues/727)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

`ProfileImageStorage`(`LocalProfileImageStorage` 구현체)는 프로필 이미지를 애플리케이션이 실행되는 EC2 호스트의 로컬 디스크(`app.profile-image.upload-dir`, 기본 `./uploads/profile`)에 저장하고, `WebMvcConfig`가 그 경로를 `/uploads/profile/**` 정적 리소스로 서빙한다.

P1 인프라([ADR-0051](0051-p1-self-managed-aws-infrastructure.md))는 App1·App2 두 EC2에서 각각 Spring 컨테이너를 실행하고, `frontend/nginx.production.conf`의 `upstream spring_backend`가 `/api/`와 `/uploads/` 요청을 **모두 두 인스턴스에 라운드로빈으로 분산**한다. App1·App2는 각자 별도의 Docker named volume(`profile-images`)을 쓰므로, 같은 이름이라도 실제로는 서로 다른 호스트의 분리된 디스크다.

결과: 사용자가 프로필 이미지를 업로드하면 두 인스턴스 중 하나가 요청을 처리해 그 호스트의 로컬 디스크에만 파일을 저장하고 DB에는 URL만 기록된다. 이후 같은 URL로 이미지를 조회하는 요청이 nginx 라운드로빈에 의해 **다른** 인스턴스로 가면 그 호스트에는 파일이 없어 404가 된다.

이 문제는 [GitHub Issue #727](https://github.com/bamsongi-club/albam-mate/issues/727) 조사에서 발견됐고, 로컬 환경에서 같은 메커니즘(라운드로빈 뒤 두 백엔드 중 한쪽에만 파일이 있는 상태)을 재현해 **정확히 50% 확률로 404**가 발생함을 확인했다. `compose.production.yml`의 기존 주석에도 이미 알려진 한계로 기록돼 있었다("실제 scale-out 검증 전에는 이 스토리지를 공용 객체 스토리지(S3 등)로 옮겨야 한다").

로컬 개발 환경(`compose.local.yml`)은 spring-1·spring-2가 **같은** Docker named volume을 공유하도록 구성돼 있어 이 문제가 재현되지 않는다(직접 파일 쓰기·읽기 테스트로 확인). 따라서 이번 결정은 운영 배포에만 적용되면 충분하고, 로컬 개발 워크플로를 바꿀 필요는 없다.

**판단 기준**
1. App1·App2 어느 인스턴스가 업로드를 처리했든 이후 조회가 인스턴스와 무관하게 항상 성공해야 한다.
2. 로컬 개발자 전원에게 AWS 자격증명을 요구하지 않아야 한다.
3. P1 인프라([ADR-0051](0051-p1-self-managed-aws-infrastructure.md))의 저비용 4-EC2 자체 운영 기조에서 크게 벗어나지 않아야 한다.
4. 기존 `ProfileImageStorage` 인터페이스 경계를 유지해 HTTP 계층·서비스 계층 변경을 최소화해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| S3(공용 객체 스토리지)로 전환, `production` 프로파일에서만 활성화 | 인스턴스 수·재배포와 무관하게 항상 같은 파일을 조회. 관리형 서비스라 자체 운영 부담이 없음. `ProfileImageStorage` 인터페이스만 구현체 추가로 대응 가능 | 새 AWS 리소스(버킷·IAM) 필요, 로컬 개발은 영향 없지만 운영 배포·자격증명 관리가 늘어남 | 선택 |
| nginx `/uploads/` 경로만 특정 인스턴스로 고정 라우팅(sticky) | 코드 변경 없이 nginx 설정만으로 즉시 적용 가능 | 그 인스턴스가 재기동·교체되면 다시 깨짐. App1 장애 시 App2로 자동 전환되는 기존 장애 대응(ADR-0051)과 충돌. 근본 해결이 아니라 임시방편 | 제외 |
| App1·App2 간 파일 동기화(rsync 등 주기 동기화) | 별도 AWS 리소스 불필요 | 동기화 지연 구간에서 여전히 실패 가능. 두 호스트 간 새 동기화 인프라·모니터링이 필요해 운영 복잡도가 오히려 늘어남 | 제외 |
| 공용 EFS/NFS 마운트로 전환 | 파일시스템 API를 그대로 유지해 코드 변경이 가장 적음 | P1은 `t4g.micro` 저비용 자체 운영 기조([ADR-0051](0051-p1-self-managed-aws-infrastructure.md))인데 EFS는 추가 비용·NFS 마운트 운영 부담이 S3보다 큼. 이미지 파일처럼 정적 자산에는 객체 스토리지가 더 적합 | 제외 |

## 결정

프로필 이미지 저장소를 **운영(`production` 프로파일)에서만** S3(또는 호환 객체 스토리지)로 전환한다.

- `ProfileImageStorage` 인터페이스는 그대로 유지하고, `S3ProfileImageStorage` 구현체를 추가해 `production` 프로파일에서 빈으로 등록한다. `local`·`test` 프로파일은 기존 `LocalProfileImageStorage`를 그대로 쓴다(로컬은 volume 공유로 이미 문제가 없으므로 변경 불필요).
- 조회 URL은 S3 객체에 직접 접근하는 URL(또는 CloudFront 등 CDN 경유)로 변경하고, `WebMvcConfig`의 `/uploads/profile/**` 정적 리소스 서빙은 `production` 프로파일에서 더 이상 쓰지 않는다.
- AWS 자격증명은 정적 액세스 키를 환경 변수로 주입하지 않고, EC2 인스턴스에 부여한 IAM 역할로 접근한다.
- 버킷·IAM 정책 등 실제 AWS 리소스 생성은 별도 인프라 저장소의 Terraform 모듈로 관리한다([ADR-0051](0051-p1-self-managed-aws-infrastructure.md)과 동일한 관리 방식).

## 결과

- 얻는 것:
    - App1·App2 어느 인스턴스가 업로드를 처리했든 이후 조회가 항상 성공한다(#727 근본 해결).
    - 인스턴스 추가·교체 시에도 이미지 유실이나 조회 실패가 발생하지 않는다.
- 감수할 비용·위험:
    - 새 AWS 리소스(S3 버킷, IAM 정책)와 그에 따른 비용·권한 관리가 추가된다.
    - 운영 배포 파이프라인에 S3 접근 설정(버킷명·리전 등 환경 변수, IAM 역할 연결)이 새로 필요하다.
    - 기존에 App1·App2 로컬 디스크에 이미 저장된 프로필 이미지가 있다면 S3로 마이그레이션하거나, 마이그레이션 전까지 해당 사용자의 이미지가 깨진 상태로 남을 수 있다.
- 후속 작업:
    - `S3ProfileImageStorage` 구현체와 `production` 프로파일 빈 등록, 관련 단위·통합 테스트를 추가한다.
    - 별도 인프라 저장소에 S3 버킷·IAM 정책 Terraform 모듈을 추가한다.
    - `compose.production.yml`의 `profile-images` local volume 마운트와 `nginx.production.conf`의 `/uploads/` location을 정리하거나, 과도기 동안의 공존 방식을 정한다.
    - 기존 운영 데이터가 있다면 마이그레이션 절차를 `docs/guides/`에 기록한다.
    - `docs/API.md`의 프로필 이미지 URL 응답 형식이 바뀌면 계약 문서를 갱신한다.

## 보류 및 재검토

- 지금 하지 않는 것: 로컬 개발 환경의 저장 방식 변경(volume 공유로 이미 문제 없음), CDN(CloudFront) 도입 여부 결정.
- 보류 이유: 로컬은 재현되지 않는 문제라 변경 범위에 넣을 이유가 없고, CDN은 이번 결정의 핵심(다중 인스턴스 조회 정합성)과 분리해서 별도로 판단할 수 있다.
- 다시 검토할 조건: 프로필 이미지 트래픽·용량이 커져 CDN 도입이 필요하다고 판단되거나, S3 대신 다른 관리형 스토리지로 인프라 전체가 이전될 때.

## 참고 자료

- 이 문서의 맥락·대안으로 갈음.

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - `S3ProfileImageStorage` 구현과 `production` 프로파일 빈 등록이 실제로 이뤄졌는지
    - 운영 환경에서 App1·App2 어느 쪽으로 업로드하든 조회가 항상 성공하는지
    - 기존 로컬 디스크에 저장된 운영 데이터의 마이그레이션 여부

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
