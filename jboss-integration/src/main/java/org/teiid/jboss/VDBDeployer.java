/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.teiid.jboss;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceBuilder.DependencyType;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.teiid.adminapi.Translator;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.adminapi.impl.VDBTranslatorMetaData;
import org.teiid.deployers.TeiidAttachments;
import org.teiid.deployers.UDFMetaData;
import org.teiid.deployers.VDBRepository;
import org.teiid.dqp.internal.datamgr.TranslatorRepository;
import org.teiid.logging.LogConstants;
import org.teiid.logging.LogManager;
import org.teiid.metadata.index.IndexMetadataFactory;
import org.teiid.query.ObjectReplicator;
import org.teiid.runtime.RuntimePlugin;
import org.teiid.services.BufferServiceImpl;


class VDBDeployer implements DeploymentUnitProcessor {
			
	private TranslatorRepository translatorRepository;
	private String asyncThreadPoolName;
	
	public VDBDeployer (TranslatorRepository translatorRepo, String poolName) {
		this.translatorRepository = translatorRepo;
		this.asyncThreadPoolName = poolName;
	}
	
	public void deploy(final DeploymentPhaseContext context)  throws DeploymentUnitProcessingException {
		DeploymentUnit deploymentUnit = context.getDeploymentUnit();
		if (!TeiidAttachments.isVDBDeployment(deploymentUnit)) {
			return;
		}
		final String deploymentName = deploymentUnit.getName();
		VDBMetaData deployment = deploymentUnit.getAttachment(TeiidAttachments.VDB_METADATA);
		
		// check to see if there is old vdb already deployed.
        final ServiceController<?> controller = context.getServiceRegistry().getService(TeiidServiceNames.vdbServiceName(deployment.getName(), deployment.getVersion()));
        if (controller != null) {
        	LogManager.logInfo(LogConstants.CTX_RUNTIME, RuntimePlugin.Util.getString("redeploying_vdb", deployment)); //$NON-NLS-1$
            controller.setMode(ServiceController.Mode.REMOVE);
        }
		
		boolean preview = deployment.isPreview();
		if (!preview) {
			List<String> errors = deployment.getValidityErrors();
			if (errors != null && !errors.isEmpty()) {
				throw new DeploymentUnitProcessingException(RuntimePlugin.Util.getString("validity_errors_in_vdb", deployment)); //$NON-NLS-1$
			}
		}
				
		// add required connector managers; if they are not already there
		for (Translator t: deployment.getOverrideTranslators()) {
			VDBTranslatorMetaData data = (VDBTranslatorMetaData)t;
			
			String type = data.getType();
			Translator parent = this.translatorRepository.getTranslatorMetaData(type);
			if ( parent == null) {
				throw new DeploymentUnitProcessingException(RuntimePlugin.Util.getString("translator_type_not_found", deploymentName)); //$NON-NLS-1$
			}
		}
				
		// check if this is a VDB with index files, if there are then build the TransformationMetadata
		UDFMetaData udf = deploymentUnit.getAttachment(TeiidAttachments.UDF_METADATA);
		if (udf != null) {
			deployment.addAttchment(UDFMetaData.class, udf);
		}

		IndexMetadataFactory indexFactory = deploymentUnit.getAttachment(TeiidAttachments.INDEX_METADATA);
		if (indexFactory != null) {
			deployment.addAttchment(IndexMetadataFactory.class, indexFactory);
		}

		// remove the metadata objects as attachments
		deploymentUnit.removeAttachment(TeiidAttachments.INDEX_METADATA);
		deploymentUnit.removeAttachment(TeiidAttachments.UDF_METADATA);
		
		// build a VDB service
		ArrayList<String> unAvailableDS = new ArrayList<String>();
		VDBService vdb = new VDBService(deployment);
		ServiceBuilder<VDBMetaData> vdbService = context.getServiceTarget().addService(TeiidServiceNames.vdbServiceName(deployment.getName(), deployment.getVersion()), vdb);
//		for (ModelMetaData model:deployment.getModelMetaDatas().values()) {
//			for (String sourceName:model.getSourceNames()) {
//				vdbService.addDependency(ServiceName.JBOSS.append("data-source", model.getSourceConnectionJndiName(sourceName)));	//$NON-NLS-1$
//				if (context.getServiceRegistry().getService(ServiceName.JBOSS.append("data-source", model.getSourceConnectionJndiName(sourceName))) == null) { //$NON-NLS-1$
//					unAvailableDS.add(model.getSourceConnectionJndiName(sourceName));
//				}
//			}
//		}
		
		// adding the translator services is redundant, however if one is removed then it is an issue.
		for (Translator t: deployment.getOverrideTranslators()) {
			VDBTranslatorMetaData data = (VDBTranslatorMetaData)t;
			String type = data.getType();
			vdbService.addDependency(TeiidServiceNames.translatorServiceName(type));
		}	
		
		vdbService.addDependency(TeiidServiceNames.VDB_REPO, VDBRepository.class,  vdb.getVDBRepositoryInjector());
		vdbService.addDependency(TeiidServiceNames.TRANSLATOR_REPO, TranslatorRepository.class,  vdb.getTranslatorRepositoryInjector());
		vdbService.addDependency(TeiidServiceNames.executorServiceName(this.asyncThreadPoolName), Executor.class,  vdb.getExecutorInjector());
		vdbService.addDependency(TeiidServiceNames.OBJECT_SERIALIZER, ObjectSerializer.class, vdb.getSerializerInjector());
		vdbService.addDependency(TeiidServiceNames.BUFFER_MGR, BufferServiceImpl.class, vdb.getBufferServiceInjector());
		vdbService.addDependency(DependencyType.OPTIONAL, TeiidServiceNames.OBJECT_REPLICATOR, ObjectReplicator.class, vdb.getObjectReplicatorInjector());
		vdbService.setInitialMode(Mode.PASSIVE).install();
		
		if (!unAvailableDS.isEmpty()) {
			LogManager.logInfo(LogConstants.CTX_RUNTIME, IntegrationPlugin.Util.getString("vdb-inactive", deployment.getName(), deployment.getVersion(), unAvailableDS)); //$NON-NLS-1$
		}
	}


	@Override
	public void undeploy(final DeploymentUnit deploymentUnit) {
		if (!TeiidAttachments.isVDBDeployment(deploymentUnit)) {
			return;
		}		
		
		VDBMetaData deployment = deploymentUnit.getAttachment(TeiidAttachments.VDB_METADATA);
        final ServiceController<?> controller = deploymentUnit.getServiceRegistry().getService(TeiidServiceNames.vdbServiceName(deployment.getName(), deployment.getVersion()));
        if (controller != null) {
        	VDBService vdbService = (VDBService)controller.getService();
        	vdbService.undeployInProgress();
        	
            controller.setMode(ServiceController.Mode.REMOVE);
        }
	}

}
