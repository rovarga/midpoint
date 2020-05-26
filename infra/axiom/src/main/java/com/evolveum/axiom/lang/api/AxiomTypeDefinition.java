/*
 * Copyright (c) 2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.axiom.lang.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.evolveum.axiom.api.AxiomIdentifier;
import com.evolveum.axiom.api.meta.Inheritance;
import com.google.common.collect.ImmutableMap;

public interface AxiomTypeDefinition extends AxiomNamedDefinition, AxiomItemValue<AxiomTypeDefinition> {

    public final AxiomIdentifier IDENTIFIER_MEMBER = AxiomIdentifier.axiom("name");
    public final AxiomIdentifier IDENTIFIER_SPACE = AxiomIdentifier.axiom("AxiomTypeDefinition");


    @Override
    default AxiomTypeDefinition get() {
        return this;
    }

    @Override
    default Optional<AxiomTypeDefinition> type() {
        return Optional.empty();
    }

    Optional<AxiomItemDefinition> argument();

    Optional<AxiomTypeDefinition> superType();

    Map<AxiomIdentifier, AxiomItemDefinition> itemDefinitions();

    Collection<AxiomIdentifierDefinition> identifierDefinitions();

    default Optional<AxiomItemDefinition> itemDefinition(AxiomIdentifier child) {
        AxiomItemDefinition maybe = itemDefinitions().get(child);
        if(maybe == null) {
            maybe = itemDefinitions().get(Inheritance.adapt(name(), child));
        }
        if(maybe == null && child.namespace().isEmpty()) {
            maybe = itemDefinitions().get(name().localName(child.localName()));
        }
        return Optional.ofNullable(maybe).or(() -> superType().flatMap(s -> s.itemDefinition(child)));
    }

    static IdentifierSpaceKey identifier(AxiomIdentifier name) {
        return IdentifierSpaceKey.from(ImmutableMap.of(IDENTIFIER_MEMBER, name));
    }

    default Collection<AxiomItemDefinition> requiredItems() {
        return itemDefinitions().values().stream().filter(AxiomItemDefinition::required).collect(Collectors.toList());
    }

    default Optional<AxiomItemDefinition> itemDefinition(AxiomIdentifier parentItem, AxiomIdentifier name) {
        return itemDefinition(Inheritance.adapt(parentItem, name));
    }

    default boolean isSubtypeOf(AxiomTypeDefinition type) {
        return isSubtypeOf(type.name());
    }

    default boolean isSupertypeOf(AxiomTypeDefinition other) {
        return other.isSubtypeOf(this);
    }

    default boolean isSubtypeOf(AxiomIdentifier other) {
        Optional<AxiomTypeDefinition> current = Optional.of(this);
        while(current.isPresent()) {
            if(current.get().name().equals(other)) {
                return true;
            }
            current = current.get().superType();
        }
        return false;
    }

}
