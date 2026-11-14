package com.localllm.locie;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.Set;

/**
 * The architecture rules that keep changes cheap: features stay independent, controllers stay thin,
 * and the model runtime stays behind its port.
 *
 * <p>These are ordinary JUnit tests, so {@code mvn test} fails the build when a boundary is crossed.
 */
@AnalyzeClasses(packages = "com.localllm.locie", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Logic that must be testable without starting Spring. */
    private static final DescribedPredicate<JavaClass> DOMAIN_HELPERS = DescribedPredicate.describe(
            "a framework-free helper",
            javaClass -> Set.of("ContextBudget", "ContextView", "ChatExport", "CommandRunner")
                    .contains(javaClass.getSimpleName()));

    private static final DescribedPredicate<JavaClass> SPRING_OR_JAKARTA = DescribedPredicate.describe(
            "a Spring or Jakarta type",
            javaClass -> {
                String name = javaClass.getPackageName();
                return name.startsWith("org.springframework") || name.startsWith("jakarta");
            });

    @ArchTest
    static final ArchRule controllers_do_not_touch_repositories =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                    .because("controllers talk to services; persistence is a service concern");

    @ArchTest
    static final ArchRule feature_modules_have_no_cycles =
            SlicesRuleDefinition.slices().matching("com.localllm.locie.(*)..")
                    .should().beFreeOfCycles()
                    .because("chat, model, system and command must be usable and testable on their own");

    @ArchTest
    static final ArchRule model_does_not_depend_on_features_above_it =
            noClasses().that().resideInAPackage("..model..")
                    .should().dependOnClassesThat().resideInAnyPackage("..chat..", "..command..", "..system..")
                    .because("the provider layer is the bottom of the stack, not a peer of the features");

    @ArchTest
    static final ArchRule domain_helpers_stay_framework_free =
            noClasses().that(DOMAIN_HELPERS)
                    .should().dependOnClassesThat(SPRING_OR_JAKARTA)
                    .because("pure logic must be unit-testable without a Spring context");

    @ArchTest
    static final ArchRule chat_depends_on_the_port_not_on_the_ollama_clients =
            noClasses().that().haveSimpleName("ChatService")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("Ollama")
                    .because("a second provider should mean a new adapter, not an edited ChatService");

    @ArchTest
    static final ArchRule the_port_carries_no_transport_details =
            noClasses().that().haveSimpleName("LlmProvider")
                    .should().dependOnClassesThat().haveSimpleNameStartingWith("Ollama")
                    .because("the interface describes capabilities, the adapter owns the wire format");
}
