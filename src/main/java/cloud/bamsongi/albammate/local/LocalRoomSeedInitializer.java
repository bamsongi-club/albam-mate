package cloud.bamsongi.albammate.local;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** local PostgreSQL에서만 개발용 공개 모임을 준비한다. */
@Component
@Profile("local")
@Order(0)
@RequiredArgsConstructor
public class LocalRoomSeedInitializer implements ApplicationRunner {

	private final LocalRoomSeedService localRoomSeedService;

	@Override
	public void run(ApplicationArguments arguments) {
		localRoomSeedService.seed();
	}
}
