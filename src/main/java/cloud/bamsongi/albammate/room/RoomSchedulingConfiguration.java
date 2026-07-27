package cloud.bamsongi.albammate.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 방 상태 정합화 스케줄러를 실행하기 위해 Spring scheduling을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoomSchedulingConfiguration {}
