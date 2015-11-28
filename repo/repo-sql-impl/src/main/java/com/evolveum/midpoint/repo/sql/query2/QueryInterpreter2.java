/*
 * Copyright (c) 2010-2015 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.repo.sql.query2;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.prism.query.AllFilter;
import com.evolveum.midpoint.prism.query.AndFilter;
import com.evolveum.midpoint.prism.query.ExistsFilter;
import com.evolveum.midpoint.prism.query.InOidFilter;
import com.evolveum.midpoint.prism.query.NoneFilter;
import com.evolveum.midpoint.prism.query.NotFilter;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.ObjectPaging;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.OrFilter;
import com.evolveum.midpoint.prism.query.OrderDirection;
import com.evolveum.midpoint.prism.query.OrgFilter;
import com.evolveum.midpoint.prism.query.RefFilter;
import com.evolveum.midpoint.prism.query.TypeFilter;
import com.evolveum.midpoint.prism.query.UndefinedFilter;
import com.evolveum.midpoint.prism.query.ValueFilter;
import com.evolveum.midpoint.repo.sql.SqlRepositoryConfiguration;
import com.evolveum.midpoint.repo.sql.data.common.embedded.RPolyString;
import com.evolveum.midpoint.repo.sql.query.QueryException;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaAnyDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaEntityDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaEntityItemDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaItemDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaPropertyDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaReferenceDefinition;
import com.evolveum.midpoint.repo.sql.query2.definition.JpaRootEntityDefinition;
import com.evolveum.midpoint.repo.sql.query2.hqm.ProjectionElement;
import com.evolveum.midpoint.repo.sql.query2.hqm.RootHibernateQuery;
import com.evolveum.midpoint.repo.sql.query2.hqm.condition.Condition;
import com.evolveum.midpoint.repo.sql.query2.matcher.DefaultMatcher;
import com.evolveum.midpoint.repo.sql.query2.matcher.Matcher;
import com.evolveum.midpoint.repo.sql.query2.matcher.PolyStringMatcher;
import com.evolveum.midpoint.repo.sql.query2.matcher.StringMatcher;
import com.evolveum.midpoint.repo.sql.query2.restriction.AndRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.AnyPropertyRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.ExistsRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.InOidRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.NotRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.OrRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.OrgRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.PropertyRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.ReferenceRestriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.Restriction;
import com.evolveum.midpoint.repo.sql.query2.restriction.TypeRestriction;
import com.evolveum.midpoint.repo.sql.util.GetObjectResult;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import org.apache.commons.lang.Validate;
import org.hibernate.Session;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.evolveum.midpoint.repo.sql.SqlRepositoryServiceImpl.ObjectPagingAfterOid;

/**
 * Interprets midPoint queries by translating them to hibernate (HQL) ones.
 * <p/>
 * There are two parts:
 * - filter translation,
 * - paging translation.
 * <p/>
 * As for filter translation, we traverse the filter depth-first, creating an isomorphic structure of Restrictions.
 * While creating them, we continually build a set of entity references that are necessary to evaluate the query;
 * these references are in the form of cartesian join of entities from which each one can have a set of entities
 * connected to it via left outer join. An example:
 * <p/>
 * from
 * RUser u
 * left join u.assignments a with ...
 * left join u.organization o,
 * RRole r
 * left join r.assignments a2 with ...
 * <p/>
 * This structure is maintained in InterpretationContext, namely in the HibernateQuery being prepared. (In order to
 * produce HQL, we use ad-hoc "hibernate query model" in hqm package, rooted in HibernateQuery class.)
 * <p/>
 * Paging translation is done after filters are translated. It may add some entity references as well, if they are not
 * already present.
 *
 * @author lazyman
 * @author mederly
 */
public class QueryInterpreter2 {

    private static final Trace LOGGER = TraceManager.getTrace(QueryInterpreter2.class);
    private static final Map<Class, Matcher> AVAILABLE_MATCHERS;

    static {
        Map<Class, Matcher> matchers = new HashMap<Class, Matcher>();
        //default matcher with null key
        matchers.put(null, new DefaultMatcher());
        matchers.put(PolyString.class, new PolyStringMatcher());
        matchers.put(String.class, new StringMatcher());

        AVAILABLE_MATCHERS = Collections.unmodifiableMap(matchers);
    }

    private SqlRepositoryConfiguration repoConfiguration;

    public QueryInterpreter2(SqlRepositoryConfiguration repoConfiguration) {
        this.repoConfiguration = repoConfiguration;
    }

    public SqlRepositoryConfiguration getRepoConfiguration() {
        return repoConfiguration;
    }

    public RootHibernateQuery interpret(ObjectQuery query, Class<? extends Containerable> type,
                                        Collection<SelectorOptions<GetOperationOptions>> options, PrismContext prismContext,
                                        boolean countingObjects, Session session) throws QueryException {
        Validate.notNull(type, "Type must not be null.");
        Validate.notNull(session, "Session must not be null.");
        Validate.notNull(prismContext, "Prism context must not be null.");

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Interpreting query for type '{}', query:\n{}", new Object[]{type, query});
        }

        InterpretationContext context = new InterpretationContext(this, type, prismContext, session);

        interpretQueryFilter(context, query);
        interpretPagingAndSorting(context, query, countingObjects);

        RootHibernateQuery hibernateQuery = context.getHibernateQuery();

        if (countingObjects) {
            hibernateQuery.addProjectionElement(new ProjectionElement("count(*)"));
        } else {
            String rootAlias = hibernateQuery.getPrimaryEntityAlias();
            hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".fullObject"));
            // TODO other objects if parent is requested?
            if (context.isObject()) {
                hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".stringsCount"));
                hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".longsCount"));
                hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".datesCount"));
                hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".referencesCount"));
                hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".polysCount"));
                hibernateQuery.addProjectionElement(new ProjectionElement(rootAlias + ".booleansCount"));
            }

            hibernateQuery.setResultTransformer(GetObjectResult.RESULT_TRANSFORMER);
        }

        return hibernateQuery;
    }

    private void interpretQueryFilter(InterpretationContext context, ObjectQuery query) throws QueryException {
        if (query != null && query.getFilter() != null) {
            Condition c = interpretFilter(context, query.getFilter(), null);
            context.getHibernateQuery().addCondition(c);
        }
    }

    public Condition interpretFilter(InterpretationContext context, ObjectFilter filter, Restriction parent) throws QueryException {
        Restriction restriction = findAndCreateRestriction(filter, context, parent);
        Condition condition = restriction.interpret();
        return condition;
    }

    private <T extends ObjectFilter> Restriction findAndCreateRestriction(T filter, InterpretationContext context,
                                                                          Restriction parent) throws QueryException {

        Validate.notNull(filter);
        Validate.notNull(context);

        LOGGER.trace("Determining restriction for filter {}", filter);

        InterpreterHelper helper = context.getHelper();
        JpaEntityDefinition baseEntityDefinition;
        if (parent != null) {
            baseEntityDefinition = parent.getBaseEntityDefinitionForChildren();
        } else {
            baseEntityDefinition = context.getRootEntityDefinition();
        }
        Restriction restriction = findAndCreateRestrictionInternal(filter, context, parent, helper, baseEntityDefinition);

        LOGGER.trace("Restriction for {} is {}", filter.getClass().getSimpleName(), restriction);
        return restriction;
    }

    private <T extends ObjectFilter>
    Restriction findAndCreateRestrictionInternal(T filter, InterpretationContext context, Restriction parent,
                                                 InterpreterHelper helper, JpaEntityDefinition baseEntityDefinition) throws QueryException {

        // the order of processing restrictions can be important, so we do the selection via handwritten code

        if (filter instanceof AndFilter) {
            return new AndRestriction(context, (AndFilter) filter, baseEntityDefinition, parent);
        } else if (filter instanceof OrFilter) {
            return new OrRestriction(context, (OrFilter) filter, baseEntityDefinition, parent);
        } else if (filter instanceof NotFilter) {
            return new NotRestriction(context, (NotFilter) filter, baseEntityDefinition, parent);
        } else if (filter instanceof InOidFilter) {
            return new InOidRestriction(context, (InOidFilter) filter, baseEntityDefinition, parent);
        } else if (filter instanceof OrgFilter) {
            return new OrgRestriction(context, (OrgFilter) filter, baseEntityDefinition, parent);
        } else if (filter instanceof TypeFilter) {
            TypeFilter typeFilter = (TypeFilter) filter;
            JpaEntityDefinition refinedEntityDefinition = helper.findRestrictedEntityDefinition(baseEntityDefinition, typeFilter.getType());
            return new TypeRestriction(context, typeFilter, refinedEntityDefinition, parent);
        } else if (filter instanceof ExistsFilter) {
            ExistsFilter existsFilter = (ExistsFilter) filter;
            ItemPath path = existsFilter.getFullPath();
            ProperDefinitionSearchResult<JpaEntityItemDefinition> searchResult = helper.findProperDefinition(baseEntityDefinition, path, JpaEntityItemDefinition.class);
            if (searchResult == null) {
                throw new QueryException("Path for ExistsFilter (" + path + ") doesn't point to a hibernate entity");
            }
            return new ExistsRestriction(context, existsFilter, searchResult.getEntityDefinition(), parent, searchResult.getItemDefinition());
        } else if (filter instanceof RefFilter) {
            RefFilter refFilter = (RefFilter) filter;
            ItemPath path = refFilter.getFullPath();
            ProperDefinitionSearchResult<JpaReferenceDefinition> searchResult = helper.findProperDefinition(baseEntityDefinition, path, JpaReferenceDefinition.class);
            if (searchResult == null) {
                throw new QueryException("Path for RefFilter (" + path + ") doesn't point to a reference item");
            }
            return new ReferenceRestriction(context, refFilter, searchResult.getEntityDefinition(),
                    parent, searchResult.getItemDefinition());
        } else if (filter instanceof ValueFilter) {
            ValueFilter valFilter = (ValueFilter) filter;
            ItemPath path = valFilter.getFullPath();

            ProperDefinitionSearchResult<JpaPropertyDefinition> propDefRes = helper.findProperDefinition(baseEntityDefinition, path, JpaPropertyDefinition.class);
            if (propDefRes != null) {
                return new PropertyRestriction(context, valFilter, propDefRes.getEntityDefinition(), parent, propDefRes.getItemDefinition());
            }
            ProperDefinitionSearchResult<JpaAnyDefinition> anyDefRes = helper.findProperDefinition(baseEntityDefinition, path, JpaAnyDefinition.class);
            if (anyDefRes != null) {
                if (ItemPath.containsSingleNameSegment(anyDefRes.getRemainder())) {
                    return new AnyPropertyRestriction(context, valFilter, anyDefRes.getEntityDefinition(), parent, anyDefRes.getItemDefinition());
                } else {
                    throw new QueryException("Unsupported any-targeted query: should contain single item name to be resolved in the 'any' container but contains '" +
                            anyDefRes.getRemainder() + "' instead");
                }
            }
            throw new QueryException("Couldn't find a proper restriction for a ValueFilter: " + valFilter.debugDump());
        } else if (filter instanceof NoneFilter || filter instanceof AllFilter || filter instanceof UndefinedFilter) {
            // these should be filtered out by the client
            throw new IllegalStateException("Trivial filters are not supported by QueryInterpreter: " + filter.debugDump());
        } else {
            throw new IllegalStateException("Unknown filter: " + filter.debugDump());
        }
    }

    private void interpretPagingAndSorting(InterpretationContext context, ObjectQuery query, boolean countingObjects) throws QueryException {
        RootHibernateQuery hibernateQuery = context.getHibernateQuery();
        String rootAlias = hibernateQuery.getPrimaryEntityAlias();

        if (query != null && query.getPaging() instanceof ObjectPagingAfterOid) {
            ObjectPagingAfterOid paging = (ObjectPagingAfterOid) query.getPaging();
            if (paging.getOidGreaterThan() != null) {
                Condition c = hibernateQuery.createSimpleComparisonCondition(rootAlias + ".oid", paging.getOidGreaterThan(), ">");
                hibernateQuery.addCondition(c);
            }
        }

        if (!countingObjects && query != null && query.getPaging() != null) {
            if (query.getPaging() instanceof ObjectPagingAfterOid) {
                updatePagingAndSortingByOid(hibernateQuery, (ObjectPagingAfterOid) query.getPaging());                // very special case - ascending ordering by OID (nothing more)
            } else {
                updatePagingAndSorting(context, hibernateQuery, query.getPaging());
            }
        }
    }

    protected void updatePagingAndSortingByOid(RootHibernateQuery hibernateQuery, ObjectPagingAfterOid paging) {
        String rootAlias = hibernateQuery.getPrimaryEntityAlias();
        if (paging.getOrderBy() != null || paging.getDirection() != null || paging.getOffset() != null) {
            throw new IllegalArgumentException("orderBy, direction nor offset is allowed on ObjectPagingAfterOid");
        }
        hibernateQuery.setOrder(rootAlias + ".oid", OrderDirection.ASCENDING);
        if (paging.getMaxSize() != null) {
            hibernateQuery.setMaxResults(paging.getMaxSize());
        }
    }

    public <T extends Containerable> void updatePagingAndSorting(InterpretationContext context, RootHibernateQuery hibernateQuery,
                                                                 ObjectPaging paging) throws QueryException {
        if (paging == null) {
            return;
        }
        if (paging.getOffset() != null) {
            hibernateQuery.setFirstResult(paging.getOffset());
        }
        if (paging.getMaxSize() != null) {
            hibernateQuery.setMaxResults(paging.getMaxSize());
        }

        ItemPath orderByPath = paging.getOrderBy();
        if (ItemPath.isNullOrEmpty(orderByPath)) {
            if (paging.getDirection() == null) {
                return;
            } else {
                throw new QueryException("Ordering by empty property path is not possible");
            }
        }

        ProperDefinitionSearchResult<JpaItemDefinition> result = context.getHelper().findProperDefinition(
                context.getRootEntityDefinition(), orderByPath, JpaItemDefinition.class);
        if (result == null) {
            throw new QueryException("Unknown path '" + orderByPath + "', couldn't find definition for it, "
                    + "list will not be ordered by it.");
        } else if (result.getItemDefinition() instanceof JpaAnyDefinition) {
            throw new QueryException("Sorting based on extension item or attribute is not supported yet: " + orderByPath);
        } else if (result.getItemDefinition() instanceof JpaReferenceDefinition) {
            throw new QueryException("Sorting based on reference is not supported: " + orderByPath);
        } else if (result.getItemDefinition().isMultivalued()) {
            throw new QueryException("Sorting based on multi-valued item is not supported: " + orderByPath);
        } else if (result.getItemDefinition() instanceof JpaEntityItemDefinition) {
            throw new QueryException("Sorting based on entity is not supported: " + orderByPath);
        } else if (!(result.getItemDefinition() instanceof JpaPropertyDefinition)) {
            throw new IllegalStateException("Unknown item definition type: " + result.getClass());
        }

        JpaEntityDefinition baseEntityDefinition = result.getEntityDefinition();
        JpaPropertyDefinition orderByDefinition = (JpaPropertyDefinition) result.getItemDefinition();
        String hqlPropertyPath = context.getHelper().prepareJoins(orderByPath, context.getPrimaryEntityAlias(), baseEntityDefinition)
                + "." + orderByDefinition.getJpaName();
        if (RPolyString.class.equals(orderByDefinition.getJpaClass())) {
            hqlPropertyPath += ".orig";
        }
        if (paging.getDirection() != null) {
            switch (paging.getDirection()) {
                case ASCENDING:
                    hibernateQuery.setOrder(hqlPropertyPath, OrderDirection.ASCENDING);
                    break;
                case DESCENDING:
                    hibernateQuery.setOrder(hqlPropertyPath, OrderDirection.DESCENDING);
                    break;
            }
        } else {
            hibernateQuery.setOrder(hqlPropertyPath, OrderDirection.ASCENDING);
        }
    }

    public <T extends Object> Matcher<T> findMatcher(T value) {
        return findMatcher(value != null ? (Class<T>) value.getClass() : null);
    }

    public <T extends Object> Matcher<T> findMatcher(Class<T> type) {
        Matcher<T> matcher = AVAILABLE_MATCHERS.get(type);
        if (matcher == null) {
            //we return default matcher
            matcher = AVAILABLE_MATCHERS.get(null);
        }
        return matcher;
    }
}