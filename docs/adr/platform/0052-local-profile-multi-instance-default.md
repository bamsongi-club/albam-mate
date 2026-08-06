# ADR-0052: `local` 프로필을 다중 인스턴스 기본 검증 환경으로 통합

- 상태: 승인됨
- 작성일: 2026-08-07
- 결정일: 2026-08-07
- 관련: [P1 실행 환경과 공용 인프라](../../P1-spec.md#실행-환경과-공용-인프라), [아키텍처의 다중 인스턴스 실행](../../ARCHITECTURE.md#다중-인스턴스-실행), [FND-10 실시간 전달과 재연결 기반](../../p1/foundation.md#fnd-10-실시간-전달과-재연결-기반), [ADR-0038 공용 세션·스케줄 조정](0038-multi-instance-session-and-scheduler-coordination.md), [ADR-0045 채팅방 스키마와 기존 ROOM 초기화 경계](../chat/0045-chat-room-schema-and-backfill-boundary.md), [GitHub Issue #471](https://github.com/bamsongi-club/albam-mate/issues/471)
- 대체 대상: [ADR-0038](0038-multi-instance-session-and-scheduler-coordination.md) 중 실행 프로필·로컬 검증 경계
- 후속 ADR: 없음

## 맥락

P1의 교차 인스턴스 세션·WebSocket·Redis Pub/Sub·전송 제한·ShedLock 검증은 이미 `local-multi` 구성으로 정의되어 있다. 반면 `local` 프로필은 단일 Spring 인스턴스와 인메모리 저장소를 사용해 빠른 확인만 지원한다. 실제 기본 실행 명칭과 P1 검증 명칭이 달라 개발·CI·문서가 두 경로를 유지하고, 운영과 같은 Redis 의존 경계를 로컬 기본 실행에서 확인할 수 없다.

상시 데모를 먼저 운영하더라도 로컬에서 검증하는 코드는 `production`과 같은 Redis 기반 세션·전송 제한·Pub/Sub 구현을 사용해야 한다. 이 결정은 실행 프로필과 로컬 검증 경계만 다룬다. AWS 토폴로지, 운영 PostgreSQL·Redis 제품, Terraform, CD와 실제 production 배포는 [GitHub Issue #456](https://github.com/bamsongi-club/albam-mate/issues/456) 및 후속 OPS 범위로 남긴다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 단일 `local`을 기본으로 두고 `local-multi`를 선택 실행 | 빠른 단일 서버 개발을 유지할 수 있다. | 기본 CI·데모가 다중 인스턴스 계약을 검증하지 못하고 프로필·Compose·환경변수 경계가 이중화된다. | 제외 |
| `local-multi`를 그대로 유지하고 별도 명칭으로 문서화 | 기존 실행 구성과 키 이름을 그대로 쓸 수 있다. | 기본 프로필 `local`과 실제 검증 환경이 계속 어긋나며 배포 전 실행 명령이 복잡해진다. | 제외 |
| `local` 프로필과 Compose를 다중 인스턴스 구성으로 통합 | 기본 실행·CI·문서가 P1 필수 검증 환경과 일치하고 production과 같은 코드 경로를 확인한다. | 단일 서버 인메모리 실행을 지원 범위에서 제거하고 로컬 Redis·두 애플리케이션을 항상 준비해야 한다. | 선택 |

## 결정

지원하는 로컬 실행 환경은 `local` 하나이며, `local`은 로컬 프록시, Spring 애플리케이션 두 대, 공용 PostgreSQL·Redis로 구성한다. 기존 단일 서버 `local` 실행 경로와 `local-multi` 프로필·Compose·환경변수 명칭은 제거하고, 다중 인스턴스 구성의 내용을 `local` 명칭으로 통합한다.

`test`와 `postgresTest`는 애플리케이션 로컬 실행이 아닌 테스트 격리 경계로 유지한다. `local`은 PostgreSQL과 Redis를 필수로 사용하며 세션·채팅 Pub/Sub·사용자·방 단위 전송 제한에 같은 Redis를 사용하되 다음 namespace를 분리한다.

- 세션: `albam-mate:local:session`, `albam-mate:production:session`
- 전송 제한: `albam-mate:local:ratelimit`, `albam-mate:production:ratelimit`
- 채팅 이벤트: `albam-mate:{env}:chat:events`

`local`과 `production`은 Spring Session Redis, Redis 전송 제한과 Redis Pub/Sub 구현을 공유한다. Redis가 필요한 경로에서 인메모리 구현으로 fallback하지 않으며 세션·전송 제한 상태를 확인할 수 없으면 저장 전에 계약된 `503 SERVICE_UNAVAILABLE`으로 실패한다. PostgreSQL 커밋 뒤 Pub/Sub 실패는 저장 결과를 롤백하지 않고 재연결·catch-up 경계로 복구한다.

`local` 설정은 `db/local` Flyway callback을 유지해 데모용 seed를 준비한다. `production`은 schema-only migration 경계를 유지하며 local seed callback을 실행하지 않는다. 모든 local 애플리케이션 인스턴스는 스케줄을 등록하고 PostgreSQL ShedLock을 얻은 하나만 ROOM 상태 보정·채팅 만료 삭제를 실행한다. 공용 세션·Pub/Sub·전송 제한 기술 선택과 ShedLock 의미는 ADR-0038의 나머지 결정으로 유지한다.

기존 `local-multi` Redis key와 channel은 production과 공유하지 않으며, 로컬 데이터는 개발·데모 데이터로 간주해 namespace 이름 변경을 위한 운영 데이터 마이그레이션은 수행하지 않는다.

## 결과

- 얻는 것:
    - 기본 `local` 실행·CI·문서·테스트가 P1 다중 인스턴스 검증 환경과 일치한다.
    - 로컬과 production이 같은 Redis 기반 세션·전송 제한·Pub/Sub 코드 경로를 사용한다.
    - local과 production의 Redis 상태가 namespace로 분리되고 Redis 장애 fallback 금지 경계를 로컬에서 확인한다.
- 감수할 비용·위험:
    - 로컬 실행에 Docker PostgreSQL·Redis와 Spring 두 대가 필요하다.
    - 기존 단일 서버 `local` 실행과 local-multi key의 호환성을 유지하지 않는다.
    - 실제 AWS ALB·ASG·운영 Redis의 가용성·보안·비용은 여전히 검증 대상이다.
- 후속 작업:
    - 프로필·Compose·환경변수·namespace·테스트를 `local` 기준으로 통합한다.
    - CI와 실행 가이드를 기본 local Compose에 맞춘다.
    - 상시 데모를 실제 운영 검증으로 승격할 때 AWS·CD 계약을 별도 이슈와 ADR로 승인한다.

## 보류 및 재검토

- 지금 하지 않는 것: AWS 인프라 생성·변경, production 토폴로지 변경, Terraform·CD 구축, Redis HA·TLS·백업과 부하·가용성 기준 확정
- 보류 이유: 이 ADR은 애플리케이션 실행 프로필과 로컬 다중 인스턴스 검증 경계만 통합하며, 운영 인프라 결정은 #456 및 후속 OPS가 소유한다.
- 다시 검토할 조건: 실제 AWS 배포·상시 데모 운영에서 장애 격리, 비용, 연결 draining, scale-out 또는 namespace migration 요구가 확인되고 별도 근거가 승인될 때

## 참고 자료

- [ADR 작성·대체 규칙](../README.md)
- [ADR-0038 다중 인스턴스의 공용 세션과 스케줄 실행 조정](0038-multi-instance-session-and-scheduler-coordination.md)
- [GitHub Issue #471](https://github.com/bamsongi-club/albam-mate/issues/471)
- [GitHub Issue #456](https://github.com/bamsongi-club/albam-mate/issues/456)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - local Compose의 proxy·Spring 두 대·PostgreSQL·Redis healthy 기동과 loopback 노출
    - local Redis Session·rate limit·Pub/Sub namespace와 production 분리
    - 교차 인스턴스 HTTP/WebSocket 전달·재연결·ShedLock 단일 소유
    - local seed callback과 production schema-only migration
    - CI·문서 링크·전체 승인 T1~T7 실행

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
