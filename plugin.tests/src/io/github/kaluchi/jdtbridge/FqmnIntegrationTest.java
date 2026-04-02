package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for FQMN (Fully Qualified Method Name) support.
 * Tests paramTypes-based method resolution against real JDT types.
 */
public class FqmnIntegrationTest {

    private static final SearchHandler handler = new SearchHandler();

    @BeforeAll
    public static void setUp() throws Exception {
        TestFixture.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestFixture.destroy();
    }

    // ---- findMethod with paramTypes ----

    @Nested
    class FindMethodByParamTypes {

        @Test
        void findByExactPrimitiveParams() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            assertNotNull(type);

            IMethod m = JdtUtils.findMethod(type, "add", "int,int");
            assertNotNull(m, "Should find add(int,int)");
            assertEquals(2, m.getNumberOfParameters());
        }

        @Test
        void findByExactDoubleParams() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            IMethod m = JdtUtils.findMethod(
                    type, "add", "double,double");
            assertNotNull(m, "Should find add(double,double)");
            assertEquals(2, m.getNumberOfParameters());
        }

        @Test
        void findThreeArgOverload() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            IMethod m = JdtUtils.findMethod(
                    type, "add", "int,int,int");
            assertNotNull(m, "Should find add(int,int,int)");
            assertEquals(3, m.getNumberOfParameters());
        }

        @Test
        void nullParamTypesReturnsFirst() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            IMethod m = JdtUtils.findMethod(type, "add", null);
            assertNotNull(m, "Should find first add()");
        }

        @Test
        void emptyParamTypesMatchesZeroArg() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            IMethod m = JdtUtils.findMethod(type, "bark", "");
            assertNotNull(m, "Should find bark()");
            assertEquals(0, m.getNumberOfParameters());
        }

        @Test
        void wrongParamTypesReturnsNull() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            IMethod m = JdtUtils.findMethod(
                    type, "add", "String,String");
            assertNull(m, "No add(String,String) exists");
        }

        @Test
        void wrongArityReturnsNull() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            IMethod m = JdtUtils.findMethod(type, "add", "int");
            assertNull(m, "No add(int) exists");
        }

        @Test
        void findWithSimpleNameVsFqn() throws Exception {
            IType type = JdtUtils.findType("test.edge.Repository");
            // JDT stores as unresolved "QString;", Signature.toString
            // gives "String". User provides "String" — should match.
            IMethod m = JdtUtils.findMethod(type, "save", "String");
            assertNotNull(m, "Should find save(String)");
            assertEquals(1, m.getNumberOfParameters());
        }

        @Test
        void findWithFqnParam() throws Exception {
            IType type = JdtUtils.findType("test.edge.Repository");
            // User provides FQN — should still match simple name
            IMethod m = JdtUtils.findMethod(
                    type, "save", "java.lang.String");
            assertNotNull(m, "Should find save(java.lang.String)");
            assertEquals(1, m.getNumberOfParameters());
        }

        @Test
        void findWithTwoParams() throws Exception {
            IType type = JdtUtils.findType("test.edge.Repository");
            IMethod m = JdtUtils.findMethod(
                    type, "save", "String,int");
            assertNotNull(m, "Should find save(String,int)");
            assertEquals(2, m.getNumberOfParameters());
        }

        @Test
        void findWithGenericParam() throws Exception {
            IType type = JdtUtils.findType("test.edge.Repository");
            // List<String> → after erasure/strip → "List"
            IMethod m = JdtUtils.findMethod(type, "save", "List");
            assertNotNull(m, "Should find save(List)");
            assertEquals(1, m.getNumberOfParameters());
        }

        @Test
        void findWithArrayParam() throws Exception {
            IType type = JdtUtils.findType("test.edge.Repository");
            IMethod m = JdtUtils.findMethod(
                    type, "findByIds", "String[]");
            assertNotNull(m, "Should find findByIds(String[])");
        }
    }

    // ---- findMethods (plural) with paramTypes ----

    @Nested
    class FindMethodsByParamTypes {

        @Test
        void findAllOverloads() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            List<IMethod> methods = JdtUtils.findMethods(
                    type, "add", null);
            assertEquals(3, methods.size(),
                    "Should find all 3 overloads");
        }

        @Test
        void findSpecificOverload() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            List<IMethod> methods = JdtUtils.findMethods(
                    type, "add", "int,int");
            assertEquals(1, methods.size(),
                    "Should find exactly 1 overload");
        }

        @Test
        void noMatchReturnsEmpty() throws Exception {
            IType type = JdtUtils.findType("test.edge.Calculator");
            List<IMethod> methods = JdtUtils.findMethods(
                    type, "add", "String");
            assertTrue(methods.isEmpty(),
                    "Should find no overloads");
        }

        @Test
        void findAllSaveOverloads() throws Exception {
            IType type = JdtUtils.findType("test.edge.Repository");
            List<IMethod> methods = JdtUtils.findMethods(
                    type, "save", null);
            assertEquals(3, methods.size(),
                    "Should find all 3 save overloads");
        }
    }

    // ---- Handler integration (source, references) with paramTypes ----

    @Nested
    class HandlerWithParamTypes {

        @Test
        void sourceWithParamTypes() throws Exception {
            HttpServer.Response resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add",
                            "paramTypes", "double,double"), ProjectScope.ALL);
            assertTrue(resp.body().contains("double a"),
                    "Should contain double param: " + resp.body());
            assertFalse(resp.body().contains("int a"),
                    "Should NOT contain int overload: "
                            + resp.body());
        }

        @Test
        void sourceAllOverloads() throws Exception {
            HttpServer.Response resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add"), ProjectScope.ALL);
            // Should return JSON array with multiple overloads
            assertEquals("application/json", resp.contentType());
            assertTrue(resp.body().startsWith("["),
                    "Should be JSON array: " + resp.body());
        }

        @Test
        void sourceWithEmptyParamTypes() throws Exception {
            HttpServer.Response resp = handler.handleSource(
                    Map.of("class", "test.model.Dog",
                            "method", "bark",
                            "paramTypes", ""), ProjectScope.ALL);
            assertTrue(resp.body().contains("bark"),
                    "Should contain bark: " + resp.body());
        }

        @Test
        void sourceMethodNotFoundWithWrongParams() throws Exception {
            HttpServer.Response resp = handler.handleSource(
                    Map.of("class", "test.edge.Calculator",
                            "method", "add",
                            "paramTypes", "String,String"), ProjectScope.ALL);
            assertTrue(resp.body().contains("error"),
                    "Should be error: " + resp.body());
        }

        @Test
        void referencesWithParamTypes() throws Exception {
            // bark() has references in AnimalService
            String json = handler.handleReferences(
                    Map.of("class", "test.model.Dog",
                            "method", "bark",
                            "paramTypes", ""), ProjectScope.ALL);
            assertTrue(json.contains("AnimalService"),
                    "Should find ref: " + json);
        }

        @Test
        void referencesMethodNotFoundWithParams() throws Exception {
            String json = handler.handleReferences(
                    Map.of("class", "test.model.Dog",
                            "method", "bark",
                            "paramTypes", "int"), ProjectScope.ALL);
            assertTrue(json.contains("error"),
                    "Should be error: " + json);
        }

        @Test
        void sourceWithGenericParamStripped() throws Exception {
            HttpServer.Response resp = handler.handleSource(
                    Map.of("class", "test.edge.Repository",
                            "method", "save",
                            "paramTypes", "List"), ProjectScope.ALL);
            assertTrue(resp.body().contains("List<String>"),
                    "Should find save(List<String>): "
                            + resp.body());
        }
    }

    // ---- HTTP query parameter encoding ----

    @Nested
    class QueryParamEncoding {

        @Test
        void parseQueryWithParamTypes() {
            Map<String, String> params = HttpServer.parseQuery(
                    "class=test.edge.Calculator"
                            + "&method=add&paramTypes=int%2Cint");
            assertEquals("test.edge.Calculator",
                    params.get("class"));
            assertEquals("add", params.get("method"));
            assertEquals("int,int", params.get("paramTypes"));
        }

        @Test
        void parseQueryWithEmptyParamTypes() {
            Map<String, String> params = HttpServer.parseQuery(
                    "class=Foo&method=bar&paramTypes=");
            assertEquals("", params.get("paramTypes"));
        }

        @Test
        void parseQueryWithArrayParamTypes() {
            Map<String, String> params = HttpServer.parseQuery(
                    "class=Foo&method=bar&paramTypes="
                            + "String%5B%5D%2Cint");
            assertEquals("String[],int", params.get("paramTypes"));
        }

        @Test
        void parseQueryNoParamTypes() {
            Map<String, String> params = HttpServer.parseQuery(
                    "class=Foo&method=bar");
            assertNull(params.get("paramTypes"));
        }
    }
}
