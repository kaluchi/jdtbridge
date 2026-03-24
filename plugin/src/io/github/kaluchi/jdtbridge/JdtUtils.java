package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Shared JDT utilities used by multiple handlers.
 */
class JdtUtils {

    static IType findType(String fqn) throws JavaModelException {
        var model = JavaCore.create(
                ResourcesPlugin.getWorkspace().getRoot());
        for (IJavaProject project : model.getJavaProjects()) {
            IType type = project.findType(fqn);
            if (type != null && type.exists()) return type;
        }
        return null;
    }

    static IMethod findMethod(IType type, String name,
            String paramTypesStr) throws JavaModelException {
        String[] paramTypes = parseParamTypes(paramTypesStr);
        for (IMethod m : type.getMethods()) {
            if (!m.getElementName().equals(name)) continue;
            if (paramTypes != null) {
                if (matchesParamTypes(m, paramTypes)) return m;
            } else {
                return m;
            }
        }
        return null;
    }

    static List<IMethod> findMethods(IType type, String name,
            String paramTypesStr) throws JavaModelException {
        String[] paramTypes = parseParamTypes(paramTypesStr);
        List<IMethod> result = new ArrayList<>();
        for (IMethod m : type.getMethods()) {
            if (!m.getElementName().equals(name)) continue;
            if (paramTypes != null) {
                if (matchesParamTypes(m, paramTypes))
                    result.add(m);
            } else {
                result.add(m);
            }
        }
        return result;
    }

    static String[] parseParamTypes(String s) {
        if (s == null) return null;
        if (s.isEmpty()) return new String[0];
        return s.split(",");
    }

    private static boolean matchesParamTypes(IMethod m,
            String[] paramTypes) throws JavaModelException {
        String[] methodParams = m.getParameterTypes();
        if (methodParams.length != paramTypes.length) return false;
        for (int i = 0; i < methodParams.length; i++) {
            String jdtType = Signature.toString(methodParams[i]);
            if (!typeMatches(jdtType, paramTypes[i].trim())) {
                return false;
            }
        }
        return true;
    }

    static boolean typeMatches(String jdtType, String userType) {
        String jdt = stripGenerics(jdtType);
        String user = stripGenerics(userType);
        if (jdt.equals(user)) return true;
        return simpleName(jdt).equals(simpleName(user));
    }

    private static String stripGenerics(String type) {
        int start = type.indexOf('<');
        if (start < 0) return type;
        int end = type.lastIndexOf('>');
        String suffix = end + 1 < type.length()
                ? type.substring(end + 1) : "";
        return type.substring(0, start) + suffix;
    }

    private static String simpleName(String type) {
        String suffix = "";
        String base = type;
        while (base.endsWith("[]")) {
            suffix += "[]";
            base = base.substring(0, base.length() - 2);
        }
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(dot + 1);
        return base + suffix;
    }

    static String typeKind(IType type) throws JavaModelException {
        if (type.isAnnotation()) return "annotation";
        if (type.isEnum()) return "enum";
        if (type.isInterface()) return "interface";
        return "class";
    }

    /**
     * Wait for Eclipse auto-build to finish, with a 2-minute
     * safety timeout to prevent indefinite hangs.
     */
    static void joinAutoBuild() throws InterruptedException {
        NullProgressMonitor monitor = new NullProgressMonitor();
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(120_000);
            } catch (InterruptedException e) { /* ok */ }
            monitor.setCanceled(true);
        });
        Job.getJobManager().join(
                ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
    }

    static String compactSignature(IMethod m) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        sig.append(m.getElementName()).append("(");
        String[] paramTypes = m.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.toString(paramTypes[i]));
        }
        sig.append(")");
        return sig.toString();
    }
}
