/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.ui.kb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.form.ChoiceRenderer;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.panel.EmptyPanel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.eclipse.rdf4j.model.vocabulary.RDFS;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxFormComponentUpdatingBehavior;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaModel;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.graph.KBConcept;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.graph.RdfUtils;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxConceptSelectionEvent;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxNewConceptEvent;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxNewPropertyEvent;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxPropertySelectionEvent;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxStatementChangedEvent;

/**
 * Houses the UI for interacting with one knowledge base.
 */
public class KnowledgeBasePanel
    extends EventListeningPanel
{

    private static final long serialVersionUID = -3717326058176546655L;

    private static final String DETAIL_CONTAINER_MARKUP_ID = "detailContainer";
    private static final String DETAILS_MARKUP_ID = "details";

    private @SpringBean KnowledgeBaseService kbService;

    private IModel<KnowledgeBase> kbModel;
    private Model<KBHandle> selectedConceptHandle = Model.of();
    private Model<KBHandle> selectedPropertyHandle = Model.of();

    private WebMarkupContainer detailContainer;
    private ConceptTreePanel conceptTreePanel;
    private PropertyListPanel propertyListPanel;
    
    /**
     * right-side component which either displays concept details or property details
     */
    private Component details;

    public KnowledgeBasePanel(String id, IModel<Project> aProjectModel,
            IModel<KnowledgeBase> aKbModel)
    {
        super(id, aKbModel);

        setOutputMarkupId(true);

        kbModel = aKbModel;
        
        // add the selector for the knowledge bases
        DropDownChoice<KnowledgeBase> ddc = new DropDownChoice<KnowledgeBase>("knowledgebases",
                LambdaModel.of(() -> kbService.getEnabledKnowledgeBases(aProjectModel.getObject())))
        {

            private static final long serialVersionUID = -2635546743813402116L;

            @Override
            public boolean isVisible() {
                // only visible if there is a choice between two or more KBs
                return getChoices().size() >= 2;
            }
        };
        ddc.add(new LambdaAjaxFormComponentUpdatingBehavior("change", t -> {
            details = details.replaceWith(new EmptyPanel(DETAILS_MARKUP_ID));
            t.add(KnowledgeBasePanel.this);
        }));
        ddc.setModel(aKbModel);
        ddc.setChoiceRenderer(new ChoiceRenderer<>("name"));
        add(ddc);

        add(conceptTreePanel = new ConceptTreePanel("concepts", kbModel, selectedConceptHandle));
        add(propertyListPanel = new PropertyListPanel("properties", kbModel,
                selectedPropertyHandle));

        // Callbacks to property/concept selection events follow below. A note on the use of the
        // selection events: If a property is currently selected and the user selects a concept
        // instead, only one event should be broadcast (concept selected), not two (concept selected
        // and property deselected). However, there are cases when property/concept deselection
        // events make sense, which is when a user creates a new property but then decides to cancel
        // the creation process. In this case, neither a property nor a concept should be selected,
        // hence a deselection makes sense to reset the UI.
        eventHandler.addCallback(AjaxPropertySelectionEvent.class,
                this::actionPropertySelectionChanged);
        eventHandler.addCallback(AjaxNewPropertyEvent.class, this::actionNewProperty);
        eventHandler.addCallback(AjaxConceptSelectionEvent.class,
                this::actionConceptSelectionChanged);
        eventHandler.addCallback(AjaxNewConceptEvent.class, this::actionNewConcept);

        // react to changing statements
        eventHandler.addCallback(AjaxStatementChangedEvent.class, this::actionStatementChanged);
        
        detailContainer = new WebMarkupContainer(DETAIL_CONTAINER_MARKUP_ID);
        detailContainer.setOutputMarkupId(true);
        add(detailContainer);
        
        details = new EmptyPanel(DETAILS_MARKUP_ID);
        detailContainer.add(details);
    }

    private List<KnowledgeBase> getEnabledKBs(Project aProject){
    	ArrayList<KnowledgeBase> enabledKBs =  new ArrayList<KnowledgeBase>();
    	
    	//Check for all KnowledgeBases if the are enabled
    	for(KnowledgeBase kb : kbService.getKnowledgeBases(aProject)) {
    		if(kb.isEnabled()) {
    			enabledKBs.add(kb);
    			System.out.println(kb.getName() + "is enabled");
    		}
    		else{
    			System.out.println(kb.getName() + "is not enabled");
    		}
    	}
    	
    	return enabledKBs;
    }
    
    /**
     * Acts upon statement changes. If the changed statement does <strong>not</strong> involve an
     * RDFS or OWL property, the no action is taken. If the changed statement renames the selected
     * concept or property, the name in the respective {@link KBHandle} is updated. For any other
     * statement changes affecting the schema, the page is reloaded via AJAX. Reloading the whole
     * page may not seem like the smartest solution. However, given the severe consequences a single
     * statement change can have (transforming a property into a concept?), it is the simplest
     * working solution.
     *
     * @param target
     * @param event
     */
    private void actionStatementChanged(AjaxRequestTarget target, AjaxStatementChangedEvent event)
    {
        boolean isSchemaChangeEvent = RdfUtils
                .isFromImplicitNamespace(event.getStatement().getProperty());
        if (!isSchemaChangeEvent) {
            return;
        }

        // if this event is not about renaming (changing the RDFS label) of a KBObject, return
        KBStatement statement = event.getStatement();
        boolean isRenameEvent = statement.getProperty().getIdentifier()
                .equals(RDFS.LABEL.stringValue());
        if (isRenameEvent) {
            // determine whether the concept name or property name was changed (or neither), then
            // update the name in the respective KBHandle
            List<Model<KBHandle>> models = Arrays.asList(selectedConceptHandle,
                    selectedPropertyHandle);
            models.stream().filter(model -> model.getObject() != null && model.getObject()
                    .getIdentifier().equals(statement.getInstance().getIdentifier()))
                    .forEach(model -> {
                        model.getObject().setName((String) statement.getValue());
                        target.add(this);
                    });
        }
        else {
            target.add(getPage());
        }
    }

    private void actionConceptSelectionChanged(AjaxRequestTarget target,
            AjaxConceptSelectionEvent event)
    {
        // cancel selection of property
        selectedPropertyHandle.setObject(null);
        selectedConceptHandle.setObject(event.getSelection());

        // replace detail view: empty panel if a deselection took place (see lengthy explanation
        // above)
        Component replacementPanel;
        if (selectedConceptHandle.getObject() == null) {
            replacementPanel = new EmptyPanel(DETAILS_MARKUP_ID);
        }
        else {
            // TODO: Fix this Optional get() to actual checking
            KBConcept selectedConcept = kbService.readConcept(kbModel.getObject(),
                    selectedConceptHandle.getObject().getIdentifier()).get();
            replacementPanel = new ConceptInstancePanel(DETAILS_MARKUP_ID, kbModel,
                    selectedConceptHandle, Model.of(selectedConcept));
        }
        details = details.replaceWith(replacementPanel);

        target.add(conceptTreePanel);
        target.add(propertyListPanel);
        target.add(detailContainer);
    }

    private void actionNewConcept(AjaxRequestTarget target, AjaxNewConceptEvent event)
    {
        // cancel selections for concepts and properties
        selectedConceptHandle.setObject(null);
        selectedPropertyHandle.setObject(null);

        // show panel for new, empty property
        Component replacement = new ConceptInstancePanel(DETAILS_MARKUP_ID, kbModel,
                selectedConceptHandle, Model.of(new KBConcept()));
        details = details.replaceWith(replacement);

        target.add(KnowledgeBasePanel.this);
    }

    private void actionPropertySelectionChanged(AjaxRequestTarget target,
            AjaxPropertySelectionEvent event)
    {
        // cancel selection of concept
        selectedConceptHandle.setObject(null);
        selectedPropertyHandle.setObject(event.getSelection());

        // replace detail view: empty panel if a deselection took place (see lengthy explanation
        // above)
        Component replacementPanel;
        if (selectedPropertyHandle.getObject() == null) {
            replacementPanel = new EmptyPanel(DETAILS_MARKUP_ID);
        }
        else {
            String identifier = selectedPropertyHandle.getObject().getIdentifier();
            replacementPanel = kbService.readProperty(kbModel.getObject(), identifier)
                    .<Component>map(selectedProperty -> {
                        Model<KBProperty> model = Model.of(selectedProperty);
                        return new PropertyPanel(DETAILS_MARKUP_ID, kbModel, selectedPropertyHandle,
                                model);
                    }).orElse(new EmptyPanel(DETAILS_MARKUP_ID));
        }
        details = details.replaceWith(replacementPanel);

        target.add(conceptTreePanel);
        target.add(propertyListPanel);
        target.add(detailContainer);
    }

    private void actionNewProperty(AjaxRequestTarget target, AjaxNewPropertyEvent event)
    {
        // cancel selections for concepts and properties
        selectedConceptHandle.setObject(null);
        selectedPropertyHandle.setObject(null);

        // show panel for new, empty property
        Component replacement = new PropertyPanel(DETAILS_MARKUP_ID, kbModel,
                selectedPropertyHandle, Model.of(new KBProperty()));
        details = details.replaceWith(replacement);

        target.add(KnowledgeBasePanel.this);
    }
}
