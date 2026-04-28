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

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
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
        assertNotNull(loc, "bark() must have a location in source fixture");
        assertTrue(loc.get("file").getAsString().endsWith("Dog.java"));
        assertTrue(loc.get("startLine").getAsInt() > 0);
        assertTrue(loc.get("endLine").getAsInt()
                >= loc.get("startLine").getAsInt());
        assertTrue(loc.has("nameStart"));
        assertTrue(loc.has("nameEnd"));
        assertTrue(loc.get("nameEnd").getAsInt()
                > loc.get("nameStart").getAsInt());
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
}
