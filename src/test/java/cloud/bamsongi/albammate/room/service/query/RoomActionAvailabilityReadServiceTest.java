package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class RoomActionAvailabilityReadServiceTest {

	@Test
	void T6_목록과_상세_읽기는_독립_REPEATABLE_READ_읽기_트랜잭션이다() throws Exception {
		assertReadSnapshot(RoomListReadService.class, "findPublicRooms");
		assertReadSnapshot(RoomDetailReadService.class, "findRoomDetail");
	}

	@Test
	void T8_검색_조건과_페이지_조회는_기존_ReadService_경계를_유지하고_잔여석_판정을_복제하지_않는다() throws Exception {
		assertNotNull(RoomListReadService.class.getDeclaredMethod(
			"findPublicRooms",
			RoomListSearchCriteria.class,
			org.springframework.data.domain.Pageable.class,
			Long.class));
		assertEquals(
			0,
			java.util.Arrays.stream(RoomListQueryService.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("isJoinable"))
				.count());
	}

	private void assertReadSnapshot(Class<?> serviceType, String methodName) {
		Transactional transaction = java.util.Arrays.stream(serviceType.getDeclaredMethods())
			.filter(method -> method.getName().equals(methodName) && method.getParameterCount() > 1)
			.findFirst()
			.map(method -> method.getAnnotation(Transactional.class))
			.orElse(null);

		assertNotNull(transaction);
		assertEquals(Propagation.REQUIRES_NEW, transaction.propagation());
		assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
		assertEquals(true, transaction.readOnly());
	}
}
