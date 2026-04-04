package io.github.kaluchi.jdtbridge.ui.preferences;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Arrays;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.kaluchi.jdtbridge.ui.Activator;

public class BridgePreferencePage extends PreferencePage
		implements IWorkbenchPreferencePage {

	private static final String AGENT_LAUNCH_TYPE =
			"io.github.kaluchi.jdtbridge.ui.agentLaunchType";

	private Text terminalCommandField;
	private Button bindLoopbackRadio;
	private Button bindAllRadio;
	private Label bindWarningLabel;
	private Text portField;
	private Label portStatusLabel;

	public BridgePreferencePage() {
		setDescription("JDT Bridge settings for AI agent integration.");
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
	}

	@Override
	protected Control createContents(Composite parent) {
		Composite container = new Composite(parent, SWT.NONE);
		container.setLayout(new GridLayout(1, false));
		container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		createTerminalSection(container);
		createHttpServerSection(container);

		loadValues();
		return container;
	}

	private void createTerminalSection(Composite parent) {
		Composite row = new Composite(parent, SWT.NONE);
		row.setLayout(new GridLayout(2, false));
		row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label label = new Label(row, SWT.NONE);
		label.setText("Terminal command:");

		terminalCommandField = new Text(row, SWT.BORDER);
		terminalCommandField.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));
	}

	private void createHttpServerSection(Composite parent) {
		Group group = new Group(parent, SWT.NONE);
		group.setText("HTTP Server");
		group.setLayout(new GridLayout(3, false));
		group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		// Bind address radios
		Label bindLabel = new Label(group, SWT.NONE);
		bindLabel.setText("Bind address:");

		Composite radioGroup = new Composite(group, SWT.NONE);
		radioGroup.setLayout(new GridLayout(1, false));
		GridData radioData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		radioData.horizontalSpan = 2;
		radioGroup.setLayoutData(radioData);

		bindLoopbackRadio = new Button(radioGroup, SWT.RADIO);
		bindLoopbackRadio.setText("Loopback only (127.0.0.1)");

		bindAllRadio = new Button(radioGroup, SWT.RADIO);
		bindAllRadio.setText("All interfaces (0.0.0.0)");

		// Security warning (spans full width)
		bindWarningLabel = new Label(group, SWT.WRAP);
		bindWarningLabel.setText(
				"\u26A0 All interfaces exposes the bridge to your "
				+ "network. Use only with trusted networks.");
		GridData warnData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		warnData.horizontalSpan = 3;
		warnData.widthHint = 400;
		bindWarningLabel.setLayoutData(warnData);
		bindWarningLabel.setVisible(false);

		bindLoopbackRadio.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				bindWarningLabel.setVisible(false);
			}
		});
		bindAllRadio.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				bindWarningLabel.setVisible(true);
			}
		});

		// Port field
		Label portLabel = new Label(group, SWT.NONE);
		portLabel.setText("Port:");

		portField = new Text(group, SWT.BORDER);
		portField.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Button checkButton = new Button(group, SWT.PUSH);
		checkButton.setText("Check");
		checkButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				checkPortAvailability();
			}
		});

		// Port hint
		Label portHint = new Label(group, SWT.WRAP);
		portHint.setText(
				"0 = auto-assigned by OS. "
				+ "Fixed port enables stable Docker/firewall rules.");
		GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		hintData.horizontalSpan = 3;
		hintData.widthHint = 400;
		portHint.setLayoutData(hintData);

		// Port check status
		portStatusLabel = new Label(group, SWT.NONE);
		GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		statusData.horizontalSpan = 3;
		portStatusLabel.setLayoutData(statusData);
	}

	private void loadValues() {
		IPreferenceStore store = getPreferenceStore();
		terminalCommandField.setText(
				store.getString(PreferenceConstants.TERMINAL_COMMAND));

		String bindAddress = store.getString(
				PreferenceConstants.HTTP_BIND_ADDRESS);
		boolean isAll = PreferenceConstants.BIND_ALL.equals(bindAddress);
		bindLoopbackRadio.setSelection(!isAll);
		bindAllRadio.setSelection(isAll);
		bindWarningLabel.setVisible(isAll);

		int port = store.getInt(PreferenceConstants.HTTP_FIXED_PORT);
		portField.setText(String.valueOf(port));
	}

	@Override
	public boolean performOk() {
		if (!validatePort()) return false;

		warnRunningAgents();

		IPreferenceStore store = getPreferenceStore();
		store.setValue(PreferenceConstants.TERMINAL_COMMAND,
				terminalCommandField.getText().trim());
		store.setValue(PreferenceConstants.HTTP_BIND_ADDRESS,
				bindAllRadio.getSelection()
						? PreferenceConstants.BIND_ALL
						: PreferenceConstants.BIND_LOOPBACK);
		store.setValue(PreferenceConstants.HTTP_FIXED_PORT,
				Integer.parseInt(portField.getText().trim()));
		return true;
	}

	@Override
	protected void performDefaults() {
		IPreferenceStore store = getPreferenceStore();
		terminalCommandField.setText(
				store.getDefaultString(
						PreferenceConstants.TERMINAL_COMMAND));
		bindLoopbackRadio.setSelection(true);
		bindAllRadio.setSelection(false);
		bindWarningLabel.setVisible(false);
		portField.setText("0");
		portStatusLabel.setText("");
		super.performDefaults();
	}

	private boolean validatePort() {
		String text = portField.getText().trim();
		try {
			int port = Integer.parseInt(text);
			if (port == 0) return true;
			if (port < 1024 || port > 65535) {
				setErrorMessage("Port must be 0 (auto) or 1024\u201365535.");
				return false;
			}
			setErrorMessage(null);
			return true;
		} catch (NumberFormatException e) {
			setErrorMessage("Port must be a number.");
			return false;
		}
	}

	private void checkPortAvailability() {
		String text = portField.getText().trim();
		try {
			int port = Integer.parseInt(text);
			if (port == 0) {
				portStatusLabel.setText("Auto-assign: always available.");
				return;
			}
			if (port < 1024 || port > 65535) {
				portStatusLabel.setText("Invalid range (1024\u201365535).");
				return;
			}
			try (ServerSocket probe = new ServerSocket(
					port, 1, InetAddress.getLoopbackAddress())) {
				portStatusLabel.setText(
						"Port " + port + " is available.");
			} catch (Exception e) {
				portStatusLabel.setText(
						"Port " + port + " is in use.");
			}
		} catch (NumberFormatException e) {
			portStatusLabel.setText("Enter a valid number.");
		}
	}

	private void warnRunningAgents() {
		try {
			ILaunch[] launches = DebugPlugin.getDefault()
					.getLaunchManager().getLaunches();
			boolean agentsRunning = Arrays.stream(launches)
					.filter(l -> !l.isTerminated())
					.anyMatch(l -> {
						try {
							return AGENT_LAUNCH_TYPE.equals(
									l.getLaunchConfiguration()
											.getType().getIdentifier());
						} catch (Exception e) {
							return false;
						}
					});

			if (agentsRunning) {
				MessageDialog.openWarning(getShell(),
						"Running agents detected",
						"Running agents will keep using the old "
						+ "connection.\nRestart them to use the new "
						+ "bind address/port.");
			}
		} catch (Exception e) {
			// DebugPlugin not available — skip
		}
	}
}
