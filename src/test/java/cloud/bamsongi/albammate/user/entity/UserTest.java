package cloud.bamsongi.albammate.user.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UserTest {

	@Test
	void 닉네임_변경은_검증된_새_표시이름을_반영한다() {
		User user = User.create("user@example.com", "{bcrypt}hash", "이전 닉네임");

		user.changeNickname("새 닉네임");

		assertEquals("새 닉네임", user.getNickname());
	}
}
