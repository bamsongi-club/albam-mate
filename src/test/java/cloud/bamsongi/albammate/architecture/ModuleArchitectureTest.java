package cloud.bamsongi.albammate.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ModuleArchitectureTest {

    private static final String ROOT_PACKAGE = "cloud.bamsongi.albammate";
    private static final List<String> BUSINESS_MODULES = List.of("auth", "user", "game", "room");
    private static final String[] BUSINESS_MODULE_PACKAGES =
            BUSINESS_MODULES.stream()
                    .map(ModuleArchitectureTest::modulePackage)
                    .toArray(String[]::new);
    private static final Map<String, List<String>> FORBIDDEN_DEPENDENCIES =
            Map.of(
                    "auth", List.of("game", "room"),
                    "user", List.of("auth", "game", "room"),
                    "game", List.of("auth", "user", "room"),
                    "room", List.of("auth"));
    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT_PACKAGE);

    @Test
    void 업무_모듈_간_순환_의존이_없다() {
        JavaClasses businessClasses =
                PRODUCTION_CLASSES.that(resideInAnyPackage(BUSINESS_MODULE_PACKAGES));

        slices().matching(ROOT_PACKAGE + ".(*)..")
                .should()
                .beFreeOfCycles()
                .because("업무 모듈 사이의 순환 의존은 허용하지 않는다")
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
                (sourceModule, targetModules) ->
                        noClasses()
                                .that()
                                .resideInAPackage(modulePackage(sourceModule))
                                .should()
                                .dependOnClassesThat()
                                .resideInAnyPackage(
                                        targetModules.stream()
                                                .map(ModuleArchitectureTest::modulePackage)
                                                .toArray(String[]::new))
                                .because("허용된 참조 방향은 room에서 game과 user, auth에서 user뿐이다")
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
    void production_코드는_Autowired를_사용하지_않는다() {
        classes()
                .should(notUseAutowired())
                .because("생산 코드는 단일 생성자 또는 명시적 구성으로 의존성을 주입한다")
                .check(PRODUCTION_CLASSES);
    }

    private static ArchCondition<JavaClass> notUseAutowired() {
        return new ArchCondition<>("not use @Autowired on fields, constructors, or methods") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean usesAutowired =
                        javaClass.getFields().stream()
                                        .anyMatch(field -> field.isAnnotatedWith(Autowired.class))
                                || javaClass.getConstructors().stream()
                                        .anyMatch(
                                                constructor ->
                                                        constructor.isAnnotatedWith(
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

    private static String modulePackage(String module) {
        return ROOT_PACKAGE + "." + module + "..";
    }

    private static String contractPackage(String module) {
        return ROOT_PACKAGE + "." + module + ".contract..";
    }
}
