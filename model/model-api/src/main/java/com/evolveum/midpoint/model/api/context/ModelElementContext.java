/*
 * Copyright (c) 2010-2018 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.model.api.context;

import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.ObjectDeltaOperation;
import com.evolveum.midpoint.util.DebugDumpable;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ArchetypeType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * @author semancik
 *
 */
public interface ModelElementContext<O extends ObjectType> extends Serializable, DebugDumpable {

    Class<O> getObjectTypeClass();

    PrismObject<O> getObjectOld();

    void setObjectOld(PrismObject<O> objectOld);

    PrismObject<O> getObjectNew();

    PrismObject<O> getObjectCurrent();

    void setObjectNew(PrismObject<O> objectNew);

    ObjectDelta<O> getPrimaryDelta();

    void setPrimaryDelta(ObjectDelta<O> primaryDelta);

    void addPrimaryDelta(ObjectDelta<O> value) throws SchemaException;

    ObjectDelta<O> getSecondaryDelta();

    void setSecondaryDelta(ObjectDelta<O> secondaryDelta);

    List<? extends ObjectDeltaOperation> getExecutedDeltas();

    String getOid();

    /**
     * Returns all policy rules that apply to this object - even those that were not triggered.
     * The policy rules are compiled from all the applicable sources (target, meta-roles, etc.)
     */
    @NotNull
    Collection<EvaluatedPolicyRule> getPolicyRules();

    boolean isOfType(Class<?> aClass);

}
