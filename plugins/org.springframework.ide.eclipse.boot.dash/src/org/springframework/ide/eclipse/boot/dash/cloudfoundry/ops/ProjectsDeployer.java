/*******************************************************************************
 * Copyright (c) 2015, 2016 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.eclipse.boot.dash.cloudfoundry.ops;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.osgi.util.NLS;
import org.springframework.ide.eclipse.boot.dash.BootDashActivator;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.CloudAppDashElement;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.CloudFoundryBootDashModel;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.client.ClientRequests;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.client.v2.CFPushArguments;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.debug.DebugSupport;
import org.springframework.ide.eclipse.boot.dash.cloudfoundry.deployment.CloudApplicationDeploymentProperties;
import org.springframework.ide.eclipse.boot.dash.model.BootDashElement;
import org.springframework.ide.eclipse.boot.dash.model.BootDashModel;
import org.springframework.ide.eclipse.boot.dash.model.LocalRunTarget;
import org.springframework.ide.eclipse.boot.dash.model.RunState;
import org.springframework.ide.eclipse.boot.dash.model.UserInteractions;
import org.springframework.ide.eclipse.boot.dash.util.CancelationTokens;
import org.springframework.ide.eclipse.boot.dash.util.CancelationTokens.CancelationToken;

public class ProjectsDeployer extends CloudOperation {

	private final static String APP_FOUND_TITLE = "Replace Existing Application";

	private final static String APP_FOUND_MESSAGE = "Replace the existing application - {0} - with project: {1}?";

	private final Set<IProject> projectsToDeploy;
	private final UserInteractions ui;
	private final RunState runOrDebug;
	private final DebugSupport debugSupport;

	public ProjectsDeployer(CloudFoundryBootDashModel model,
			UserInteractions ui,
			Set<IProject> projectsToDeploy,
			RunState runOrDebug,
			DebugSupport debugSupport) {
		super("Deploying projects", model);
		this.projectsToDeploy = projectsToDeploy;
		this.ui = ui;
		this.runOrDebug = runOrDebug;
		this.debugSupport = debugSupport;
	}

	protected void doCloudOp(IProgressMonitor monitor) throws Exception, OperationCanceledException {
		monitor.beginTask("Deploy projects", projectsToDeploy.size());
		try {
			for (Iterator<IProject> it = projectsToDeploy.iterator(); it.hasNext();) {
				IProject project = it.next();
				if (monitor.isCanceled()) {
					throw new OperationCanceledException();
				}
				deployProject(project, new SubProgressMonitor(monitor, 1));
			}
		} finally {
			monitor.done();
		}
	}

	private void deployProject(IProject project, IProgressMonitor monitor) throws Exception {
		CloudApplicationDeploymentProperties properties = model.createDeploymentProperties(project, ui, monitor);
		CloudAppDashElement cde = model.ensureApplication(properties.getAppName());
		runAsync("Deploy project '"+project.getName()+"'", properties.getAppName(), (IProgressMonitor progressMonitor) -> {
			ClientRequests client = model.getRunTarget().getClient();
			CancelationToken cancelToken = cde.startOperationStarting();
			Exception error = null;
			try {
				if (client.applicationExists(properties.getAppName())) {
					if (!confirmOverwriteExisting(properties)) {
						throw new OperationCanceledException();
					}
				}
				cde.setDeploymentManifestFile(properties.getManifestFile());
				cde.setProject(project);
				copyTags(project, cde);
				cde.print("Pushing project '"+project.getName()+"'");
				try (CFPushArguments args = properties.toPushArguments(model.getCloudDomains(monitor))) {
					if (isDebugEnabled()) {
						debugSupport.setupEnvVars(args.getEnv());
					}
					client.push(args);
					cde.print("Pushing project '"+project.getName()+"' SUCCEEDED!");
				}
			} catch (Exception e) {
				cde.printError("Pushing FAILED!");
				error = e;
				if (!(e instanceof OperationCanceledException)) {
					BootDashActivator.log(e);
					if (ui != null) {
						String message = e.getMessage() != null && e.getMessage().trim().length() > 0 ? e.getMessage()
								: "Error type: " + e.getClass().getName()
										+ ". Check Error Log view for further details.";
						ui.errorPopup("Operation Failure", message);
					}
				}
			} finally {
				cde.refresh();
				cde.startOperationEnded(error, cancelToken, monitor);
			}
			//Careful, we do this at the very end because if we do it before the refresh, then creating the SSH tunnel will fail as
			// we have not yet obtained the app's info and will fail to determine app guid to connect to.
			if (error==null) {
				if (isDebugEnabled()) {
					debugSupport.createOperation(cde, "Connect Debugger for "+cde.getName() , ui, cancelToken).runOp(progressMonitor);
				}
			}
		});
	}

	private boolean isDebugEnabled() {
		return runOrDebug==RunState.DEBUGGING && debugSupport!=null;
	}

	private void copyTags(IProject project, CloudAppDashElement cde) {
		BootDashElement localElement = findLocalBdeForProject(project);
		if (localElement!=null) {
			copyTags(localElement, cde);
		}
	}

	private BootDashElement findLocalBdeForProject(IProject project) {
		BootDashModel localModel = model.getViewModel().getSectionByTargetId(LocalRunTarget.INSTANCE.getId());
		if (localModel != null) {
			for (BootDashElement bde : localModel.getElements().getValue()) {
				if (project.equals(bde.getProject())) {
					return bde;
				}
			}
		}
		return null;
	}

	private static void copyTags(BootDashElement sourceBde, BootDashElement targetBde) {
		LinkedHashSet<String> tagsToCopy = sourceBde.getTags();
		if (tagsToCopy != null && !tagsToCopy.isEmpty()) {
			LinkedHashSet<String> targetTags = targetBde.getTags();
			for (String tag : tagsToCopy) {
				targetTags.add(tag);
			}
			targetBde.setTags(targetTags);
		}
	}


	private void runAsync(String opName, String appName, JobBody runnable) {
		model.getOperationsExecution(ui).runOpAsynch(new CloudApplicationOperation(opName, model, appName, CancelationTokens.NULL) {
			@Override
			protected void doCloudOp(IProgressMonitor monitor) throws Exception, OperationCanceledException {
				runnable.run(monitor);
			}
		});
	}

	private boolean confirmOverwriteExisting(CloudApplicationDeploymentProperties properties) {
		return ui.confirmOperation(APP_FOUND_TITLE, NLS.bind(APP_FOUND_MESSAGE, properties.getAppName(),
				properties.getProject().getName()));
	}
}