package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.TextEdit;

/**
 * Handlers for refactoring operations: organize-imports, format,
 * rename, move.
 */
class RefactoringHandler {

    String handleOrganizeImports(Map<String, String> params)
            throws Exception {
        String filePath = params.get("file");
        if (filePath == null || filePath.isBlank()) {
            return HttpServer.jsonError("Missing 'file' parameter");
        }

        ICompilationUnit cu = findCompilationUnit(filePath);
        if (cu == null) {
            return HttpServer.jsonError("Java file not found: " + filePath);
        }

        cu.getResource().refreshLocal(IResource.DEPTH_ZERO, null);

        OrganizeImportsOperation.IChooseImportQuery query =
                (openChoices, ranges) -> {
                    TypeNameMatch[] result =
                            new TypeNameMatch[openChoices.length];
                    for (int i = 0; i < openChoices.length; i++) {
                        result[i] = openChoices[i][0];
                    }
                    return result;
                };

        cu.becomeWorkingCopy(null);
        try {
            OrganizeImportsOperation op =
                    new OrganizeImportsOperation(
                            cu, null, true, false, true, query);
            op.run(null);

            int added = op.getNumberOfImportsAdded();
            int removed = op.getNumberOfImportsRemoved();

            if (added > 0 || removed > 0) {
                cu.commitWorkingCopy(true, null);
            }

            var r = new JsonObject();
            r.addProperty("added", added);
            r.addProperty("removed", removed);
            return r.toString();
        } finally {
            cu.discardWorkingCopy();
        }
    }

    String handleFormat(Map<String, String> params) throws Exception {
        String filePath = params.get("file");
        if (filePath == null || filePath.isBlank()) {
            return HttpServer.jsonError("Missing 'file' parameter");
        }

        ICompilationUnit cu = findCompilationUnit(filePath);
        if (cu == null) {
            return HttpServer.jsonError("Java file not found: " + filePath);
        }

        cu.getResource().refreshLocal(IResource.DEPTH_ZERO, null);

        String source = cu.getSource();
        Map<String, String> options =
                cu.getJavaProject().getOptions(true);

        String lineSep = source.contains("\r\n") ? "\r\n" : "\n";
        CodeFormatter formatter =
                ToolFactory.createCodeFormatter(options);
        TextEdit edit = formatter.format(
                CodeFormatter.K_COMPILATION_UNIT,
                source, 0, source.length(),
                0, lineSep);

        if (edit == null) {
            var r = new JsonObject();
            r.addProperty("modified", false);
            r.addProperty("reason",
                    "formatter returned no edits"
                    + " (syntax error?)");
            return r.toString();
        }

        Document document = new Document(source);
        edit.apply(document);
        String formatted = document.get();

        if (formatted.equals(source)) {
            var r = new JsonObject();
            r.addProperty("modified", false);
            return r.toString();
        }

        cu.becomeWorkingCopy(null);
        try {
            cu.getBuffer().setContents(formatted);
            cu.commitWorkingCopy(true, null);
        } finally {
            cu.discardWorkingCopy();
        }

        var r = new JsonObject();
        r.addProperty("modified", true);
        return r.toString();
    }

    String handleRename(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String newName = params.get("newName");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }
        if (newName == null || newName.isBlank()) {
            return HttpServer.jsonError("Missing 'newName' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        String methodName = params.get("method");
        String fieldName = params.get("field");

        IJavaElement element;
        String refactoringId;

        if (fieldName != null && !fieldName.isBlank()) {
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return HttpServer.jsonError("Field not found: " + fieldName
                        + " in " + fqn);
            }
            element = field;
            refactoringId = IJavaRefactorings.RENAME_FIELD;
        } else if (methodName != null && !methodName.isBlank()) {
            IMethod method = JdtUtils.findMethod(type, methodName,
                    params.get("paramTypes"));
            if (method == null) {
                return HttpServer.jsonError("Method not found: " + methodName
                        + " in " + fqn);
            }
            element = method;
            refactoringId = IJavaRefactorings.RENAME_METHOD;
        } else {
            element = type;
            refactoringId = IJavaRefactorings.RENAME_TYPE;
        }

        return performRefactoring(refactoringId, descriptor -> {
            RenameJavaElementDescriptor rd =
                    (RenameJavaElementDescriptor) descriptor;
            rd.setJavaElement(element);
            rd.setNewName(newName);
            rd.setUpdateReferences(true);
        });
    }

    String handleMove(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String targetPkg = params.get("target");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.jsonError("Missing 'class' parameter");
        }
        if (targetPkg == null || targetPkg.isBlank()) {
            return HttpServer.jsonError("Missing 'target' parameter");
        }

        IType type = JdtUtils.findType(fqn);
        if (type == null) {
            return HttpServer.jsonError("Type not found: " + fqn);
        }

        ICompilationUnit cu = type.getCompilationUnit();
        if (cu == null) {
            return HttpServer.jsonError("Cannot move binary type");
        }

        IPackageFragmentRoot sourceRoot = (IPackageFragmentRoot)
                cu.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        IPackageFragment dest =
                sourceRoot.getPackageFragment(targetPkg);
        if (!dest.exists()) {
            dest = sourceRoot.createPackageFragment(
                    targetPkg, true, new NullProgressMonitor());
        }

        final IPackageFragment targetDest = dest;
        return performRefactoring(IJavaRefactorings.MOVE,
                descriptor -> {
                    MoveDescriptor md = (MoveDescriptor) descriptor;
                    md.setMoveResources(
                            new IFile[0],
                            new org.eclipse.core.resources.IFolder[0],
                            new ICompilationUnit[]{cu});
                    md.setDestination(targetDest);
                    md.setUpdateReferences(true);
                    md.setUpdateQualifiedNames(true);
                });
    }

    // ---- Helpers ----

    private ICompilationUnit findCompilationUnit(String filePath) {
        IWorkspaceRoot root =
                ResourcesPlugin.getWorkspace().getRoot();
        IResource resource = root.findMember(filePath);
        if (resource == null || !(resource instanceof IFile file)) {
            return null;
        }
        ICompilationUnit cu =
                JavaCore.createCompilationUnitFrom(file);
        if (cu == null || !cu.exists()) {
            return null;
        }
        return cu;
    }

    @FunctionalInterface
    private interface DescriptorConfigurer {
        void configure(
                org.eclipse.ltk.core.refactoring.RefactoringDescriptor d)
                throws Exception;
    }

    private String performRefactoring(String refactoringId,
            DescriptorConfigurer configurer) throws Exception {
        var descriptor = RefactoringCore
                .getRefactoringContribution(refactoringId)
                .createDescriptor();
        configurer.configure(descriptor);

        RefactoringStatus status = new RefactoringStatus();
        Refactoring refactoring =
                descriptor.createRefactoring(status);
        if (status.hasFatalError()) {
            return HttpServer.jsonError(status.getMessageMatchingSeverity(
                    RefactoringStatus.FATAL));
        }

        status.merge(refactoring.checkInitialConditions(
                new NullProgressMonitor()));
        if (status.hasFatalError()) {
            return HttpServer.jsonError(status.getMessageMatchingSeverity(
                    RefactoringStatus.FATAL));
        }

        status.merge(refactoring.checkFinalConditions(
                new NullProgressMonitor()));
        if (status.hasFatalError()) {
            return HttpServer.jsonError(status.getMessageMatchingSeverity(
                    RefactoringStatus.FATAL));
        }

        Change change = refactoring.createChange(
                new NullProgressMonitor());
        try {
            change.perform(new NullProgressMonitor());
        } finally {
            change.dispose();
        }

        var result = new JsonObject();
        result.addProperty("ok", true);
        if (status.hasWarning()) {
            var warnings = new JsonArray();
            for (var entry : status.getEntries()) {
                if (entry.isWarning()) {
                    warnings.add(entry.getMessage());
                }
            }
            result.add("warnings", warnings);
        }
        return result.toString();
    }
}
