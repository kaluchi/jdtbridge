package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

class EditorHandler {

    String handleEditors(Map<String, String> params,
            ProjectScope scope) throws Exception {
        String[] result = {"[]"};
        Runnable query = () -> {
            try {
                IWorkbenchWindow window = PlatformUI
                        .getWorkbench()
                        .getActiveWorkbenchWindow();
                if (window == null
                        || window.getActivePage() == null) {
                    return;
                }

                IWorkbenchPage page =
                        window.getActivePage();
                IEditorReference[] refs =
                        page.getEditorReferences();
                IEditorPart active =
                        page.getActiveEditor();

                var arr = new JsonArray();
                if (active != null) {
                    addEditorEntry(
                            active.getEditorInput(), arr,
                            true, scope);
                }
                for (IEditorReference ref : refs) {
                    IEditorPart editor =
                            ref.getEditor(false);
                    if (editor != null && editor == active)
                        continue;
                    try {
                        addEditorEntry(
                                ref.getEditorInput(), arr,
                                false, scope);
                    } catch (Exception ignored) {
                    }
                }
                result[0] = arr.toString();
            } catch (Exception e) {
            }
        };
        Display display = Display.getCurrent();
        if (display != null) {
            query.run();
        } else {
            try {
                Display.getDefault().syncExec(query);
            } catch (Exception e) {
            }
        }
        return result[0];
    }

    private void addEditorEntry(
            org.eclipse.ui.IEditorInput input,
            JsonArray arr, boolean isActive,
            ProjectScope scope) {
        if (!(input instanceof IFileEditorInput fi)) return;
        IFile file = fi.getFile();
        if (file.getLocation() == null) return;
        if (!scope.containsProject(
                file.getProject().getName())) return;
        var obj = new JsonObject();
        obj.addProperty("file",
                file.getLocation().toOSString());
        obj.addProperty("project",
                file.getProject().getName());
        if (isActive) obj.addProperty("active", true);
        // FQN for Java files
        try {
            var javaElement = org.eclipse.jdt.core.JavaCore
                    .create(file);
            if (javaElement instanceof org.eclipse.jdt.core
                    .ICompilationUnit cu) {
                var types = cu.getTypes();
                if (types.length > 0) {
                    obj.addProperty("fqn",
                            types[0].getFullyQualifiedName());
                }
            }
        } catch (Exception ignored) { }
        arr.add(obj);
    }

    String handleOpen(Map<String, String> params)
            throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'class' parameter");
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
                var ok = new JsonObject();
                ok.addProperty("ok", true);
                result[0] = ok.toString();
            } catch (Exception e) {
                result[0] = HttpServer.jsonError(
                        e.getMessage());
            }
        });
        return result[0];
    }
}
