package io.github.kaluchi.jdtbridge.ui.preferences;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.kaluchi.jdtbridge.ui.Activator;

public class BridgePreferencePage extends FieldEditorPreferencePage
		implements IWorkbenchPreferencePage {

	public BridgePreferencePage() {
		super(GRID);
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
		setDescription("JDT Bridge settings for AI agent integration.");
	}

	@Override
	public void init(IWorkbench workbench) {
		// nothing to initialize
	}

	@Override
	protected void createFieldEditors() {
		addField(new StringFieldEditor(
				PreferenceConstants.TERMINAL_COMMAND,
				"Terminal command:",
				getFieldEditorParent()));
	}
}
