/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.h2.api.ErrorCode;
import org.h2.command.query.AllColumnsForPlan;
import org.h2.command.query.Select;
import org.h2.engine.Database;
import org.h2.engine.Right;
import org.h2.engine.SessionLocal;
import org.h2.expression.Expression;
import org.h2.expression.condition.Comparison;
import org.h2.expression.condition.ConditionAndOr;
import org.h2.index.Index;
import org.h2.index.IndexCondition;
import org.h2.index.IndexCursor;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.util.HasSQL;
import org.h2.util.ParserUtil;
import org.h2.util.StringUtils;
import org.h2.util.Utils;
import org.h2.value.TypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueInteger;
import org.h2.value.ValueNull;
import org.h2.value.ValueSmallint;
import org.h2.value.ValueTinyint;

/**
 * A table filter represents a table that is used in a query. There is one such
 * object whenever a table (or view) is used in a query. For example the
 * following query has 2 table filters: SELECT * FROM TEST T1, TEST T2.
 */
public class TableFilter implements ColumnResolver {

    private static final int BEFORE_FIRST = 0, FOUND = 1, AFTER_LAST = 2, NULL_ROW = 3;

    /**
     * Comparator that uses order in FROM clause as a sort key.
     */
    public static final Comparator<TableFilter> ORDER_IN_FROM_COMPARATOR =
            Comparator.comparing(TableFilter::getOrderInFrom);

    /**
     * A visitor that sets joinOuterIndirect to true.
     */
    private static final TableFilterVisitor JOI_VISITOR = f -> f.joinOuterIndirect = true;

    /**
     * Whether this is a direct or indirect (nested) outer join
     */
    protected boolean joinOuterIndirect;

    private SessionLocal session;

    private final Table table;
    private final Select select;
    private String alias;
    private Index index;
    private final IndexHints indexHints;
    private int[] masks;
    private int scanCount;
    private boolean evaluatable;

    /**
     * Indicates that this filter is used in the plan.
     */
    private boolean used;

    /**
     * The filter used to walk through the index.
     */
    private final IndexCursor cursor;

    /**
     * The index conditions used for direct index lookup (start or end).
     */
    private final ArrayList<IndexCondition> indexConditions = Utils.newSmallArrayList();

    /**
     * Additional conditions that can't be used for index lookup, but for row
     * filter for this table (ID=ID, NAME LIKE '%X%')
     */
    private Expression filterCondition;

    /**
     * The complete join condition.
     */
    private Expression joinCondition;

    private SearchRow currentSearchRow;
    private Row current;
    private int state;

    /**
     * The joined table (if there is one).
     */
    private TableFilter join;

    /**
     * Whether this is an outer join.
     */
    private boolean joinOuter;

    /**
     * The nested joined table (if there is one).
     */
    private TableFilter nestedJoin;

    /**
     * Map of common join columns, used for NATURAL joins and USING clause of
     * other joins. This map preserves original order of the columns.
     */
    private LinkedHashMap<Column, Column> commonJoinColumns;

    private TableFilter commonJoinColumnsFilter;
    private ArrayList<Column> commonJoinColumnsToExclude;
    private boolean foundOne;
    private Expression fullCondition;
    private final int hashCode;
    private final int orderInFrom;

    /**
     * Map of derived column names. This map preserves original order of the
     * columns.
     */
    private LinkedHashMap<Column, String> derivedColumnMap;

    /**
     * Create a new table filter object.
     *
     * @param session the session
     * @param table the table from where to read data
     * @param alias the alias name
     * @param rightsChecked true if rights are already checked
     * @param select the select statement
     * @param orderInFrom original order number (index) of this table filter in
     * @param indexHints the index hints to be used by the query planner
     */
    public TableFilter(SessionLocal session, Table table, String alias,
            boolean rightsChecked, Select select, int orderInFrom, IndexHints indexHints) {
        this.session = session;
        this.table = table;
        this.alias = alias;
        this.select = select;
        this.cursor = new IndexCursor();
        if (!rightsChecked) {
            session.getUser().checkTableRight(table, Right.SELECT);
        }
        hashCode = session.nextObjectId();
        this.orderInFrom = orderInFrom;
        this.indexHints = indexHints;
    }

    /**
     * Get the order number (index) of this table filter in the "from" clause of
     * the query.
     *
     * @return the index (0, 1, 2,...)
     */
    public int getOrderInFrom() {
        return orderInFrom;
    }

    @Override
    public Select getSelect() {
        return select;
    }

    public Table getTable() {
        return table;
    }

    /**
     * Lock the table. This will also lock joined tables.
     *
     * @param s the session
     */
    public void lock(SessionLocal s) {
        table.lock(s, Table.READ_LOCK);
        if (join != null) {
            join.lock(s);
        }
    }

    /**
     * Get the best plan item (index, cost) to use for the current join
     * order.
     *
     * @param s the session
     * @param filters all joined table filters
     * @param filter the current table filter index
     * @param allColumnsSet the set of all columns
     * @return the best plan item
     */
    public PlanItem getBestPlanItem(SessionLocal s, TableFilter[] filters, int filter,
            AllColumnsForPlan allColumnsSet, boolean isSelectCommand) {
        PlanItem item1 = null;
        SortOrder sortOrder = null;
        if (select != null) {
            sortOrder = select.getSortOrder();
        }
        if (indexConditions.isEmpty()) {
            item1 = new PlanItem();
            item1.setIndex(table.getScanIndex(s, null, filters, filter,
                    sortOrder, allColumnsSet));
            item1.cost = item1.getIndex().getCost(s, null, filters, filter,
                    sortOrder, allColumnsSet, isSelectCommand);
        }
        int len = table.getColumns().length;
        int[] masks = new int[len];
        for (IndexCondition condition : indexConditions) {
            if (condition.isEvaluatable()) {
                if (condition.isAlwaysFalse()) {
                    masks = null;
                    break;
                }
                if (condition.isCompoundColumns()) {
                    // Set the op mask in case of compound columns as well.
                    Column[] columns = condition.getColumns();
                    for (Column column : columns) {
                        int id = column.getColumnId();
                        if (id >= 0) {
                            masks[id] |= condition.getMask(indexConditions);
                        }
                    }
                }
                else {
                    int id = condition.getColumn().getColumnId();
                    if (id >= 0) {
                        masks[id] |= condition.getMask(indexConditions);
                    }
                }
            }
        }
        PlanItem item = table.getBestPlanItem(s, masks, filters, filter, sortOrder, allColumnsSet, isSelectCommand);
        item.setMasks(masks);
        // The more index conditions, the earlier the table.
        // This is to ensure joins without indexes run quickly:
        // x (x.a=10); y (x.b=y.b) - see issue 113
        item.cost -= item.cost * indexConditions.size() / 100 / (filter + 1);

        if (item1 != null && item1.cost < item.cost) {
            item = item1;
        }

        if (nestedJoin != null) {
            setEvaluatable(true);
            item.setNestedJoinPlan(nestedJoin.getBestPlanItem(s, filters, filter, allColumnsSet, isSelectCommand));
            // TODO optimizer: calculate cost of a join: should use separate
            // expected row number and lookup cost
            item.cost += item.cost * item.getNestedJoinPlan().cost;
        }
        if (join != null) {
            setEvaluatable(true);
            do {
                filter++;
            } while (filters[filter] != join);
            item.setJoinPlan(join.getBestPlanItem(s, filters, filter, allColumnsSet, isSelectCommand));
            // TODO optimizer: calculate cost of a join: should use separate
            // expected row number and lookup cost
            item.cost += item.cost * item.getJoinPlan().cost;
        }
        return item;
    }

    /**
     * Set what plan item (index, cost, masks) to use.
     *
     * @param item the plan item
     */
    public void setPlanItem(PlanItem item) {
        if (item == null) {
            // invalid plan, most likely because a column wasn't found
            // this will result in an exception later on
            return;
        }
        setIndex(item.getIndex(), false);
        masks = item.getMasks();
        if (nestedJoin != null) {
            if (item.getNestedJoinPlan() != null) {
                nestedJoin.setPlanItem(item.getNestedJoinPlan());
            } else {
                nestedJoin.setScanIndexes();
            }
        }
        if (join != null) {
            if (item.getJoinPlan() != null) {
                join.setPlanItem(item.getJoinPlan());
            } else {
                join.setScanIndexes();
            }
        }
    }

    /**
     * Set all missing indexes to scan indexes recursively.
     */
    private void setScanIndexes() {
        if (index == null) {
            setIndex(table.getScanIndex(session), false);
        }
        if (join != null) {
            join.setScanIndexes();
        }
        if (nestedJoin != null) {
            nestedJoin.setScanIndexes();
        }
    }

    /**
     * Prepare reading rows. This method will remove all index conditions that
     * can not be used, and optimize the conditions.
     */
    public void prepare() {
        // forget all unused index conditions
        // the indexConditions list may be modified here
        boolean compoundIndexConditionFound = false;
        for (int i = 0; i < indexConditions.size(); i++) {
            IndexCondition condition = indexConditions.get(i);
            if (!condition.isAlwaysFalse()) {
                if (compoundIndexConditionFound) {
                    // A compound index condition is already found. We cannot use other indexes with it, so removing
                    // everything else. The compound condition was added first.
                    // See: ConditionIn#createIndexConditions(SessionLocal, TableFilter)
                    indexConditions.remove(i);
                    i--;
                } else if (condition.isCompoundColumns()) {
                    if ( index.getIndexType().isScan() ) {
                        // This is only a pseudo index.
                        indexConditions.remove(i);
                        i--;
                        continue;
                    }
                    // Checking the columns match with the index.
                    if (IndexCursor.canUseIndexForIn(index, condition.getColumns())) {
                        // The condition uses the exact columns in the right order.
                        compoundIndexConditionFound = true;
                        continue;
                    }
                    // Trying to fix the order of the condition columns.
                    IndexCondition fixedCondition = condition.cloneWithIndexColumns(index);
                    if (fixedCondition != null) {
                        indexConditions.set(i, fixedCondition);
                        compoundIndexConditionFound = true;
                        continue;
                    }
                    // Index condition cannot be used.
                    indexConditions.remove(i);
                    i--;
                } else {
                    Column col = condition.getColumn();
                    if (col.getColumnId() >= 0) {
                        int columnIndex = index.getColumnIndex(col);
                        if (columnIndex == 0) {
                            // The first column of the index always matches.
                            continue;
                        }
                        if (columnIndex < 0 || condition.getCompareType() == Comparison.IN_LIST ) {
                            // The index does not contain the column, or this is an IN() condition which can be used
                            // only if the first index column is the searched one.
                            // See: IndexCursor#canUseIndexFor(column)
                            indexConditions.remove(i);
                            i--;
                        }
                    }
                }
            }
        }
        if (nestedJoin != null) {
            if (nestedJoin == this) {
                throw DbException.getInternalError("self join");
            }
            nestedJoin.prepare();
        }
        if (join != null) {
            if (join == this) {
                throw DbException.getInternalError("self join");
            }
            join.prepare();
        }
        if (filterCondition != null) {
            filterCondition = filterCondition.optimizeCondition(session);
        }
        if (joinCondition != null) {
            joinCondition = joinCondition.optimizeCondition(session);
        }
    }

    /**
     * Start the query. This will reset the scan counts.
     *
     * @param s the session
     */
    public void startQuery(SessionLocal s) {
        this.session = s;
        scanCount = 0;
        if (nestedJoin != null) {
            nestedJoin.startQuery(s);
        }
        if (join != null) {
            join.startQuery(s);
        }
    }

    /**
     * Reset to the current position.
     */
    public void reset() {
        if (nestedJoin != null) {
            nestedJoin.reset();
        }
        if (join != null) {
            join.reset();
        }
        state = BEFORE_FIRST;
        foundOne = false;
    }

    /**
     * Check if there are more rows to read.
     *
     * @return true if there are
     */
    public boolean next() {
        if (state == AFTER_LAST) {
            return false;
        } else if (state == BEFORE_FIRST) {
            cursor.find(session, indexConditions);
            if (!cursor.isAlwaysFalse()) {
                if (nestedJoin != null) {
                    nestedJoin.reset();
                }
                if (join != null) {
                    join.reset();
                }
            }
        } else {
            // state == FOUND || NULL_ROW
            // the last row was ok - try next row of the join
            if (join != null && join.next()) {
                return true;
            }
        }
        while (true) {
            // go to the next row
            if (state == NULL_ROW) {
                break;
            }
            if (cursor.isAlwaysFalse()) {
                state = AFTER_LAST;
            } else if (nestedJoin != null) {
                if (state == BEFORE_FIRST) {
                    state = FOUND;
                }
            } else {
                if ((++scanCount & 4095) == 0) {
                    checkTimeout();
                }
                if (cursor.next()) {
                    currentSearchRow = cursor.getSearchRow();
                    current = null;
                    state = FOUND;
                } else {
                    state = AFTER_LAST;
                }
            }
            if (nestedJoin != null && state == FOUND) {
                if (!nestedJoin.next()) {
                    state = AFTER_LAST;
                    if (joinOuter && !foundOne) {
                        // possibly null row
                    } else {
                        continue;
                    }
                }
            }
            // if no more rows found, try the null row (for outer joins only)
            if (state == AFTER_LAST) {
                if (joinOuter && !foundOne) {
                    setNullRow();
                } else {
                    break;
                }
            }
            if (!isOk(filterCondition)) {
                continue;
            }
            boolean joinConditionOk = isOk(joinCondition);
            if (state == FOUND) {
                if (joinConditionOk) {
                    foundOne = true;
                } else {
                    continue;
                }
            }
            if (join != null) {
                join.reset();
                if (!join.next()) {
                    continue;
                }
            }
            // check if it's ok
            if (state == NULL_ROW || joinConditionOk) {
                return true;
            }
        }
        state = AFTER_LAST;
        return false;
    }

    public boolean isNullRow() {
        return state == NULL_ROW;
    }

    /**
     * Set the state of this and all nested tables to the NULL row.
     */
    protected void setNullRow() {
        state = NULL_ROW;
        current = table.getNullRow();
        currentSearchRow = current;
        if (nestedJoin != null) {
            nestedJoin.visit(TableFilter::setNullRow);
        }
    }

    private void checkTimeout() {
        session.checkCanceled();
    }

    /**
     * Whether the current value of the condition is true, or there is no
     * condition.
     *
     * @param condition the condition (null for no condition)
     * @return true if yes
     */
    boolean isOk(Expression condition) {
        return condition == null || condition.getBooleanValue(session);
    }

    /**
     * Get the current row.
     *
     * @return the current row, or null
     */
    public Row get() {
        if (current == null && currentSearchRow != null) {
            current = cursor.get();
        }
        return current;
    }

    /**
     * Set the current row.
     *
     * @param current the current row
     */
    public void set(Row current) {
        this.current = current;
        this.currentSearchRow = current;
    }

    /**
     * Get the table alias name. If no alias is specified, the table name is
     * returned.
     *
     * @return the alias name
     */
    @Override
    public String getTableAlias() {
        if (alias != null) {
            return alias;
        }
        return table.getName();
    }

    /**
     * Add an index condition.
     *
     * @param condition the index condition
     */
    public void addIndexCondition(IndexCondition condition) {
        indexConditions.add(condition);
    }

    /**
     * Add a filter condition.
     *
     * @param condition the condition
     * @param isJoin if this is in fact a join condition
     */
    public void addFilterCondition(Expression condition, boolean isJoin) {
        if (isJoin) {
            if (joinCondition == null) {
                joinCondition = condition;
            } else {
                joinCondition = new ConditionAndOr(ConditionAndOr.AND,
                        joinCondition, condition);
            }
        } else {
            if (filterCondition == null) {
                filterCondition = condition;
            } else {
                filterCondition = new ConditionAndOr(ConditionAndOr.AND,
                        filterCondition, condition);
            }
        }
    }

    /**
     * Add a joined table.
     *
     * @param filter the joined table filter
     * @param outer if this is an outer join
     * @param on the join condition
     */
    public void addJoin(TableFilter filter, boolean outer, Expression on) {
        if (on != null) {
            TableFilterVisitor visitor = new MapColumnsVisitor(on);
            visit(visitor);
            filter.visit(visitor);
        }
        if (join == null) {
            join = filter;
            filter.joinOuter = outer;
            if (outer) {
                filter.visit(JOI_VISITOR);
            }
            if (on != null) {
                filter.addFilter(on);
            }
        } else {
            join.addJoin(filter, outer, on);
        }
    }

    /**
     * Set a nested joined table.
     *
     * @param filter the joined table filter
     */
    public void setNestedJoin(TableFilter filter) {
        nestedJoin = filter;
    }

    /**
     * Add the join condition.
     *
     * @param on the condition
     */
    public void addFilter(Expression on) {
        addFilterCondition(on, true);
        if (join != null) {
            join.addFilter(on);
        }
    }

    /**
     * Map the columns to the given column resolver.
     *
     * @param resolver
     *            the resolver
     * @param level
     *            the subquery level (0 is the top level query, 1 is the first
     *            subquery level)
     * @param outer
     *            whether this method was called from the outer query
     */
    public void mapColumns(ColumnResolver resolver, int level, boolean outer) {
        if (!outer && joinOuter) {
            return;
        }
        if (joinCondition != null) {
            joinCondition.mapColumns(resolver, level, Expression.MAP_INITIAL);
        }
        if (nestedJoin != null) {
            nestedJoin.mapColumns(resolver, level, outer);
        }
        if (join != null) {
            join.mapColumns(resolver, level, outer);
        }
    }

    /**
     * Create the index conditions for this filter if needed.
     */
    public void createIndexConditions() {
        if (joinCondition != null) {
            joinCondition = joinCondition.optimizeCondition(session);
            if (joinCondition != null) {
                joinCondition.createIndexConditions(session, this);
                if (nestedJoin != null) {
                    joinCondition.createIndexConditions(session, nestedJoin);
                }
            }
        }
        if (join != null) {
            join.createIndexConditions();
        }
        if (nestedJoin != null) {
            nestedJoin.createIndexConditions();
        }
    }

    public TableFilter getJoin() {
        return join;
    }

    /**
     * Whether this is an outer joined table.
     *
     * @return true if it is
     */
    public boolean isJoinOuter() {
        return joinOuter;
    }

    /**
     * Whether this is indirectly an outer joined table (nested within an inner
     * join).
     *
     * @return true if it is
     */
    public boolean isJoinOuterIndirect() {
        return joinOuterIndirect;
    }

    /**
     * Get the query execution plan text to use for this table filter and append
     * it to the specified builder.
     *
     * @param builder string builder to append to
     * @param isJoin if this is a joined table
     * @param sqlFlags formatting flags
     * @return the specified builder
     */
    public StringBuilder getPlanSQL(StringBuilder builder, boolean isJoin, int sqlFlags) {
        if (isJoin) {
            if (joinOuter) {
                builder.append("LEFT OUTER JOIN ");
            } else {
                builder.append("INNER JOIN ");
            }
        }
        if (nestedJoin != null) {
            StringBuilder buffNested = new StringBuilder();
            TableFilter n = nestedJoin;
            do {
                n.getPlanSQL(buffNested, n != nestedJoin, sqlFlags).append('\n');
                n = n.getJoin();
            } while (n != null);
            String nested = buffNested.toString();
            boolean enclose = !nested.startsWith("(");
            if (enclose) {
                builder.append("(\n");
            }
            StringUtils.indent(builder, nested, 4, false);
            if (enclose) {
                builder.append(')');
            }
            if (isJoin) {
                builder.append(" ON ");
                if (joinCondition == null) {
                    // need to have a ON expression,
                    // otherwise the nesting is unclear
                    builder.append("1=1");
                } else {
                    joinCondition.getUnenclosedSQL(builder, sqlFlags);
                }
            }
            return builder;
        }
        table.getSQL(builder, sqlFlags);
        if (table instanceof TableView && ((TableView) table).isInvalid()) {
            throw DbException.get(ErrorCode.VIEW_IS_INVALID_2, table.getName(), "not compiled");
        }
        if (alias != null) {
            builder.append(' ');
            ParserUtil.quoteIdentifier(builder, alias, sqlFlags);
            if (derivedColumnMap != null) {
                builder.append('(');
                boolean f = false;
                for (String name : derivedColumnMap.values()) {
                    if (f) {
                        builder.append(", ");
                    }
                    f = true;
                    ParserUtil.quoteIdentifier(builder, name, sqlFlags);
                }
                builder.append(')');
            }
        }
        if (indexHints != null) {
            builder.append(" USE INDEX (");
            boolean first = true;
            for (String index : indexHints.getAllowedIndexes()) {
                if (!first) {
                    builder.append(", ");
                } else {
                    first = false;
                }
                ParserUtil.quoteIdentifier(builder, index, sqlFlags);
            }
            builder.append(")");
        }
        if (index != null && (sqlFlags & HasSQL.ADD_PLAN_INFORMATION) != 0) {
            builder.append('\n');
            StringBuilder planBuilder = new StringBuilder().append("/* ").append(index.getPlanSQL());
            if (!indexConditions.isEmpty()) {
                planBuilder.append(": ");
                for (int i = 0, size = indexConditions.size(); i < size; i++) {
                    if (i > 0) {
                        planBuilder.append("\n    AND ");
                    }
                    planBuilder.append(indexConditions.get(i).getSQL(
                            HasSQL.TRACE_SQL_FLAGS | HasSQL.ADD_PLAN_INFORMATION));
                }
            }
            if (planBuilder.indexOf("\n", 3) >= 0) {
                planBuilder.append('\n');
            }
            StringUtils.indent(builder, planBuilder.append(" */").toString(), 4, false);
        }
        if (isJoin) {
            builder.append("\n    ON ");
            if (joinCondition == null) {
                // need to have a ON expression, otherwise the nesting is
                // unclear
                builder.append("1=1");
            } else {
                joinCondition.getUnenclosedSQL(builder, sqlFlags);
            }
        }
        if ((sqlFlags & HasSQL.ADD_PLAN_INFORMATION) != 0) {
            if (filterCondition != null) {
                builder.append('\n');
                String condition = filterCondition.getSQL(HasSQL.TRACE_SQL_FLAGS | HasSQL.ADD_PLAN_INFORMATION,
                        Expression.WITHOUT_PARENTHESES);
                condition = "/* WHERE " + condition + "\n*/";
                StringUtils.indent(builder, condition, 4, false);
            }
            if (scanCount > 0) {
                builder.append("\n    /* scanCount: ").append(scanCount).append(" */");
            }
        }
        return builder;
    }

    /**
     * Remove all index conditions that are not used by the current index.
     */
    void removeUnusableIndexConditions() {
        // the indexConditions list may be modified here
        for (int i = 0; i < indexConditions.size(); i++) {
            IndexCondition cond = indexConditions.get(i);
            if (cond.getMask(indexConditions) == 0 || !cond.isEvaluatable()) {
                indexConditions.remove(i--);
            }
        }
    }

    public int[] getMasks() {
        return masks;
    }

    public Index getIndex() {
        return index;
    }

    public void setIndex(Index index, boolean reverse) {
        this.index = index;
        cursor.setIndex(index, reverse);
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public boolean isUsed() {
        return used;
    }

    /**
     * Remove the joined table
     */
    public void removeJoin() {
        this.join = null;
    }

    public Expression getJoinCondition() {
        return joinCondition;
    }

    /**
     * Remove the join condition.
     */
    public void removeJoinCondition() {
        this.joinCondition = null;
    }

    public Expression getFilterCondition() {
        return filterCondition;
    }

    /**
     * Remove the filter condition.
     */
    public void removeFilterCondition() {
        this.filterCondition = null;
    }

    public void setFullCondition(Expression condition) {
        this.fullCondition = condition;
        if (join != null) {
            join.setFullCondition(condition);
        }
    }

    /**
     * Optimize the full condition. This will add the full condition to the
     * filter condition.
     */
    void optimizeFullCondition() {
        if (!joinOuter && fullCondition != null) {
            fullCondition.addFilterConditions(this);
            if (nestedJoin != null) {
                nestedJoin.optimizeFullCondition();
            }
            if (join != null) {
                join.optimizeFullCondition();
            }
        }
    }

    /**
     * Update the filter and join conditions of this and all joined tables with
     * the information that the given table filter and all nested filter can now
     * return rows or not.
     *
     * @param filter the table filter
     * @param b the new flag
     */
    public void setEvaluatable(TableFilter filter, boolean b) {
        filter.setEvaluatable(b);
        if (filterCondition != null) {
            filterCondition.setEvaluatable(filter, b);
        }
        if (joinCondition != null) {
            joinCondition.setEvaluatable(filter, b);
        }
        if (nestedJoin != null) {
            // don't enable / disable the nested join filters
            // if enabling a filter in a joined filter
            if (this == filter) {
                nestedJoin.setEvaluatable(nestedJoin, b);
            }
        }
        if (join != null) {
            join.setEvaluatable(filter, b);
        }
    }

    public void setEvaluatable(boolean evaluatable) {
        this.evaluatable = evaluatable;
    }

    @Override
    public String getSchemaName() {
        if (alias == null && !(table instanceof VirtualTable)) {
            return table.getSchema().getName();
        }
        return null;
    }

    @Override
    public Column[] getColumns() {
        return table.getColumns();
    }

    @Override
    public Column findColumn(String name) {
        HashMap<Column, String> map = derivedColumnMap;
        if (map != null) {
            Database db = session.getDatabase();
            for (Entry<Column, String> entry : derivedColumnMap.entrySet()) {
                if (db.equalsIdentifiers(entry.getValue(), name)) {
                    return entry.getKey();
                }
            }
            return null;
        }
        return table.findColumn(name);
    }

    @Override
    public String getColumnName(Column column) {
        HashMap<Column, String> map = derivedColumnMap;
        return map != null ? map.get(column) : column.getName();
    }

    @Override
    public boolean hasDerivedColumnList() {
        return derivedColumnMap != null;
    }

    /**
     * Get the column with the given name.
     *
     * @param columnName
     *            the column name
     * @param ifExists
     *            if {@code true} return {@code null} if column does not exist
     * @return the column
     * @throws DbException
     *             if the column was not found and {@code ifExists} is
     *             {@code false}
     */
    public Column getColumn(String columnName, boolean ifExists) {
        HashMap<Column, String> map = derivedColumnMap;
        if (map != null) {
            Database database = session.getDatabase();
            for (Entry<Column, String> entry : map.entrySet()) {
                if (database.equalsIdentifiers(columnName, entry.getValue())) {
                    return entry.getKey();
                }
            }
            if (ifExists) {
                return null;
            } else {
                throw DbException.get(ErrorCode.COLUMN_NOT_FOUND_1, columnName);
            }
        }
        return table.getColumn(columnName, ifExists);
    }

    /**
     * Get the system columns that this table understands. This is used for
     * compatibility with other databases. The columns are only returned if the
     * current mode supports system columns.
     *
     * @return the system columns
     */
    @Override
    public Column[] getSystemColumns() {
        if (!session.getDatabase().getMode().systemColumns) {
            return null;
        }
        Column[] sys = { //
                new Column("oid", TypeInfo.TYPE_INTEGER, table, 0), //
                new Column("ctid", TypeInfo.TYPE_VARCHAR, table, 0) //
        };
        return sys;
    }

    @Override
    public Column getRowIdColumn() {
        return table.getRowIdColumn();
    }

    @Override
    public Value getValue(Column column) {
        if (currentSearchRow == null) {
            return null;
        }
        int columnId = column.getColumnId();
        if (columnId == -1) {
            return ValueBigint.get(currentSearchRow.getKey());
        }
        if (current == null) {
            Value v = currentSearchRow.getValue(columnId);
            if (v != null) {
                return v;
            }
            if (columnId == column.getTable().getMainIndexColumn()) {
                return getDelegatedValue(column);
            }
            current = cursor.get();
            if (current == null) {
                return ValueNull.INSTANCE;
            }
        }
        return current.getValue(columnId);
    }

    private Value getDelegatedValue(Column column) {
        long key = currentSearchRow.getKey();
        switch (column.getType().getValueType()) {
        case Value.TINYINT:
            return ValueTinyint.get((byte) key);
        case Value.SMALLINT:
            return ValueSmallint.get((short) key);
        case Value.INTEGER:
            return ValueInteger.get((int) key);
        case Value.BIGINT:
            return ValueBigint.get(key);
        default:
            throw DbException.getInternalError();
        }
    }

    @Override
    public TableFilter getTableFilter() {
        return this;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    /**
     * Set derived column list.
     *
     * @param derivedColumnNames names of derived columns
     */
    public void setDerivedColumns(ArrayList<String> derivedColumnNames) {
        Column[] columns = getColumns();
        int count = columns.length;
        if (count != derivedColumnNames.size()) {
            throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
        }
        LinkedHashMap<Column, String> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String alias = derivedColumnNames.get(i);
            for (int j = 0; j < i; j++) {
                if (alias.equals(derivedColumnNames.get(j))) {
                    throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1, alias);
                }
            }
            map.put(columns[i], alias);
        }
        this.derivedColumnMap = map;
    }

    @Override
    public String toString() {
        return alias != null ? alias : table.toString();
    }

    /**
     * Add a column to the common join column list for a left table filter.
     *
     * @param leftColumn
     *            the column on the left side
     * @param replacementColumn
     *            the column to use instead, may be the same as column on the
     *            left side
     * @param replacementFilter
     *            the table filter for replacement columns
     */
    public void addCommonJoinColumns(Column leftColumn, Column replacementColumn, TableFilter replacementFilter) {
        if (commonJoinColumns == null) {
            commonJoinColumns = new LinkedHashMap<>();
            commonJoinColumnsFilter = replacementFilter;
        } else {
            assert commonJoinColumnsFilter == replacementFilter;
        }
        commonJoinColumns.put(leftColumn, replacementColumn);
    }

    /**
     * Add an excluded column to the common join column list.
     *
     * @param columnToExclude
     *            the column to exclude
     */
    public void addCommonJoinColumnToExclude(Column columnToExclude) {
        if (commonJoinColumnsToExclude == null) {
            commonJoinColumnsToExclude = Utils.newSmallArrayList();
        }
        commonJoinColumnsToExclude.add(columnToExclude);
    }

    /**
     * Returns common join columns map.
     *
     * @return common join columns map, or {@code null}
     */
    public LinkedHashMap<Column, Column> getCommonJoinColumns() {
        return commonJoinColumns;
    }

    /**
     * Returns common join columns table filter.
     *
     * @return common join columns table filter, or {@code null}
     */
    public TableFilter getCommonJoinColumnsFilter() {
        return commonJoinColumnsFilter;
    }

    /**
     * Check if the given column is an excluded common join column.
     *
     * @param c
     *            the column to check
     * @return true if this is an excluded common join column
     */
    public boolean isCommonJoinColumnToExclude(Column c) {
        return commonJoinColumnsToExclude != null && commonJoinColumnsToExclude.contains(c);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public TableFilter getNestedJoin() {
        return nestedJoin;
    }

    /**
     * Visit this and all joined or nested table filters.
     *
     * @param visitor the visitor
     */
    public void visit(TableFilterVisitor visitor) {
        TableFilter f = this;
        do {
            visitor.accept(f);
            TableFilter n = f.nestedJoin;
            if (n != null) {
                n.visit(visitor);
            }
            f = f.join;
        } while (f != null);
    }

    public boolean isEvaluatable() {
        return evaluatable;
    }

    public SessionLocal getSession() {
        return session;
    }

    public IndexHints getIndexHints() {
        return indexHints;
    }

    /**
     * Returns whether this is a table filter with implicit DUAL table for a
     * SELECT without a FROM clause.
     *
     * @return false if this is a table filter with implicit DUAL table, true otherwise
     */
    public boolean hasFromClause() {
        return !(table instanceof DualTable && join == null && nestedJoin == null
                && joinCondition == null && filterCondition == null);
    }

    /**
     * Retrieves the full condition expression.
     *
     * @return The complete condition represented as an {@code Expression} object.
     */
    public Expression getFullCondition(){
        return fullCondition;
    }

    /**
     * A visitor for table filters.
     */
    public interface TableFilterVisitor {

        /**
         * This method is called for each nested or joined table filter.
         *
         * @param f the filter
         */
        void accept(TableFilter f);
    }

    /**
     * A visitor that maps columns.
     */
    private static final class MapColumnsVisitor implements TableFilterVisitor {
        private final Expression on;

        MapColumnsVisitor(Expression on) {
            this.on = on;
        }

        @Override
        public void accept(TableFilter f) {
            on.mapColumns(f, 0, Expression.MAP_INITIAL);
        }
    }

}
