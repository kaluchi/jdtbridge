package io.github.kaluchi.jdtbridge.ui.preferences;

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
	private Button localRegenerateTokenCheckbox;
	private int localConfiguredPort;

	// Remote socket controls
	private Button remoteEnabledCheckbox;
	private Text remotePortField;
	private Text remoteTokenField;
	private Button remoteRegenerateTokenCheckbox;
	private Composite remoteContent;
	private int remoteConfiguredPort;

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
		localHeader.setLayout(new GridLayout(2, false));
		localHeader.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label localLabel = new Label(localHeader, SWT.NONE);
		localLabel.setText("Local 127.0.0.1");

		Button localEnabledCheckbox = new Button(localHeader, SWT.CHECK);
		localEnabledCheckbox.setSelection(true);
		localEnabledCheckbox.setEnabled(false);

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

		localPortField = new Text(
				localContent, SWT.BORDER | SWT.READ_ONLY);
		GridData localPortLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		localPortLayout.widthHint = 200;
		localPortField.setLayoutData(localPortLayout);

		Button localPortCopyButton = new Button(localContent, SWT.PUSH);
		localPortCopyButton.setText("Copy");
		localPortCopyButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				copyActualPort(true);
			}
		});

		createPortReplaceButton(localContent, localPortField, true);

		createPortCheckButton(localContent, localPortField, true);

		// spacer for 6th column
		new Label(localContent, SWT.NONE);

		// Token
		Label localTokenLabel = new Label(localContent, SWT.NONE);
		localTokenLabel.setText("Token:");
		GridData localTokenLabelLayout = new GridData();
		localTokenLabelLayout.widthHint = 40;
		localTokenLabel.setLayoutData(localTokenLabelLayout);

		localTokenField = new Text(
				localContent, SWT.BORDER | SWT.READ_ONLY);
		GridData localTokenLayout = new GridData(
				SWT.LEFT, SWT.CENTER, false, false);
		localTokenLayout.widthHint = 200;
		localTokenField.setLayoutData(localTokenLayout);

		createTokenButtons(localContent, localTokenField);

		// Regenerate checkbox
		localRegenerateTokenCheckbox = new Button(
				localContent, SWT.CHECK);
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
		remoteHeader.setLayout(new GridLayout(2, false));
		remoteHeader.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));

		Label remoteLabel = new Label(remoteHeader, SWT.NONE);
		remoteLabel.setText("Remote 0.0.0.0");

		remoteEnabledCheckbox = new Button(remoteHeader, SWT.CHECK);

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

		remotePortField = new Text(
				remoteContent, SWT.BORDER | SWT.READ_ONLY);
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
			public void widgetSelected(SelectionEvent e) {
				copyActualPort(false);
			}
		});

		createPortReplaceButton(remoteContent, remotePortField, false);

		createPortCheckButton(remoteContent, remotePortField, false);

		// spacer for 6th column
		new Label(remoteContent, SWT.NONE);

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
		remoteRegenerateTokenCheckbox.setLayoutData(remoteRegenLayout);

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
			public void widgetSelected(SelectionEvent e) {
				boolean remoteSelected =
						remoteEnabledCheckbox.getSelection();
				setRemoteContentEnabled(remoteSelected);
				updatePortStatus(remotePortField,
						remoteConfiguredPort, false,
						remoteSelected);
			}
		});
	}

	private void createPortReplaceButton(Composite parent,
			Text portField, boolean isLocal) {
		Button replaceButton = new Button(parent, SWT.PUSH);
		replaceButton.setText("Replace...");
		replaceButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				int currentPort = isLocal
						? localConfiguredPort : remoteConfiguredPort;
				InputDialog portDialog = new InputDialog(
						getShell(), "Replace Port",
						isLocal
								? "Enter port (0 = auto-assigned by OS):"
								: "Enter port (1024\u201365535, required):",
						String.valueOf(currentPort),
						newValue -> validatePortInput(
								newValue, isLocal));
				if (portDialog.open() == Window.OK) {
					int newPort = Integer.parseInt(
							portDialog.getValue().trim());
					if (isLocal) {
						localConfiguredPort = newPort;
					} else {
						remoteConfiguredPort = newPort;
					}
					boolean enabled = isLocal
							|| remoteEnabledCheckbox.getSelection();
					updatePortStatus(portField, newPort, isLocal,
							enabled);
				}
			}
		});
	}

	private void createPortCheckButton(Composite parent,
			Text portField, boolean isLocal) {
		Button checkButton = new Button(parent, SWT.PUSH);
		checkButton.setText("Check");
		checkButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				int configuredPort = isLocal
						? localConfiguredPort : remoteConfiguredPort;
				boolean enabled = isLocal
						|| remoteEnabledCheckbox.getSelection();
				var pluginActivator =
						io.github.kaluchi.jdtbridge.Activator
								.getInstance();
				if (pluginActivator == null) {
					portField.setText(formatPortStatus(
							configuredPort, -1, "not connected",
							enabled));
					return;
				}
				int actualPort = isLocal
						? pluginActivator.getLocalPort()
						: pluginActivator.getRemotePort();
				if (actualPort <= 0) {
					portField.setText(formatPortStatus(
							configuredPort, 0, null, enabled));
					return;
				}
				String bindHost = isLocal
						? "127.0.0.1" : "0.0.0.0";
				try (var socket = new java.net.Socket()) {
					socket.connect(
							new java.net.InetSocketAddress(
									bindHost, actualPort), 2000);
					var out = socket.getOutputStream();
					out.write(("GET /status HTTP/1.1\r\nHost: "
							+ bindHost + ":" + actualPort
							+ "\r\n\r\n").getBytes());
					out.flush();
					byte[] buf = new byte[256];
					socket.setSoTimeout(2000);
					int bytesRead = socket.getInputStream().read(buf);
					String response = new String(buf, 0,
							Math.max(bytesRead, 0));
					String status = response.contains("200")
							? "listening" : response.split("\r?\n")[0];
					portField.setText(formatPortStatus(
							configuredPort, actualPort, status,
							enabled));
				} catch (Exception probeException) {
					portField.setText(formatPortStatus(
							configuredPort, actualPort,
							probeException.getMessage(), enabled));
				}
			}
		});
	}

	private void updatePortStatus(Text portField,
			int configuredPort, boolean isLocal, boolean enabled) {
		var pluginActivator =
				io.github.kaluchi.jdtbridge.Activator.getInstance();
		int actualPort = 0;
		String status = null;
		if (pluginActivator != null) {
			actualPort = isLocal
					? pluginActivator.getLocalPort()
					: pluginActivator.getRemotePort();
			if (actualPort > 0) status = "listening";
		}
		portField.setText(formatPortStatus(
				configuredPort, actualPort, status, enabled));
	}

	private static String formatPortStatus(int configuredPort,
			int actualPort, String status, boolean enabled) {
		if (!enabled) {
			return configuredPort == 0
					? "auto, disabled"
					: configuredPort + " disabled";
		}
		StringBuilder sb = new StringBuilder();
		if (configuredPort == 0) {
			sb.append("auto");
			if (actualPort > 0) {
				sb.append(" \u2192 :").append(actualPort);
			}
		} else {
			sb.append(configuredPort).append(" pinned");
		}
		if (status != null) {
			sb.append(", ").append(status);
		}
		return sb.toString();
	}

	private String validatePortInput(String portText, boolean isLocal) {
		String trimmed = portText.trim();
		if (trimmed.isEmpty()) return "Port cannot be empty.";
		if (!trimmed.chars().allMatch(Character::isDigit))
			return "Port must be a number.";
		try {
			int portNumber = Integer.parseInt(trimmed);
			if (isLocal && portNumber == 0) return null;
			if (!isLocal && portNumber == 0)
				return "Remote port must be fixed (1024\u201365535).";
			if (portNumber < 1024 || portNumber > 65535)
				return "Port must be 0 (auto) or 1024\u201365535.";
			// Cross-check: remote port != local port
			Text otherField = isLocal ? remotePortField : localPortField;
			if (otherField != null) {
				String otherText = otherField.getText().trim();
				try {
					int otherPort = Integer.parseInt(otherText);
					if (otherPort != 0 && portNumber == otherPort)
						return "Must differ from the other socket's port.";
				} catch (NumberFormatException nfe) { /* ignore */ }
			}
			return null;
		} catch (NumberFormatException nfe) {
			return "Port must be a number.";
		}
	}

	private void createTokenButtons(Composite parent, Text tokenField) {
		Button copyButton = new Button(parent, SWT.PUSH);
		copyButton.setText("Copy");
		copyButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				String fullToken = (String) tokenField.getData(
						"fullToken");
				if (fullToken != null && !fullToken.isEmpty()) {
					copyToClipboard(fullToken);
				}
			}
		});

		Button replaceButton = new Button(parent, SWT.PUSH);
		replaceButton.setText("Replace...");
		replaceButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
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
		localConfiguredPort = store.getInt(
				PreferenceConstants.LOCAL_PORT);
		localRegenerateTokenCheckbox.setSelection(
				store.getBoolean(
						PreferenceConstants.LOCAL_REGENERATE_TOKEN));
		String localToken = store.getString(
				PreferenceConstants.LOCAL_TOKEN);
		if (localToken.isEmpty()) {
			var pluginActivator =
					io.github.kaluchi.jdtbridge.Activator.getInstance();
			if (pluginActivator != null) {
				localToken = pluginActivator.getLocalToken();
				if (localToken == null) localToken = "";
			}
		}
		localTokenField.setText(
				localToken.isEmpty() ? "(auto)" : maskToken(localToken));
		localTokenField.setData("fullToken", localToken);
		updatePortStatus(localPortField, localConfiguredPort,
				true, true);

		// Remote
		boolean remoteEnabled = store.getBoolean(
				PreferenceConstants.REMOTE_ENABLED);
		remoteEnabledCheckbox.setSelection(remoteEnabled);
		remoteConfiguredPort = store.getInt(
				PreferenceConstants.REMOTE_PORT);
		remoteRegenerateTokenCheckbox.setSelection(
				store.getBoolean(
						PreferenceConstants.REMOTE_REGENERATE_TOKEN));
		String remoteToken = store.getString(
				PreferenceConstants.REMOTE_TOKEN);
		if (remoteToken.isEmpty()) {
			var pluginActivator =
					io.github.kaluchi.jdtbridge.Activator.getInstance();
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
		updatePortStatus(remotePortField, remoteConfiguredPort,
				false, remoteEnabled);
	}

	@Override
	public boolean performOk() {
		if (remoteEnabledCheckbox.getSelection()) {
			if (remoteConfiguredPort == 0) {
				setErrorMessage(
						"Remote port must be fixed (1024\u201365535).");
				return false;
			}
			if (localConfiguredPort != 0
					&& localConfiguredPort == remoteConfiguredPort) {
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
				localConfiguredPort);
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
				remoteConfiguredPort);
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

		localConfiguredPort = 0;
		localRegenerateTokenCheckbox.setSelection(true);
		localTokenField.setText("(auto)");
		localTokenField.setData("fullToken", "");
		updatePortStatus(localPortField, 0, true, true);

		remoteEnabledCheckbox.setSelection(false);
		remoteConfiguredPort = 0;
		remoteRegenerateTokenCheckbox.setSelection(false);
		remoteTokenField.setText("(not set)");
		remoteTokenField.setData("fullToken", "");
		setRemoteContentEnabled(false);
		updatePortStatus(remotePortField, 0, false, false);

		super.performDefaults();
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
			copyToClipboard(String.valueOf(actualPort));
		}
	}

	private void copyToClipboard(String text) {
		org.eclipse.swt.dnd.Clipboard clipboard =
				new org.eclipse.swt.dnd.Clipboard(
						getShell().getDisplay());
		clipboard.setContents(
				new Object[] { text },
				new org.eclipse.swt.dnd.Transfer[] {
					org.eclipse.swt.dnd.TextTransfer.getInstance() });
		clipboard.dispose();
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
