package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.core.resources.IFile;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for Maven operations.
 * Creates its own Maven projects (broken + clean) to test
 * success paths, error detection, and all response fields.
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class MavenIntegrationTest {

    private static final String MAVEN_PROJECT =
            "jdtbridge-maven-test";
    private static final String CLEAN_PROJECT =
            "jdtbridge-maven-clean";
    private static final MavenHandler handler =
            new MavenHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        createMavenProject(MAVEN_PROJECT,
                "jdtbridge-maven-test", "test.maven",
                new String[]{
                        "Good.java", """
                        package test.maven;
                        public class Good {
                            public String hello() {
                                return "hi";
                            }
                        }
                        """,
                        "Broken.java", """
                        package test.maven;
                        public class Broken {
                            UnknownType x;
                        }
                        """
                });

        createMavenProject(CLEAN_PROJECT,
                "clean", "test.clean",
                new String[]{
                        "Valid.java", """
                        package test.clean;
                        public class Valid {
                            public int value() { return 42; }
                        }
                        """
                });
    }

    @AfterAll
    public static void tearDown() throws Exception {
        deleteProject(MAVEN_PROJECT);
        deleteProject(CLEAN_PROJECT);
    }

    // ---- fixture helpers ----

    private static void createMavenProject(String name,
            String artifactId, String pkg,
            String[] sources) throws Exception {
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(name);
        if (project.exists()) {
            project.delete(true, true, null);
        }

        project.create(null);
        project.open(null);

        IProjectDescription desc = project.getDescription();
        desc.setNatureIds(new String[]{
                JavaCore.NATURE_ID,
                "org.eclipse.m2e.core.maven2Nature"});
        project.setDescription(desc, null);

        IFile pom = project.getFile("pom.xml");
        pom.create(new ByteArrayInputStream((
                "<project xmlns=\"http://maven.apache.org"
                + "/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId>"
                + "<artifactId>" + artifactId
                + "</artifactId>"
                + "<version>0.0.1</version>"
                + "</project>")
                .getBytes()), true, null);

        IJavaProject jp = JavaCore.create(project);
        IFolder src = project.getFolder("src");
        src.create(true, true, null);
        jp.setRawClasspath(new IClasspathEntry[]{
                JavaCore.newSourceEntry(src.getFullPath()),
                JavaCore.newContainerEntry(new Path(
                        "org.eclipse.jdt.launching"
                        + ".JRE_CONTAINER"))
        }, null);

        IPackageFragmentRoot srcRoot =
                jp.getPackageFragmentRoot(src);
        IPackageFragment pkgFrag =
                srcRoot.createPackageFragment(
                        pkg, true, null);
        for (int i = 0; i < sources.length; i += 2) {
            pkgFrag.createCompilationUnit(
                    sources[i], sources[i + 1], true, null);
        }

        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, null);
    }

    private static void deleteProject(String name)
            throws Exception {
        IProject p = ResourcesPlugin.getWorkspace()
                .getRoot().getProject(name);
        if (p.exists()) p.delete(true, true, null);
    }

    // ---- test helpers ----

    private JsonObject update(Map<String, String> params)
            throws Exception {
        return JsonParser.parseString(
                handler.handleUpdate(params))
                .getAsJsonObject();
    }

    private Map<String, String> params(String... kvs) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < kvs.length; i += 2) {
            m.put(kvs[i], kvs[i + 1]);
        }
        return m;
    }

    // ---- update single project ----

    @Test
    public void updateReturnsOk() throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", ""));
        assertTrue(r.get("ok").getAsBoolean(),
                "Should be ok: " + r);
    }

    @Test
    public void updateReturnsProjectCount() throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", ""));
        assertEquals(1, r.get("updated").getAsInt());
    }

    @Test
    public void updateReturnsProjectName() throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", ""));
        var projects = r.getAsJsonArray("projects");
        assertNotNull(projects, "Should have projects array");
        assertEquals(1, projects.size());
        assertEquals(MAVEN_PROJECT,
                projects.get(0).getAsString());
    }

    @Test
    public void updateWithoutWaitHasNoErrorField()
            throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", ""));
        assertFalse(r.has("errors"),
                "Without wait should not have errors: " + r);
    }

    // ---- wait + error detection ----

    @Test
    public void waitFindsCompilationErrors() throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", "", "wait", ""));
        assertTrue(r.has("errors"),
                "With wait should have errors: " + r);
        int errors = r.get("errors").getAsInt();
        assertTrue(errors > 0,
                "Broken.java has UnknownType — expect errors: "
                + errors);
    }

    @Test
    public void waitStillReportsOk() throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", "", "wait", ""));
        assertTrue(r.get("ok").getAsBoolean(),
                "Maven update ok even with compile errors: "
                + r);
    }

    @Test
    public void waitErrorCountMatchesJdtErrors()
            throws Exception {
        var r = update(params("project", MAVEN_PROJECT,
                "no-clean", "", "wait", ""));
        int fromUpdate = r.get("errors").getAsInt();

        IProject p = ResourcesPlugin.getWorkspace()
                .getRoot().getProject(MAVEN_PROJECT);
        int fromMarkers = JdtUtils.countErrors(p);

        assertEquals(fromMarkers, fromUpdate,
                "Error count should match JdtUtils.countErrors");
    }

    // ---- clean project (no errors) ----

    @Test
    public void cleanProjectHasZeroErrors() throws Exception {
        var r = update(params("project", CLEAN_PROJECT,
                "no-clean", "", "wait", ""));
        assertTrue(r.get("ok").getAsBoolean());
        assertEquals(0, r.get("errors").getAsInt(),
                "Clean project should have 0 errors");
        assertEquals(CLEAN_PROJECT,
                r.getAsJsonArray("projects")
                        .get(0).getAsString());
    }

    // ---- error paths ----

    @Test
    public void projectNotFound() throws Exception {
        var r = update(params("project", "nonexistent-xyz"));
        assertTrue(r.has("error"),
                "Should return error: " + r);
        assertTrue(r.get("error").getAsString()
                .contains("not found"),
                "Error should say not found: " + r);
    }

    @Test
    public void nonMavenProject() throws Exception {
        TestFixture.createNonJavaProject();
        var r = update(params("project",
                TestFixture.NON_JAVA_PROJECT_NAME));
        assertTrue(r.has("error"),
                "Non-maven should error: " + r);
        assertTrue(r.get("error").getAsString()
                .contains("Not a Maven"),
                "Error should say not maven: " + r);
    }

    // ---- all maven projects ----

    @Test
    public void updateAllMavenProjects() throws Exception {
        var r = update(params("no-clean", ""));
        assertTrue(r.get("ok").getAsBoolean());
        int updated = r.get("updated").getAsInt();
        assertTrue(updated >= 1,
                "Should update at least our test project: "
                + updated);
        var projects = r.getAsJsonArray("projects");
        var names = StreamSupport.stream(
                        projects.spliterator(), false)
                .map(e -> e.getAsString())
                .collect(Collectors.toSet());
        assertTrue(names.contains(MAVEN_PROJECT),
                "Should include test project: " + names);
    }
}
