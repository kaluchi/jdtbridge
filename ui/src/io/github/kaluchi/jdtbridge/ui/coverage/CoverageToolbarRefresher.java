package io.github.kaluchi.jdtbridge.ui.coverage;

import org.eclipse.core.runtime.Platform;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

/**
 * Workaround for an EclEmma quirk: their toolbar handlers
 * (subclasses of {@code AbstractSessionManagerHandler}) call
 * {@code fireHandlerChanged} from {@code ISessionListener}
 * callbacks, but the workbench does not pick that up for view
 * toolbar items until the next focus event — so the user sees
 * stale enabled/disabled state until they click somewhere.
 *
 * <p>This listener subscribes to the same session events and
 * forces the EclEmma Coverage View's action bars to re-render
 * via {@code IActionBars.updateActionBars()}, on the UI thread.
 *
 * <p>Activated by the UI plugin's Activator when EclEmma is on
 * the classpath. No-op when the Coverage View is not open.
 */
public final class CoverageToolbarRefresher
        implements ISessionListener {

    private static final String COVERAGE_VIEW_ID =
            "org.eclipse.eclemma.ui.CoverageView";

    private CoverageToolbarRefresher() {
    }

    public static CoverageToolbarRefresher install() {
        CoverageToolbarRefresher r = new CoverageToolbarRefresher();
        CoverageTools.getSessionManager().addSessionListener(r);
        return r;
    }

    public void uninstall() {
        // OSGi shutdown can stop the EclEmma bundle before our UI
        // bundle. CoverageTools.getSessionManager() then dereferences
        // a null EclEmmaCorePlugin instance and throws NPE. The
        // session manager and our listener live inside that bundle's
        // classloader, so once it's gone the listener is unreachable
        // anyway — there is nothing to remove.
        Bundle eclemma = Platform.getBundle("org.eclipse.eclemma.core");
        if (eclemma == null || eclemma.getState() != Bundle.ACTIVE) {
            return;
        }
        CoverageTools.getSessionManager().removeSessionListener(this);
    }

    @Override
    public void sessionAdded(ICoverageSession s) {
        scheduleRefresh();
    }

    @Override
    public void sessionRemoved(ICoverageSession s) {
        scheduleRefresh();
    }

    @Override
    public void sessionActivated(ICoverageSession s) {
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        Display.getDefault().asyncExec(this::refreshCoverageViewBars);
    }

    private void refreshCoverageViewBars() {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null) {
            return;
        }
        for (IWorkbenchWindow win : wb.getWorkbenchWindows()) {
            IWorkbenchPage page = win.getActivePage();
            if (page == null) {
                continue;
            }
            IViewPart view = page.findView(COVERAGE_VIEW_ID);
            if (view != null) {
                view.getViewSite().getActionBars()
                        .updateActionBars();
            }
        }
    }
}
