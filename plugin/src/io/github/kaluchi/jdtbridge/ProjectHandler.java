package io.github.kaluchi.jdtbridge;

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

/**
 * Handler for /project-info endpoint: project overview
 * with adaptive detail.
 */
class ProjectHandler {

    private static final int DEFAULT_MEMBERS_THRESHOLD = 200;

    String handleProjectInfo(Map<String, String> params)
            throws Exception {
        String projectName = params.get("project");
        if (projectName == null || projectName.isBlank()) {
            return HttpServer.jsonError("Missing 'project' parameter");
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(projectName);
        if (!project.exists()) {
            return HttpServer.jsonError("Project not found: " + projectName);
        }

        IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null || !javaProject.exists()) {
            return HttpServer.jsonError(
                    "Not a Java project: " + projectName);
        }

        int membersThreshold = DEFAULT_MEMBERS_THRESHOLD;
        String thresholdParam = params.get("members-threshold");
        if (thresholdParam != null) {
            try {
                membersThreshold = Integer.parseInt(thresholdParam);
            } catch (NumberFormatException e) { /* use default */ }
        }

        // Quick count pass
        int totalTypes = 0;
        List<IPackageFragmentRoot> sourceRoots = new ArrayList<>();
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
        boolean includeMembers = totalTypes <= membersThreshold;

        // Location
        String location = project.getLocation() != null
                ? project.getLocation().toOSString() : "?";

        // Natures
        Json natures = Json.array();
        for (String id
                : project.getDescription().getNatureIds()) {
            natures.add(shortNature(id));
        }

        // Dependencies
        Json deps = Json.array();
        for (String dep : javaProject.getRequiredProjectNames()) {
            deps.add(dep);
        }

        // Source roots with packages and types
        Json rootsArr = Json.array();
        for (IPackageFragmentRoot root : sourceRoots) {
            String rootPath = root.getResource()
                    .getProjectRelativePath().toString();
            Json packages = Json.array();
            int rootTypeCount = 0;

            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg))
                    continue;
                ICompilationUnit[] units =
                        pkg.getCompilationUnits();
                if (units.length == 0) continue;

                String pkgName = pkg.getElementName();
                if (pkgName.isEmpty()) pkgName = "(default)";

                Json types = Json.array();
                for (ICompilationUnit unit : units) {
                    for (IType type : unit.getTypes()) {
                        rootTypeCount++;
                        Json typeObj = Json.object()
                                .put("name",
                                        type.getElementName())
                                .put("kind",
                                        JdtUtils.typeKind(type))
                                .put("fields",
                                        type.getFields().length);
                        if (includeMembers) {
                            typeObj.put("methods",
                                    buildMethodGroups(type));
                        } else {
                            typeObj.put("methods",
                                    type.getMethods().length);
                        }
                        types.add(typeObj);
                    }
                }

                packages.add(Json.object()
                        .put("name", pkgName)
                        .put("types", types));
            }

            rootsArr.add(Json.object()
                    .put("path", rootPath)
                    .put("packages", packages)
                    .put("typeCount", rootTypeCount));
        }

        return Json.object()
                .put("name", projectName)
                .put("location", location)
                .put("natures", natures)
                .put("dependencies", deps)
                .put("totalTypes", totalTypes)
                .put("membersIncluded", includeMembers)
                .put("sourceRoots", rootsArr)
                .toString();
    }

    private Json buildMethodGroups(IType type)
            throws JavaModelException {
        Json pub = Json.array();
        Json prot = Json.array();
        Json def = Json.array();
        Json priv = Json.array();

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

        return Json.object()
                .put("public", pub)
                .put("protected", prot)
                .put("default", def)
                .put("private", priv);
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
        return dot >= 0 ? natureId.substring(dot + 1) : natureId;
    }
}
