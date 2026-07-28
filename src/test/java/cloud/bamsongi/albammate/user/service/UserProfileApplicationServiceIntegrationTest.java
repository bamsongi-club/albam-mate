package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.bamsongi.albammate.user.contract.UserProfile;
import cloud.bamsongi.albammate.user.contract.UserProfileService;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UserProfileApplicationServiceIntegrationTest {

    @Autowired private UserProfileService userProfileService;
    @Autowired private UserRepository userRepository;

    @Test
    void 변경한_닉네임은_후속_프로필_조회에_반영된다() {
        User user =
                userRepository.saveAndFlush(
                        User.create("profile-service@example.com", "{bcrypt}hash", "이전"));

        UserProfile updated = userProfileService.changeNickname(user.getId(), "변경됨");

        assertEquals(new UserProfile(user.getId(), "변경됨"), updated);
        assertEquals(
                new UserProfile(user.getId(), "변경됨"), userProfileService.findProfile(user.getId()));
    }
}
