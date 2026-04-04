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
		Composite terminalRow = new Composite(parent, SWT.NONE);
		terminalRow.setLayout(new GridLayout(2, false));
		terminalRow.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label terminalLabel = new Label(terminalRow, SWT.NONE);
		terminalLabel.setText("Terminal command:");

		terminalCommandField = new Text(terminalRow, SWT.BORDER);
		terminalCommandField.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));
	}

	private void createHttpServerSection(Composite parent) {
		Group serverGroup = new Group(parent, SWT.NONE);
		serverGroup.setText("HTTP Server");
		serverGroup.setLayout(new GridLayout(3, false));
		serverGroup.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		// Bind address radios
		Label bindAddressLabel = new Label(serverGroup, SWT.NONE);
		bindAddressLabel.setText("Bind address:");

		Composite bindRadioGroup = new Composite(serverGroup, SWT.NONE);
		bindRadioGroup.setLayout(new GridLayout(1, false));
		GridData bindRadioLayout = new GridData(
				SWT.FILL, SWT.CENTER, true, false);
		bindRadioLayout.horizontalSpan = 2;
		bindRadioGroup.setLayoutData(bindRadioLayout);

		bindLoopbackRadio = new Button(bindRadioGroup, SWT.RADIO);
		bindLoopbackRadio.setText("Loopback only (127.0.0.1)");

		bindAllRadio = new Button(bindRadioGroup, SWT.RADIO);
		bindAllRadio.setText("All interfaces (0.0.0.0)");

		// Security warning (spans full width)
		bindWarningLabel = new Label(serverGroup, SWT.WRAP);
		bindWarningLabel.setText(
				"\u26A0 All interfaces exposes the bridge to your "
				+ "network. Use only with trusted networks.");
		GridData warningLayout = new GridData(
				SWT.FILL, SWT.CENTER, true, false);
		warningLayout.horizontalSpan = 3;
		warningLayout.widthHint = 400;
		bindWarningLabel.setLayoutData(warningLayout);
		bindWarningLabel.setVisible(false);

		bindLoopbackRadio.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				bindWarningLabel.setVisible(false);
			}
		});
		bindAllRadio.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				bindWarningLabel.setVisible(true);
			}
		});

		// Port field
		Label portLabel = new Label(serverGroup, SWT.NONE);
		portLabel.setText("Port:");

		portField = new Text(serverGroup, SWT.BORDER);
		portField.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Button portCheckButton = new Button(serverGroup, SWT.PUSH);
		portCheckButton.setText("Check");
		portCheckButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				checkPortAvailability();
			}
		});

		// Port hint
		Label portHintLabel = new Label(serverGroup, SWT.WRAP);
		portHintLabel.setText(
				"0 = auto-assigned by OS. "
				+ "Fixed port enables stable Docker/firewall rules.");
		GridData portHintLayout = new GridData(
				SWT.FILL, SWT.CENTER, true, false);
		portHintLayout.horizontalSpan = 3;
		portHintLayout.widthHint = 400;
		portHintLabel.setLayoutData(portHintLayout);

		// Port check status
		portStatusLabel = new Label(serverGroup, SWT.NONE);
		GridData portStatusLayout = new GridData(
				SWT.FILL, SWT.CENTER, true, false);
		portStatusLayout.horizontalSpan = 3;
		portStatusLabel.setLayoutData(portStatusLayout);
	}

	private void loadValues() {
		IPreferenceStore store = getPreferenceStore();
		terminalCommandField.setText(
				store.getString(PreferenceConstants.TERMINAL_COMMAND));

		String bindAddressMode = store.getString(
				PreferenceConstants.HTTP_BIND_ADDRESS);
		boolean isAllInterfaces =
				PreferenceConstants.BIND_ALL.equals(bindAddressMode);
		bindLoopbackRadio.setSelection(!isAllInterfaces);
		bindAllRadio.setSelection(isAllInterfaces);
		bindWarningLabel.setVisible(isAllInterfaces);

		int configuredPort = store.getInt(
				PreferenceConstants.HTTP_FIXED_PORT);
		portField.setText(String.valueOf(configuredPort));
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
		String portText = portField.getText().trim();
		try {
			int portNumber = Integer.parseInt(portText);
			if (portNumber == 0) return true;
			if (portNumber < 1024 || portNumber > 65535) {
				setErrorMessage(
						"Port must be 0 (auto) or 1024\u201365535.");
				return false;
			}
			setErrorMessage(null);
			return true;
		} catch (NumberFormatException invalidPortNumber) {
			setErrorMessage("Port must be a number.");
			return false;
		}
	}

	private void checkPortAvailability() {
		String portText = portField.getText().trim();
		try {
			int portNumber = Integer.parseInt(portText);
			if (portNumber == 0) {
				portStatusLabel.setText(
						"Auto-assign: always available.");
				return;
			}
			if (portNumber < 1024 || portNumber > 65535) {
				portStatusLabel.setText(
						"Invalid range (1024\u201365535).");
				return;
			}
			InetAddress probeAddress;
			try {
				probeAddress = bindAllRadio.getSelection()
						? InetAddress.getByName("0.0.0.0")
						: InetAddress.getLoopbackAddress();
			} catch (Exception addressException) {
				probeAddress = InetAddress.getLoopbackAddress();
			}
			try (ServerSocket portProbe = new ServerSocket(
					portNumber, 1, probeAddress)) {
				portStatusLabel.setText(
						"Port " + portNumber + " is available.");
			} catch (Exception portBindException) {
				portStatusLabel.setText(
						"Port " + portNumber + " is in use.");
			}
		} catch (NumberFormatException invalidPortNumber) {
			portStatusLabel.setText("Enter a valid number.");
		}
	}

	private void warnRunningAgents() {
		try {
			ILaunch[] agentLaunches = DebugPlugin.getDefault()
					.getLaunchManager().getLaunches();
			boolean hasRunningAgents = Arrays.stream(agentLaunches)
					.filter(launch -> !launch.isTerminated())
					.anyMatch(launch -> {
						try {
							return AGENT_LAUNCH_TYPE.equals(
									launch.getLaunchConfiguration()
											.getType().getIdentifier());
						} catch (Exception launchConfigException) {
							return false;
						}
					});

			if (hasRunningAgents) {
				MessageDialog.openWarning(getShell(),
						"Running agents detected",
						"Running agents will keep using the old "
						+ "connection.\nRestart them to use the new "
						+ "bind address/port.");
			}
		} catch (Exception debugPluginException) {
			// DebugPlugin not available — skip
		}
	}
}
