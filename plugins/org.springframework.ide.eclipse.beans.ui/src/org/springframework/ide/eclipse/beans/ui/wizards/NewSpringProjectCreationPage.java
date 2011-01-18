/*******************************************************************************
 * Copyright (c) 2006, 2007 Spring IDE Developers
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Spring IDE Developers - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.beans.ui.wizards;

import java.util.Set;
import java.util.StringTokenizer;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.WizardNewProjectCreationPage;
import org.springframework.ide.eclipse.beans.core.model.IBeansProject;
import org.springframework.ide.eclipse.core.StringUtils;
import org.springframework.ide.eclipse.ui.SpringUIUtils;

/**
 * @author Torsten Juergeleit
 * @author Christian Dupuis
 */
public class NewSpringProjectCreationPage extends WizardNewProjectCreationPage {

	private Button isJavaButton;
	private Text sourceDirText;
	private Label sourceDirLabel;
	private Text outputDirText;
	private Label outputDirLabel;
	private Text suffixesText;
	
	private Button enableProjectFacetsButton;

	public NewSpringProjectCreationPage(String pageName) {
		super(pageName);
	}

	public boolean isJavaProject() {
		return isJavaButton.getSelection();
	}

	public boolean enableProjectFacets() {
		return enableProjectFacetsButton.getSelection();
	}

	public String getSourceDirectory() {
		return sourceDirText.getText();
	}

	public String getOutputDirectory() {
		return outputDirText.getText();
	}

	public Set<String> getConfigSuffixes() {
		return StringUtils.commaDelimitedListToSet(suffixesText.getText());
	}

	@Override
	public void createControl(Composite parent) {
		super.createControl(parent);
		Composite control = (Composite)getControl();
		GridLayout layout = new GridLayout();
		layout.verticalSpacing = 10;
		control.setLayout(layout);

		createProjectTypeGroup(control);

		Dialog.applyDialogFont(control);
		setControl(control);
	}

	private void createProjectTypeGroup(Composite container) {
		Group springGroup = new Group(container, SWT.NONE);
		springGroup.setText(BeansWizardsMessages.NewProjectPage_springSettings);
		GridLayout dirLayout = new GridLayout();
		dirLayout.numColumns = 1;
		springGroup.setLayout(dirLayout);
		springGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		suffixesText = SpringUIUtils.createTextField(springGroup,
				BeansWizardsMessages.NewProjectPage_suffixes);
		suffixesText.setText(IBeansProject.DEFAULT_CONFIG_SUFFIX);
		suffixesText.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				setPageComplete(validatePage());
			}
		});

		Group javaGroup = new Group(container, SWT.NONE);
		javaGroup.setText(BeansWizardsMessages.NewProjectPage_javaSettings);
		GridLayout layout = new GridLayout();
		layout.numColumns = 2;
		javaGroup.setLayout(layout);
		javaGroup.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		isJavaButton = createButton(javaGroup, SWT.CHECK, 2, 0);
		isJavaButton.setText(BeansWizardsMessages.NewProjectPage_java);
		isJavaButton.setSelection(true);
		isJavaButton.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				boolean enabled = isJavaButton.getSelection();
				sourceDirLabel.setEnabled(enabled);
				sourceDirText.setEnabled(enabled);
				outputDirLabel.setEnabled(enabled);
				outputDirText.setEnabled(enabled);
				setPageComplete(validatePage());
			}
		});

		sourceDirLabel = createLabel(javaGroup,
				BeansWizardsMessages.NewProjectPage_source);
		sourceDirText = createText(javaGroup);
		IPreferenceStore store = PreferenceConstants.getPreferenceStore();
		sourceDirText.setText(store
				.getString(PreferenceConstants.SRCBIN_SRCNAME));

		outputDirLabel = createLabel(javaGroup,
				BeansWizardsMessages.NewProjectPage_output);
		outputDirText = createText(javaGroup);
		outputDirText.setText(store
				.getString(PreferenceConstants.SRCBIN_BINNAME));
		
		enableProjectFacetsButton = createButton(container, SWT.CHECK, 2, 0);
		enableProjectFacetsButton.setText(BeansWizardsMessages.NewProjectPage_enableProjectFacets);
		enableProjectFacetsButton.setSelection(false);
	}

	private Button createButton(Composite container, int style, int span,
			int indent) {
		Button button = new Button(container, style);
		GridData gd = new GridData();
		gd.horizontalSpan = span;
		gd.horizontalIndent = indent;
		button.setLayoutData(gd);
		return button;
	}

	private Label createLabel(Composite container, String text) {
		Label label = new Label(container, SWT.NONE);
		label.setText(text);
		GridData gd = new GridData();
		gd.horizontalIndent = 30;
		label.setLayoutData(gd);
		return label;
	}

	private Text createText(Composite container) {
		Text text = new Text(container, SWT.BORDER | SWT.SINGLE);
		GridData gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.widthHint = 300;
		text.setLayoutData(gd);
		text.addModifyListener(new ModifyListener() {
			public void modifyText(ModifyEvent e) {
				setPageComplete(validatePage());
			}
		});
		return text;
	}

	@Override
	protected boolean validatePage() {
		if (!super.validatePage()) {
			return false;
		}

		if (isJavaButton.getSelection()) {
			IWorkspace workspace = ResourcesPlugin.getWorkspace();
			IProject dummy = workspace.getRoot().getProject("project");
			IStatus status;
			if (sourceDirText != null &&
					sourceDirText.getText().length() != 0) {
				status = workspace.validatePath(dummy.getFullPath().append(
						sourceDirText.getText()).toString(), IResource.FOLDER);
				if (!status.isOK()) {
					setErrorMessage(status.getMessage());
					return false;
				}
			}
			if (outputDirText != null &&
					outputDirText.getText().length() != 0) {
				status = workspace.validatePath(dummy.getFullPath().append(
						outputDirText.getText()).toString(), IResource.FOLDER);
				if (!status.isOK()) {
					setErrorMessage(status.getMessage());
					return false;
				}
			}
		}

		String suffixes = suffixesText.getText().trim();
		if (suffixes.length() == 0) {
			setErrorMessage(BeansWizardsMessages.NewProjectPage_noSuffixes);
			return false;
		}
		StringTokenizer tokenizer = new StringTokenizer(suffixes, ",");
		while (tokenizer.hasMoreTokens()) {
			String suffix = tokenizer.nextToken().trim();
			if (!isValidSuffix(suffix)) {
				setErrorMessage(BeansWizardsMessages.NewProjectPage_invalidSuffixes);
				return false;
			}
		}
		return true;
	}

	private boolean isValidSuffix(String suffix) {
		if (suffix.length() == 0) {
			return false;
		}
		return true;
	}
}