/*
 * Copyright (c) 2010-2018 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.security.api;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;

import java.util.Collection;

/**
 * Service that exposes security functions for internal use inside midPoint and for other
 * spring-security-enabled purposes.
 *
 * This is using simple  MidPointPrincipal that is NOT GUI-enriched. Therefore it is NOT
 * suitable for use in GUI. See UserProfileService for that purpose.
 *
 * @author lazyman
 * @author Igor Farinic
 * @author Radovan Semancik
 */
public interface MidPointPrincipalManager extends OwnerResolver {

    String DOT_CLASS = MidPointPrincipalManager.class.getName() + ".";
    String OPERATION_GET_PRINCIPAL = DOT_CLASS + "getPrincipal";
    String OPERATION_UPDATE_USER = DOT_CLASS + "updateUser";

    MidPointPrincipal getPrincipal(String username) throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException;

    MidPointPrincipal getPrincipalByOid(String oid) throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException;

    MidPointPrincipal getPrincipal(PrismObject<UserType> user) throws SchemaException, CommunicationException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException;

    MidPointPrincipal getPrincipal(PrismObject<UserType> user, AuthorizationTransformer authorizationTransformer, OperationResult result) throws SchemaException, CommunicationException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException;

    void updateUser(MidPointPrincipal principal, Collection<? extends ItemDelta<?, ?>> itemDeltas);

}
