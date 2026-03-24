package io.github.kaluchi.jdtbridge;

import java.nio.file.Path;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;

/**
 * Registered via org.eclipse.ui.startup extension point.
 * Forces early activation of the bundle so the Activator runs at
 * Eclipse startup. After the workbench is up, checks whether the
 * CLI is installed and opens the welcome page if not.
 */
public class StartupHandler implements IStartup {

    @Override
    public void earlyStartup() {
        Job job = Job.create("JDT Bridge welcome check", this::check);
        job.setSystem(true);
        job.schedule(5000);
    }

    private IStatus check(IProgressMonitor monitor) {
        try {
            Path home = Activator.getHome();
            ConfigService config = new ConfigService(home);
            WelcomeHandler welcome = new WelcomeHandler(config);

            if (welcome.isDismissed()) {
                return Status.OK_STATUS;
            }
            if (welcome.isCliInstalled()) {
                return Status.OK_STATUS;
            }
            int port = config.readBridgeInfo().port();
            if (port <= 0) {
                return Status.OK_STATUS;
            }
            Display.getDefault().asyncExec(() -> openWelcome(port));
        } catch (Exception e) {
            Log.warn("Welcome check failed", e);
        }
        return Status.OK_STATUS;
    }

    private void openWelcome(int port) {
        String url = "http://localhost:" + port + "/status";
        Log.info("Opening welcome page: " + url);
        Program.launch(url);
    }
}
