/*
 * Copyright (c) 2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.model.impl.lens.projector.focus;

import static com.evolveum.midpoint.util.DebugUtil.debugDumpLazily;

import java.util.Collection;
import java.util.function.Supplier;

import com.evolveum.midpoint.prism.xml.XmlTypeConverter;

import org.apache.commons.collections.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.model.impl.lens.LensProjectionContext;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.util.annotation.Experimental;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

/**
 * Creates value metadata for source projections: resource objects that are to be fed into inbound
 * mappings. It is a temporary/experimental solution: normally, such metadata should be provided by the connector
 * or provisioning module. But to optimize processing, let us create such metadata only for values that
 * are really used in inbound mappings.
 */
@Experimental
@Component
public class ProjectionValueMetadataCreator {

    private static final Trace LOGGER = TraceManager.getTrace(ProjectionValueMetadataCreator.class);

    @Autowired private PrismContext prismContext;

    <V extends PrismValue, D extends ItemDefinition>
    void setValueMetadata(@NotNull Item<V, D> resourceObjectItem, @NotNull LensProjectionContext projectionCtx) {
        apply(resourceObjectItem.getValues(), () -> createMetadata(projectionCtx, resourceObjectItem), resourceObjectItem::getPath);
    }

    <D extends ItemDefinition, V extends PrismValue>
    void setValueMetadata(@NotNull ItemDelta<V, D> itemDelta, @NotNull LensProjectionContext projectionCtx) {
        apply(itemDelta.getValuesToAdd(), () -> createMetadata(projectionCtx, itemDelta), () -> "ADD set of" + itemDelta);
        apply(itemDelta.getValuesToReplace(), () -> createMetadata(projectionCtx, itemDelta), () -> "REPLACE set of " + itemDelta);
    }

    private <V extends PrismValue> void apply(Collection<V> values, Supplier<ValueMetadataType> metadataSupplier,
            Supplier<Object> descSupplier) {
        ValueMetadataType metadata = null;
        if (CollectionUtils.isNotEmpty(values)) {
            int changed = 0;
            for (V value : values) {
                if (value.getValueMetadata().isEmpty()) {
                    if (metadata == null) {
                        metadata = metadataSupplier.get();
                    }
                    value.setValueMetadata(metadata);
                    changed++;
                }
            }
            LOGGER.trace("Value metadata set for {} out of {} value(s) of {}:\n{}", changed, values.size(), descSupplier.get(),
                    debugDumpLazily(metadata));
        }
    }

    private ValueMetadataType createMetadata(@NotNull LensProjectionContext projectionCtx, Object desc) {
        String resourceOid = projectionCtx.getResourceOid();
        if (resourceOid == null) {
            LOGGER.trace("No resource OID for {}, not creating value metadata for {}", projectionCtx, desc);
            return null;
        }

        ResourceObjectTypeDefinitionType def = projectionCtx.getResourceObjectTypeDefinitionType();
        ObjectReferenceType originRef = def != null ? def.getOriginRef() : null;

        return new ValueMetadataType(prismContext)
                .beginProvenance()
                    .beginYield()
                        .beginAcquisition()
                            .timestamp(XmlTypeConverter.createXMLGregorianCalendar())
                            .resourceRef(resourceOid, ResourceType.COMPLEX_TYPE)
                            .originRef(originRef)
                        .<ProvenanceYieldType>end()
                    .<ProvenanceMetadataType>end()
                .end();
    }
}
