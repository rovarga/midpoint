/*
 * Copyright (c) 2010-2013 Evolveum
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

package com.evolveum.midpoint.web.component.wizard;

import com.evolveum.midpoint.web.component.AjaxButton;
import com.evolveum.midpoint.web.component.AjaxSubmitButton;
import com.evolveum.midpoint.web.page.admin.resources.PageResourceWizard;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.wizard.*;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.IFormSubmittingComponent;
import org.apache.wicket.markup.html.panel.Panel;

/**
 * @author lazyman
 */
public class WizardButtonBar extends Panel implements IDefaultButtonProvider {

	private static final String ID_PREVIOUS = "previous";
	private static final String ID_NEXT = "next";
	private static final String ID_LAST = "last";
	private static final String ID_FINISH = "finish";
	private static final String ID_CANCEL = "cancel";
	private static final String ID_VALIDATE = "validate";
	private static final String ID_SAVE = "save";

    public WizardButtonBar(String id, final Wizard wizard) {
        super(id);
        add(new PreviousButton(ID_PREVIOUS, wizard) {
			@Override
			public void onClick() {
				IWizardModel wizardModel = getWizardModel();
				IWizardStep step = wizardModel.getActiveStep();
				step.applyState();
				if (step.isComplete()) {
					wizardModel.previous();
				} else {
					couldntSave();
				}
			}
		});
        add(new NextButton(ID_NEXT, wizard) {
			@Override
			public void onClick() {
				IWizardModel wizardModel = getWizardModel();
				IWizardStep step = wizardModel.getActiveStep();
				step.applyState();
				if (step.isComplete()) {
					wizardModel.next();
				} else {
					couldntSave();
				}
			}
		});
        add(new LastButton(ID_LAST, wizard));			// not used at all
        add(new CancelButton(ID_CANCEL, wizard));
        add(new FinishButton(ID_FINISH, wizard){

			@Override
			public void onClick()
			{
				IWizardModel wizardModel = getWizardModel();
				IWizardStep step = wizardModel.getActiveStep();
				step.applyState();
				if (step.isComplete()) {
					getWizardModel().finish();
				} else {
					couldntSave();
				}
			}
			/*
             *   Finish button is always enabled, so user don't have to
             *   click through every step of wizard every time it is used
             */
            @Override
            public boolean isEnabled() {
				final IWizardStep activeStep = wizard.getModelObject().getActiveStep();
				return activeStep == null || activeStep.isComplete();
            }
        });

		add(new AjaxSubmitButton(ID_VALIDATE) {
			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				((PageResourceWizard) getPage()).refreshIssues(target);
			}
		});

		add(new AjaxSubmitButton(ID_SAVE) {
			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				IWizardStep activeStep = wizard.getModelObject().getActiveStep();
				if (activeStep != null) {
					activeStep.applyState();
				}
			}
		});

	}

	private void couldntSave() {
		// we should't come here
		error("Fix the indicated errors first.");
		getPage().setResponsePage(getPage());
	}

	@Override
    public IFormSubmittingComponent getDefaultButton(IWizardModel model) {

        if (model.isNextAvailable()){
            return (IFormSubmittingComponent)get("next");
        }

        else if (model.isLastAvailable()){
            return (IFormSubmittingComponent)get("last");
        }

        else if (model.isLastStep(model.getActiveStep())){
            return (IFormSubmittingComponent)get("finish");
        }

        return null;
    }
}
