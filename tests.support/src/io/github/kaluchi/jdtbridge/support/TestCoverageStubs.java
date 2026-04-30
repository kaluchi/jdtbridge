package io.github.kaluchi.jdtbridge.support;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.IExecutionDataSource;
import org.eclipse.eclemma.core.analysis.IJavaModelCoverage;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.jacoco.core.analysis.CoverageNodeImpl;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ICoverageNode;
import org.jacoco.core.analysis.ICoverageNode.CounterEntity;
import org.jacoco.core.analysis.ICoverageNode.ElementType;
import org.jacoco.core.internal.analysis.CounterImpl;

/**
 * Proxy-based stubs for coverage-related interfaces. Lives in
 * tests.support so the bytecodes of unused proxy handler branches
 * don't count against individual test files.
 */
@SuppressWarnings("restriction")
public final class TestCoverageStubs {

    private TestCoverageStubs() {
    }

    public static IJavaProject fakeProject() {
        return (IJavaProject) Proxy.newProxyInstance(
                IJavaProject.class.getClassLoader(),
                new Class<?>[] { IJavaProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" ->
                            System.identityHashCode(proxy);
                    default -> null;
                });
    }

    public static ICoverageNode fakeNode(int instMissed,
            int instCovered, int branchMissed, int branchCovered) {
        return new FakeNode(instMissed, instCovered,
                branchMissed, branchCovered);
    }

    public static IJavaModelCoverage fakeModel(
            Map<IJavaProject, ICoverageNode> projectMap) {
        return new IJavaModelCoverage() {
            public IJavaProject[] getProjects() {
                return projectMap.keySet()
                        .toArray(new IJavaProject[0]);
            }
            public IPackageFragmentRoot[] getPackageFragmentRoots() {
                return new IPackageFragmentRoot[0];
            }
            public IPackageFragment[] getPackageFragments() {
                return new IPackageFragment[0];
            }
            public IType[] getTypes() {
                return new IType[0];
            }
            public ICoverageNode getCoverageFor(IJavaElement element) {
                return projectMap.get(element);
            }
            public ElementType getElementType() {
                return ElementType.GROUP;
            }
            public String getName() {
                return "fake-model";
            }
            public boolean containsCode() {
                return false;
            }
            public ICoverageNode getPlainCopy() {
                return this;
            }
            public ICounter getInstructionCounter() {
                return CounterImpl.COUNTER_0_0;
            }
            public ICounter getBranchCounter() {
                return CounterImpl.COUNTER_0_0;
            }
            public ICounter getLineCounter() {
                return CounterImpl.COUNTER_0_0;
            }
            public ICounter getComplexityCounter() {
                return CounterImpl.COUNTER_0_0;
            }
            public ICounter getMethodCounter() {
                return CounterImpl.COUNTER_0_0;
            }
            public ICounter getClassCounter() {
                return CounterImpl.COUNTER_0_0;
            }
            public ICounter getCounter(CounterEntity entity) {
                return CounterImpl.COUNTER_0_0;
            }
        };
    }

    public static IExecutionDataSource emptyDataSource() {
        return (execVisitor, sessionInfoVisitor) -> {
        };
    }

    public static ICoverageSession stubSession() {
        return (ICoverageSession) Proxy.newProxyInstance(
                ICoverageSession.class.getClassLoader(),
                new Class<?>[] { ICoverageSession.class },
                (p, m, a) -> null);
    }

    public static ICoverageSession fakeSession(String description) {
        return fakeSession(description, null);
    }

    public static ICoverageSession fakeSession(String description,
            org.eclipse.debug.core.ILaunchConfiguration config) {
        return (ICoverageSession) Proxy.newProxyInstance(
                ICoverageSession.class.getClassLoader(),
                new Class<?>[] { ICoverageSession.class },
                (p, m, a) -> switch (m.getName()) {
                    case "getDescription" -> description;
                    case "getLaunchConfiguration" -> config;
                    case "getScope" -> java.util.Set.of();
                    case "equals" -> p == a[0];
                    case "hashCode" ->
                            System.identityHashCode(p);
                    default -> null;
                });
    }

    private static final class FakeNode extends CoverageNodeImpl {
        FakeNode(int instMissed, int instCovered,
                int branchMissed, int branchCovered) {
            super(ElementType.GROUP, "fake-project-coverage");
            this.instructionCounter = CounterImpl.getInstance(
                    instMissed, instCovered);
            this.branchCounter = CounterImpl.getInstance(
                    branchMissed, branchCovered);
        }
    }
}
