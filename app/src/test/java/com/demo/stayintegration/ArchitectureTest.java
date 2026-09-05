package com.demo.stayintegration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import jakarta.persistence.Entity;

// CLAUDE.md의 경계 5개. 리뷰로 지키는 규칙은 세션이 바뀌면 잊히지만 여기 적힌 것은 빌드가 기억한다.
// 테스트 클래스는 제외한다 — stub 어댑터·와이어 테스트는 의도적으로 여러 패키지를 가로지른다.
@AnalyzeClasses(packages = "com.demo.stayintegration", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	// 경계 1. 공급사 전용 DTO는 자기 어댑터 패키지 밖으로 나가지 않는다.
	// 지금은 non-public 클래스라 컴파일러가 막지만, 누가 public으로 바꾸는 순간을 잡는다. 중첩 record(SupplierAResponse$Item)까지 포함.
	@ArchTest
	static final ArchRule supplierDtosStayInsideTheirAdapterPackage = classes()
			.that().resideInAPackage("..supplier.adapter..")
			.and().haveNameMatching(".*\\.Supplier\\w+Response(\\$\\w+)*")
			.should(onlyBeDependedOnByClassesInTheSamePackage());

	// 경계 2. search·catalog는 포트만 안다. 어댑터는 레지스트리로 주입된다 — 신규 공급사 추가 시 이 두 패키지가 무변경이어야 한다.
	@ArchTest
	static final ArchRule searchAndCatalogKnowOnlyThePort = noClasses()
			.that().resideInAnyPackage("..search..", "..catalog..")
			.should().dependOnClassesThat().resideInAPackage("..supplier.adapter..");

	// 경계 3. 어댑터와 정규화는 DB를 모른다. 리액티브 체인 안에서 JPA를 부를 길을 패키지 수준에서 끊는다.
	@ArchTest
	static final ArchRule supplierPackageIsFreeOfPersistence = noClasses()
			.that().resideInAPackage("..supplier..")
			.should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.data..", "..repository..");

	// 경계 4. controller → service → function → repository 단방향. 레이어 밖 클래스(dto·설정·포트)의 의존은 보지 않는다 —
	// 이 규칙이 말하려는 것은 계층 간 방향이고, common의 예외 핸들러가 service의 예외 타입을 아는 것은 그 대상이 아니다.
	@ArchTest
	static final ArchRule layersFlowOneWay = layeredArchitecture()
			.consideringOnlyDependenciesInLayers()
			.layer("Controller").definedBy("..controller..")
			.layer("Service").definedBy("..service..")
			.layer("Function").definedBy("..function..")
			.layer("Repository").definedBy("..repository..")
			.whereLayer("Controller").mayNotBeAccessedByAnyLayer()
			.whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
			.whereLayer("Function").mayOnlyBeAccessedByLayers("Service")
			.whereLayer("Repository").mayOnlyBeAccessedByLayers("Function");

	// 경계 5. @Entity는 controller 경계를 넘지 않는다. 시그니처뿐 아니라 컨트롤러 본문에서도 엔티티를 만지지 않는다 — 매핑은 service의 일이다.
	@ArchTest
	static final ArchRule entitiesNeverReachControllers = noClasses()
			.that().resideInAPackage("..controller..")
			.should().dependOnClassesThat().areAnnotatedWith(Entity.class);

	// 경계 2의 연장. 서킷은 어댑터의 내부 사정이다 — 열렸을 때 Skipped를 돌려주는 것까지 어댑터가 표현하므로 병합 쪽은 라이브러리를 몰라야 한다.
	@ArchTest
	static final ArchRule circuitBreakerIsAnAdapterConcern = noClasses()
			.that().resideInAnyPackage("..search..", "..catalog..")
			.should().dependOnClassesThat().resideInAPackage("io.github.resilience4j..");

	// 지표 이름·태그 규약은 common.SupplierCallMetrics 한 곳이 소유한다. 예외는 서킷 레지스트리를 묶는 어댑터 인프라뿐 —
	// 병합 지점(search·catalog)이 Micrometer를 직접 잡으면 태그가 흩어져 대시보드 쿼리가 한쪽만 맞는 사고가 난다.
	@ArchTest
	static final ArchRule micrometerStaysInsideCommonAndAdapterSupport = noClasses()
			.that().resideOutsideOfPackages("..common..", "..supplier.adapter.support..")
			.should().dependOnClassesThat().resideInAPackage("io.micrometer..");

	private static ArchCondition<JavaClass> onlyBeDependedOnByClassesInTheSamePackage() {
		return new ArchCondition<>("only be depended on by classes in the same package") {
			@Override
			public void check(JavaClass javaClass, ConditionEvents events) {
				for (Dependency dependency : javaClass.getDirectDependenciesToSelf()) {
					if (!dependency.getOriginClass().getPackageName().equals(javaClass.getPackageName())) {
						events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
					}
				}
			}
		};
	}
}
