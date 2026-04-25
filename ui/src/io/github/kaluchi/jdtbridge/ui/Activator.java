package io.github.kaluchi.jdtbridge.ui;

import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import io.github.kaluchi.jdtbridge.ui.coverage.CoverageToolbarRefresher;

public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "io.github.kaluchi.jdtbridge.ui";

	private static Activator instance;

	private CoverageToolbarRefresher coverageRefresher;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		instance = this;
		if (Platform.getBundle("org.eclipse.eclemma.core") != null) {
			coverageRefresher = CoverageToolbarRefresher.install();
		}
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		if (coverageRefresher != null) {
			coverageRefresher.uninstall();
			coverageRefresher = null;
		}
		instance = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return instance;
	}
}
