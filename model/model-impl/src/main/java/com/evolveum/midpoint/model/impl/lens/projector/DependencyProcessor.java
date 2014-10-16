/*
 * Copyright (c) 2010-2014 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.midpoint.model.impl.lens.projector;

import static com.evolveum.midpoint.common.InternalsConfig.consistencyChecks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.xml.datatype.XMLGregorianCalendar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.common.Clock;
import com.evolveum.midpoint.common.refinery.ResourceShadowDiscriminator;
import com.evolveum.midpoint.model.api.PolicyViolationException;
import com.evolveum.midpoint.model.api.context.ModelState;
import com.evolveum.midpoint.model.api.context.SynchronizationPolicyDecision;
import com.evolveum.midpoint.model.impl.lens.LensContext;
import com.evolveum.midpoint.model.impl.lens.LensFocusContext;
import com.evolveum.midpoint.model.impl.lens.LensObjectDeltaOperation;
import com.evolveum.midpoint.model.impl.lens.LensProjectionContext;
import com.evolveum.midpoint.model.impl.lens.LensUtil;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.xml.XmlTypeConverter;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ResourceTypeUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SecurityViolationException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceObjectTypeDependencyStrictnessType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceObjectTypeDependencyType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ShadowType;

/**
 * @author Radovan Semancik
 *
 */
@Component
public class DependencyProcessor {
		
	private static final Trace LOGGER = TraceManager.getTrace(DependencyProcessor.class);
	
	@Autowired
    private ProvisioningService provisioningService;
	
	public <F extends ObjectType> void resetWaves(LensContext<F> context) throws PolicyViolationException {
	}
	
	public <F extends ObjectType> void sortProjectionsToWaves(LensContext<F> context) throws PolicyViolationException {
		// Create a snapshot of the projection collection at the beginning of computation.
		// The collection may be changed during computation (projections may be added). We do not want to process
		// these added projections. They are already processed inside the computation.
		// This also avoids ConcurrentModificationException
		LensProjectionContext[] projectionArray = context.getProjectionContexts().toArray(new LensProjectionContext[0]);
		
		// Reset incomplete flag for those contexts that are not yet computed
		for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
			if (projectionContext.getWave() < 0) {
				projectionContext.setWaveIncomplete(true);
			}
		}
		
		for (LensProjectionContext projectionContext: projectionArray) {
			determineProjectionWave(context, projectionContext, null, null);
			projectionContext.setWaveIncomplete(false);
		}
		
		if (LOGGER.isTraceEnabled()) {
			StringBuilder sb = new StringBuilder();
			for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
				sb.append("\n");
				sb.append(projectionContext.getResourceShadowDiscriminator());
				sb.append(": ");
				sb.append(projectionContext.getWave());
			}
			LOGGER.trace("Projections sorted to waves (projection wave {}, execution wave {}):{}", 
					new Object[]{context.getProjectionWave(), context.getExecutionWave(), sb.toString()});
		}
	}
	
	public <F extends ObjectType> int computeMaxWaves(LensContext<F> context) {
		// Let's do one extra wave with no accounts in it. This time we expect to get the results of the execution to the user
        // via inbound, e.g. identifiers generated by the resource, DNs and similar things. Hence the +2 instead of +1
		return context.getMaxWave() + 2;
	}

	private <F extends ObjectType> LensProjectionContext determineProjectionWave(LensContext<F> context, 
			LensProjectionContext projectionContext, ResourceObjectTypeDependencyType inDependency, List<ResourceObjectTypeDependencyType> depPath) throws PolicyViolationException {
		if (!projectionContext.isWaveIncomplete()) {
			// This was already processed
			return projectionContext;
		}
		if (projectionContext.isDelete()) {
			// When deprovisioning (deleting) the dependencies needs to be processed in reverse
			LOGGER.trace("Determining wave for (deprovision): {}", projectionContext);
			return determineProjectionWaveDeprovision(context, projectionContext, inDependency, depPath);
		} else {
			LOGGER.trace("Determining wave for (provision): {}", projectionContext);
			return determineProjectionWaveProvision(context, projectionContext, inDependency, depPath);
		}
	}
	
	private <F extends ObjectType> LensProjectionContext determineProjectionWaveProvision(LensContext<F> context, 
			LensProjectionContext projectionContext, ResourceObjectTypeDependencyType inDependency, List<ResourceObjectTypeDependencyType> depPath) throws PolicyViolationException {
		if (depPath == null) {
			depPath = new ArrayList<ResourceObjectTypeDependencyType>();
		}
		int determinedWave = 0;
		int determinedOrder = 0;
		for (ResourceObjectTypeDependencyType outDependency: projectionContext.getDependencies()) {
			if (LOGGER.isTraceEnabled()) {
				LOGGER.trace("DEP: {}", outDependency);
			}
			if (inDependency != null && isHigerOrder(outDependency, inDependency)) {
				// There is incomming dependency. Deal only with dependencies of this order and lower
				// otherwise we can end up in endless loop even for legal dependencies.
				continue;
			}
			checkForCircular(depPath, outDependency);
			depPath.add(outDependency);
			ResourceShadowDiscriminator refDiscr = new ResourceShadowDiscriminator(outDependency, 
					projectionContext.getResource().getOid(), projectionContext.getKind());
			LensProjectionContext dependencyProjectionContext = findDependencyTargetContext(context, projectionContext, outDependency);
//			if (LOGGER.isTraceEnabled()) {
//				LOGGER.trace("DEP: {} -> {}", refDiscr, dependencyProjectionContext);
//			}
			if (dependencyProjectionContext == null || dependencyProjectionContext.isDelete()) {
				ResourceObjectTypeDependencyStrictnessType outDependencyStrictness = ResourceTypeUtil.getDependencyStrictness(outDependency);
				if (outDependencyStrictness == ResourceObjectTypeDependencyStrictnessType.STRICT) {
					throw new PolicyViolationException("Unsatisfied strict dependency of account "+projectionContext.getResourceShadowDiscriminator()+
						" dependent on "+refDiscr+": Account not provisioned");
				} else if (outDependencyStrictness == ResourceObjectTypeDependencyStrictnessType.LAX) {
					// independent object not in the context, just ignore it
					LOGGER.debug("Unsatisfied lax dependency of account "+projectionContext.getResourceShadowDiscriminator()+
						" dependent on "+refDiscr+"; dependency skipped");
				} else if (outDependencyStrictness == ResourceObjectTypeDependencyStrictnessType.RELAXED) {
					// independent object not in the context, just ignore it
					LOGGER.debug("Unsatisfied relaxed dependency of account "+projectionContext.getResourceShadowDiscriminator()+
						" dependent on "+refDiscr+"; dependency skipped");
				} else {
					throw new IllegalArgumentException("Unknown dependency strictness "+outDependency.getStrictness()+" in "+refDiscr);
				}
			} else {
				dependencyProjectionContext = determineProjectionWave(context, dependencyProjectionContext, outDependency, depPath);
				if (dependencyProjectionContext.getWave() + 1 > determinedWave) {
					determinedWave = dependencyProjectionContext.getWave() + 1;
					if (outDependency.getOrder() == null) {
						determinedOrder = 0;
					} else {
						determinedOrder = outDependency.getOrder();
					}
				}
			}
			depPath.remove(outDependency);
		}
		LensProjectionContext resultAccountContext = projectionContext; 
		if (projectionContext.getWave() >=0 && projectionContext.getWave() != determinedWave) {
			// Wave for this context was set during the run of this method (it was not set when we
			// started, we checked at the beginning). Therefore this context must have been visited again.
			// therefore there is a circular dependency. Therefore we need to create another context to split it.
			resultAccountContext = createAnotherContext(context, projectionContext, determinedOrder);
		}
//		LOGGER.trace("Wave for {}: {}", resultAccountContext.getResourceAccountType(), wave);
		resultAccountContext.setWave(determinedWave);
		return resultAccountContext;
	}
	
	private <F extends ObjectType> LensProjectionContext determineProjectionWaveDeprovision(LensContext<F> context, 
				LensProjectionContext projectionContext, ResourceObjectTypeDependencyType inDependency, List<ResourceObjectTypeDependencyType> depPath) throws PolicyViolationException {
		if (depPath == null) {
			depPath = new ArrayList<ResourceObjectTypeDependencyType>();
		}
		int determinedWave = 0;
		int determinedOrder = 0;
		
		// This needs to go in the reverse. We need to figure out who depends on us.
		for (DependencyAndSource ds: findReverseDependecies(context, projectionContext)) {
			LensProjectionContext dependencySourceContext = ds.sourceProjectionContext;
			ResourceObjectTypeDependencyType outDependency = ds.dependency;
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("DEP(rev): {}", outDependency);
				}
				LOGGER.trace("projection context: {}", projectionContext );
				LOGGER.trace("dependency source context: {}", dependencySourceContext);
				LOGGER.trace("in dependency {} \nout dependency {}", inDependency, outDependency);
			if (inDependency != null && isHigerOrder(outDependency, inDependency)) {
				// There is incomming dependency. Deal only with dependencies of this order and lower
				// otherwise we can end up in endless loop even for legal dependencies.
				continue;
			}
			checkForCircular(depPath, outDependency);
			depPath.add(outDependency);
			ResourceShadowDiscriminator refDiscr = new ResourceShadowDiscriminator(outDependency, 
					projectionContext.getResource().getOid(), projectionContext.getKind());
			dependencySourceContext = determineProjectionWave(context, dependencySourceContext, outDependency, depPath);
			if (dependencySourceContext.getWave() + 1 > determinedWave) {
				determinedWave = dependencySourceContext.getWave() + 1;
				if (outDependency.getOrder() == null) {
					determinedOrder = 0;
				} else {
					determinedOrder = outDependency.getOrder();
				}
			}
			depPath.remove(outDependency);
		}

		LensProjectionContext resultAccountContext = projectionContext; 
		if (projectionContext.getWave() >=0 && projectionContext.getWave() != determinedWave) {
			// Wave for this context was set during the run of this method (it was not set when we
			// started, we checked at the beginning). Therefore this context must have been visited again.
			// therefore there is a circular dependency. Therefore we need to create another context to split it.
			if (!projectionContext.isDelete()){
				resultAccountContext = createAnotherContext(context, projectionContext, determinedOrder);
			}
		}
//			LOGGER.trace("Wave for {}: {}", resultAccountContext.getResourceAccountType(), wave);
		resultAccountContext.setWave(determinedWave);
		return resultAccountContext;
	}
	
	private <F extends ObjectType> Collection<DependencyAndSource> findReverseDependecies(LensContext<F> context, 
			LensProjectionContext targetProjectionContext) throws PolicyViolationException {
		Collection<DependencyAndSource> deps = new ArrayList<>();
		for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
			for (ResourceObjectTypeDependencyType dependency: projectionContext.getDependencies()) {
				if (isDependencyTargetContext(projectionContext, targetProjectionContext, dependency)) {
					DependencyAndSource ds = new DependencyAndSource();
					ds.dependency = dependency;
					ds.sourceProjectionContext = projectionContext;
					deps.add(ds);
				}
			}
		}
		return deps;
	}

	
	private void checkForCircular(List<ResourceObjectTypeDependencyType> depPath,
			ResourceObjectTypeDependencyType outDependency) throws PolicyViolationException {
		for (ResourceObjectTypeDependencyType pathElement: depPath) {
			if (pathElement.equals(outDependency)) {
				StringBuilder sb = new StringBuilder();
				Iterator<ResourceObjectTypeDependencyType> iterator = depPath.iterator();
				while (iterator.hasNext()) {
					ResourceObjectTypeDependencyType el = iterator.next();
					sb.append(el.getResourceRef().getOid());
					if (iterator.hasNext()) {
						sb.append("->");
					}
				}
				throw new PolicyViolationException("Circular dependency, path: "+sb.toString());
			}
		}
	}

	private boolean isHigerOrder(ResourceObjectTypeDependencyType a,
			ResourceObjectTypeDependencyType b) {
		Integer ao = a.getOrder();
		Integer bo = b.getOrder();
		if (ao == null) {
			ao = 0;
		}
		if (bo == null) {
			bo = 0;
		}
		return ao > bo;
	}

	/**
	 * Find context that has the closest order to the dependency.
	 */
	private <F extends ObjectType> LensProjectionContext findDependencyTargetContext(
			LensContext<F> context, LensProjectionContext sourceProjContext, ResourceObjectTypeDependencyType dependency) {
		ResourceShadowDiscriminator refDiscr = new ResourceShadowDiscriminator(dependency, 
				sourceProjContext.getResource().getOid(), sourceProjContext.getKind());
		LensProjectionContext selected = null;
		for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
			if (!projectionContext.compareResourceShadowDiscriminator(refDiscr, false)) {
				continue;
			}
			int ctxOrder = projectionContext.getResourceShadowDiscriminator().getOrder();
			if (ctxOrder > refDiscr.getOrder()) {
				continue;
			}
			if (selected == null) {
				selected = projectionContext;
			} else {
				if (ctxOrder > selected.getResourceShadowDiscriminator().getOrder()) {
					selected = projectionContext;
				}
			}
		}
		return selected;
	}
	
	private <F extends ObjectType> boolean isDependencyTargetContext(LensProjectionContext sourceProjContext, LensProjectionContext targetProjectionContext, ResourceObjectTypeDependencyType dependency) {
		ResourceShadowDiscriminator refDiscr = new ResourceShadowDiscriminator(dependency, 
				sourceProjContext.getResource().getOid(), sourceProjContext.getKind());
		return targetProjectionContext.compareResourceShadowDiscriminator(refDiscr, false);
	}
	
	private <F extends ObjectType> LensProjectionContext createAnotherContext(LensContext<F> context, 
			LensProjectionContext origProjectionContext, int order) throws PolicyViolationException {
		ResourceShadowDiscriminator origDiscr = origProjectionContext.getResourceShadowDiscriminator();
		ResourceShadowDiscriminator discr = new ResourceShadowDiscriminator(origDiscr.getResourceOid(), origDiscr.getKind(), origDiscr.getIntent(), origDiscr.isThombstone());
		discr.setOrder(order);
		LensProjectionContext otherCtx = context.createProjectionContext(discr);
		otherCtx.setResource(origProjectionContext.getResource());
		// Force recon for the new context. This is a primitive way how to avoid phantom changes.
		otherCtx.setDoReconciliation(true);
		return otherCtx;
	}
	
	/**
	 * Check that the dependencies are still satisfied. Also check for high-ordes vs low-order operation consistency
	 * and stuff like that. 
	 */
	public <F extends ObjectType> boolean checkDependencies(LensContext<F> context, 
    		LensProjectionContext projContext, OperationResult result) throws PolicyViolationException {
		if (projContext.isDelete()) {
			// It is OK if we depend on something that is not there if we are being removed ... for now
			return true;
		}
		
		if (projContext.getOid() == null || projContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.ADD) {
			// Check for lower-order contexts
			LensProjectionContext lowerOrderContext = null;
			for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
				if (projContext == projectionContext) {
					continue;
				}
				if (projectionContext.compareResourceShadowDiscriminator(projContext.getResourceShadowDiscriminator(), false) &&
						projectionContext.getResourceShadowDiscriminator().getOrder() < projContext.getResourceShadowDiscriminator().getOrder()) {
					if (projectionContext.getOid() != null) {
						lowerOrderContext = projectionContext;
						break;
					}
				}
			}
			if (lowerOrderContext != null) {
				if (lowerOrderContext.getOid() != null) {
					if (projContext.getOid() == null) {
						projContext.setOid(lowerOrderContext.getOid());
					}
					if (projContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.ADD) {
						// This context cannot be ADD. There is a lower-order context with an OID
						// it means that the lower-order projection exists, we cannot add it twice
						projContext.setSynchronizationPolicyDecision(SynchronizationPolicyDecision.KEEP);
					}
				}
				if (lowerOrderContext.isDelete()) {
					projContext.setSynchronizationPolicyDecision(SynchronizationPolicyDecision.DELETE);
				}
			}
		}		
		
		for (ResourceObjectTypeDependencyType dependency: projContext.getDependencies()) {
			ResourceShadowDiscriminator refRat = new ResourceShadowDiscriminator(dependency, 
					projContext.getResource().getOid(), projContext.getKind());
			LOGGER.trace("LOOKING FOR {}", refRat);
			LensProjectionContext dependencyAccountContext = context.findProjectionContext(refRat);
			ResourceObjectTypeDependencyStrictnessType strictness = ResourceTypeUtil.getDependencyStrictness(dependency);
			if (dependencyAccountContext == null) {
				if (strictness == ResourceObjectTypeDependencyStrictnessType.STRICT) {
					// This should not happen, it is checked before projection
					throw new PolicyViolationException("Unsatisfied strict dependency of "
							+ projContext.getResourceShadowDiscriminator().toHumanReadableString() +
							" dependent on " + refRat.toHumanReadableString() + ": No context in dependency check");
				} else if (strictness == ResourceObjectTypeDependencyStrictnessType.LAX) {
					// independent object not in the context, just ignore it
					LOGGER.trace("Unsatisfied lax dependency of account " + 
							projContext.getResourceShadowDiscriminator().toHumanReadableString() +
							" dependent on " + refRat.toHumanReadableString() + "; dependency skipped");
				} else if (strictness == ResourceObjectTypeDependencyStrictnessType.RELAXED) {
					// independent object not in the context, just ignore it
					LOGGER.trace("Unsatisfied relaxed dependency of account "
							+ projContext.getResourceShadowDiscriminator().toHumanReadableString() +
							" dependent on " + refRat.toHumanReadableString() + "; dependency skipped");
				} else {
					throw new IllegalArgumentException("Unknown dependency strictness "+dependency.getStrictness()+" in "+refRat);
				}
			} else {
				// We have the context of the object that we depend on. We need to check if it was provisioned.
				if (strictness == ResourceObjectTypeDependencyStrictnessType.STRICT
						|| strictness == ResourceObjectTypeDependencyStrictnessType.RELAXED) {
					if (wasProvisioned(dependencyAccountContext, context.getExecutionWave())) {
						// everything OK
					} else {
						// We do not want to throw exception here. That will stop entire projection.
						// Let's just mark the projection as broken and skip it.
						LOGGER.warn("Unsatisfied dependency of account "+projContext.getResourceShadowDiscriminator()+
								" dependent on "+refRat+": Account not provisioned in dependency check (execution wave "+context.getExecutionWave()+", account wave "+projContext.getWave() + ", depenedency account wave "+dependencyAccountContext.getWave()+")");
						projContext.setSynchronizationPolicyDecision(SynchronizationPolicyDecision.BROKEN);
						return false;
					}
				} else if (strictness == ResourceObjectTypeDependencyStrictnessType.LAX) {
					// we don't care what happened, just go on
					return true;
				} else {
					throw new IllegalArgumentException("Unknown dependency strictness "+dependency.getStrictness()+" in "+refRat);
				}
			}
		}
		return true;
	}
	
	public <F extends ObjectType> void preprocessDependencies(LensContext<F> context){
		
		//in the first wave we do not have enougth information to preprocess connetxts
		if (context.getExecutionWave() == 0){
			return;
		}
		
		for (LensProjectionContext projContext : context.getProjectionContexts()){
			if (!projContext.isCanProject()){
				continue;
			}
		
			for (ResourceObjectTypeDependencyType dependency: projContext.getDependencies()) {
				ResourceShadowDiscriminator refRat = new ResourceShadowDiscriminator(dependency, 
						projContext.getResource().getOid(), projContext.getKind());
				LOGGER.trace("LOOKING FOR {}", refRat);
				LensProjectionContext dependencyAccountContext = context.findProjectionContext(refRat);
				ResourceObjectTypeDependencyStrictnessType strictness = ResourceTypeUtil.getDependencyStrictness(dependency);
				if (dependencyAccountContext != null){
					if (!dependencyAccountContext.isCanProject()){
						continue;
					}
					// We have the context of the object that we depend on. We need to check if it was provisioned.
					if (strictness == ResourceObjectTypeDependencyStrictnessType.STRICT
							|| strictness == ResourceObjectTypeDependencyStrictnessType.RELAXED) {
						if (wasExecuted(dependencyAccountContext)) {
							// everything OK
							if (ResourceTypeUtil.isForceLoadDependentShadow(dependency) && !dependencyAccountContext.isDelete()){
								dependencyAccountContext.setDoReconciliation(true);
								projContext.setDoReconciliation(true);
							}
						}
					}
				}
			}
		}

	}
	
	/**
	 * Finally checks for all the dependencies. Some dependencies cannot be checked during wave computations as
	 * we might not have all activation decisions yet. 
	 */
	public <F extends ObjectType> void checkDependenciesFinal(LensContext<F> context, OperationResult result) throws PolicyViolationException {
		
		for (LensProjectionContext accountContext: context.getProjectionContexts()) {
			checkDependencies(context, accountContext, result);
		}
		
		for (LensProjectionContext accountContext: context.getProjectionContexts()) {
			if (accountContext.isDelete() 
					|| accountContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.UNLINK) {
				// It is OK if we depend on something that is not there if we are being removed
				// but we still need to check if others depends on me
				for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
					if (projectionContext.isDelete()
							|| projectionContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.UNLINK
							|| projectionContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.BROKEN 
							|| projectionContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.IGNORE) {
						// If someone who is being deleted depends on us then it does not really matter
						continue;
					}
					for (ResourceObjectTypeDependencyType dependency: projectionContext.getDependencies()) {
                        String dependencyResourceOid = dependency.getResourceRef() != null ?
                                dependency.getResourceRef().getOid() : projectionContext.getResource().getOid();
						if (dependencyResourceOid.equals(accountContext.getResource().getOid())) {
							// Someone depends on us
							if (ResourceTypeUtil.getDependencyStrictness(dependency) == ResourceObjectTypeDependencyStrictnessType.STRICT) {
								throw new PolicyViolationException("Cannot remove "+accountContext.getHumanReadableName()
										+" because "+projectionContext.getHumanReadableName()+" depends on it");
							}
						}
					}
				}
				
			}
		}
	}
	
	private <F extends ObjectType> boolean wasProvisioned(LensProjectionContext accountContext, int executionWave) {
		int accountWave = accountContext.getWave();
		if (accountWave >= executionWave) {
			// This had no chance to be provisioned yet, so we assume it will be provisioned
			return true;
		}
		if (accountContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.BROKEN 
				|| accountContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.IGNORE) {
			return false;
		}
		
		
		PrismObject<ShadowType> objectCurrent = accountContext.getObjectCurrent();
		if (objectCurrent != null && objectCurrent.asObjectable().getFailedOperationType() != null) {
			// There is unfinished operation in the shadow. We cannot continue.
			return false;
		}		
		
		if (accountContext.isExists()) {
			return true;
		}
		
		if (accountContext.isAdd()) {
			List<LensObjectDeltaOperation<ShadowType>> executedDeltas = accountContext.getExecutedDeltas();
			if (executedDeltas == null || executedDeltas.isEmpty()) {
				return false;
			}
			for (LensObjectDeltaOperation<ShadowType> executedDelta: executedDeltas) {
				OperationResult executionResult = executedDelta.getExecutionResult();
				if (executionResult == null || !executionResult.isSuccess()) {
					return false;
				}
			}
			return true;
		}
		
		return false;
	}
	
	private boolean wasExecuted(LensProjectionContext accountContext){
		if (accountContext.isAdd()) {
			
			if (accountContext.getOid() == null){
				return false;
			}
			
			List<LensObjectDeltaOperation<ShadowType>> executedDeltas = accountContext.getExecutedDeltas();
			if (executedDeltas == null || executedDeltas.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	class DependencyAndSource {
		ResourceObjectTypeDependencyType dependency;
		LensProjectionContext sourceProjectionContext;
	}
	

}
