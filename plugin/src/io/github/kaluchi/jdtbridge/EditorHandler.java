package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Map;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;

class EditorHandler {

    String handleEditors(Map<String, String> params,
            ProjectScope scope) throws Exception {
        String[] result = {"[]"};
        Runnable query = () -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench()
                    .getActiveWorkbenchWindow();
            if (window == null
                    || window.getActivePage() == null) {
                return;
            }
            IWorkbenchPage page = window.getActivePage();
            IEditorPart active = page.getActiveEditor();
            JsonArray arr = new JsonArray();
            if (active != null) {
                appendEntry(active.getEditorInput(), arr,
                        true, scope);
            }
            for (IEditorReference ref
                    : page.getEditorReferences()) {
                IEditorPart editor = ref.getEditor(false);
                if (editor != null && editor == active) continue;
                appendEntry(inputOf(ref), arr, false, scope);
            }
            result[0] = arr.toString();
        };
        if (Display.getCurrent() != null) {
            query.run();
        } else {
            Display.getDefault().syncExec(query);
        }
        return result[0];
    }

    private static IEditorInput inputOf(IEditorReference ref) {
        try {
            return ref.getEditorInput();
        } catch (org.eclipse.ui.PartInitException e) {
            throw new IllegalStateException(
                    "Editor reference not initialised: " + ref, e);
        }
    }

    private static void appendEntry(IEditorInput input,
            JsonArray arr, boolean isActive,
            ProjectScope scope) {
        if (!(input instanceof IFileEditorInput fi)) return;
        IFile file = fi.getFile();
        if (file.getLocation() == null) return;
        String project = file.getProject().getName();
        if (!scope.containsProject(project)) return;
        arr.add(EditorJson.entry(
                file.getLocation().toOSString(),
                project,
                primaryTypeFqn(file),
                isActive));
    }

    /**
     * Primary type FQN if {@code file} is a compilation unit,
     * otherwise {@code null}. {@link JavaCore#create(IFile)} returns
     * {@code null} for non-Java files, so the cast is the gate.
     */
    private static String primaryTypeFqn(IFile file)
            throws RuntimeException {
        IJavaElement el = JavaCore.create(file);
        if (!(el instanceof ICompilationUnit cu)) return null;
        try {
            IType[] types = cu.getTypes();
            return types.length > 0
                    ? types[0].getFullyQualifiedName()
                    : null;
        } catch (JavaModelException e) {
            throw new RuntimeException(
                    "Could not read types of " + file, e);
        }
    }

    String handleOpen(Map<String, String> params)
            throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.missingParamError("class");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError(
                    "Type not found: " + fqn);
        }

        IJavaElement target = type;
        if (methodName != null && !methodName.isBlank()) {
            IMethod method = JdtUtils.findMethod(
                    type, methodName,
                    params.get("paramTypes"));
            if (method != null) {
                target = method;
            }
        }

        final IJavaElement element = target;
        String[] result = {HttpServer.jsonError(
                "Failed to open editor")};
        Display.getDefault().syncExec(() -> {
            try {
                IEditorPart editor =
                        JavaUI.openInEditor(element);
                if (editor != null) {
                    JavaUI.revealInEditor(editor, element);
                }
                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                result[0] = ok.toString();
            } catch (Exception e) {
                result[0] = HttpServer.jsonError(
                        e.getMessage());
            }
        });
        return result[0];
    }

    /**
     * Open an arbitrary filesystem path in Eclipse. Picks the editor
     * via {@link IDE#openEditor(IWorkbenchPage, IFile)} for workspace
     * resources (content-type + name/extension binding), or
     * {@link IDE#openEditorOnFileStore} via EFS for external files.
     * <p>
     * {@code path} must be absolute and host-native. CLI is expected
     * to translate from the agent-local form before sending.
     */
    String handleOpenFile(Map<String, String> params)
            throws Exception {
        String pathParam = params.get("path");
        if (pathParam == null || pathParam.isBlank()) {
            return HttpServer.missingParamError("path");
        }

        java.io.File f = new java.io.File(pathParam);
        if (!f.isAbsolute()) {
            return HttpServer.jsonError(
                    "Path must be absolute: " + pathParam);
        }

        URI uri = f.toURI();
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        IFile[] workspaceFiles =
                root.findFilesForLocationURI(uri);

        String[] result = {HttpServer.jsonError(
                "Failed to open editor")};
        Display.getDefault().syncExec(() -> {
            try {
                IWorkbenchWindow window = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow();
                if (window == null
                        || window.getActivePage() == null) {
                    result[0] = HttpServer.jsonError(
                            "No active workbench page");
                    return;
                }
                IWorkbenchPage page = window.getActivePage();

                IEditorPart editor;
                if (workspaceFiles.length > 0) {
                    editor = IDE.openEditor(
                            page, workspaceFiles[0]);
                } else {
                    IFileStore store = EFS.getStore(uri);
                    var info = store.fetchInfo();
                    if (!info.exists()) {
                        result[0] = HttpServer.jsonError(
                                "File not found: " + pathParam);
                        return;
                    }
                    if (info.isDirectory()) {
                        result[0] = HttpServer.jsonError(
                                "Path is a directory: "
                                + pathParam);
                        return;
                    }
                    editor = IDE.openEditorOnFileStore(
                            page, store);
                }

                JsonObject ok = new JsonObject();
                ok.addProperty("ok", true);
                if (editor != null) {
                    ok.addProperty("editorId",
                            editor.getSite().getId());
                }
                result[0] = ok.toString();
            } catch (Exception e) {
                String msg = e.getMessage();
                result[0] = HttpServer.jsonError(
                        msg != null ? msg : e.toString());
            }
        });
        return result[0];
    }
}
