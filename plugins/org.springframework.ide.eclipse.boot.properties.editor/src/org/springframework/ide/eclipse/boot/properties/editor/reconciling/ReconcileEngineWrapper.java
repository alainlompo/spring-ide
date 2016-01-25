/*******************************************************************************
 * Copyright (c) 2006, 2008, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Kris De Volder - copied from org.eclipse.ui.texteditor.spelling.SpellingReconcileStrategy and modified
 *                      into an adapter/wrapper for a more abstract ReconcileEngine
 *******************************************************************************/
package org.springframework.ide.eclipse.boot.properties.editor.reconciling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ISynchronizable;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.springframework.ide.eclipse.boot.properties.editor.DocumentContextFinder;
import org.springframework.ide.eclipse.boot.properties.editor.IReconcileTrigger;
import org.springframework.ide.eclipse.boot.properties.editor.SpringPropertiesEditorPlugin;
import org.springframework.ide.eclipse.boot.properties.editor.preferences.ProblemSeverityPreferencesUtil;
import org.springframework.ide.eclipse.editor.support.reconcile.IProblemCollector;
import org.springframework.ide.eclipse.editor.support.reconcile.IReconcileEngine;
import org.springframework.ide.eclipse.editor.support.reconcile.ProblemSeverity;
import org.springframework.ide.eclipse.editor.support.reconcile.ReconcileProblem;
import org.springframework.ide.eclipse.editor.support.reconcile.ReconcileProblemAnnotation;

/**
 * Eclipse {@link IReconcilingStrategy} which wraps out own (simpler) abstraction {@link IReconcileEngine} so that implementing
 * a reconciling engine is not so horribly complicated.
 */
public class ReconcileEngineWrapper implements IReconcilingStrategy, IReconcilingStrategyExtension, IPropertyChangeListener {


	/**
	 * Problem collector.
	 */
	private class SeverityAwareProblemCollector implements IProblemCollector {

		/** Annotation model. */
		private IAnnotationModel fAnnotationModel;

		/** Annotations to add. */
		private Map<ReconcileProblemAnnotation, Position> fAddAnnotations;

		/** Lock object for modifying the annotations. */
		private Object fLockObject;

		/**
		 * Initializes this collector with the given annotation model.
		 *
		 * @param annotationModel the annotation model
		 */
		public SeverityAwareProblemCollector(IAnnotationModel annotationModel) {
			Assert.isLegal(annotationModel != null);
			fAnnotationModel= annotationModel;
			if (fAnnotationModel instanceof ISynchronizable)
				fLockObject= ((ISynchronizable)fAnnotationModel).getLockObject();
			else
				fLockObject= fAnnotationModel;
		}

		public void accept(ReconcileProblem problem) {
			ProblemSeverity severity = fSeverities.getSeverity(problem);
			String annotationType = ReconcileProblemAnnotation.getAnnotationType(severity);
			if (annotationType!=null) {
				fAddAnnotations.put(new ReconcileProblemAnnotation(annotationType, problem), new Position(problem.getOffset(), problem.getLength()));
			}
		}

		public void beginCollecting() {
			fAddAnnotations= new HashMap<ReconcileProblemAnnotation, Position>();
		}

		public void endCollecting() {

			List<Annotation> toRemove= new ArrayList<Annotation>();

			synchronized (fLockObject) {
				@SuppressWarnings("unchecked")
				Iterator<ReconcileProblemAnnotation> iter= fAnnotationModel.getAnnotationIterator();
				while (iter.hasNext()) {
					Annotation annotation= iter.next();
					if (ReconcileProblemAnnotation.TYPES.contains(annotation.getType()))
						toRemove.add(annotation);
				}
				Annotation[] annotationsToRemove= toRemove.toArray(new Annotation[toRemove.size()]);

				if (fAnnotationModel instanceof IAnnotationModelExtension)
					((IAnnotationModelExtension)fAnnotationModel).replaceAnnotations(annotationsToRemove, fAddAnnotations);
				else {
					for (int i= 0; i < annotationsToRemove.length; i++)
						fAnnotationModel.removeAnnotation(annotationsToRemove[i]);
					for (iter= fAddAnnotations.keySet().iterator(); iter.hasNext();) {
						Annotation annotation= iter.next();
						fAnnotationModel.addAnnotation(annotation, fAddAnnotations.get(annotation));
					}
				}
			}

			fAddAnnotations= null;
		}
	}


	/** Text content type */
	private static final IContentType TEXT_CONTENT_TYPE= Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT);

	/** The text editor to operate on. */
	private ISourceViewer fViewer;

	private IDocument fDocument;

	private IProgressMonitor fProgressMonitor;

	private SeverityAwareProblemCollector fProblemCollector;

	private IReconcileEngine fEngine;

	private SeverityProvider fSeverities;

	private DocumentContextFinder fDocumentContextFinder;

	private IPreferenceStore fProjectPreferences;

	private IReconcileTrigger fReconcileTrigger;

	public ReconcileEngineWrapper(ISourceViewer viewer, IReconcileEngine engine, DocumentContextFinder documentContextFinder, IReconcileTrigger reconcileTrigger) {
		Assert.isNotNull(viewer);
		fDocumentContextFinder = documentContextFinder;
		fViewer= viewer;
		fEngine = engine;
		fReconcileTrigger = reconcileTrigger;
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension#initialReconcile()
	 */
	public void initialReconcile() {
		reconcile(new Region(0, fDocument.getLength()));
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategy#reconcile(org.eclipse.jface.text.reconciler.DirtyRegion,org.eclipse.jface.text.IRegion)
	 */
	public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
		try {
			IRegion startLineInfo= fDocument.getLineInformationOfOffset(subRegion.getOffset());
			IRegion endLineInfo= fDocument.getLineInformationOfOffset(subRegion.getOffset() + Math.max(0, subRegion.getLength() - 1));
			if (startLineInfo.getOffset() == endLineInfo.getOffset())
				subRegion= startLineInfo;
			else
				subRegion= new Region(startLineInfo.getOffset(), endLineInfo.getOffset() + Math.max(0, endLineInfo.getLength() - 1) - startLineInfo.getOffset());

		} catch (BadLocationException e) {
			subRegion= new Region(0, fDocument.getLength());
		}
		reconcile(subRegion);
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategy#reconcile(org.eclipse.jface.text.IRegion)
	 */
	public void reconcile(IRegion region) {
		if (getAnnotationModel() == null || fProblemCollector == null)
			return;
		//Note: This isn't an 'incremental' reconciler. It always checks the whole document. The dirty
		// region is ignored.
		fSeverities.startReconciling();
		fEngine.reconcile(fDocument, fProblemCollector, fProgressMonitor);
	}



	/**
	 * Returns the content type of the underlying editor input.
	 *
	 * @return the content type of the underlying editor input or
	 *         <code>null</code> if none could be determined
	 */
	protected IContentType getContentType() {
		return TEXT_CONTENT_TYPE;
	}

	/**
	 * Returns the document which is spell checked.
	 *
	 * @return the document
	 */
	protected final IDocument getDocument() {
		return fDocument;
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategy#setDocument(org.eclipse.jface.text.IDocument)
	 */
	public void setDocument(IDocument document) {
		fDocument= document;
		fSeverities = fDocumentContextFinder.getSeverityProvider(fDocument);
		fProblemCollector= createProblemCollector();
		if (fProjectPreferences!=null) {
			fProjectPreferences.removePropertyChangeListener(this);
		}
		IProject project = getProject();
		if (project!=null) {
			fProjectPreferences = new ScopedPreferenceStore(new ProjectScope(project), SpringPropertiesEditorPlugin.PLUGIN_ID);
			fProjectPreferences.addPropertyChangeListener(this);
		}
	}

	private IProject getProject() {
		if (fDocument!=null) {
			IJavaProject jp = fDocumentContextFinder.getJavaProject(fDocument);
			if (jp!=null) {
				return jp.getProject();
			}
		}
		return null;
	}

	protected SeverityAwareProblemCollector createProblemCollector() {
		IAnnotationModel model= getAnnotationModel();
		if (model == null)
			return null;
		return new SeverityAwareProblemCollector(model);
	}

	/*
	 * @see org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension#setProgressMonitor(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public final void setProgressMonitor(IProgressMonitor monitor) {
		fProgressMonitor= monitor;
	}

	/**
	 * Returns the annotation model to be used by this reconcile strategy.
	 *
	 * @return the annotation model of the underlying editor input or
	 *         <code>null</code> if none could be determined
	 */
	protected IAnnotationModel getAnnotationModel() {
		return fViewer.getAnnotationModel();
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (event.getProperty().startsWith(ProblemSeverityPreferencesUtil.PREFERENCE_PREFIX)) {
			fReconcileTrigger.forceReconcile();
		}
	}

}