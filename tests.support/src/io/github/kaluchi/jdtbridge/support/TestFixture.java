package io.github.kaluchi.jdtbridge.support;

import io.github.kaluchi.jdtbridge.JdtUtils;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;

/**
 * Creates an in-memory Java project ({@value #PROJECT_NAME}) via JDT API
 * for integration tests. The project lives in the Eclipse workspace only —
 * no files on disk.
 *
 * <pre>
 * test.model
 *   Animal               — interface with name()
 *   Dog                  — implements Animal, has bark(), field age
 *   Cat                  — implements Animal
 *
 * test.service
 *   AnimalService        — uses Dog.bark(), Animal.name()
 *   EnrichedRefService   — exercises annotations, static fields, inner classes
 *   GenericService&lt;T&gt;    — bounded generic (T extends Animal)
 *   CallerService        — delegates to AnimalService (caller-chain tests)
 *   GenericCallerService — calls Repository with generic params
 *   AnonymousCallerService — anonymous Animal impl
 *
 * test.edge
 *   Calculator           — overloaded add() (int, double, 3-arg)
 *   Outer / Inner / StaticNested — inner class variants
 *   Color                — enum with method
 *   Marker               — runtime annotation
 *   AbstractPet / Parrot — abstract hierarchy
 *   Repository           — generic erasure (List, Map signatures)
 *   SimpleTest           — JUnit 5 test class (for test-runner tests)
 *   EdgeCaseMembers      — deprecated field, throws, javadoc (coverage edge cases)
 *
 * test.broken
 *   BrokenClass          — intentional compile error (UnknownType)
 *
 * test.refactor
 *   RenameTarget / RenameCaller — rename refactoring targets
 *   FormatTarget         — intentionally messy formatting
 *   ImportTarget         — unused imports (organize-imports tests)
 * </pre>
 */
public class TestFixture {

    public static final String PROJECT_NAME = "jdtbridge-test";
    public static final String NON_JAVA_PROJECT_NAME = "jdtbridge-test-nonjava";

    private static final String ANIMAL_SRC = """
            package test.model;

            public interface Animal {
                String name();
                default String kind() { return "animal"; }
            }
            """;

    private static final String DOG_SRC = """
            package test.model;

            public class Dog implements Animal {
                private int age;

                @Override
                public String name() {
                    return "Dog";
                }

                public void bark() {
                    System.out.println("Woof!");
                }
            }
            """;

    private static final String CAT_SRC = """
            package test.model;

            public class Cat implements Animal {
                @Override
                public String name() {
                    return "Cat";
                }
            }
            """;

    private static final String SERVICE_SRC = """
            package test.service;

            import test.model.Animal;
            import test.model.Dog;

            public class AnimalService {
                public void process(Animal animal) {
                    animal.name();
                }

                public Dog createDog() {
                    Dog d = new Dog();
                    d.bark();
                    return d;
                }
            }
            """;

    private static final String BROKEN_SRC = """
            package test.broken;

            public class BrokenClass {
                UnknownType x;
            }
            """;

    // ---- Edge case types ----

    private static final String OVERLOADED_SRC = """
            package test.edge;

            public class Calculator {
                public int add(int a, int b) {
                    return a + b;
                }

                public double add(double a, double b) {
                    return a + b;
                }

                public int add(int a, int b, int c) {
                    return a + b + c;
                }
            }
            """;

    private static final String INNER_SRC = """
            package test.edge;

            public class Outer {
                private String name;

                public class Inner {
                    public String getOuterName() {
                        return name;
                    }
                }

                public static class StaticNested {
                    public static final int VALUE = 42;
                }
            }
            """;

    private static final String ENUM_SRC = """
            package test.edge;

            public enum Color {
                RED, GREEN, BLUE;

                public String lower() {
                    return name().toLowerCase();
                }
            }
            """;

    private static final String ANNOTATION_SRC = """
            package test.edge;

            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;

            @Retention(RetentionPolicy.RUNTIME)
            public @interface Marker {
                String value() default "";
            }
            """;

    private static final String ABSTRACT_SRC = """
            package test.edge;

            import test.model.Animal;

            /** An abstract pet with a name.
             * @param petName display name
             */
            public abstract class AbstractPet implements Animal {
                protected final String petName;

                protected AbstractPet(String petName) {
                    this.petName = petName;
                }

                @Override
                public String name() {
                    return petName;
                }

                @Deprecated
                public abstract void speak();
            }
            """;

    private static final String CONCRETE_PET_SRC = """
            package test.edge;

            public class Parrot extends AbstractPet {
                public Parrot() {
                    super("Polly");
                }

                @Override
                public void speak() {
                    System.out.println("Hello!");
                }
            }
            """;

    private static final String GENERIC_SRC = """
            package test.edge;

            import java.util.List;
            import java.util.Map;

            public class Repository {
                public void save(String item) {}
                public void save(String item, int priority) {}
                public void save(List<String> items) {}
                public List<String> findAll() { return null; }
                public Map<String, Object> findByIds(String[] ids) {
                    return null;
                }
            }
            """;

    private static final String SIMPLE_TEST_SRC = """
            package test.edge;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            public class SimpleTest {
                @Test
                public void onePlusOne() {
                    assertEquals(2, 1 + 1);
                }
            }
            """;

    // Source type whose name matches the substring "Object" — anchors
    // tests that drive `pattern=Object` against `sourceOnly` to
    // confirm the binary `java.lang.Object` is filtered out while
    // a bona-fide source match is still returned.
    private static final String OBJECT_HOLDER_SRC = """
            package test.edge;

            public class ObjectHolder {
                public Object value;
            }
            """;

    private static final String EDGE_MEMBERS_SRC = """
            package test.edge;

            /** Edge-case members for coverage of deprecated, throws, and javadoc paths. */
            public class EdgeCaseMembers {
                /** The count of items. */
                @Deprecated
                private int count;

                /**
                 * Processes the input.
                 * @param input the data
                 * @throws IllegalArgumentException if input is null
                 */
                public void process(String input) throws IllegalArgumentException {
                    if (input == null) throw new IllegalArgumentException("null");
                    count++;
                }
            }
            """;

    // ---- Enriched ref testing ----

    private static final String ENRICHED_SERVICE_SRC = """
            package test.service;

            import test.model.Animal;
            import test.model.Dog;
            import test.edge.AbstractPet;
            import test.edge.Parrot;
            import test.edge.Color;
            import test.edge.Marker;
            import test.edge.Outer;

            @Marker("enriched")
            public class EnrichedRefService {
                private static final Dog SHARED_DOG = new Dog();

                public static Dog getSharedDog() {
                    return SHARED_DOG;
                }

                public String getParrotName(Parrot p) {
                    return p.name();
                }

                public String getAnimalName(Animal a) {
                    return a.name();
                }

                public int getStaticValue() {
                    return Outer.StaticNested.VALUE;
                }

                public Color getColor() {
                    return Color.RED;
                }
            }
            """;

    private static final String GENERIC_SERVICE_SRC = """
            package test.service;

            import test.model.Animal;

            public class GenericService<T extends Animal> {
                private T item;

                public T get() {
                    return item;
                }

                public void set(T item) {
                    this.item = item;
                }

                public String name() {
                    return item.name();
                }
            }
            """;

    private static final String CALLER_SRC = """
            package test.service;

            import test.model.Animal;
            import test.model.Dog;

            public class CallerService {
                private final AnimalService service =
                        new AnimalService();

                public void callProcess() {
                    Animal dog = new Dog();
                    service.process(dog);
                }

                public Dog callCreateDog() {
                    return service.createDog();
                }
            }
            """;

    // ---- Anonymous subtype testing ----

    private static final String ANONYMOUS_CALLER_SRC = """
            package test.service;

            import test.model.Animal;

            public class AnonymousCallerService {
                public Animal createAnonymous() {
                    return new Animal() {
                        @Override
                        public String name() {
                            return "Anonymous";
                        }
                    };
                }
            }
            """;

    // ---- Lambda caller for synthetic-FQN round-trip tests ----

    private static final String LAMBDA_CALLER_SRC = """
            package test.service;

            public class LambdaCallerService {
                public Runnable createLambda() {
                    return () -> {
                        String local = "hi";
                        System.out.println(local);
                    };
                }
            }
            """;

    // ---- Generic erasure testing ----

    private static final String GENERIC_CALLER_SRC = """
            package test.service;

            import java.util.List;
            import java.util.Map;
            import test.edge.Repository;

            public class GenericCallerService {
                private final Repository repo = new Repository();

                public void saveItems(List<String> items) {
                    repo.save(items);
                }

                public Map<String, Object> lookup(String[] ids) {
                    return repo.findByIds(ids);
                }
            }
            """;

    // ---- Refactoring targets (separate classes that can be renamed/moved) ----

    private static final String RENAME_TARGET_SRC = """
            package test.refactor;

            public class RenameTarget {
                private int counter;

                public int getCounter() {
                    return counter;
                }

                public void increment() {
                    counter++;
                }
            }
            """;

    private static final String RENAME_CALLER_SRC = """
            package test.refactor;

            public class RenameCaller {
                public void use() {
                    RenameTarget t = new RenameTarget();
                    t.increment();
                    int c = t.getCounter();
                }
            }
            """;

    private static final String FORMAT_TARGET_SRC = """
            package test.refactor;

            public class FormatTarget {
            public    void   messy(  )  {
                    int x=1+2;
            String s  ="hello";
            }
            }
            """;

    private static final String IMPORT_TARGET_SRC = """
            package test.refactor;

            import java.util.List;
            import java.util.Map;
            import java.util.Set;

            public class ImportTarget {
                List<String> items;
            }
            """;

    public static void create() throws Exception {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(PROJECT_NAME);

        if (project.exists()) {
            project.delete(true, true, null);
        }

        project.create(null);
        project.open(null);

        // Add Java nature
        IProjectDescription desc = project.getDescription();
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(desc, null);

        IJavaProject javaProject = JavaCore.create(project);

        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        srcFolder.create(true, true, null);
        IFolder binFolder = project.getFolder("bin");
        binFolder.create(true, true, null);

        // Set classpath: src + JRE + JUnit 5. The src entry carries
        // an explicit output location so tests asserting that
        // overrides serialise as absolute paths have a fixture to
        // exercise (Eclipse projects without overrides emit no
        // outputLocation field).
        IClasspathEntry srcEntry = JavaCore.newSourceEntry(
                srcFolder.getFullPath(),
                new IPath[0], new IPath[0],
                binFolder.getFullPath());
        IClasspathEntry jreEntry = JavaCore.newContainerEntry(
                new Path("org.eclipse.jdt.launching.JRE_CONTAINER"));
        IClasspathEntry junitEntry = JavaCore.newContainerEntry(
                new Path("org.eclipse.jdt.junit.JUNIT_CONTAINER/5"));
        javaProject.setRawClasspath(new IClasspathEntry[] {
                srcEntry, jreEntry, junitEntry }, null);

        // Initialize project preferences (needed by refactoring APIs)
        javaProject.setOption(JavaCore.COMPILER_SOURCE, "21");
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, "21");

        // Create packages and source files
        IPackageFragmentRoot srcRoot =
                javaProject.getPackageFragmentRoot(srcFolder);

        IPackageFragment modelPkg =
                srcRoot.createPackageFragment("test.model", true, null);
        modelPkg.createCompilationUnit(
                "Animal.java", ANIMAL_SRC, true, null);
        modelPkg.createCompilationUnit("Dog.java", DOG_SRC, true, null);
        modelPkg.createCompilationUnit("Cat.java", CAT_SRC, true, null);

        IPackageFragment servicePkg =
                srcRoot.createPackageFragment("test.service", true, null);
        servicePkg.createCompilationUnit(
                "AnimalService.java", SERVICE_SRC, true, null);
        servicePkg.createCompilationUnit(
                "EnrichedRefService.java",
                ENRICHED_SERVICE_SRC, true, null);
        servicePkg.createCompilationUnit(
                "GenericService.java",
                GENERIC_SERVICE_SRC, true, null);
        servicePkg.createCompilationUnit(
                "CallerService.java",
                CALLER_SRC, true, null);
        servicePkg.createCompilationUnit(
                "GenericCallerService.java",
                GENERIC_CALLER_SRC, true, null);
        servicePkg.createCompilationUnit(
                "AnonymousCallerService.java",
                ANONYMOUS_CALLER_SRC, true, null);
        servicePkg.createCompilationUnit(
                "LambdaCallerService.java",
                LAMBDA_CALLER_SRC, true, null);

        IPackageFragment brokenPkg =
                srcRoot.createPackageFragment("test.broken", true, null);
        brokenPkg.createCompilationUnit(
                "BrokenClass.java", BROKEN_SRC, true, null);

        // Edge case types
        IPackageFragment edgePkg =
                srcRoot.createPackageFragment("test.edge", true, null);
        edgePkg.createCompilationUnit(
                "Calculator.java", OVERLOADED_SRC, true, null);
        edgePkg.createCompilationUnit(
                "Outer.java", INNER_SRC, true, null);
        edgePkg.createCompilationUnit(
                "Color.java", ENUM_SRC, true, null);
        edgePkg.createCompilationUnit(
                "Marker.java", ANNOTATION_SRC, true, null);
        edgePkg.createCompilationUnit(
                "AbstractPet.java", ABSTRACT_SRC, true, null);
        edgePkg.createCompilationUnit(
                "Parrot.java", CONCRETE_PET_SRC, true, null);
        edgePkg.createCompilationUnit(
                "Repository.java", GENERIC_SRC, true, null);
        edgePkg.createCompilationUnit(
                "SimpleTest.java", SIMPLE_TEST_SRC, true, null);
        edgePkg.createCompilationUnit(
                "ObjectHolder.java", OBJECT_HOLDER_SRC, true, null);
        edgePkg.createCompilationUnit(
                "EdgeCaseMembers.java",
                EDGE_MEMBERS_SRC, true, null);

        // Refactoring targets
        IPackageFragment refactorPkg =
                srcRoot.createPackageFragment("test.refactor", true, null);
        refactorPkg.createCompilationUnit(
                "RenameTarget.java", RENAME_TARGET_SRC, true, null);
        refactorPkg.createCompilationUnit(
                "RenameCaller.java", RENAME_CALLER_SRC, true, null);
        refactorPkg.createCompilationUnit(
                "FormatTarget.java", FORMAT_TARGET_SRC, true, null);
        refactorPkg.createCompilationUnit(
                "ImportTarget.java", IMPORT_TARGET_SRC, true, null);

        // Wait for auto-build to finish
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);
    }

    public static void destroy() throws Exception {
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        for (String name : new String[] {
                PROJECT_NAME, NON_JAVA_PROJECT_NAME }) {
            IProject p = root.getProject(name);
            if (p.exists()) {
                p.delete(true, true, null);
            }
        }
    }

    /** Create a plain project without Java nature. */
    public static void createNonJavaProject() throws Exception {
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(NON_JAVA_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        // No Java nature — just a plain project
    }
}
