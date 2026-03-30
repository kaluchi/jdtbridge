package io.github.kaluchi.jdtbridge.ui.launch;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/**
 * Main tab for agent launch configuration.
 * Provider (local/sandbox), agent name, and working directory.
 */
public class AgentTab extends AbstractLaunchConfigurationTab {

	private Combo providerCombo;
	private Text agentText;
	private Text workingDirText;

	@Override
	public void createControl(Composite parent) {
		Composite comp = new Composite(parent, SWT.NONE);
		comp.setLayout(new GridLayout(3, false));
		comp.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		setControl(comp);

		ModifyListener listener = e -> {
			setDirty(true);
			updateLaunchConfigurationDialog();
		};

		// Provider
		Label providerLabel = new Label(comp, SWT.NONE);
		providerLabel.setText("Provider:");
		providerCombo = new Combo(comp, SWT.DROP_DOWN | SWT.READ_ONLY);
		providerCombo.setItems("local", "sandbox");
		providerCombo.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		providerCombo.addModifyListener(listener);

		// Agent
		Label agentLabel = new Label(comp, SWT.NONE);
		agentLabel.setText("Agent:");
		agentText = new Text(comp, SWT.BORDER);
		agentText.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
		agentText.addModifyListener(listener);

		// Working directory
		Label dirLabel = new Label(comp, SWT.NONE);
		dirLabel.setText("Working directory:");
		workingDirText = new Text(comp, SWT.BORDER);
		workingDirText.setLayoutData(
				new GridData(SWT.FILL, SWT.CENTER, true, false));
		workingDirText.addModifyListener(listener);

		Button browseBtn = new Button(comp, SWT.PUSH);
		browseBtn.setText("Browse...");
		browseBtn.addListener(SWT.Selection, e -> {
			DirectoryDialog dialog = new DirectoryDialog(
					parent.getShell());
			dialog.setFilterPath(workingDirText.getText());
			String result = dialog.open();
			if (result != null) {
				workingDirText.setText(result);
			}
		});
	}

	@Override
	public void setDefaults(
			ILaunchConfigurationWorkingCopy config) {
		config.setAttribute(AgentLaunchDelegate.ATTR_PROVIDER, "local");
		config.setAttribute(AgentLaunchDelegate.ATTR_AGENT, "claude");
		config.setAttribute(AgentLaunchDelegate.ATTR_WORKING_DIR, "");
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
