/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.gui.impl.prism.panel;

import com.evolveum.midpoint.gui.impl.prism.wrapper.PrismPropertyValueWrapper;
import com.evolveum.midpoint.gui.api.prism.wrapper.PrismPropertyWrapper;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.model.IModel;

/**
 * @author katkav
 */
public class  PrismPropertyPanel<T> extends ItemPanel<PrismPropertyValueWrapper<T>, PrismPropertyWrapper<T>> {

    private static final long serialVersionUID = 1L;
    private static final Trace LOGGER = TraceManager.getTrace(PrismPropertyPanel.class);

    private static final String ID_HEADER = "header";

    /**
     * @param id
     * @param model
     */
    public PrismPropertyPanel(String id, IModel<PrismPropertyWrapper<T>> model, ItemPanelSettings settings) {
        super(id, model, settings);
    }

    @Override
    protected Component createHeaderPanel() {
        return new PrismPropertyHeaderPanel<T>(ID_HEADER, getModel()) {
            @Override
            protected void refreshPanel(AjaxRequestTarget target) {
                target.add(PrismPropertyPanel.this);
            }
        };
    }


    @Override
    protected Component createValuePanel(ListItem<PrismPropertyValueWrapper<T>> item) {
        PrismPropertyValuePanel<T> panel = new PrismPropertyValuePanel<T>("value", item.getModel(), getSettings()) {

            @Override
            protected void removeValue(PrismPropertyValueWrapper<T> valueToRemove, AjaxRequestTarget target) throws SchemaException {
                PrismPropertyPanel.this.removeValue(valueToRemove, target);
            }
        };
        item.add(panel);
        return panel;
    }


    @Override
    protected <PV extends PrismValue> PV createNewValue(PrismPropertyWrapper<T> itemWrapper) {
        return (PV) getPrismContext().itemFactory().createPropertyValue();
    }
}
