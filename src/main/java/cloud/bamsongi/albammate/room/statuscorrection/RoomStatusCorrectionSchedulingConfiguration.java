package cloud.bamsongi.albammate.room.statuscorrection;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** 방 상태 정합화 스케줄러를 실행하기 위해 Spring scheduling을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RoomStatusCorrectionSchedulingConfiguration implements SchedulingConfigurer {

	private final RoomStatusCorrectionScheduler scheduler;

	RoomStatusCorrectionSchedulingConfiguration(RoomStatusCorrectionScheduler scheduler) {
		this.scheduler = scheduler;
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addTriggerTask(scheduler::correctDueRooms, scheduler);
	}
}
