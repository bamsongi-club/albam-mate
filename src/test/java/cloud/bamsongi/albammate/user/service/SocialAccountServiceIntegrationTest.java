package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialLinkResult;
import cloud.bamsongi.albammate.user.contract.SocialLoginResult;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.entity.SocialAccount;
import cloud.bamsongi.albammate.user.repository.SocialAccountRepository;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest
class SocialAccountServiceIntegrationTest {

	@Autowired
	private SocialAccountService socialAccountService;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Test
	void 같은_외부_신원은_선택_이메일과_닉네임_유무와_관계없이_한_사용자로_수렴한다() {
		String subject = unique("same-identity");
		SocialIdentity firstIdentity = identity(SocialProvider.GOOGLE, subject, Optional.empty(), Optional.empty());

		SocialLoginResult.LoggedIn first = loggedIn(socialAccountService.login(firstIdentity));
		SocialLoginResult.LoggedIn repeated = loggedIn(
			socialAccountService.login(
				identity(
					SocialProvider.GOOGLE,
					subject,
					Optional.of(email("ignored-" + subject + "@example.com")),
					Optional.of(nickname("다른 닉네임")))));

		assertEquals(first.account(), repeated.account());
		assertEquals("Google 사용자", first.account().nickname());
		assertEquals(
			1,
			socialAccountRepository.findByProviderAndProviderSubject(SocialProvider.GOOGLE, subject).stream().count());
	}

	@Test
	void 기존_이메일과_겹치는_첫_로그인은_연결을_요구하고_명시적_연결은_이메일을_무시한다() {
		String existingEmail = unique("existing-email") + "@example.com";
		UserAccount existing = userAccountService.createAccount(
			command(existingEmail, "기존 이메일 사용자"));
		long userCountBefore = userRepository.count();
		SocialIdentity identity = identity(
			SocialProvider.KAKAO,
			unique("email-conflict"),
			Optional.of(email(existingEmail)),
			Optional.of(nickname("외부 사용자")));

		assertInstanceOf(SocialLoginResult.LinkRequired.class, socialAccountService.login(identity));
		assertEquals(userCountBefore, userRepository.count());
		assertTrue(socialAccountRepository.findByProviderAndProviderSubject(
			SocialProvider.KAKAO, identity.providerSubject()).isEmpty());

		assertEquals(SocialLinkResult.LINKED, socialAccountService.link(existing.id(), identity));
		assertEquals(
			existing.id(),
			socialAccountRepository
				.findByProviderAndProviderSubject(SocialProvider.KAKAO, identity.providerSubject())
				.orElseThrow()
				.getUser()
				.getId());
	}

	@Test
	void 기존_외부_연결과_같은_제공자의_다른_계정은_덮어쓰지_않고_충돌한다() {
		SocialIdentity ownedIdentity = identity(
			SocialProvider.NAVER, unique("owned"), Optional.empty(), Optional.empty());
		SocialLoginResult.LoggedIn owner = loggedIn(socialAccountService.login(ownedIdentity));
		UserAccount anotherUser = userAccountService.createAccount(
			command(unique("another") + "@example.com", "다른 사용자"));

		assertEquals(SocialLinkResult.LINKED, socialAccountService.link(owner.account().id(), ownedIdentity));
		assertEquals(SocialLinkResult.LINK_CONFLICT, socialAccountService.link(anotherUser.id(), ownedIdentity));
		assertEquals(
			SocialLinkResult.LINK_CONFLICT,
			socialAccountService.link(
				owner.account().id(),
				identity(SocialProvider.NAVER, unique("replacement"), Optional.empty(), Optional.empty())));
		assertEquals(
			owner.account().id(),
			socialAccountRepository
				.findByProviderAndProviderSubject(SocialProvider.NAVER, ownedIdentity.providerSubject())
				.orElseThrow()
				.getUser()
				.getId());
	}

	@Test
	void 같은_외부_신원의_동시_첫_로그인은_한_사용자와_한_연결로_수렴한다() throws Exception {
		String subject = unique("concurrent");
		SocialIdentity identity = identity(SocialProvider.GOOGLE, subject, Optional.empty(), Optional.empty());
		long userCountBefore = userRepository.count();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<SocialLoginResult> first = executor.submit(() -> loginAfterStart(ready, start, identity));
			Future<SocialLoginResult> second = executor.submit(() -> loginAfterStart(ready, start, identity));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			assertEquals(loggedIn(first.get(10, TimeUnit.SECONDS)).account(),
				loggedIn(second.get(10, TimeUnit.SECONDS)).account());
			assertEquals(userCountBefore + 1, userRepository.count());
			assertEquals(
				1,
				socialAccountRepository.findByProviderAndProviderSubject(SocialProvider.GOOGLE, subject).stream().count());
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void 외부_인증정보_필드는_공개_계약과_영속_모델에_없다() {
		Set<String> forbiddenNames = Set.of(
			"accessToken", "refreshToken", "idToken", "authorizationCode", "clientSecret");
		Set<String> fieldNames = java.util.Arrays.stream(SocialAccount.class.getDeclaredFields())
			.map(Field::getName)
			.collect(java.util.stream.Collectors.toSet());

		assertTrue(java.util.Collections.disjoint(forbiddenNames, fieldNames));
		assertTrue(java.util.Arrays.stream(SocialIdentity.class.getRecordComponents())
			.map(component -> component.getName())
			.noneMatch(forbiddenNames::contains));
	}

	private SocialLoginResult loginAfterStart(
		CountDownLatch ready, CountDownLatch start, SocialIdentity identity) throws InterruptedException {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new AssertionError("동시 소셜 로그인 시작 신호를 기다리다 시간 초과했습니다.");
		}
		return socialAccountService.login(identity);
	}

	private SocialLoginResult.LoggedIn loggedIn(SocialLoginResult result) {
		return assertInstanceOf(SocialLoginResult.LoggedIn.class, result);
	}

	private CreateUserAccountCommand command(String email, String nickname) {
		return new CreateUserAccountCommand(
			email(email), RawPassword.from("123456789012345").orElseThrow(), nickname(nickname));
	}

	private SocialIdentity identity(
		SocialProvider provider,
		String subject,
		Optional<UserEmail> email,
		Optional<UserNickname> nickname) {
		return new SocialIdentity(provider, subject, email, nickname);
	}

	private UserEmail email(String value) {
		return UserEmail.from(value).orElseThrow();
	}

	private UserNickname nickname(String value) {
		return UserNickname.from(value).orElseThrow();
	}

	private String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
	}
}
