# ADR-0032: HTTP로 저장하고 방별 WebSocket으로 실시간 수신

- 상태: 승인됨
- 작성일: 2026-07-31
- 결정일: 2026-08-01
- 관련: [P1 방 채팅 명세](../../p1/chatting.md), [채팅 API](../../API.md#채팅-공통-계약), [아키텍처](../../ARCHITECTURE.md#채팅-흐름), [ADR-0003 서버 세션](../auth/0003-p0-server-session-spring-security.md), [ADR-0020 API 인가 정책](../auth/0020-api-endpoint-authorization-policy-registry.md), [ADR-0031 커서 조회](0031-chat-history-cursor-pagination.md), [ADR-0033 메시지 정본과 전달](0033-postgresql-source-after-commit-delivery.md), [ADR-0038 공용 세션·스케줄 조정](../platform/0038-multi-instance-session-and-scheduler-coordination.md), [ADR-0051 P1 AWS 토폴로지](../platform/0051-p1-self-managed-aws-infrastructure.md)
- 대체 대상: 없음
- 후속 ADR: 없음

> 2026-08-06 승인된 ADR-0051이 이 문서의 후속 작업에 적힌 ALB·ASG 경로를 App1 Nginx·고정 EC2 경로로 바꿨다. HTTP 저장·WebSocket 수신 결정과 재연결 계약은 그대로 유효하다.

## 맥락

채팅 사용자는 메시지 저장 성공을 명확히 확인하면서 다른 관계자의 새 메시지를 지연 없이 받아야 한다. 기존 애플리케이션은 `JSESSIONID` 세션, CSRF와 HTTP 오류 계약을 사용한다. [ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md)의 다중 인스턴스 환경에서는 HTTP 저장 요청과 WebSocket 연결이 서로 다른 인스턴스에 도달할 수 있다.

메시지 전송까지 WebSocket으로 옮기면 인증 이후의 명령 오류·멱등성·재시도 계약을 별도 프로토콜로 다시 만들어야 한다. 반대로 SSE는 서버 발신만 필요할 때 단순하지만, 팀은 채팅 전용 연결을 WebSocket으로 통일하고 이후 양방향 기능 확장 가능성을 남기기로 했다.

판단 기준은 다음과 같다.

- 기존 HTTP 인증·CSRF·검증·멱등성 계약을 재사용할 것
- 방 단위 권한 상실 시 연결을 분리하고 종료하기 쉬울 것
- 연결 단절 중 누락된 메시지를 PostgreSQL 이력으로 복구할 것
- 인스턴스가 교체되거나 재연결 대상이 바뀌어도 공용 세션과 메시지 이력으로 복구할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| HTTP 전송과 SSE 수신 | 서버 발신 전용 구현이 단순하고 브라우저 자동 재연결을 활용할 수 있다. | 채팅 실시간 경로가 향후 양방향 기능과 다른 기술로 남고, 팀이 선택한 WebSocket 기준과 맞지 않는다. | 제외 |
| WebSocket 송수신 | 하나의 연결에서 명령과 이벤트를 모두 처리할 수 있다. | HTTP의 CSRF·오류·멱등성 계약을 별도 프레임 프로토콜로 다시 정의해야 한다. | 제외 |
| HTTP 전송과 방별 WebSocket 수신 | 저장 명령은 기존 HTTP 계약을 유지하고, 실시간 이벤트만 방 단위 연결로 격리한다. | HTTP 응답과 WebSocket 이벤트 중복을 클라이언트가 `messageId`로 제거해야 한다. | 선택 |
| 공용 WebSocket 한 개와 다중 방 구독 | 여러 방의 미확인 메시지 기능으로 확장하기 쉽다. | 구독·해제·방별 권한 상태와 라우팅 프로토콜이 P1 화면 흐름보다 복잡하다. | 제외 |

공용 세션 저장소, 실행 프로필과 fallback 정책은 [ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md)이 소유한다. 이 ADR은 해당 인증 상태를 HTTP와 WebSocket에서 어떻게 이어 쓰는지만 결정한다.

## 결정

메시지는 HTTP POST로 저장하고, 커밋된 새 메시지는 방별 WebSocket으로 실시간 수신한다.

- 전송은 `POST /api/rooms/{roomId}/chat/messages`를 사용한다.
- 실시간 연결은 `/api/rooms/{roomId}/chat/ws`에서 방마다 하나씩 연다.
- handshake는 기존 `JSESSIONID` 세션을 사용하고 허용된 `Origin`만 받는다. `local-multi`와 `prod`에서는 [ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md)의 Spring Session Redis 인증 상태를 사용하며, 별도 JWT나 WebSocket 전용 토큰을 만들지 않는다.
- handshake 시 방 주최자 또는 현재 `ACTIVE` 참가자인지와 방 상태가 `RECRUITING` 또는 `CLOSED`인지 검증한다.
- 참가 취소, 세션 만료 또는 방의 `CANCELED`·`FINISHED` 전환으로 접근 권한을 잃으면 서버가 연결을 종료한다.
- 클라이언트는 마지막으로 확인한 `messageId`를 `afterMessageId`로 전달한다.
- 서버는 연결을 복구 상태로 등록하고, DB의 누락분을 ID 오름차순으로 전송하는 동안 새 실시간 이벤트를 임시 버퍼에 보관한다. 누락분 전송 뒤 버퍼를 ID 순으로 중복 제거해 전달하고 실시간 상태로 전환한다.
- 서버 발신 이벤트는 `MESSAGE_CREATED`만 지원한다. 클라이언트의 애플리케이션 메시지 프레임 전송은 P1에서 지원하지 않는다.
- 각 인스턴스는 자신에게 연결된 WebSocket만 메모리에서 관리한다. 커밋된 메시지의 인스턴스 간 fan-out은 [ADR-0033](0033-postgresql-source-after-commit-delivery.md)의 Redis 신호와 PostgreSQL catch-up으로 처리한다.

## 결과

- 얻는 것: 메시지 명령은 기존 HTTP 보안·오류 계약을 유지하면서 연결된 인스턴스와 무관하게 방별 실시간 이벤트를 받을 수 있다. 인스턴스 교체와 재연결 누락은 공용 세션, PostgreSQL과 `messageId`로 복구한다.
- 감수할 비용·위험: HTTP 성공 응답과 WebSocket 이벤트가 중복될 수 있고, 프로세스 재시작·ASG 교체 때 연결을 다시 열어야 한다. 공용 세션과 배포 인프라 비용은 ADR-0038을 따른다.
- 후속 작업: WebSocket endpoint, 공용 세션 handshake 인증·Origin 검사, 권한 상실 연결 종료, 복구 중 live-gap 방지와 로컬 두 인스턴스의 중복 제거·재연결 테스트를 구현한다. 실제 ALB·ASG 경로는 후속 OPS에서 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: WebSocket 명령 전송, STOMP broker, 공용 다중 방 구독, WebSocket 전용 JWT
- 보류 이유: P1은 한 채팅방 화면과 기존 HTTP 명령 계약을 유지하며, 서버 발신 실시간 이벤트만 필요하다.
- 다시 검토할 조건: 입력 중 표시·접속 상태처럼 클라이언트 발신 실시간 신호가 필요하거나, 한 사용자가 여러 방을 동시에 구독해야 할 때

## 참고 자료

- [Spring WebSocket 인증](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication.html)
- [RFC 6455 WebSocket Protocol](https://www.rfc-editor.org/rfc/rfc6455)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - WebSocket endpoint, Spring Session Redis와 인증·인가·재연결 구현이 없다.
    - 로컬 두 인스턴스 교차 전달을 확인하지 않았다.
    - ADR-0051의 App1 Nginx 경로에서 WebSocket Upgrade, App2 실패 제외와 연결 종료 동작을 검증해야 한다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
