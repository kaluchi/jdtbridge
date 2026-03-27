package io.github.kaluchi.jdtbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

class ProjectHandler {

    private static final int DEFAULT_MEMBERS_THRESHOLD = 200;

    String handleProjectInfo(Map<String, String> params)
            throws Exception {
        String projectName = params.get("project");
        if (projectName == null || projectName.isBlank()) {
            return HttpServer.jsonError(
                    "Missing 'project' parameter");
        }

        IProject project = ResourcesPlugin.getWorkspace()
                .getRoot().getProject(projectName);
        if (!project.exists()) {
            return HttpServer.jsonError(
                    "Project not found: " + projectName);
        }

        IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null || !javaProject.exists()) {
            return HttpServer.jsonError(
                    "Not a Java project: " + projectName);
        }

        int membersThreshold = DEFAULT_MEMBERS_THRESHOLD;
        String thresholdParam =
                params.get("members-threshold");
        if (thresholdParam != null) {
            try {
                membersThreshold =
                        Integer.parseInt(thresholdParam);
            } catch (NumberFormatException e) { /* default */ }
        }

        int totalTypes = 0;
        List<IPackageFragmentRoot> sourceRoots =
                new ArrayList<>();
        for (IPackageFragmentRoot root
                : javaProject.getPackageFragmentRoots()) {
            if (root.getKind()
                    != IPackageFragmentRoot.K_SOURCE) continue;
            sourceRoots.add(root);
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    for (ICompilationUnit u
                            : pkg.getCompilationUnits()) {
                        totalTypes += u.getTypes().length;
                    }
                }
            }
        }
        boolean includeMembers =
                totalTypes <= membersThreshold;

        String location = project.getLocation() != null
                ? project.getLocation().toOSString() : "?";

        var natures = new JsonArray();
        for (String id
                : project.getDescription().getNatureIds()) {
            natures.add(shortNature(id));
        }

        var deps = new JsonArray();
        for (String dep
                : javaProject.getRequiredProjectNames()) {
            deps.add(dep);
        }

        var rootsArr = new JsonArray();
        for (IPackageFragmentRoot root : sourceRoots) {
            String rootPath = root.getResource()
                    .getProjectRelativePath().toString();
            var packages = new JsonArray();
            int rootTypeCount = 0;

            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg))
                    continue;
                ICompilationUnit[] units =
                        pkg.getCompilationUnits();
                if (units.length == 0) continue;

                String pkgName = pkg.getElementName();
                if (pkgName.isEmpty()) pkgName = "(default)";

                var types = new JsonArray();
                for (ICompilationUnit unit : units) {
                    for (IType type : unit.getTypes()) {
                        rootTypeCount++;
                        var typeObj = new JsonObject();
                        typeObj.addProperty("name",
                                type.getElementName());
                        typeObj.addProperty("kind",
                                JdtUtils.typeKind(type));
                        typeObj.addProperty("fields",
                                type.getFields().length);
                        if (includeMembers) {
                            typeObj.add("methods",
                                    buildMethodGroups(type));
                        } else {
                            typeObj.addProperty("methods",
                                    type.getMethods().length);
                        }
                        types.add(typeObj);
                    }
                }

                var pkgObj = new JsonObject();
                pkgObj.addProperty("name", pkgName);
                pkgObj.add("types", types);
                packages.add(pkgObj);
            }

            var rootObj = new JsonObject();
            rootObj.addProperty("path", rootPath);
            rootObj.add("packages", packages);
            rootObj.addProperty("typeCount", rootTypeCount);
            rootsArr.add(rootObj);
        }

        var result = new JsonObject();
        result.addProperty("name", projectName);
        result.addProperty("location", location);
        result.add("natures", natures);
        result.add("dependencies", deps);
        result.addProperty("totalTypes", totalTypes);
        result.addProperty("membersIncluded",
                includeMembers);
        result.add("sourceRoots", rootsArr);
        return result.toString();
    }

    private JsonObject buildMethodGroups(IType type)
            throws JavaModelException {
        var pub = new JsonArray();
        var prot = new JsonArray();
        var def = new JsonArray();
        var priv = new JsonArray();

        for (IMethod m : type.getMethods()) {
            String sig;
            try {
                sig = JdtUtils.compactSignature(m);
            } catch (JavaModelException e) {
                sig = m.getElementName() + "(?)";
            }
            int flags = m.getFlags();
            if (Flags.isPublic(flags)) pub.add(sig);
            else if (Flags.isProtected(flags)) prot.add(sig);
            else if (Flags.isPrivate(flags)) priv.add(sig);
            else def.add(sig);
        }

        var result = new JsonObject();
        result.add("public", pub);
        result.add("protected", prot);
        result.add("default", def);
        result.add("private", priv);
        return result;
    }

    static String shortNature(String natureId) {
        if (natureId.contains("javanature")) return "java";
        if (natureId.contains("maven")) return "maven";
        if (natureId.contains("pde")
                || natureId.contains("Plugin")) {
            return "pde";
        }
        if (natureId.contains("gradle")) return "gradle";
        int dot = natureId.lastIndexOf('.');
        return dot >= 0 ? natureId.substring(dot + 1)
                : natureId;
    }
}
