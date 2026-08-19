package cloud.bamsongi.albammate.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

class ModuleArchitectureTest {

	private static final String ROOT_PACKAGE = "cloud.bamsongi.albammate";
	private static final Set<String> ALLOWED_ROOM_PACKAGES = Set.of(
		ROOT_PACKAGE + ".room.contract",
		ROOT_PACKAGE + ".room.controller",
		ROOT_PACKAGE + ".room.dto",
		ROOT_PACKAGE + ".room.entity",
		ROOT_PACKAGE + ".room.enums",
		ROOT_PACKAGE + ".room.repository",
		ROOT_PACKAGE + ".room.service",
		ROOT_PACKAGE + ".room.service.query",
		ROOT_PACKAGE + ".room.service.command",
		ROOT_PACKAGE + ".room.statuscorrection");
	private static final Set<String> ALLOWED_NOTIFICATION_PACKAGES = Set.of(
		ROOT_PACKAGE + ".notification.controller",
		ROOT_PACKAGE + ".notification.dto",
		ROOT_PACKAGE + ".notification.entity",
		ROOT_PACKAGE + ".notification.enums",
		ROOT_PACKAGE + ".notification.exception",
		ROOT_PACKAGE + ".notification.repository",
		ROOT_PACKAGE + ".notification.service",
		ROOT_PACKAGE + ".notification.service.query",
		ROOT_PACKAGE + ".notification.service.command",
		ROOT_PACKAGE + ".notification.relay",
		ROOT_PACKAGE + ".notification.recovery",
		ROOT_PACKAGE + ".notification.cleanup");
	private static final Set<String> ALLOWED_CHAT_PACKAGES = Set.of(
		ROOT_PACKAGE + ".chat.contract",
		ROOT_PACKAGE + ".chat.controller",
		ROOT_PACKAGE + ".chat.dto",
		ROOT_PACKAGE + ".chat.entity",
		ROOT_PACKAGE + ".chat.repository",
		ROOT_PACKAGE + ".chat.service",
		ROOT_PACKAGE + ".chat.match",
		ROOT_PACKAGE + ".chat.match.adapter",
		ROOT_PACKAGE + ".chat.match.contract",
		ROOT_PACKAGE + ".chat.match.entity",
		ROOT_PACKAGE + ".chat.match.repository",
		ROOT_PACKAGE + ".chat.match.service",
		ROOT_PACKAGE + ".chat.retention",
		ROOT_PACKAGE + ".chat.websocket");
	private static final Set<String> ALLOWED_MATCHING_PACKAGES = Set.of(
		ROOT_PACKAGE + ".matching",
		ROOT_PACKAGE + ".matching.contract",
		ROOT_PACKAGE + ".matching.controller",
		ROOT_PACKAGE + ".matching.dto",
		ROOT_PACKAGE + ".matching.entity",
		ROOT_PACKAGE + ".matching.repository",
		ROOT_PACKAGE + ".matching.service.command",
		ROOT_PACKAGE + ".matching.service.query",
		ROOT_PACKAGE + ".matching.recovery");
	private static final String ROOM_RETRIER = ROOT_PACKAGE + ".room.service.RoomOptimisticLockRetrier";
	private static final Set<String> ALLOWED_ROOM_RETRIER_USERS = Set.of(
		ROOT_PACKAGE + ".room.service.command.RoomCommandExecutionCoordinator",
		ROOT_PACKAGE + ".room.statuscorrection.RoomStatusCorrectionCoordinator");
	private static final List<String> BUSINESS_MODULES = List.of("auth", "user", "game", "room", "notification",
		"chat", "matching", "assistant");
	private static final String[] BUSINESS_MODULE_PACKAGES = BUSINESS_MODULES.stream()
		.map(ModuleArchitectureTest::modulePackage)
		.toArray(String[]::new);
	private static final Map<String, List<String>> FORBIDDEN_DEPENDENCIES = Map.of(
		"auth", List.of("game", "room", "notification", "chat", "matching"),
		"user", List.of("auth", "game", "room", "notification", "chat", "matching"),
		"game", List.of("auth", "user", "room", "notification", "chat", "matching"),
		"room", List.of("auth", "notification", "matching"),
		"notification", List.of("auth", "user", "game", "chat", "matching"),
		"chat", List.of("auth", "game", "notification"),
		"matching", List.of("auth", "game", "room", "notification", "chat"),
		"assistant", List.of("auth", "user", "game", "room", "notification", "chat", "matching"));
	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages(ROOT_PACKAGE);

	@Test
	void 업무_모듈_간_순환_의존이_없다() {
		JavaClasses businessClasses = PRODUCTION_CLASSES.that(resideInAnyPackage(BUSINESS_MODULE_PACKAGES));

		slices().matching(ROOT_PACKAGE + ".(*)..")
			.should()
			.beFreeOfCycles()
			.because("업무 모듈 사이의 순환 의존은 허용하지 않는다")
			.ignoreDependency(
				resideInAPackage(ROOT_PACKAGE + ".chat.."), resideInAPackage(ROOT_PACKAGE + ".room.contract.."))
			.ignoreDependency(
				resideInAPackage(ROOT_PACKAGE + ".room.."), resideInAPackage(ROOT_PACKAGE + ".chat.contract.."))
			.check(businessClasses);
	}

	@Test
	void 다른_업무_모듈의_내부_구현을_참조하지_않는다() {
		for (String targetModule : BUSINESS_MODULES) {
			noClasses()
				.that(
					resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
						.and(resideOutsideOfPackage(modulePackage(targetModule))))
				.should()
				.dependOnClassesThat(
					resideInAPackage(modulePackage(targetModule))
						.and(resideOutsideOfPackage(contractPackage(targetModule))))
				.because("다른 업무 모듈은 contract 패키지를 통해서만 참조한다")
				.check(PRODUCTION_CLASSES);
		}
	}

	@Test
	void 업무_모듈은_허용된_방향으로만_참조한다() {
		FORBIDDEN_DEPENDENCIES.forEach(
			(sourceModule, targetModules) -> noClasses()
				.that()
				.resideInAPackage(modulePackage(sourceModule))
				.should()
				.dependOnClassesThat()
				.resideInAnyPackage(
					targetModules.stream()
						.map(ModuleArchitectureTest::modulePackage)
						.toArray(String[]::new))
				.because(
					"업무 모듈 간 참조 방향은 이 테스트의 명시된 금지 목록과 각 모듈의 contract 경계를 따른다")
				.allowEmptyShould(sourceModule.equals("notification"))
				.check(PRODUCTION_CLASSES));
	}

	@Test
	void global_패키지는_업무_모듈에_의존하지_않는다() {
		noClasses()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".global..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
			.because("global은 업무 모듈의 Entity, DTO 또는 업무 규칙을 우회 공유하지 않는다")
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void infra는_업무_모듈의_contract만_참조한다() {
		for (String targetModule : BUSINESS_MODULES) {
			noClasses()
				.that()
				.resideInAPackage(ROOT_PACKAGE + ".infra..")
				.should()
				.dependOnClassesThat(
					resideInAPackage(modulePackage(targetModule))
						.and(resideOutsideOfPackages(contractPackages(targetModule))))
				.because("infra는 업무 모듈의 contract를 통해서만 참조한다")
				.check(PRODUCTION_CLASSES);
		}
	}

	@Test
	void 업무_모듈은_infra_구체_구현을_참조하지_않는다() {
		noClasses()
			.that()
			.resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
			.should()
			.dependOnClassesThat()
			.resideInAPackage(ROOT_PACKAGE + ".infra..")
			.because("업무 모듈은 infra의 구체 구현을 직접 참조하지 않는다")
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void production_코드는_Autowired를_사용하지_않는다() {
		classes()
			.should(notUseAutowired())
			.because("생산 코드는 단일 생성자 또는 명시적 구성으로 의존성을 주입한다")
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void ROOM_코드는_정본에_선언한_패키지에만_배치한다() {
		classes()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".room..")
			.should(resideInAllowedPackage(ALLOWED_ROOM_PACKAGES, "ROOM"))
			.because("ROOM은 contract, controller, service/query, service/command와 statuscorrection 경계를 사용한다")
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void Notification_코드는_정본에_선언한_패키지에만_배치한다() {
		classes()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".notification..")
			.should(resideInAllowedPackage(ALLOWED_NOTIFICATION_PACKAGES, "Notification"))
			.because("Notification은 query, command, relay, recovery와 cleanup 경계를 사용한다")
			.allowEmptyShould(true)
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void Chat_코드는_정본에_선언한_패키지에만_배치한다() {
		classes()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".chat..")
			.should(resideInAllowedPackage(ALLOWED_CHAT_PACKAGES, "Chat"))
			.because("CHAT-01은 entity, repository와 room.contract만 사용하는 lifecycle service를 소유하고,"
				+ " CHAT-03은 방별 WebSocket handshake 경계를 websocket 패키지에 소유한다")
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void MATCH는_chat_구현을_참조하지_않고_chat은_matching_contract만_참조한다() {
		JavaClasses matchingClasses = PRODUCTION_CLASSES.that(
			resideInAPackage(ROOT_PACKAGE + ".matching.."));
		assertFalse(matchingClasses.isEmpty(), "MATCH 저장·계약 생산 패키지가 등록되지 않았습니다.");
		classes()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".matching..")
			.should(resideInAllowedPackage(ALLOWED_MATCHING_PACKAGES, "MATCH"))
			.because("MATCH는 정본에 선언한 contract, entity, repository, service, recovery 경계만 사용한다")
			.check(PRODUCTION_CLASSES);
		noClasses()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".matching..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage(ROOT_PACKAGE + ".chat..")
			.because("matching은 chat 구현·Entity·Repository를 참조하지 않는다")
			.check(PRODUCTION_CLASSES);
		noClasses()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".chat..")
			.should()
			.dependOnClassesThat(
				resideInAPackage(ROOT_PACKAGE + ".matching..")
					.and(resideOutsideOfPackage(ROOT_PACKAGE + ".matching.contract..")))
			.because("chat은 matching.contract 밖 구현을 참조하지 않는다")
			.allowEmptyShould(true)
			.check(PRODUCTION_CLASSES);
	}

	@Test
	void ROOM_Retrier는_두_Coordinator만_직접_사용한다() {
		classes()
			.that()
			.resideInAPackage(ROOT_PACKAGE + ".room..")
			.should(useRoomRetrierOnlyFromCoordinators())
			.because("재시도 정책의 직접 사용자는 Command와 상태 보정 Coordinator뿐이다")
			.check(PRODUCTION_CLASSES);
	}

	private static ArchCondition<JavaClass> notUseAutowired() {
		return new ArchCondition<>("not use @Autowired on fields, constructors, or methods") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				boolean usesAutowired = javaClass.getFields().stream()
					.anyMatch(field -> field.isAnnotatedWith(Autowired.class))
					|| javaClass.getConstructors().stream()
						.anyMatch(
							constructor -> constructor.isAnnotatedWith(
								Autowired.class))
					|| javaClass.getMethods().stream()
						.anyMatch(
							method -> method.isAnnotatedWith(Autowired.class));
				if (usesAutowired) {
					events.add(
						SimpleConditionEvent.violated(
							javaClass,
							javaClass.getFullName()
								+ " uses @Autowired on a field, constructor, or method"));
				}
			}
		};
	}

	private static ArchCondition<JavaClass> resideInAllowedPackage(
		Set<String> allowedPackages, String moduleName) {
		return new ArchCondition<>("reside in an allowed " + moduleName + " package") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				if (!allowedPackages.contains(javaClass.getPackageName())) {
					events.add(SimpleConditionEvent.violated(
						javaClass,
						javaClass.getFullName() + " resides in an undeclared " + moduleName + " package"));
				}
			}
		};
	}

	private static ArchCondition<JavaClass> useRoomRetrierOnlyFromCoordinators() {
		return new ArchCondition<>("use RoomOptimisticLockRetrier only from the two coordinators") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				boolean directlyUsesRetrier = javaClass.getDirectDependenciesFromSelf().stream()
					.anyMatch(dependency -> dependency.getTargetClass().getFullName().equals(ROOM_RETRIER));
				if (directlyUsesRetrier && !ALLOWED_ROOM_RETRIER_USERS.contains(javaClass.getFullName())) {
					events.add(SimpleConditionEvent.violated(
						javaClass,
						javaClass.getFullName() + " directly uses RoomOptimisticLockRetrier"));
				}
			}
		};
	}

	private static String modulePackage(String module) {
		return ROOT_PACKAGE + "." + module + "..";
	}

	private static String contractPackage(String module) {
		return ROOT_PACKAGE + "." + module + ".contract..";
	}

	/** ADR-0080이 승인한 공유 컴포넌트 경계 — chat 모듈만 {@code chat.match.contract}도 infra가 참조할 수 있는
	 * contract로 함께 허용한다. 그 외 모듈은 자기 자신의 {@code <module>.contract} 패키지만 허용한다. */
	private static String[] contractPackages(String module) {
		if ("chat".equals(module)) {
			return new String[] {contractPackage(module), ROOT_PACKAGE + ".chat.match.contract.."};
		}
		return new String[] {contractPackage(module)};
	}
}
