/*
 * Copyright (C) 2010-2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.rest.impl;

import static org.springframework.http.ResponseEntity.status;

import java.net.URI;
import javax.servlet.http.HttpServletRequest;

import com.evolveum.midpoint.model.api.authentication.MidpointAuthentication;

import com.evolveum.midpoint.model.common.SystemObjectCache;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.security.api.SecurityUtil;

import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.evolveum.midpoint.audit.api.AuditEventRecord;
import com.evolveum.midpoint.audit.api.AuditEventStage;
import com.evolveum.midpoint.audit.api.AuditEventType;
import com.evolveum.midpoint.audit.api.AuditService;
import com.evolveum.midpoint.model.impl.security.SecurityHelper;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.result.OperationResultStatus;
import com.evolveum.midpoint.security.api.MidPointPrincipal;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.OperationResultType;

/**
 * Base REST controller class providing common (utility) methods and logger.
 */
class AbstractRestController {

    protected final Trace logger = TraceManager.getTrace(getClass());

    private final String opNamePrefix = getClass().getName() + ".";

    @Autowired protected AuditService auditService;
    @Autowired protected SecurityHelper securityHelper;
    @Autowired protected TaskManager taskManager;
    @Autowired protected PrismContext prismContext;
    @Autowired private SystemObjectCache systemObjectCache;

    protected Task initRequest() {
        // No need to audit login. it was already audited during authentication
        Task task = taskManager.createTaskInstance(opNamePrefix + "restService");
        task.setChannel(SchemaConstants.CHANNEL_REST_URI);
        return task;
    }

    protected OperationResult createSubresult(Task task, String operation) {
        return task.getResult().createSubresult(opNamePrefix + operation);
    }

    protected ResponseEntity<?> createResponse(HttpStatus statusCode, OperationResult result) {
        return createResponse(statusCode, null, result, false);
    }

    protected <T> ResponseEntity<?> createResponse(
            HttpStatus httpStatus, T body, OperationResult result) {
        return createResponse(httpStatus, body, result, false);
    }

    protected <T> ResponseEntity<?> createResponse(HttpStatus httpStatus,
            T body, OperationResult result, boolean sendOriginObjectIfNotSuccess) {
        result.computeStatusIfUnknown();

        if (result.isPartialError()) {
            return createBody(status(250), sendOriginObjectIfNotSuccess, body, result);
        } else if (result.isHandledError()) {
            return createBody(status(240), sendOriginObjectIfNotSuccess, body, result);
        }

        return status(httpStatus).body(body);
    }

    protected ResponseEntity<?> createResponseWithLocation(
            HttpStatus httpStatus, URI location, OperationResult result) {
        result.computeStatusIfUnknown();

        if (result.isPartialError()) {
            return ResponseEntity.status(250).location(location).body(result);
        } else if (result.isHandledError()) {
            return ResponseEntity.status(240).location(location).body(result);
        }

        return location == null ? ResponseEntity.status(httpStatus).build()
                : ResponseEntity.status(httpStatus).location(location).build();
    }

    protected <T> ResponseEntity<?> createBody(ResponseEntity.BodyBuilder builder,
            boolean sendOriginObjectIfNotSuccess, T body, OperationResult result) {
        if (sendOriginObjectIfNotSuccess) {
            return builder.body(body);
        }
        return builder.body(result);
    }

    protected ResponseEntity<?> handleException(OperationResult result, Throwable t) {
        LoggingUtils.logUnexpectedException(logger, "Got exception while servicing REST request: {}", t,
                result != null ? result.getOperation() : "(null)");
        return handleExceptionNoLog(result, t);
    }

    protected ResponseEntity<?> handleExceptionNoLog(OperationResult result, Throwable t) {
        return createErrorResponseBuilder(result, t);
    }

    protected ResponseEntity<?> createErrorResponseBuilder(OperationResult result, Throwable t) {
        if (t instanceof ObjectNotFoundException) {
            return createErrorResponseBuilder(HttpStatus.NOT_FOUND, result);
        }

        if (t instanceof CommunicationException
                || t instanceof TunnelException) {
            return createErrorResponseBuilder(HttpStatus.GATEWAY_TIMEOUT, result);
        }

        if (t instanceof SecurityViolationException) {
            return createErrorResponseBuilder(HttpStatus.FORBIDDEN, result);
        }

        if (t instanceof ConfigurationException) {
            return createErrorResponseBuilder(HttpStatus.BAD_GATEWAY, result);
        }

        if (t instanceof SchemaException
                || t instanceof ExpressionEvaluationException
                || t instanceof IllegalArgumentException) {
            return createErrorResponseBuilder(HttpStatus.BAD_REQUEST, result);
        }

        if (t instanceof PolicyViolationException
                || t instanceof ObjectAlreadyExistsException
                || t instanceof ConcurrencyException) {
            return createErrorResponseBuilder(HttpStatus.CONFLICT, result);
        }

        return createErrorResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR, result);
    }

    protected ResponseEntity<?> createErrorResponseBuilder(
            HttpStatus status, OperationResult result) {
        OperationResultType resultBean;
        if (result != null) {
            result.computeStatusIfUnknown();
            resultBean = result.createOperationResultType();
        } else {
            resultBean = null;
        }
        return status(status).body(resultBean);
    }

    protected void finishRequest() {
        auditEvent();
        SecurityContextHolder.getContext().setAuthentication(null);
    }

    private void auditEvent() {
        String channel = SchemaConstants.CHANNEL_REST_URI;
        SystemConfigurationType system = null;
        try {
            system = systemObjectCache.getSystemConfiguration(new OperationResult("LOAD SYSTEM CONFIGURATION")).asObjectable();
        } catch (SchemaException e) {
            logger.error("Couldn't get system configuration from cache", e);
        }
        if (!SecurityUtil.isAuditedLoginAndLogout(system, channel)) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication.getPrincipal();
        String name = null;
        if (principal instanceof MidPointPrincipal) {
            name = ((MidPointPrincipal) principal).getUsername();
        } else if (principal != null) {
            return;
        }
        PrismObject<? extends FocusType> user = principal != null ? ((MidPointPrincipal) principal).getFocus().asPrismObject() : null;

        Task task = taskManager.createTaskInstance();
        task.setOwner(user);
        task.setChannel(channel);

        AuditEventRecord record = new AuditEventRecord(AuditEventType.TERMINATE_SESSION, AuditEventStage.REQUEST);
        record.setInitiator(user, prismContext);
        record.setParameter(name);

        record.setChannel(SchemaConstants.CHANNEL_REST_URI);
        record.setTimestamp(System.currentTimeMillis());
        record.setOutcome(OperationResultStatus.SUCCESS);
        if (authentication instanceof MidpointAuthentication) {
            record.setSessionIdentifier(((MidpointAuthentication) authentication).getSessionId());
        }

        auditService.audit(record, task);
    }

    private final String[] requestMappingPaths =
            getClass().getAnnotation(RequestMapping.class).value();

    /**
     * Returns base path (without servlet context) reflecting currently used request.
     * This solves the problem of base path being one of multiple possible mappings.
     */
    protected String controllerBasePath() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes) {
            HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
            String servletPath = request.getServletPath();
            for (String requestMappingPath : requestMappingPaths) {
                if (servletPath.startsWith(requestMappingPath)) {
                    return requestMappingPath;
                }
            }
        }

        throw new NullPointerException("Base controller URL could not be determined.");
    }
}
