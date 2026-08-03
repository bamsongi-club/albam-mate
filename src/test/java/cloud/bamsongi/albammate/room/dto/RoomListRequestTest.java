package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class RoomListRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 필터를_생략한_목록은_빈_페이지_parameter의_기본값을_유지한다() {
		RoomListRequest request = new RoomListRequest();
		request.setPage(null);
		request.setSize(null);

		assertEquals(0, request.getPage());
		assertEquals(10, request.getSize());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 유형과_gameId와_keyword는_독립적인_선택_필터다() {
		RoomListRequest request = new RoomListRequest();
		request.setType(RoomType.PERSON_FOCUSED);
		request.setGameId(1L);
		request.setKeyword("모임");

		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void gameId와_페이지_크기_범위를_검증한다() {
		RoomListRequest invalidGameId = new RoomListRequest();
		invalidGameId.setGameId(0L);
		RoomListRequest invalidPage = new RoomListRequest();
		invalidPage.setPage(-1);
		RoomListRequest invalidSize = new RoomListRequest();
		invalidSize.setSize(101);

		assertFalse(validator.validate(invalidGameId).isEmpty());
		assertFalse(validator.validate(invalidPage).isEmpty());
		assertFalse(validator.validate(invalidSize).isEmpty());
	}

	@Test
	void P1_방_조건의_범위와_날짜_순서를_검증한다() {
		RoomListRequest valid = new RoomListRequest();
		valid.setStartsAtFrom(Instant.parse("2099-01-01T00:00:00Z"));
		valid.setStartsAtTo(Instant.parse("2099-01-02T00:00:00Z"));
		valid.setMinRemainingSeats(1);
		valid.setExperienceLevels(
			Set.of(ExperienceLevel.ALL_LEVELS, ExperienceLevel.BEGINNER_WELCOME));
		valid.setRulemasterOnly(true);
		RoomListRequest sameBoundary = new RoomListRequest();
		sameBoundary.setStartsAtFrom(Instant.parse("2099-01-01T00:00:00Z"));
		sameBoundary.setStartsAtTo(Instant.parse("2099-01-01T00:00:00Z"));
		RoomListRequest reversedRange = new RoomListRequest();
		reversedRange.setStartsAtFrom(Instant.parse("2099-01-02T00:00:00Z"));
		reversedRange.setStartsAtTo(Instant.parse("2099-01-01T00:00:00Z"));
		RoomListRequest invalidSeats = new RoomListRequest();
		invalidSeats.setMinRemainingSeats(11);
		RoomListRequest fromOnly = new RoomListRequest();
		fromOnly.setStartsAtFrom(Instant.parse("2099-01-01T00:00:00Z"));
		RoomListRequest toOnly = new RoomListRequest();
		toOnly.setStartsAtTo(Instant.parse("2099-01-02T00:00:00Z"));
		RoomListRequest nullableFilters = new RoomListRequest();
		nullableFilters.setExperienceLevels(null);
		nullableFilters.setRulemasterOnly(null);

		assertTrue(validator.validate(valid).isEmpty());
		assertFalse(validator.validate(sameBoundary).isEmpty());
		assertFalse(validator.validate(reversedRange).isEmpty());
		assertFalse(validator.validate(invalidSeats).isEmpty());
		assertTrue(validator.validate(fromOnly).isEmpty());
		assertTrue(validator.validate(toOnly).isEmpty());
		assertEquals(Set.of(), nullableFilters.getExperienceLevels());
		assertFalse(nullableFilters.isRulemasterOnly());
	}
}
