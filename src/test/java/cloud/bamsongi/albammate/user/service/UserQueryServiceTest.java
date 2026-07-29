package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {

	@Mock
	private UserRepository userRepository;
	@InjectMocks
	private UserQueryService userQueryService;

	@Test
	void 닉네임_단건_조회는_공개_필드_projection만_위임한다() {
		when(userRepository.findNicknameById(42L)).thenReturn(Optional.of("방장"));

		assertEquals(Optional.of("방장"), userQueryService.findNicknameById(42L));
		verify(userRepository).findNicknameById(42L);
	}

	@Test
	void 존재하지_않는_사용자는_empty다() {
		when(userRepository.findNicknameById(404L)).thenReturn(Optional.empty());

		assertTrue(userQueryService.findNicknameById(404L).isEmpty());
	}
}
