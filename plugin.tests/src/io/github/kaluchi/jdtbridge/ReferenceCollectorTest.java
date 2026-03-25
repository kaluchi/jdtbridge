package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for {@link ReferenceCollector} — AST-based reference
 * resolution. Uses TestFixture classes (test.model, test.service).
 */
@EnabledIfSystemProperty(
        named = "jdtbridge.integration-tests",
        matches = "true")
public class ReferenceCollectorTest {

    @BeforeAll
    static void setUp() throws Exception { TestFixture.create(); }

    @AfterAll
    static void tearDown() throws Exception { TestFixture.destroy(); }

    @Nested
    class CollectFromMethod {

        @Test
        void findsReferencesInServiceMethod() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            assertNotNull(type, "AnimalService should exist");
            IMethod[] methods = type.getMethods();
            assertTrue(methods.length > 0,
                    "Should have methods");

            var refs = ReferenceCollector.collect(methods[0]);
            assertFalse(refs.isEmpty(),
                    "Should find references");
        }

        @Test
        void findsMethodCalls() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod[] methods = type.getMethods();

            // Collect from all methods
            var allRefs = new java.util.LinkedHashMap<
                    String, ReferenceCollector.Ref>();
            for (IMethod m : methods) {
                allRefs.putAll(ReferenceCollector.collect(m));
            }

            // Should find Dog or Animal references
            assertTrue(allRefs.values().stream()
                    .anyMatch(r -> r.fqmn().contains("Dog")
                            || r.fqmn().contains("Animal")),
                    "Should find Dog/Animal refs: "
                            + allRefs.keySet());
        }

        @Test
        void skipsJavaLang() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod[] methods = type.getMethods();

            var allRefs = new java.util.LinkedHashMap<
                    String, ReferenceCollector.Ref>();
            for (IMethod m : methods) {
                allRefs.putAll(ReferenceCollector.collect(m));
            }

            assertFalse(allRefs.containsKey("java.lang.String"),
                    "Should skip java.lang.String");
        }

        @Test
        void deduplicatesReferences() throws Exception {
            IType type = JdtUtils.findType(
                    "test.service.AnimalService");
            IMethod[] methods = type.getMethods();

            var refs = new java.util.LinkedHashMap<
                    String, ReferenceCollector.Ref>();
            for (IMethod m : methods) {
                refs.putAll(ReferenceCollector.collect(m));
            }

            // Each FQMN appears at most once
            long total = refs.size();
            long unique = refs.keySet().stream().distinct().count();
            assertTrue(total == unique,
                    "All refs should be unique");
        }

        @Test
        void collectFromType() throws Exception {
            IType type = JdtUtils.findType("test.model.Dog");
            assertNotNull(type);

            var refs = ReferenceCollector.collect(type);
            // Dog implements Animal — should reference it
            assertTrue(refs.values().stream()
                    .anyMatch(r -> r.fqmn().contains("Animal")),
                    "Dog should reference Animal: "
                            + refs.keySet());
        }
    }
}
