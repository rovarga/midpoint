/*
 * Copyright (c) 2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.model.common.expression.evaluator.path;

import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.PrismValueDeltaSetTriple;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.util.DefinitionResolver;
import com.evolveum.midpoint.prism.util.ItemDeltaItem;
import com.evolveum.midpoint.repo.common.expression.ExpressionEvaluationContext;
import com.evolveum.midpoint.repo.common.expression.evaluator.ExpressionEvaluatorUtil;
import com.evolveum.midpoint.schema.expression.TypedValue;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.SchemaException;

import org.jetbrains.annotations.Nullable;

/**
 * Evaluation of the "path" expression.
 */
class PathExpressionEvaluation<V extends PrismValue, D extends ItemDefinition> {

    private final PathExpressionEvaluator<V, D> evaluator;
    private final ExpressionEvaluationContext context;

    /**
     * Path to be resolved. Changes during resolution process.
     */
    private ItemPath pathToResolve;

    /**
     * Context (e.g. prism item or prism value) in which the resolution takes places.
     * Changes during resolution process.
     */
    private ResolutionContext resolutionContext;

    PathExpressionEvaluation(PathExpressionEvaluator<V, D> evaluator, ExpressionEvaluationContext context) {
        this.evaluator = evaluator;
        this.context = context;
    }

    PrismValueDeltaSetTriple<V> evaluate() throws ExpressionEvaluationException, SchemaException {
        pathToResolve = evaluator.path;
        resolutionContext = determineInitialResolveContext();
        if (resolutionContext == null) {
            return null;
        }

        stepAlongResolvePath();
        return prepareOutputTriple();
    }

    private ResolutionContext determineInitialResolveContext() throws ExpressionEvaluationException {
        if (pathToResolve.startsWithVariable()) {
            return getInitialResolveContextFromVariable();
        } else if (context.getSources().size() == 1) {
            return IdiResolutionContext.fromIdi(context.getSources().iterator().next());
        } else if (context.getDefaultSource() != null) {
            return IdiResolutionContext.fromIdi(context.getDefaultSource());
        } else if (context.getSources().isEmpty()) {
            throw new IllegalStateException("There is no source to be used for path resolution. In " +
                    context.getContextDescription());
        } else {
            throw new IllegalStateException("There is are multiple sources to be used for path resolution. In " +
                    context.getContextDescription());
        }
    }

    private ResolutionContext getInitialResolveContextFromVariable() throws ExpressionEvaluationException {
        String variableName = ItemPath.toVariableName(pathToResolve.first()).getLocalPart();
        pathToResolve = pathToResolve.rest();

        TypedValue variableValueAndDefinition = ExpressionEvaluatorUtil.findInSourcesAndVariables(context, variableName);
        if (variableValueAndDefinition == null) {
            throw new ExpressionEvaluationException("No variable with name "+variableName+" in "+ context.getContextDescription());
        }

        Object variableValue = variableValueAndDefinition.getValue();
        if (variableValue == null) {
            return null;
        } else if (variableValue instanceof Item || variableValue instanceof ItemDeltaItem<?,?>) {
            return IdiResolutionContext.fromAnyObject(variableValue);
        } else if (variableValue instanceof PrismValue) {
            return new ValueResolutionContext((PrismValue) variableValue, context.getContextDescription());
        } else {
            throw new ExpressionEvaluationException("Unexpected variable value "+variableValue+" ("+variableValue.getClass()+")");
        }
    }

    @Nullable
    private PrismValueDeltaSetTriple<V> prepareOutputTriple() {
        PrismValueDeltaSetTriple<V> outputTriple = resolutionContext.createOutputTriple(evaluator.prismContext);
        if (outputTriple == null) {
            return null;
        } else {
            return ExpressionEvaluatorUtil.toOutputTriple(outputTriple, evaluator.outputDefinition,
                    context.getAdditionalConvertor(), null, evaluator.protector, evaluator.prismContext);
        }
    }

    private void stepAlongResolvePath() throws SchemaException, ExpressionEvaluationException {

        while (!pathToResolve.isEmpty()) {
            if (resolutionContext.isContainer()) {
                DefinitionResolver defResolver = (parentDef, path) -> {
                    if (parentDef != null && parentDef.isDynamic()) {
                        // This is the case of dynamic schema extensions, such as assignment extension.
                        // Those may not have a definition. In that case just assume strings.
                        // In fact, this is a HACK. All such schemas should have a definition.
                        // Otherwise there may be problems with parameter types for caching compiles scripts and so on.
                        return evaluator.prismContext.definitionFactory().createPropertyDefinition(path.firstName(), PrimitiveType.STRING.getQname());
                    } else {
                        return null;
                    }
                };

                try {
                    resolutionContext = resolutionContext.stepInto(pathToResolve.firstToName(), defResolver);
                    pathToResolve = pathToResolve.rest();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(e.getMessage()+"; resolving path "+ pathToResolve.firstAsPath()+" on "+
                            resolutionContext +"; in "+context.getContextDescription(), e);
                }

                if (resolutionContext == null) {
                    throw new ExpressionEvaluationException("Cannot find item using path "+evaluator.path+" in "+
                            context.getContextDescription());
                }

            } else if (resolutionContext.isStructuredProperty()) {
                resolutionContext = resolutionContext.resolveStructuredProperty(pathToResolve,
                        (PrismPropertyDefinition) evaluator.outputDefinition, evaluator.prismContext);
                pathToResolve = ItemPath.EMPTY_PATH;

            } else if (resolutionContext.isNull()) {
                pathToResolve = ItemPath.EMPTY_PATH;

            } else {
                throw new ExpressionEvaluationException("Cannot resolve path "+ pathToResolve +" on "+ resolutionContext +" in "+ context.getContextDescription());
            }
        }
    }

}
