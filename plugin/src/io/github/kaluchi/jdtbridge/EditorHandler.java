package io.github.kaluchi.jdtbridge;

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

/**
 * Handlers for Eclipse editor interaction: editors list, open.
 */
class EditorHandler {

    String handleEditors(Map<String, String> params) throws Exception {
        String[] result = {"[]"};
        Runnable query = () -> {
            try {
                IWorkbenchWindow window = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow();
                if (window == null
                        || window.getActivePage() == null) {
                    return;
                }

                IWorkbenchPage page = window.getActivePage();
                IEditorReference[] refs =
                        page.getEditorReferences();
                IEditorPart active = page.getActiveEditor();

                Json arr = Json.array();
                if (active != null) {
                    addEditorEntry(active.getEditorInput(), arr);
                }
                for (IEditorReference ref : refs) {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor != null && editor == active)
                        continue;
                    try {
                        addEditorEntry(ref.getEditorInput(), arr);
                    } catch (Exception ignored) {
                        // Skip editors that can't provide input
                    }
                }
                result[0] = arr.toString();
            } catch (Exception e) {
                // No workbench — return empty array
            }
        };
        Display display = Display.getCurrent();
        if (display != null) {
            query.run();
        } else {
            try {
                Display.getDefault().syncExec(query);
            } catch (Exception e) {
                // Headless — no Display thread
            }
        }
        return result[0];
    }

    private void addEditorEntry(
            org.eclipse.ui.IEditorInput input, Json arr) {
        if (!(input instanceof IFileEditorInput fi)) return;
        IFile file = fi.getFile();
        if (file.getLocation() == null) return;
        arr.add(Json.object()
                .put("file", file.getLocation().toOSString()));
    }

    String handleOpen(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return Json.error("Missing 'class' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return Json.error("Type not found: " + fqn);
        }

        IJavaElement target = type;
        if (methodName != null && !methodName.isBlank()) {
            IMethod method = JdtUtils.findMethod(type, methodName,
                    params.get("paramTypes"));
            if (method != null) {
                target = method;
            }
        }

        final IJavaElement element = target;
        String[] result = {Json.error("Failed to open editor")};
        Display.getDefault().syncExec(() -> {
            try {
                IEditorPart editor = JavaUI.openInEditor(element);
                if (editor != null) {
                    JavaUI.revealInEditor(editor, element);
                }
                result[0] = Json.object()
                        .put("ok", true).toString();
            } catch (Exception e) {
                result[0] = Json.error(e.getMessage());
            }
        });
        return result[0];
    }
}
