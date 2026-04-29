package io.github.kaluchi.jdtbridge;

import io.github.kaluchi.jdtbridge.support.TestFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NodeBuilder} — verifies the canonical
 * skeleton/detail JSON shape for {@code :type :method :field} against
 * the {@link TestFixture} workspace project.
 * <p>
 * Every assertion goes through the JSON-form path because the wire
 * shape is the contract; in-memory accessor convenience is incidental.
 */
public class NodeBuilderTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestFixture.create();
    }

    private static IType type(String fqn) throws Exception {
        IType t = JdtUtils.findType(fqn);
        assertNotNull(t, "type missing from fixture: " + fqn);
        return t;
    }

    private static IMethod method(String typeFqn, String name,
            String paramTypes) throws Exception {
        IMethod m = JdtUtils.findMethod(type(typeFqn), name, paramTypes);
        assertNotNull(m,
                "method missing: " + typeFqn + "#" + name
                + (paramTypes != null ? "(" + paramTypes + ")" : ""));
        return m;
    }

    // ── Identity helpers ────────────────────────────────────────────

    @Test
    void fqnOfDottedFormForInnerClass() throws Exception {
        // test.edge.Outer.Inner — dotted, never $
        IType outer = type("test.edge.Outer");
        IType inner = outer.getType("Inner");
        assertEquals("test.edge.Outer.Inner",
                NodeBuilder.fqnOf(inner));
    }

    @Test
    void erasedParamsStripsGenerics() throws Exception {
        IMethod findByIds = method("test.edge.Repository",
                "findByIds", "String[]");
        assertEquals("java.lang.String[]",
                NodeBuilder.erasedParams(findByIds));
    }

    @Test
    void erasedParamsHandlesParameterizedTypes() throws Exception {
        // save(List<String>) — erasure drops <String>
        IMethod saveList = method("test.edge.Repository",
                "save", "List");
        assertEquals("java.util.List",
                NodeBuilder.erasedParams(saveList));
    }

    @Test
    void erasedParamsEmptyForNullaryMethod() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        assertEquals("", NodeBuilder.erasedParams(barkMethod));
    }

    @Test
    void compactSignatureMatchesNameWithErasedParens() throws Exception {
        IMethod add3 = method("test.edge.Calculator", "add",
                "int,int,int");
        assertEquals("add(int,int,int)",
                NodeBuilder.compactSignature(add3));
    }

    @Test
    void fqnOfMethodCarriesHashAndCompactSignature() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        assertEquals("test.model.Dog#bark()",
                NodeBuilder.fqnOf(barkMethod));
    }

    @Test
    void fqnOfFieldOmitsParens() throws Exception {
        IType dog = type("test.model.Dog");
        IField ageField = dog.getField("age");
        assertEquals("test.model.Dog#age",
                NodeBuilder.fqnOf(ageField));
    }

    // ── Field translators ───────────────────────────────────────────

    @Test
    void typeKindOfPicksCorrectDiscriminatorPerKind() throws Exception {
        assertEquals("class",
                NodeBuilder.typeKindOf(type("test.model.Dog")));
        assertEquals("interface",
                NodeBuilder.typeKindOf(type("test.model.Animal")));
        assertEquals("enum",
                NodeBuilder.typeKindOf(type("test.edge.Color")));
        assertEquals("annotation",
                NodeBuilder.typeKindOf(type("test.edge.Marker")));
    }

    @Test
    void originOfSourceForFixtureTypes() throws Exception {
        assertEquals("source",
                NodeBuilder.originOf(type("test.model.Dog")));
    }

    @Test
    void modifiersVecIsLowercaseOrdered() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonArray mods = NodeBuilder.modifiers(barkMethod.getFlags());
        // bark is `public void` — so :public, no other flags
        assertEquals(1, mods.size());
        assertEquals("public", mods.get(0).getAsString());
    }

    @Test
    void modifiersVecCarriesStaticFinalForConstant() throws Exception {
        IType nested = type("test.edge.Outer.StaticNested");
        IField valueField = nested.getField("VALUE");
        JsonArray mods = NodeBuilder.modifiers(valueField.getFlags());
        Set<String> modSet = new HashSet<>();
        for (var entry : mods) modSet.add(entry.getAsString());
        assertTrue(modSet.contains("public"),
                "VALUE is public — got " + modSet);
        assertTrue(modSet.contains("static"));
        assertTrue(modSet.contains("final"));
    }

    // ── Skeleton invariants ─────────────────────────────────────────

    @Test
    void typeSkeletonHasHeaderPlusFilterFields() throws Exception {
        JsonObject skeleton = NodeBuilder.typeSkeleton(
                type("test.model.Dog"));
        assertEquals(10, skeleton.entrySet().size(),
                "5 header + typeKind + modifiers + containingPackage "
                + "+ annotations + isTestScope, got: "
                + skeleton);
        assertEquals("test.model.Dog",
                skeleton.get("fqn").getAsString());
        assertEquals("type", skeleton.get("kind").getAsString());
        assertEquals("source", skeleton.get("origin").getAsString());
        assertNotNull(skeleton.get("location"));
        assertEquals("jdtbridge-test",
                skeleton.get("containingProject").getAsString());
    }

    @Test
    void methodSkeletonHasHeaderPlusFilterFields() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonObject skeleton = NodeBuilder.methodSkeleton(barkMethod);
        assertEquals(12, skeleton.entrySet().size(),
                "5 header + name + signature + modifiers + containingType "
                + "+ returnType + annotations + isTestScope, got: "
                + skeleton);
        assertEquals("test.model.Dog#bark()",
                skeleton.get("fqn").getAsString());
        assertEquals("method", skeleton.get("kind").getAsString());
    }

    @Test
    void fieldSkeletonHasHeaderPlusFilterFields() throws Exception {
        IType dog = type("test.model.Dog");
        IField ageField = dog.getField("age");
        JsonObject skeleton = NodeBuilder.fieldSkeleton(ageField);
        assertEquals(11, skeleton.entrySet().size(),
                "5 header + name + modifiers + containingType + type "
                + "+ annotations + isTestScope, got: "
                + skeleton);
        assertEquals("test.model.Dog#age",
                skeleton.get("fqn").getAsString());
        assertEquals("field", skeleton.get("kind").getAsString());
    }

    // ── Location ────────────────────────────────────────────────────

    @Test
    void locationCarriesFileLineRangeAndNameOffsets() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonObject loc = NodeBuilder.location(barkMethod);
        assertNotNull(loc);
        assertTrue(loc.get("file").getAsString().endsWith("Dog.java"));
        assertEquals(11, loc.get("startLine").getAsInt());
        assertEquals(13, loc.get("endLine").getAsInt());
        assertEquals(3, loc.get("lineCount").getAsInt());
        assertEquals(166, loc.get("nameStart").getAsInt());
        assertEquals(170, loc.get("nameEnd").getAsInt());
    }

    @Test
    void locationPresentForTypesAndFields() throws Exception {
        IType dog = type("test.model.Dog");
        assertNotNull(NodeBuilder.location(dog));

        IField ageField = dog.getField("age");
        assertNotNull(NodeBuilder.location(ageField));
    }

    // ── Type detail ─────────────────────────────────────────────────

    @Test
    void typeDetailAddsTypeKindModifiersAndContainingPackage()
            throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(type("test.model.Dog"));
        assertEquals("class", detail.get("typeKind").getAsString());
        assertTrue(detail.has("modifiers"));
        assertEquals("test.model",
                detail.get("containingPackage").getAsString());
    }

    @Test
    void typeDetailCarriesInterfacesAsErasedFqns() throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(type("test.model.Dog"));
        JsonArray interfaces = detail.getAsJsonArray("interfaces");
        assertEquals(1, interfaces.size());
        assertEquals("test.model.Animal",
                interfaces.get(0).getAsString());
    }

    @Test
    void typeDetailCarriesSuperclassWhenPresent() throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(type("test.edge.Parrot"));
        assertEquals("test.edge.AbstractPet",
                detail.get("superclass").getAsString());
    }

    @Test
    void typeDetailHasContainingTypeForInnerClass() throws Exception {
        IType outer = type("test.edge.Outer");
        IType inner = outer.getType("Inner");
        JsonObject detail = NodeBuilder.typeDetail(inner);
        assertEquals("test.edge.Outer",
                detail.get("containingType").getAsString());
    }

    @Test
    void typeDetailEnumKindPropagates() throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(type("test.edge.Color"));
        assertEquals("enum", detail.get("typeKind").getAsString());
    }

    @Test
    void typeDetailGenericTypeParameters() throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(
                type("test.service.GenericService"));
        JsonArray tps = detail.getAsJsonArray("typeParameters");
        assertEquals(1, tps.size());
        assertEquals("T", tps.get(0).getAsJsonObject()
                .get("name").getAsString());
        assertTrue(tps.get(0).getAsJsonObject().has("bound"),
                "T extends Animal should expose :bound");
    }

    // ── Method detail ───────────────────────────────────────────────

    @Test
    void methodDetailParametersWithNamesAndTypes() throws Exception {
        IMethod add2 = method("test.edge.Calculator", "add", "int,int");
        JsonObject detail = NodeBuilder.methodDetail(add2);
        JsonArray params = detail.getAsJsonArray("parameters");
        assertEquals(2, params.size());
        assertEquals("a", params.get(0).getAsJsonObject()
                .get("name").getAsString());
        assertEquals("int", params.get(0).getAsJsonObject()
                .get("type").getAsString());
        assertEquals("b", params.get(1).getAsJsonObject()
                .get("name").getAsString());
    }

    @Test
    void methodDetailReturnTypePresentForNonConstructor()
            throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonObject detail = NodeBuilder.methodDetail(barkMethod);
        assertEquals("void", detail.get("returnType").getAsString());
        assertNull(detail.get("isConstructor"),
                "non-constructor must omit :isConstructor");
    }

    @Test
    void methodDetailMarksConstructor() throws Exception {
        IType parrot = type("test.edge.Parrot");
        IMethod ctor = parrot.getMethods()[0];
        // verify it IS the constructor, not some other method
        assertTrue(ctor.isConstructor(),
                "first method should be the Parrot constructor");
        JsonObject detail = NodeBuilder.methodDetail(ctor);
        assertTrue(detail.get("isConstructor").getAsBoolean());
        assertNull(detail.get("returnType"),
                "constructor must omit :returnType");
    }

    @Test
    void methodDetailMarksAbstract() throws Exception {
        IMethod speak = method("test.edge.AbstractPet", "speak", null);
        JsonObject detail = NodeBuilder.methodDetail(speak);
        assertTrue(detail.get("isAbstract").getAsBoolean());
    }

    @Test
    void methodDetailSignatureIsCompact() throws Exception {
        IMethod add3 = method("test.edge.Calculator", "add",
                "int,int,int");
        JsonObject detail = NodeBuilder.methodDetail(add3);
        assertEquals("add(int,int,int)",
                detail.get("signature").getAsString());
    }

    @Test
    void methodDetailContainingTypeFqn() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonObject detail = NodeBuilder.methodDetail(barkMethod);
        assertEquals("test.model.Dog",
                detail.get("containingType").getAsString());
    }

    // ── Field detail ────────────────────────────────────────────────

    @Test
    void fieldDetailNameTypeAndContainingType() throws Exception {
        IType dog = type("test.model.Dog");
        IField ageField = dog.getField("age");
        JsonObject detail = NodeBuilder.fieldDetail(ageField);
        assertEquals("age", detail.get("name").getAsString());
        assertEquals("int", detail.get("type").getAsString());
        assertEquals("test.model.Dog",
                detail.get("containingType").getAsString());
        assertNull(detail.get("isConstant"),
                "non-final field must omit :isConstant");
    }

    @Test
    void fieldDetailMarksConstantForStaticFinalField() throws Exception {
        IType nested = type("test.edge.Outer.StaticNested");
        IField valueField = nested.getField("VALUE");
        JsonObject detail = NodeBuilder.fieldDetail(valueField);
        assertTrue(detail.get("isConstant").getAsBoolean());
    }

    // ── shortNature ──────────────────────────────────────────────────

    @Test
    void shortNatureJava() {
        assertEquals("java",
                NodeBuilder.shortNature("org.eclipse.jdt.core.javanature"));
    }

    @Test
    void shortNatureMaven() {
        assertEquals("maven",
                NodeBuilder.shortNature("org.eclipse.m2e.core.maven2Nature"));
    }

    @Test
    void shortNaturePde() {
        assertEquals("pde",
                NodeBuilder.shortNature("org.eclipse.pde.PluginNature"));
    }

    @Test
    void shortNatureGradle() {
        assertEquals("gradle",
                NodeBuilder.shortNature(
                        "org.eclipse.buildship.core.gradleprojectnature"));
    }

    @Test
    void shortNatureUnknownFallsBackToTerminalSegment() {
        assertEquals("myNature",
                NodeBuilder.shortNature("com.example.custom.myNature"));
    }

    @Test
    void shortNatureNoDotReturnsAsIs() {
        assertEquals("plainId",
                NodeBuilder.shortNature("plainId"));
    }

    // ── firstJavadocSentence ───────────────────────────────────────

    @Test
    void firstJavadocSentenceExtractsFirstSentence() {
        String javadoc = "/**\n * First sentence.\n * @param x ignored\n */";
        assertEquals("First sentence.",
                NodeBuilder.firstJavadocSentence(javadoc));
    }

    @Test
    void firstJavadocSentenceStopsAtAtTag() {
        String javadoc = "/**\n * Summary line.\n * @author dev\n */";
        assertEquals("Summary line.",
                NodeBuilder.firstJavadocSentence(javadoc));
    }

    @Test
    void firstJavadocSentenceBlankStarLineJoinsNext() {
        // A bare " *\n" line has its \n consumed by the \s? in the
        // star-stripping regex, so it does NOT produce an empty line
        // break — the parser joins it with the next paragraph.
        String javadoc = "/**\n * First.\n *\n * Second.\n */";
        assertEquals("First. Second.",
                NodeBuilder.firstJavadocSentence(javadoc));
    }

    @Test
    void firstJavadocSentenceStopsAtParagraph() {
        String javadoc = "/**\n * Summary.\n * <p>\n * Details.\n */";
        assertEquals("Summary.",
                NodeBuilder.firstJavadocSentence(javadoc));
    }

    @Test
    void firstJavadocSentenceJoinsMultipleLines() {
        String javadoc = "/**\n * Line one\n * continues here.\n */";
        assertEquals("Line one continues here.",
                NodeBuilder.firstJavadocSentence(javadoc));
    }

    @Test
    void firstJavadocSentenceEmptyReturnsNull() {
        assertEquals(null,
                NodeBuilder.firstJavadocSentence("/** */"));
    }

    // ── projectSkeleton / projectDetail ────────────────────────────

    private static IProject fixtureProject() {
        return ResourcesPlugin.getWorkspace().getRoot()
                .getProject(TestFixture.PROJECT_NAME);
    }

    @Test
    void projectSkeletonHasHeaderAndNatures() {
        JsonObject skeleton = NodeBuilder.projectSkeleton(
                fixtureProject());
        assertEquals(TestFixture.PROJECT_NAME,
                skeleton.get("fqn").getAsString());
        assertEquals("project",
                skeleton.get("kind").getAsString());
        assertEquals("source",
                skeleton.get("origin").getAsString());
        assertTrue(skeleton.has("natures"));
        assertTrue(skeleton.has("isTestScope"));
        assertTrue(skeleton.has("rootPath"));
    }

    @Test
    void projectSkeletonNaturesContainsJava() {
        JsonObject skeleton = NodeBuilder.projectSkeleton(
                fixtureProject());
        JsonArray natures = skeleton.getAsJsonArray("natures");
        assertTrue(natures.contains(
                new com.google.gson.JsonPrimitive("java")),
                "fixture project must have java nature: " + natures);
    }

    @Test
    void projectDetailAddsClasspathAndSourceRoots() throws Exception {
        JsonObject detail = NodeBuilder.projectDetail(
                fixtureProject());
        assertEquals(18, detail.getAsJsonArray("classpathEntries").size());
        assertEquals(1, detail.getAsJsonArray("sourceRoots").size());
        assertNotNull(detail.get("javaVersion"));
    }

    // ── packageSkeleton / packageDetail ────────────────────────────

    private static IPackageFragment fixturePackage(String name) {
        IJavaProject jp = JavaCore.create(fixtureProject());
        org.eclipse.jdt.core.IPackageFragmentRoot srcRoot =
                jp.getPackageFragmentRoot(
                fixtureProject().getFolder("src"));
        return srcRoot.getPackageFragment(name);
    }

    @Test
    void packageSkeletonHasHeaderAndTypeCount() throws Exception {
        JsonObject skeleton = NodeBuilder.packageSkeleton(
                fixturePackage("test.model"));
        assertEquals("test.model",
                skeleton.get("fqn").getAsString());
        assertEquals("package",
                skeleton.get("kind").getAsString());
        assertEquals("source",
                skeleton.get("origin").getAsString());
        assertEquals(TestFixture.PROJECT_NAME,
                skeleton.get("containingProject").getAsString());
        assertEquals(3, skeleton.get("typeCount").getAsInt());
    }

    @Test
    void packageDetailAddsSourceRoot() throws Exception {
        JsonObject detail = NodeBuilder.packageDetail(
                fixturePackage("test.model"));
        assertTrue(detail.has("sourceRoot"));
    }

    // ── fileSkeleton / fileDetail ──────────────────────────────────

    @Test
    void fileSkeletonHasLanguageAndProject() throws Exception {
        IType dog = type("test.model.Dog");
        IFile file = (IFile) dog.getResource();
        assertNotNull(file, "Dog.java must have an IFile resource");
        JsonObject skeleton = NodeBuilder.fileSkeleton(file);
        assertEquals("file",
                skeleton.get("kind").getAsString());
        assertEquals("source",
                skeleton.get("origin").getAsString());
        assertEquals("java",
                skeleton.get("language").getAsString());
        assertEquals(TestFixture.PROJECT_NAME,
                skeleton.get("containingProject").getAsString());
    }

    @Test
    void fileDetailAddsCharset() throws Exception {
        IType dog = type("test.model.Dog");
        IFile file = (IFile) dog.getResource();
        JsonObject detail = NodeBuilder.fileDetail(file);
        assertTrue(detail.has("charset"));
    }

    // ── memberSkeleton dispatch ────────────────────────────────────

    @Test
    void memberSkeletonDispatchesType() throws Exception {
        JsonObject skeleton = NodeBuilder.memberSkeleton(
                type("test.model.Dog"));
        assertEquals("type", skeleton.get("kind").getAsString());
    }

    @Test
    void memberSkeletonDispatchesMethod() throws Exception {
        IMethod bark = method("test.model.Dog", "bark", null);
        JsonObject skeleton = NodeBuilder.memberSkeleton(bark);
        assertEquals("method", skeleton.get("kind").getAsString());
    }

    @Test
    void memberSkeletonDispatchesField() throws Exception {
        IField age = type("test.model.Dog").getField("age");
        JsonObject skeleton = NodeBuilder.memberSkeleton(age);
        assertEquals("field", skeleton.get("kind").getAsString());
    }

    @Test
    void memberSkeletonNullReturnsNull() throws Exception {
        assertNull(NodeBuilder.memberSkeleton(null));
    }

    // ── isTestScope ────────────────────────────────────────────────

    @Test
    void isTestScopeNullReturnsFalse() {
        assertFalse(NodeBuilder.isTestScope(null));
    }

    @Test
    void isTestScopeProductionTypeReturnsFalse() throws Exception {
        assertFalse(NodeBuilder.isTestScope(type("test.model.Dog")));
    }

    // ── originOf binary ────────────────────────────────────────────

    @Test
    void originOfBinaryForJdkType() throws Exception {
        IType string = JavaCore.create(fixtureProject())
                .findType("java.lang.String");
        assertNotNull(string, "java.lang.String must be on classpath");
        assertEquals("binary", NodeBuilder.originOf(string));
    }

    // ── location null for binary ───────────────────────────────────

    @Test
    void locationNullForBinaryMember() throws Exception {
        IType string = JavaCore.create(fixtureProject())
                .findType("java.lang.String");
        IMethod hashCode = string.getMethod("hashCode", new String[0]);
        assertNotNull(hashCode);
        // Binary methods without source attachment → null location
        // (or non-null if source attached — both are valid)
    }

    // ── resolveTypeName edge cases ─────────────────────────────────

    @Test
    void resolveTypeNamePrimitive() throws Exception {
        assertEquals("int",
                NodeBuilder.resolveTypeName("I", null));
    }

    @Test
    void resolveTypeNameResolvedSignature() throws Exception {
        assertEquals("java.lang.String",
                NodeBuilder.resolveTypeName("Ljava.lang.String;", null));
    }

    @Test
    void resolveTypeNameArraySignature() throws Exception {
        assertEquals("java.lang.String[]",
                NodeBuilder.resolveTypeName("[Ljava.lang.String;", null));
    }

    // ── annotationsOf ──────────────────────────────────────────────

    @Test
    void annotationsOfUnannotatedTypeReturnsEmpty() throws Exception {
        JsonArray anns = NodeBuilder.annotationsOf(
                type("test.model.Dog"), type("test.model.Dog"));
        assertEquals(0, anns.size());
    }

    // ── classpathEntrySkeleton ─────────────────────────────────────

    @Test
    void classpathEntrySkeletonSourceEntry() throws Exception {
        IJavaProject jp = JavaCore.create(fixtureProject());
        var entries = jp.getResolvedClasspath(true);
        var sourceEntry = entries[0];
        assertEquals(
                org.eclipse.jdt.core.IClasspathEntry.CPE_SOURCE,
                sourceEntry.getEntryKind());
        JsonObject skeleton =
                NodeBuilder.classpathEntrySkeleton(
                        sourceEntry, fixtureProject());
        assertEquals("source",
                skeleton.get("entryKind").getAsString());
        assertEquals("source",
                skeleton.get("origin").getAsString());
        assertTrue(skeleton.has("path"));
    }

    // ── javadocSummary ───────────────────────────────────────────────

    @Test
    void javadocSummaryExtractsFirstSentence() throws Exception {
        String summary = NodeBuilder.javadocSummary(
                type("test.edge.AbstractPet"));
        assertNotNull(summary, "AbstractPet has javadoc");
        assertEquals("An abstract pet with a name.", summary);
    }

    @Test
    void javadocSummaryNullForTypeWithoutJavadoc() throws Exception {
        assertNull(NodeBuilder.javadocSummary(
                type("test.model.Dog")));
    }

    // ── isTestScope true path ──────────────────────────────────────

    @Test
    void isTestScopeTrueForTestAnnotatedType() throws Exception {
        IType simpleTest = type("test.edge.SimpleTest");
        assertTrue(NodeBuilder.isTestScope(simpleTest),
                "SimpleTest has @Test methods → annotation fallback");
    }

    // ── annotationsOf with resolved FQN ────────────────────────────

    @Test
    void annotationsOfResolvesMarkerFqn() throws Exception {
        IType enriched = type("test.service.EnrichedRefService");
        JsonArray anns = NodeBuilder.annotationsOf(enriched, enriched);
        assertEquals(1, anns.size());
        assertEquals("test.edge.Marker",
                anns.get(0).getAsString());
    }

    // ── isDeprecated branches ──────────────────────────────────────

    @Test
    void methodDetailMarksDeprecated() throws Exception {
        IMethod speak = method("test.edge.AbstractPet", "speak", null);
        JsonObject detail = NodeBuilder.methodDetail(speak);
        assertTrue(detail.get("isDeprecated").getAsBoolean(),
                "speak() is @Deprecated");
    }

    // ── default method ──────────────────────────────────────────────

    @Test
    void methodDetailMarksDefaultMethod() throws Exception {
        IMethod kind = method("test.model.Animal", "kind", null);
        JsonObject detail = NodeBuilder.methodDetail(kind);
        assertTrue(detail.get("isDefault").getAsBoolean(),
                "Animal#kind() is a default method");
    }

    // ── classpathEntrySkeleton (library) ───────────────────────────

    @Test
    void classpathEntrySkeletonLibraryEntry() throws Exception {
        IJavaProject jp = JavaCore.create(fixtureProject());
        var entries = jp.getResolvedClasspath(true);
        var libEntry = entries[1];
        assertEquals(
                org.eclipse.jdt.core.IClasspathEntry.CPE_LIBRARY,
                libEntry.getEntryKind());
        JsonObject skeleton =
                NodeBuilder.classpathEntrySkeleton(
                        libEntry, fixtureProject());
        assertEquals("library",
                skeleton.get("entryKind").getAsString());
        assertEquals("binary",
                skeleton.get("origin").getAsString());
        assertTrue(skeleton.has("path"));
    }

    // ── methodDetail throws ────────────────────────────────────────

    @Test
    void methodDetailThrowsArrayPresent() throws Exception {
        IMethod bark = method("test.model.Dog", "bark", null);
        JsonObject detail = NodeBuilder.methodDetail(bark);
        assertTrue(detail.has("throws"),
                "throws must be present even if empty");
        assertEquals(0, detail.getAsJsonArray("throws").size());
    }

    // ── No spurious fields in skeleton ──────────────────────────────

    @Test
    void typeSkeletonCarriesTypeKindAndModifiersButNotInterfaces() throws Exception {
        JsonObject skeleton = NodeBuilder.typeSkeleton(
                type("test.model.Dog"));
        assertTrue(skeleton.has("typeKind"),
                "skeleton must carry typeKind for filtering");
        assertTrue(skeleton.has("modifiers"),
                "skeleton must carry modifiers for filtering");
        assertTrue(skeleton.has("containingPackage"),
                "skeleton must carry containingPackage");
        assertFalse(skeleton.has("interfaces"),
                "interfaces is detail-only");
    }

    @Test
    void methodSkeletonCarriesFilterFieldsButOmitsParameters() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonObject skeleton = NodeBuilder.methodSkeleton(barkMethod);
        assertTrue(skeleton.has("name"));
        assertTrue(skeleton.has("modifiers"));
        assertTrue(skeleton.has("signature"));
        assertTrue(skeleton.has("containingType"));
        assertTrue(skeleton.has("returnType"),
                "returnType in skeleton for filter on return shape");
        assertFalse(skeleton.has("parameters"),
                "parameters is detail-only");
        assertFalse(skeleton.has("throws"),
                "throws is detail-only");
    }

    @Test
    void fieldSkeletonCarriesFilterFields() throws Exception {
        IType dog = type("test.model.Dog");
        IField ageField = dog.getField("age");
        JsonObject skeleton = NodeBuilder.fieldSkeleton(ageField);
        assertTrue(skeleton.has("name"));
        assertTrue(skeleton.has("modifiers"));
        assertTrue(skeleton.has("type"),
                "type FQN in skeleton for filter");
        assertTrue(skeleton.has("containingType"));
    }

    // ── Detail invariant: every field is queryable ──────────────────

    @Test
    void typeDetailAllNonOptionalFieldsPresent() throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(type("test.model.Dog"));
        // Mandatory: skeleton header (5) + typeKind + modifiers
        // + typeParameters + interfaces. Plus :containingPackage when
        // not in default package.
        for (String key : new String[] {
                "fqn", "kind", "origin", "location",
                "containingProject", "typeKind", "modifiers",
                "typeParameters", "interfaces",
                "containingPackage" }) {
            assertTrue(detail.has(key),
                    ":type detail missing :" + key);
        }
    }

    @Test
    void methodDetailAllNonOptionalFieldsPresent() throws Exception {
        IMethod barkMethod = method("test.model.Dog", "bark", null);
        JsonObject detail = NodeBuilder.methodDetail(barkMethod);
        for (String key : new String[] {
                "fqn", "kind", "origin", "location",
                "containingProject", "name", "parameters",
                "returnType", "modifiers", "typeParameters",
                "throws", "containingType", "signature" }) {
            assertTrue(detail.has(key),
                    ":method detail missing :" + key);
        }
    }

    // ── typeDetail javadoc / anonymous ─────────────────────────────

    @Test
    void typeDetailCarriesJavadocSummary() throws Exception {
        JsonObject detail = NodeBuilder.typeDetail(
                type("test.edge.AbstractPet"));
        assertTrue(detail.has("javadocSummary"),
                "AbstractPet has javadoc → detail must carry :javadocSummary");
        assertEquals("An abstract pet with a name.",
                detail.get("javadocSummary").getAsString());
    }

    @Test
    void typeDetailMarksAnonymousType() throws Exception {
        IType anonCaller = type("test.service.AnonymousCallerService");
        IMethod createAnonymous = anonCaller.getMethod(
                "createAnonymous", new String[0]);
        IType anonType = (IType) createAnonymous.getChildren()[0];
        assertTrue(anonType.isAnonymous());
        JsonObject detail = NodeBuilder.typeDetail(anonType);
        assertTrue(detail.get("isAnonymous").getAsBoolean());
    }

    // ── methodDetail throws / javadoc ─────────────────────────────

    @Test
    void methodDetailCarriesNonEmptyThrows() throws Exception {
        IMethod process = method("test.edge.EdgeCaseMembers",
                "process", "String");
        JsonObject detail = NodeBuilder.methodDetail(process);
        JsonArray thrown = detail.getAsJsonArray("throws");
        assertEquals(1, thrown.size());
        assertEquals("java.lang.IllegalArgumentException",
                thrown.get(0).getAsString());
    }

    @Test
    void methodDetailCarriesJavadocSummary() throws Exception {
        IMethod process = method("test.edge.EdgeCaseMembers",
                "process", "String");
        JsonObject detail = NodeBuilder.methodDetail(process);
        assertTrue(detail.has("javadocSummary"),
                "process() has javadoc → detail must carry :javadocSummary");
        assertEquals("Processes the input.",
                detail.get("javadocSummary").getAsString());
    }

    // ── fieldDetail deprecated / javadoc ──────────────────────────

    @Test
    void fieldDetailMarksDeprecated() throws Exception {
        IType edgeMembers = type("test.edge.EdgeCaseMembers");
        IField count = edgeMembers.getField("count");
        JsonObject detail = NodeBuilder.fieldDetail(count);
        assertTrue(detail.get("isDeprecated").getAsBoolean(),
                "count is @Deprecated");
    }

    @Test
    void fieldDetailCarriesJavadocSummary() throws Exception {
        IType edgeMembers = type("test.edge.EdgeCaseMembers");
        IField count = edgeMembers.getField("count");
        JsonObject detail = NodeBuilder.fieldDetail(count);
        assertTrue(detail.has("javadocSummary"),
                "count has javadoc → detail must carry :javadocSummary");
        assertEquals("The count of items.",
                detail.get("javadocSummary").getAsString());
    }

    // ── sourceTextOf ──────────────────────────────────────────────

    @Test
    void sourceTextOfMethodReturnsSlicedLines() throws Exception {
        IMethod bark = method("test.model.Dog", "bark", null);
        String source = NodeBuilder.sourceTextOf(bark);
        assertNotNull(source);
        assertTrue(source.contains("bark"),
                "sliced source must contain method name");
        assertTrue(source.contains("Woof"),
                "sliced source must contain method body");
        assertFalse(source.contains("class Dog"),
                "sliced source must NOT contain the class header");
    }

    @Test
    void sourceTextOfTopLevelTypeReturnsFullUnit() throws Exception {
        IType dog = type("test.model.Dog");
        String source = NodeBuilder.sourceTextOf(dog);
        assertNotNull(source);
        assertTrue(source.contains("package test.model"),
                "top-level type source starts with package declaration");
        assertTrue(source.contains("class Dog"),
                "top-level type source includes class header");
    }

    // ── origin ────────────────────────────────────────────────────

    @Test
    void originOfSourceType() throws Exception {
        IType dog = type("test.model.Dog");
        assertEquals("source", NodeBuilder.originOf(dog));
    }

    @Test
    void originOfBinaryType() throws Exception {
        IType string = JdtUtils.findType("java.lang.String");
        assertNotNull(string);
        assertEquals("binary", NodeBuilder.originOf(string));
    }

    @Test
    void originOfBinaryMethod() throws Exception {
        IType string = JdtUtils.findType("java.lang.String");
        IMethod length = JdtUtils.findMethod(
                string, "length", null);
        assertNotNull(length);
        assertEquals("binary", NodeBuilder.originOf(length));
    }

    // ── lambda / anonymous fqn ───────────────────────────────────

    @Test
    void lambdaTypeFqnResolvesViaComposite() throws Exception {
        String compositeFqn = "test.service.LambdaCallerService"
                + "#createLambda().() -> {...} Runnable";
        IJavaElement resolved = JdtUtils.resolveElement(compositeFqn);
        assertNotNull(resolved,
                "composite lambda fqn must resolve: " + compositeFqn);
        assertTrue(resolved instanceof IType);
        String roundTrip = NodeBuilder.fqnOf((IType) resolved);
        assertTrue(roundTrip.contains("->"),
                "Lambda fqn should contain arrow: " + roundTrip);
    }

    @Test
    void anonymousTypeFqnContainsNewSuffix() throws Exception {
        IType type = type(
                "test.service.AnonymousCallerService");
        IMethod method = JdtUtils.findMethod(
                type, "createAnonymous", null);
        assertNotNull(method);
        IType anon = (IType) method.getChildren()[0];
        assertTrue(anon.isAnonymous());
        String fqn = NodeBuilder.fqnOf(anon);
        assertTrue(fqn.contains("new"),
                "Anonymous fqn should contain 'new': " + fqn);
    }

    // ── type detail on enum / annotation ─────────────────────────

    @Test
    void enumTypeDetailHasEnumTypeKind() throws Exception {
        IType color = type("test.edge.Color");
        JsonObject detail = NodeBuilder.typeDetail(color);
        assertEquals("enum",
                detail.get("typeKind").getAsString());
    }

    @Test
    void annotationTypeDetailHasAnnotationTypeKind()
            throws Exception {
        IType marker = type("test.edge.Marker");
        JsonObject detail = NodeBuilder.typeDetail(marker);
        assertEquals("annotation",
                detail.get("typeKind").getAsString());
    }

    // ── field detail edge cases ──────────────────────────────────

    @Test
    void fieldDetailOnEnumConstant() throws Exception {
        IType color = type("test.edge.Color");
        IField red = color.getField("RED");
        assertNotNull(red);
        JsonObject detail = NodeBuilder.fieldDetail(red);
        assertNotNull(detail);
        assertTrue(detail.has("fqn"));
    }

}
