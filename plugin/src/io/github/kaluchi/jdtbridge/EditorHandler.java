package io.github.kaluchi.jdtbridge;

import java.util.Map;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;

/**
 * Handlers for Eclipse editor interaction: active-editor, open.
 */
class EditorHandler {

    String handleActiveEditor(Map<String, String> params) throws Exception {
        String[] result = {Json.error("Failed to query editor")};
        Display.getDefault().syncExec(() -> {
            try {
                var window = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow();
                if (window == null
                        || window.getActivePage() == null) {
                    result[0] = Json.object()
                            .put("file", (String) null).toString();
                    return;
                }
                IEditorPart editor =
                        window.getActivePage().getActiveEditor();
                if (editor == null) {
                    result[0] = Json.object()
                            .put("file", (String) null).toString();
                    return;
                }

                String file = null;
                var input = editor.getEditorInput();
                if (input instanceof IFileEditorInput fileInput) {
                    file = fileInput.getFile().getFullPath().toString();
                }

                int line = -1;
                var sp = editor.getSite().getSelectionProvider();
                if (sp != null) {
                    var sel = sp.getSelection();
                    if (sel instanceof ITextSelection ts) {
                        line = ts.getStartLine() + 1;
                    }
                }

                if (file != null) {
                    result[0] = Json.object()
                            .put("file", file)
                            .put("line", line).toString();
                } else {
                    result[0] = Json.object()
                            .put("file", (String) null).toString();
                }
            } catch (Exception e) {
                result[0] = Json.error(e.getMessage());
            }
        });
        return result[0];
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
