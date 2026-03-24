package io.github.kaluchi.jdtbridge;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;

/**
 * Creates a test Java project with known classes for integration tests.
 *
 * <pre>
 * test.model.Animal      — interface with name()
 * test.model.Dog          — implements Animal, has bark()
 * test.model.Cat          — implements Animal
 * test.service.AnimalService — uses Dog.bark(), Animal.name()
 * test.broken.BrokenClass — intentional compilation error
 * </pre>
 */
class TestFixture {

    static final String PROJECT_NAME = "jdtbridge-test";

    private static final String ANIMAL_SRC = """
            package test.model;

            public interface Animal {
                String name();
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

            public abstract class AbstractPet implements Animal {
                protected final String petName;

                protected AbstractPet(String petName) {
                    this.petName = petName;
                }

                @Override
                public String name() {
                    return petName;
                }

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

    static void create() throws Exception {
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

        // Set classpath: src + JRE + JUnit 5
        IClasspathEntry srcEntry =
                JavaCore.newSourceEntry(srcFolder.getFullPath());
        IClasspathEntry jreEntry = JavaCore.newContainerEntry(
                new Path("org.eclipse.jdt.launching.JRE_CONTAINER"));
        IClasspathEntry junitEntry = JavaCore.newContainerEntry(
                new Path("org.eclipse.jdt.junit.JUNIT_CONTAINER/5"));
        javaProject.setRawClasspath(
                new IClasspathEntry[] { srcEntry, jreEntry,
                        junitEntry }, null);

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

    static void destroy() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, null);
        }
    }
}
