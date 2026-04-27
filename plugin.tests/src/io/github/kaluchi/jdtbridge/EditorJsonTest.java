package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

/** Pure-data tests for {@link EditorJson}. */
public class EditorJsonTest {

    @Test
    void inactiveJavaFileEmitsAllFields() {
        JsonObject obj = EditorJson.entry(
                "C:/ws/proj/src/p/Foo.java",
                "proj",
                "p.Foo",
                false);
        assertEquals("C:/ws/proj/src/p/Foo.java",
                obj.get("file").getAsString());
        assertEquals("proj", obj.get("project").getAsString());
        assertEquals("p.Foo", obj.get("fqn").getAsString());
        assertFalse(obj.has("active"),
                "inactive editor must not carry the active flag");
    }

    @Test
    void activeFlagOnlyPresentWhenTrue() {
        JsonObject obj = EditorJson.entry(
                "/ws/proj/src/p/Foo.java",
                "proj", "p.Foo", true);
        assertTrue(obj.get("active").getAsBoolean(),
                "active editor must render active=true");
    }

    @Test
    void nullFqnOmitsField() {
        JsonObject obj = EditorJson.entry(
                "/ws/proj/README.md", "proj", null, false);
        assertFalse(obj.has("fqn"),
                "non-Java file (null fqn) must not carry"
                + " the fqn property");
        assertEquals("proj",
                obj.get("project").getAsString());
        assertEquals("/ws/proj/README.md",
                obj.get("file").getAsString());
    }

    @Test
    void activeNonJavaFile() {
        JsonObject obj = EditorJson.entry(
                "/abs/pom.xml", "myproj", null, true);
        assertEquals("/abs/pom.xml",
                obj.get("file").getAsString());
        assertEquals("myproj",
                obj.get("project").getAsString());
        assertTrue(obj.get("active").getAsBoolean());
        assertFalse(obj.has("fqn"));
    }
}
