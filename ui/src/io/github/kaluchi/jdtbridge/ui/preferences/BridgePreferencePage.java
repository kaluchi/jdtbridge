package io.github.kaluchi.jdtbridge.ui.preferences;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Arrays;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.window.Window;
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

	// Local socket controls
	private Text localPortField;
	private Text localTokenField;
	private Label localPortStatusLabel;
	private Button localRegenerateTokenCheckbox;

	// Remote socket controls
	private Button remoteEnabledCheckbox;
	private Text remotePortField;
	private Text remoteTokenField;
	private Label remotePortStatusLabel;
	private Button remoteRegenerateTokenCheckbox;
	private Composite remoteContent;
	private Label remoteStatusLabel;

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
		container.setLayoutData(
				new GridData(SWT.FILL, SWT.FILL, true, true));

		createTerminalSection(container);
		createLocalSection(container);
		createRemoteSection(container);

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

	private void createLocalSection(Composite parent) {
		Group localGroup = new Group(parent, SWT.NONE);
		localGroup.setLayout(new GridLayout(1, false));
		localGroup.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Composite localHeader = new Composite(localGroup, SWT.NONE);
		localHeader.setLayout(new GridLayout(3, false));
		localHeader.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label localLabel = new Label(localHeader, SWT.NONE);
		localLabel.setText("Local 127.0.0.1");

		Button localEnabledCheckbox = new Button(localHeader, SWT.CHECK);
		localEnabledCheckbox.setSelection(true);
		localEnabledCheckbox.setEnabled(false);

		Label localStatusLabel = new Label(localHeader, SWT.NONE);
		localStatusLabel.setText("(always on)");

		Composite localContent = new Composite(localGroup, SWT.NONE);
		localContent.setLayout(new GridLayout(6, false));
		localContent.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		// Port
		Label localPortLabel = new Label(localContent, SWT.NONE);
		localPortLabel.setText("Port:");
		GridData localPortLabelLayout = new GridData();
		localPortLabelLayout.widthHint = 40;
		localPortLabel.setLayoutData(localPortLabelLayout);

		localPortField = new Text(localContent, SWT.BORDER);
		GridData localPortLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		localPortLayout.widthHint = 200;
		localPortField.setLayoutData(localPortLayout);

		Button localPortCopyButton = new Button(localContent, SWT.PUSH);
		localPortCopyButton.setText("Copy");
		localPortCopyButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				copyActualPort(true);
			}
		});

		Button localPortCheckButton = new Button(localContent, SWT.PUSH);
		localPortCheckButton.setText("Check");
		localPortCheckButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				checkPortAvailability(localPortField,
						localPortStatusLabel,
						InetAddress.getLoopbackAddress());
			}
		});

		localPortStatusLabel = new Label(localContent, SWT.NONE);
		GridData localPortStatusLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		localPortStatusLayout.horizontalSpan = 2;
		localPortStatusLabel.setLayoutData(localPortStatusLayout);

		// Token
		Label localTokenLabel = new Label(localContent, SWT.NONE);
		localTokenLabel.setText("Token:");
		GridData localTokenLabelLayout = new GridData();
		localTokenLabelLayout.widthHint = 40;
		localTokenLabel.setLayoutData(localTokenLabelLayout);

		localTokenField = new Text(localContent, SWT.BORDER | SWT.READ_ONLY);
		GridData localTokenLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		localTokenLayout.widthHint = 200;
		localTokenField.setLayoutData(localTokenLayout);

		createTokenButtons(localContent, localTokenField);

		// Regenerate checkbox
		localRegenerateTokenCheckbox = new Button(localContent, SWT.CHECK);
		localRegenerateTokenCheckbox.setText(
				"Regenerate token on Eclipse restart");
		GridData localRegenLayout = new GridData();
		localRegenLayout.horizontalSpan = 6;
		localRegenerateTokenCheckbox.setLayoutData(localRegenLayout);
	}

	private void createRemoteSection(Composite parent) {
		Group remoteGroup = new Group(parent, SWT.NONE);
		remoteGroup.setLayout(new GridLayout(1, false));
		remoteGroup.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Composite remoteHeader = new Composite(remoteGroup, SWT.NONE);
		remoteHeader.setLayout(new GridLayout(3, false));
		remoteHeader.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label remoteLabel = new Label(remoteHeader, SWT.NONE);
		remoteLabel.setText("Remote 0.0.0.0");

		remoteEnabledCheckbox = new Button(remoteHeader, SWT.CHECK);

		remoteStatusLabel = new Label(remoteHeader, SWT.NONE);
		remoteStatusLabel.setText("(disabled)");

		remoteContent = new Composite(remoteGroup, SWT.NONE);
		remoteContent.setLayout(new GridLayout(6, false));
		remoteContent.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		// Port
		Label remotePortLabel = new Label(remoteContent, SWT.NONE);
		remotePortLabel.setText("Port:");
		GridData remotePortLabelLayout = new GridData();
		remotePortLabelLayout.widthHint = 40;
		remotePortLabel.setLayoutData(remotePortLabelLayout);

		remotePortField = new Text(remoteContent, SWT.BORDER);
		GridData remotePortLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		remotePortLayout.widthHint = 200;
		remotePortField.setLayoutData(remotePortLayout);

		Button remotePortCopyButton = new Button(
				remoteContent, SWT.PUSH);
		remotePortCopyButton.setText("Copy");
		remotePortCopyButton.addSelectionListener(
				new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				copyActualPort(false);
			}
		});

		Button remotePortCheckButton = new Button(
				remoteContent, SWT.PUSH);
		remotePortCheckButton.setText("Check");
		remotePortCheckButton.addSelectionListener(
				new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				try {
					checkPortAvailability(remotePortField,
							remotePortStatusLabel,
							InetAddress.getByName("0.0.0.0"));
				} catch (Exception addressException) {
					remotePortStatusLabel.setText("error");
				}
			}
		});

		remotePortStatusLabel = new Label(remoteContent, SWT.NONE);
		GridData remotePortStatusLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		remotePortStatusLayout.horizontalSpan = 2;
		remotePortStatusLabel.setLayoutData(remotePortStatusLayout);

		// Token
		Label remoteTokenLabel = new Label(remoteContent, SWT.NONE);
		remoteTokenLabel.setText("Token:");
		GridData remoteTokenLabelLayout = new GridData();
		remoteTokenLabelLayout.widthHint = 40;
		remoteTokenLabel.setLayoutData(remoteTokenLabelLayout);

		remoteTokenField = new Text(
				remoteContent, SWT.BORDER | SWT.READ_ONLY);
		GridData remoteTokenLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		remoteTokenLayout.widthHint = 200;
		remoteTokenField.setLayoutData(remoteTokenLayout);

		createTokenButtons(remoteContent, remoteTokenField);

		// Regenerate checkbox
		remoteRegenerateTokenCheckbox = new Button(
				remoteContent, SWT.CHECK);
		remoteRegenerateTokenCheckbox.setText(
				"Regenerate token on Eclipse restart");
		GridData remoteRegenLayout = new GridData();
		remoteRegenLayout.horizontalSpan = 6;
		remoteRegenerateTokenCheckbox.setLayoutData(
				remoteRegenLayout);

		// Warning
		Label remoteWarningLabel = new Label(remoteContent, SWT.WRAP);
		remoteWarningLabel.setText(
				"\u26A0 Binds to all interfaces. Traffic is not "
				+ "encrypted.\n"
				+ "Safe: Docker containers on this machine.\n"
				+ "Unsafe: connections over network.\n"
				+ "For network access, keep this disabled and use "
				+ "SSH port forwarding\nfrom the remote machine "
				+ "to this Eclipse's local port.");
		GridData warningLayout = new GridData(
				SWT.FILL, SWT.CENTER, true, false);
		warningLayout.horizontalSpan = 6;
		warningLayout.widthHint = 400;
		remoteWarningLabel.setLayoutData(warningLayout);

		// Toggle remote content enabled state
		remoteEnabledCheckbox.addSelectionListener(
				new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				boolean remoteSelected =
						remoteEnabledCheckbox.getSelection();
				setRemoteContentEnabled(remoteSelected);
				remoteStatusLabel.setText(
						remoteSelected ? "(enabled)" : "(disabled)");
				remoteStatusLabel.getParent().layout();
			}
		});
	}

	private void createTokenButtons(Composite parent,
			Text tokenField) {
		Button copyButton = new Button(parent, SWT.PUSH);
		copyButton.setText("Copy");
		copyButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				String fullToken = (String) tokenField.getData(
						"fullToken");
				if (fullToken != null && !fullToken.isEmpty()) {
					org.eclipse.swt.dnd.Clipboard clipboard =
							new org.eclipse.swt.dnd.Clipboard(
									getShell().getDisplay());
					clipboard.setContents(
							new Object[] { fullToken },
							new org.eclipse.swt.dnd.Transfer[] {
								org.eclipse.swt.dnd.TextTransfer
										.getInstance() });
					clipboard.dispose();
				}
			}
		});

		Button replaceButton = new Button(parent, SWT.PUSH);
		replaceButton.setText("Replace...");
		replaceButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent selectionEvent) {
				InputDialog replaceDialog = new InputDialog(
						getShell(), "Replace Token",
						"Enter token (leave empty to auto-generate):",
						"", null);
				if (replaceDialog.open() == Window.OK) {
					String newToken = replaceDialog.getValue().trim();
					if (newToken.isEmpty()) {
						newToken = generateToken();
					}
					tokenField.setText(maskToken(newToken));
					tokenField.setData("fullToken", newToken);
				}
			}
		});

	}

	private void setRemoteContentEnabled(boolean enabled) {
		setEnabledRecursive(remoteContent, enabled);
	}

	private void setEnabledRecursive(Composite composite,
			boolean enabled) {
		for (Control child : composite.getChildren()) {
			child.setEnabled(enabled);
			if (child instanceof Composite childComposite) {
				setEnabledRecursive(childComposite, enabled);
			}
		}
	}

	private void loadValues() {
		IPreferenceStore store = getPreferenceStore();
		terminalCommandField.setText(
				store.getString(PreferenceConstants.TERMINAL_COMMAND));

		// Local
		int localPort = store.getInt(PreferenceConstants.LOCAL_PORT);
		localPortField.setText(String.valueOf(localPort));
		localRegenerateTokenCheckbox.setSelection(
				store.getBoolean(
						PreferenceConstants.LOCAL_REGENERATE_TOKEN));
		String localToken = store.getString(
				PreferenceConstants.LOCAL_TOKEN);
		if (localToken.isEmpty()) {
			var pluginActivator =
					io.github.kaluchi.jdtbridge.Activator
							.getInstance();
			if (pluginActivator != null) {
				localToken = pluginActivator.getLocalToken();
				if (localToken == null) localToken = "";
			}
		}
		localTokenField.setText(
				localToken.isEmpty() ? "(auto)" : maskToken(localToken));
		localTokenField.setData("fullToken", localToken);

		// Remote
		boolean remoteEnabled = store.getBoolean(
				PreferenceConstants.REMOTE_ENABLED);
		remoteEnabledCheckbox.setSelection(remoteEnabled);
		remoteStatusLabel.setText(
				remoteEnabled ? "(enabled)" : "(disabled)");
		remotePortField.setText(String.valueOf(
				store.getInt(PreferenceConstants.REMOTE_PORT)));
		remoteRegenerateTokenCheckbox.setSelection(
				store.getBoolean(
						PreferenceConstants.REMOTE_REGENERATE_TOKEN));
		String remoteToken = store.getString(
				PreferenceConstants.REMOTE_TOKEN);
		if (remoteToken.isEmpty()) {
			var pluginActivator =
					io.github.kaluchi.jdtbridge.Activator
							.getInstance();
			if (pluginActivator != null) {
				String liveRemoteToken =
						pluginActivator.getRemoteToken();
				if (liveRemoteToken != null
						&& !liveRemoteToken.isEmpty()) {
					remoteToken = liveRemoteToken;
				}
			}
		}
		remoteTokenField.setText(
				remoteToken.isEmpty()
						? "(not set)" : maskToken(remoteToken));
		remoteTokenField.setData("fullToken", remoteToken);

		setRemoteContentEnabled(remoteEnabled);
	}

	@Override
	public boolean performOk() {
		if (!validatePort(localPortField, "Local port"))
			return false;
		if (remoteEnabledCheckbox.getSelection()) {
			if (!validatePort(remotePortField, "Remote port"))
				return false;
			int remotePortValue = Integer.parseInt(
					remotePortField.getText().trim());
			if (remotePortValue == 0) {
				setErrorMessage(
						"Remote port must be fixed (1024\u201365535)."
						+ " Auto-assign not supported for remote.");
				return false;
			}
			int localPortValue = Integer.parseInt(
					localPortField.getText().trim());
			if (localPortValue != 0
					&& localPortValue == remotePortValue) {
				setErrorMessage(
						"Remote port must differ from local port.");
				return false;
			}
		}

		warnRunningAgents();

		IPreferenceStore store = getPreferenceStore();
		store.setValue(PreferenceConstants.TERMINAL_COMMAND,
				terminalCommandField.getText().trim());

		// Local
		store.setValue(PreferenceConstants.LOCAL_PORT,
				Integer.parseInt(localPortField.getText().trim()));
		store.setValue(PreferenceConstants.LOCAL_REGENERATE_TOKEN,
				localRegenerateTokenCheckbox.getSelection());
		String localFullToken = (String) localTokenField.getData(
				"fullToken");
		if (localFullToken != null) {
			store.setValue(PreferenceConstants.LOCAL_TOKEN,
					localFullToken);
		}

		// Remote
		store.setValue(PreferenceConstants.REMOTE_ENABLED,
				remoteEnabledCheckbox.getSelection());
		store.setValue(PreferenceConstants.REMOTE_PORT,
				Integer.parseInt(remotePortField.getText().trim()));
		store.setValue(PreferenceConstants.REMOTE_REGENERATE_TOKEN,
				remoteRegenerateTokenCheckbox.getSelection());
		String remoteFullToken = (String) remoteTokenField.getData(
				"fullToken");
		if (remoteFullToken != null) {
			store.setValue(PreferenceConstants.REMOTE_TOKEN,
					remoteFullToken);
		}

		return true;
	}

	@Override
	protected void performDefaults() {
		IPreferenceStore store = getPreferenceStore();
		terminalCommandField.setText(
				store.getDefaultString(
						PreferenceConstants.TERMINAL_COMMAND));

		localPortField.setText("0");
		localRegenerateTokenCheckbox.setSelection(true);
		localTokenField.setText("(auto)");
		localTokenField.setData("fullToken", "");

		remoteEnabledCheckbox.setSelection(false);
		remotePortField.setText("0");
		remoteRegenerateTokenCheckbox.setSelection(false);
		remoteTokenField.setText("(not set)");
		remoteTokenField.setData("fullToken", "");
		setRemoteContentEnabled(false);

		localPortStatusLabel.setText("");
		remotePortStatusLabel.setText("");
		remotePortStatusLabel.setText("");

		super.performDefaults();
	}

	private boolean validatePort(Text portTextField, String portLabel) {
		String portText = portTextField.getText().trim();
		try {
			int portNumber = Integer.parseInt(portText);
			if (portNumber == 0) return true;
			if (portNumber < 1024 || portNumber > 65535) {
				setErrorMessage(
						portLabel
						+ " must be 0 (auto) or 1024\u201365535.");
				return false;
			}
			setErrorMessage(null);
			return true;
		} catch (NumberFormatException invalidPortNumber) {
			setErrorMessage(portLabel + " must be a number.");
			return false;
		}
	}

	private void checkPortAvailability(Text portTextField,
			Label statusLabel, InetAddress probeAddress) {
		String portText = portTextField.getText().trim();
		try {
			int portNumber = Integer.parseInt(portText);
			if (portNumber == 0) {
				var pluginActivator =
						io.github.kaluchi.jdtbridge.Activator
								.getInstance();
				if (pluginActivator != null) {
					int currentPort = probeAddress.isLoopbackAddress()
							? pluginActivator.getLocalPort()
							: pluginActivator.getRemotePort();
					statusLabel.setText(currentPort > 0
							? "current: " + currentPort
							: "not running");
				} else {
					statusLabel.setText("auto");
				}
				statusLabel.getParent().layout();
				return;
			}
			if (portNumber < 1024 || portNumber > 65535) {
				statusLabel.setText("invalid range");
				return;
			}
			try (ServerSocket portProbe = new ServerSocket(
					portNumber, 1, probeAddress)) {
				statusLabel.setText("available");
			} catch (Exception portBindException) {
				statusLabel.setText("in use");
			}
		} catch (NumberFormatException invalidPortNumber) {
			statusLabel.setText("invalid");
		}
		statusLabel.getParent().layout();
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

	private void copyActualPort(boolean isLocal) {
		var pluginActivator =
				io.github.kaluchi.jdtbridge.Activator.getInstance();
		int actualPort = -1;
		if (pluginActivator != null) {
			actualPort = isLocal
					? pluginActivator.getLocalPort()
					: pluginActivator.getRemotePort();
		}
		if (actualPort > 0) {
			org.eclipse.swt.dnd.Clipboard clipboard =
					new org.eclipse.swt.dnd.Clipboard(
							getShell().getDisplay());
			clipboard.setContents(
					new Object[] { String.valueOf(actualPort) },
					new org.eclipse.swt.dnd.Transfer[] {
						org.eclipse.swt.dnd.TextTransfer
								.getInstance() });
			clipboard.dispose();
		}
	}

	private static String maskToken(String fullToken) {
		if (fullToken == null || fullToken.length() < 5) {
			return "******";
		}
		return "******" + fullToken.substring(
				fullToken.length() - 5);
	}

	private static String generateToken() {
		byte[] tokenBytes = new byte[16];
		new SecureRandom().nextBytes(tokenBytes);
		StringBuilder hexBuilder = new StringBuilder(32);
		for (byte tokenByte : tokenBytes) {
			hexBuilder.append(String.format("%02x",
					tokenByte & 0xff));
		}
		return hexBuilder.toString();
	}
}
