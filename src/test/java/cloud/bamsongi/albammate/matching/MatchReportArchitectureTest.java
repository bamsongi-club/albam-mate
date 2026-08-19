package cloud.bamsongi.albammate.matching;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class MatchReportArchitectureTest {

	private static final String MATCHING_PACKAGE = "cloud.bamsongi.albammate.matching";
	private static final String USER_ENTITY_PACKAGE = "cloud.bamsongi.albammate.user.entity";
	private static final String USER_REPOSITORY_PACKAGE = "cloud.bamsongi.albammate.user.repository";
	private static final String USER_ROW_LOCK_PORT = "cloud.bamsongi.albammate.user.contract.UserRowLockPort";
	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages(MATCHING_PACKAGE);

	@Test
	void G1_신고_명령과_cleanup은_UserRowLockPort만_사용하고_user_구현을_참조하지_않는다() {
		noClasses()
			.that()
			.resideInAPackage(MATCHING_PACKAGE + "..")
			.should()
			.dependOnClassesThat()
			.resideInAnyPackage(USER_ENTITY_PACKAGE + "..", USER_REPOSITORY_PACKAGE + "..")
			.check(PRODUCTION_CLASSES);

		classes()
			.that()
			.haveFullyQualifiedName(MATCHING_PACKAGE + ".service.command.MatchReportCommandExecutor")
			.or()
			.haveFullyQualifiedName(MATCHING_PACKAGE + ".recovery.MatchReportCleanupExecutor")
			.should()
			.dependOnClassesThat()
			.haveFullyQualifiedName(USER_ROW_LOCK_PORT)
			.check(PRODUCTION_CLASSES);
	}
}
