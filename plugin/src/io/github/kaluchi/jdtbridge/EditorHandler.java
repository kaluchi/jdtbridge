package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;

import org.eclipse.core.resources.IFile;
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
}
