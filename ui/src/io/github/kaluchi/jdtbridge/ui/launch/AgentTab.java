package io.github.kaluchi.jdtbridge.ui.launch;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.debug.ui.StringVariableSelectionDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.ContainerSelectionDialog;

/**
 * Main tab for agent launch configuration.
 * Provider (local/sandbox), agent name, and working directory
 * with Workspace/File System/Variables buttons (Maven-style).
 */
public class AgentTab extends AbstractLaunchConfigurationTab {

	private Combo providerCombo;
	private Text agentText;
	private Text workingDirText;
	private Button projectScopeCheckbox;
	private Text argsText;

	@Override
	public void createControl(Composite parent) {
		Composite comp = new Composite(parent, SWT.NONE);
		comp.setLayout(new GridLayout(2, false));
		comp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		setControl(comp);

		ModifyListener listener = e -> {
			setDirty(true);
			updateLaunchConfigurationDialog();
		};

		// Provider
		new Label(comp, SWT.NONE).setText("Provider:");
		providerCombo = new Combo(comp, SWT.DROP_DOWN | SWT.READ_ONLY);
		providerCombo.setItems("local", "sandbox");
		providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER,
				true, false));
		providerCombo.addModifyListener(listener);

		// Agent
		new Label(comp, SWT.NONE).setText("Agent:");
		agentText = new Text(comp, SWT.BORDER);
		agentText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER,
				true, false));
		agentText.addModifyListener(listener);

		// Working directory
		new Label(comp, SWT.NONE).setText("Working directory:");
		workingDirText = new Text(comp, SWT.BORDER);
		workingDirText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER,
				true, false));
		workingDirText.addModifyListener(listener);

		// Buttons row
		new Label(comp, SWT.NONE); // spacer
		Composite buttons = new Composite(comp, SWT.NONE);
		buttons.setLayout(new GridLayout(3, false));
		buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER,
				false, false));

		Button workspaceBtn = new Button(buttons, SWT.PUSH);
		workspaceBtn.setText("Workspace...");
		workspaceBtn.addListener(SWT.Selection, e -> browseWorkspace());

		Button fileSystemBtn = new Button(buttons, SWT.PUSH);
		fileSystemBtn.setText("File System...");
		fileSystemBtn.addListener(SWT.Selection, e -> browseFileSystem());

		Button variablesBtn = new Button(buttons, SWT.PUSH);
		variablesBtn.setText("Variables...");
		variablesBtn.addListener(SWT.Selection,
				e -> browseVariables(workingDirText));

		// Project scope checkbox
		new Label(comp, SWT.NONE); // spacer
		projectScopeCheckbox = new Button(comp, SWT.CHECK);
		projectScopeCheckbox.setText(
				"Limit project scope to working directory");
		projectScopeCheckbox.setLayoutData(new GridData(
				SWT.FILL, SWT.CENTER, true, false));
		projectScopeCheckbox.addListener(SWT.Selection, e -> {
			setDirty(true);
			updateLaunchConfigurationDialog();
		});

		// Arguments group
		Group argsGroup = new Group(comp, SWT.NONE);
		argsGroup.setText("Agent arguments");
		argsGroup.setLayout(new GridLayout(1, false));
		argsGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL,
				true, true, 2, 1));

		argsText = new Text(argsGroup,
				SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		GridData argsData = new GridData(SWT.FILL, SWT.FILL,
				true, true);
		argsData.heightHint = 60;
		argsText.setLayoutData(argsData);
		argsText.addModifyListener(listener);

		Composite argsButtons = new Composite(argsGroup, SWT.NONE);
		argsButtons.setLayout(new GridLayout(1, false));
		argsButtons.setLayoutData(new GridData(SWT.END, SWT.CENTER,
				false, false));

		Button argsVarsBtn = new Button(argsButtons, SWT.PUSH);
		argsVarsBtn.setText("Variables...");
		argsVarsBtn.addListener(SWT.Selection,
				e -> browseVariables(argsText));

		Label argsNote = new Label(argsGroup, SWT.WRAP);
		argsNote.setText(
				"Arguments passed after -- to the agent command. "
				+ "Use double quotes for arguments containing spaces.");
		argsNote.setLayoutData(new GridData(SWT.FILL, SWT.CENTER,
				true, false));
	}

	private void browseWorkspace() {
		ContainerSelectionDialog dialog = new ContainerSelectionDialog(
				getShell(),
				ResourcesPlugin.getWorkspace().getRoot(),
				false,
				"Select working directory");
		if (dialog.open() == ContainerSelectionDialog.OK) {
			Object[] result = dialog.getResult();
			if (result != null && result.length > 0) {
				IContainer container = (IContainer) ResourcesPlugin
						.getWorkspace().getRoot()
						.findMember(result[0].toString());
				if (container != null) {
					workingDirText.setText(
							"${workspace_loc:" + result[0] + "}");
				}
			}
		}
	}

	private void browseFileSystem() {
		DirectoryDialog dialog = new DirectoryDialog(getShell());
		dialog.setFilterPath(resolveWorkingDir());
		String result = dialog.open();
		if (result != null) {
			workingDirText.setText(result);
		}
	}

	private void browseVariables(Text target) {
		StringVariableSelectionDialog dialog =
				new StringVariableSelectionDialog(getShell());
		if (dialog.open() == StringVariableSelectionDialog.OK) {
			target.insert(dialog.getVariableExpression());
		}
	}

	private String resolveWorkingDir() {
		String text = workingDirText.getText().trim();
		if (text.isEmpty()) {
			return "";
		}
		try {
			return VariablesPlugin.getDefault()
					.getStringVariableManager()
					.performStringSubstitution(text);
		} catch (CoreException e) {
			return text;
		}
	}

	@Override
	public void setDefaults(
			ILaunchConfigurationWorkingCopy config) {
		config.setAttribute(AgentLaunchDelegate.ATTR_PROVIDER,
				"local");
		config.setAttribute(AgentLaunchDelegate.ATTR_AGENT,
				"claude");
		config.setAttribute(AgentLaunchDelegate.ATTR_WORKING_DIR, "");
		config.setAttribute(AgentLaunchDelegate.ATTR_PROJECT_SCOPE,
				true);
		config.setAttribute(AgentLaunchDelegate.ATTR_AGENT_ARGS, "");
		config.rename("");
	}

	@Override
	public void initializeFrom(ILaunchConfiguration config) {
		try {
			providerCombo.setText(
					config.getAttribute(
							AgentLaunchDelegate.ATTR_PROVIDER, "local"));
			agentText.setText(
					config.getAttribute(
							AgentLaunchDelegate.ATTR_AGENT, "claude"));
			workingDirText.setText(
					config.getAttribute(
							AgentLaunchDelegate.ATTR_WORKING_DIR, ""));
			projectScopeCheckbox.setSelection(
					config.getAttribute(
							AgentLaunchDelegate.ATTR_PROJECT_SCOPE,
							true));
			argsText.setText(
					config.getAttribute(
							AgentLaunchDelegate.ATTR_AGENT_ARGS, ""));
		} catch (CoreException e) {
			setErrorMessage(e.getMessage());
		}
	}

	@Override
	public void performApply(
			ILaunchConfigurationWorkingCopy config) {
		config.setAttribute(AgentLaunchDelegate.ATTR_PROVIDER,
				providerCombo.getText());
		config.setAttribute(AgentLaunchDelegate.ATTR_AGENT,
				agentText.getText().trim());
		config.setAttribute(AgentLaunchDelegate.ATTR_WORKING_DIR,
				workingDirText.getText().trim());
		config.setAttribute(AgentLaunchDelegate.ATTR_PROJECT_SCOPE,
				projectScopeCheckbox.getSelection());
		config.setAttribute(AgentLaunchDelegate.ATTR_AGENT_ARGS,
				argsText.getText().trim());
	}

	@Override
	public boolean isValid(ILaunchConfiguration config) {
		setErrorMessage(null);
		if (providerCombo.getSelectionIndex() < 0) {
			setErrorMessage("Select a provider");
			return false;
		}
		if (agentText.getText().trim().isEmpty()) {
			setErrorMessage("Enter an agent name");
			return false;
		}
		return true;
	}

	@Override
	public String getName() {
		return "Agent";
	}
}
