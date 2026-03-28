package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for JdtUtils — type matching, param type parsing,
 * generics stripping, and simple name extraction.
 */
public class JdtUtilsTest {

    // ---- parseParamTypes ----

    @Nested
    class ParseParamTypes {

        @Test
        void nullReturnsNull() {
            assertNull(JdtUtils.parseParamTypes(null));
        }

        @Test
        void emptyStringReturnsEmptyArray() {
            assertArrayEquals(new String[0],
                    JdtUtils.parseParamTypes(""));
        }

        @Test
        void singleType() {
            assertArrayEquals(new String[]{"String"},
                    JdtUtils.parseParamTypes("String"));
        }

        @Test
        void multipleTypes() {
            assertArrayEquals(
                    new String[]{"String", "int", "boolean"},
                    JdtUtils.parseParamTypes("String,int,boolean"));
        }

        @Test
        void typesWithSpaces() {
            assertArrayEquals(
                    new String[]{"String", "int"},
                    JdtUtils.parseParamTypes("String, int"));
        }

        @Test
        void arrayType() {
            assertArrayEquals(
                    new String[]{"String[]", "int[]"},
                    JdtUtils.parseParamTypes("String[],int[]"));
        }

        @Test
        void fqnTypes() {
            assertArrayEquals(
                    new String[]{"java.lang.String",
                            "java.util.List"},
                    JdtUtils.parseParamTypes(
                            "java.lang.String,java.util.List"));
        }

        // ---- Generics-aware splitting ----

        @Test
        void simpleGenericNotSplit() {
            assertArrayEquals(
                    new String[]{"Map<String,String>"},
                    JdtUtils.parseParamTypes(
                            "Map<String,String>"));
        }

        @Test
        void genericFollowedByPrimitive() {
            assertArrayEquals(
                    new String[]{"Map<String,Integer>", "int"},
                    JdtUtils.parseParamTypes(
                            "Map<String,Integer>,int"));
        }

        @Test
        void nestedGenerics() {
            assertArrayEquals(
                    new String[]{
                            "Map<String,List<Integer>>", "int"},
                    JdtUtils.parseParamTypes(
                            "Map<String,List<Integer>>,int"));
        }

        @Test
        void multipleGenericParams() {
            assertArrayEquals(
                    new String[]{"List<String>",
                            "Map<String,Integer>", "int"},
                    JdtUtils.parseParamTypes(
                            "List<String>,Map<String,Integer>"
                            + ",int"));
        }

        @Test
        void genericWithSpaces() {
            assertArrayEquals(
                    new String[]{"Map<String, Integer>", "int"},
                    JdtUtils.parseParamTypes(
                            "Map<String, Integer>, int"));
        }

        @Test
        void noGenericsUnchanged() {
            assertArrayEquals(
                    new String[]{"String", "int", "boolean"},
                    JdtUtils.parseParamTypes(
                            "String,int,boolean"));
        }
    }

    // ---- typeMatches ----

    @Nested
    class TypeMatches {

        // Exact match
        @Test
        void exactSimpleNames() {
            assertTrue(JdtUtils.typeMatches("String", "String"));
        }

        @Test
        void exactFqnNames() {
            assertTrue(JdtUtils.typeMatches(
                    "java.lang.String", "java.lang.String"));
        }

        @Test
        void exactPrimitive() {
            assertTrue(JdtUtils.typeMatches("int", "int"));
        }

        @Test
        void exactArray() {
            assertTrue(JdtUtils.typeMatches("int[]", "int[]"));
        }

        @Test
        void exactObjectArray() {
            assertTrue(JdtUtils.typeMatches(
                    "String[]", "String[]"));
        }

        @Test
        void exact2dArray() {
            assertTrue(JdtUtils.typeMatches(
                    "int[][]", "int[][]"));
        }

        // Simple name vs FQN match
        @Test
        void simpleVsFqn() {
            assertTrue(JdtUtils.typeMatches(
                    "String", "java.lang.String"));
        }

        @Test
        void fqnVsSimple() {
            assertTrue(JdtUtils.typeMatches(
                    "java.lang.String", "String"));
        }

        @Test
        void simpleVsFqnArray() {
            assertTrue(JdtUtils.typeMatches(
                    "String[]", "java.lang.String[]"));
        }

        @Test
        void fqnVsSimpleArray() {
            assertTrue(JdtUtils.typeMatches(
                    "java.lang.String[]", "String[]"));
        }

        @Test
        void simpleVsFqnDeepPackage() {
            assertTrue(JdtUtils.typeMatches(
                    "Order", "com.example.dao.Order"));
        }

        @Test
        void fqnVsSimpleDeepPackage() {
            assertTrue(JdtUtils.typeMatches(
                    "com.example.dao.Order", "Order"));
        }

        // Generics stripping
        @Test
        void genericVsRaw() {
            assertTrue(JdtUtils.typeMatches(
                    "List<String>", "List"));
        }

        @Test
        void rawVsGeneric() {
            assertTrue(JdtUtils.typeMatches(
                    "List", "List<String>"));
        }

        @Test
        void genericVsGenericSame() {
            assertTrue(JdtUtils.typeMatches(
                    "List<String>", "List<String>"));
        }

        @Test
        void genericVsGenericDifferent() {
            // After stripping generics, both become List
            assertTrue(JdtUtils.typeMatches(
                    "List<String>", "List<Integer>"));
        }

        @Test
        void nestedGenericVsRaw() {
            assertTrue(JdtUtils.typeMatches(
                    "Map<String, List<Integer>>", "Map"));
        }

        @Test
        void genericFqnVsSimple() {
            assertTrue(JdtUtils.typeMatches(
                    "java.util.List<java.lang.String>", "List"));
        }

        @Test
        void genericArrayVsRawArray() {
            assertTrue(JdtUtils.typeMatches(
                    "List<String>[]", "List[]"));
        }

        // Non-matches
        @Test
        void differentSimpleNames() {
            assertFalse(JdtUtils.typeMatches("String", "Integer"));
        }

        @Test
        void differentPrimitives() {
            assertFalse(JdtUtils.typeMatches("int", "long"));
        }

        @Test
        void primitiveVsBoxed() {
            // int != Integer — these are different types
            assertFalse(JdtUtils.typeMatches("int", "Integer"));
        }

        @Test
        void arrayVsNonArray() {
            assertFalse(JdtUtils.typeMatches("String[]", "String"));
        }

        @Test
        void nonArrayVsArray() {
            assertFalse(JdtUtils.typeMatches("String", "String[]"));
        }

        @Test
        void differentArrayDimensions() {
            assertFalse(JdtUtils.typeMatches(
                    "int[]", "int[][]"));
        }

        @Test
        void partialPackageMatch() {
            // "util.List" simple name is "List", "List" simple name
            // is "List" — this should match
            assertTrue(JdtUtils.typeMatches("util.List", "List"));
        }

        @Test
        void ambiguousSimpleNamesDifferentPackages() {
            // Both simplify to "Order" — matches
            assertTrue(JdtUtils.typeMatches(
                    "com.example.Order",
                    "com.other.Order"));
        }
    }

    // ---- HttpServer.parseQuery ----
    // (we test parseQuery here because paramTypes come through it)

    @Nested
    class ParseQuery {

        @Test
        void paramTypesEmpty() {
            var params = HttpServer.parseQuery(
                    "class=Foo&method=bar&paramTypes=");
            assertEquals("", params.get("paramTypes"));
        }

        @Test
        void paramTypesSingle() {
            var params = HttpServer.parseQuery(
                    "class=Foo&method=bar&paramTypes=String");
            assertEquals("String", params.get("paramTypes"));
        }

        @Test
        void paramTypesMultipleEncoded() {
            // Commas are encoded by encodeURIComponent
            var params = HttpServer.parseQuery(
                    "class=Foo&method=bar&paramTypes="
                            + "String%2Cint%5B%5D");
            assertEquals("String,int[]", params.get("paramTypes"));
        }

        @Test
        void paramTypesWithSpaces() {
            var params = HttpServer.parseQuery(
                    "class=Foo&method=bar&paramTypes="
                            + "String%2C%20int");
            assertEquals("String, int", params.get("paramTypes"));
        }

        @Test
        void noParamTypes() {
            var params = HttpServer.parseQuery(
                    "class=Foo&method=bar");
            assertNull(params.get("paramTypes"));
        }
    }
}
