package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonObject;

/**
 * Pure JSON shape builders for the /editors endpoint. Decoupled
 * from the workbench / IDE APIs so the wire shape can be unit-tested
 * headlessly (no SWT, no PlatformUI).
 *
 * <p>The handler ({@link EditorHandler}) extracts the field values
 * from {@code IEditorInput} / {@code IFile} on the UI thread and
 * passes them in.
 */
final class EditorJson {

    private EditorJson() { }

    /**
     * Build a single editor-entry object.
     *
     * @param filePath    absolute filesystem path of the editor's file
     * @param projectName containing project name
     * @param fqn         primary type FQN if the file is a Java
     *                    compilation unit, otherwise {@code null}
     * @param isActive    {@code true} for the active editor (renders
     *                    {@code "active": true}); omitted otherwise
     */
    static JsonObject entry(String filePath, String projectName,
            String fqn, boolean isActive) {
        var obj = new JsonObject();
        obj.addProperty("file", filePath);
        obj.addProperty("project", projectName);
        if (isActive) obj.addProperty("active", true);
        if (fqn != null) obj.addProperty("fqn", fqn);
        return obj;
    }
}
