/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.analyzer;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.execution.ddl.commands.KsqlTopic;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.expression.tree.QualifiedColumnReferenceExp;
import io.confluent.ksql.metastore.model.DataSource;
import io.confluent.ksql.metastore.model.KsqlStream;
import io.confluent.ksql.metastore.model.KsqlTable;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.properties.with.CreateSourceAsProperties;
import io.confluent.ksql.parser.tree.ResultMaterialization;
import io.confluent.ksql.parser.tree.SelectItem;
import io.confluent.ksql.parser.tree.WindowExpression;
import io.confluent.ksql.parser.tree.WithinExpression;
import io.confluent.ksql.planner.plan.JoinNode;
import io.confluent.ksql.planner.plan.JoinNode.JoinType;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.serde.SerdeOption;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.SchemaUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Analysis implements ImmutableAnalysis {

  private final ResultMaterialization resultMaterialization;
  private final Function<Map<SourceName, LogicalSchema>, SourceSchemas> sourceSchemasFactory;
  private Optional<Into> into = Optional.empty();
  private final List<AliasedDataSource> fromDataSources = new ArrayList<>();
  private final List<JoinInfo> joinInfo = new ArrayList<>();
  private Optional<Expression> whereExpression = Optional.empty();
  private final List<SelectItem> selectItems = new ArrayList<>();
  private final Set<ColumnName> selectColumnNames = new HashSet<>();
  private final Set<Expression> groupByExpressions = new LinkedHashSet<>();
  private Optional<WindowExpression> windowExpression = Optional.empty();
  private Optional<Expression> partitionBy = Optional.empty();
  private Optional<Expression> havingExpression = Optional.empty();
  private OptionalInt limitClause = OptionalInt.empty();
  private CreateSourceAsProperties withProperties = CreateSourceAsProperties.none();
  private final List<FunctionCall> tableFunctions = new ArrayList<>();

  public Analysis(final ResultMaterialization resultMaterialization) {
    this(resultMaterialization, SourceSchemas::new);
  }

  @VisibleForTesting
  Analysis(
      final ResultMaterialization resultMaterialization,
      final Function<Map<SourceName, LogicalSchema>, SourceSchemas> sourceSchemasFactory
  ) {
    this.resultMaterialization = requireNonNull(resultMaterialization, "resultMaterialization");
    this.sourceSchemasFactory = requireNonNull(sourceSchemasFactory, "sourceSchemasFactory");
  }

  ResultMaterialization getResultMaterialization() {
    return resultMaterialization;
  }

  void addSelectItem(final SelectItem selectItem) {
    selectItems.add(Objects.requireNonNull(selectItem, "selectItem"));
  }

  void addSelectColumnRefs(final Collection<ColumnName> columnNames) {
    selectColumnNames.addAll(columnNames);
  }

  @Override
  public Optional<Into> getInto() {
    return into;
  }

  public void setInto(final Into into) {
    this.into = Optional.of(into);
  }

  @Override
  public Optional<Expression> getWhereExpression() {
    return whereExpression;
  }

  void setWhereExpression(final Expression whereExpression) {
    this.whereExpression = Optional.of(whereExpression);
  }

  @Override
  public List<SelectItem> getSelectItems() {
    return Collections.unmodifiableList(selectItems);
  }

  @Override
  public Set<ColumnName> getSelectColumnNames() {
    return Collections.unmodifiableSet(selectColumnNames);
  }

  @Override
  public List<Expression> getGroupByExpressions() {
    return ImmutableList.copyOf(groupByExpressions);
  }

  void setGroupByExpressions(final List<Expression> expressions) {
    expressions.forEach(exp -> {
      if (!groupByExpressions.add(exp)) {
        throw new KsqlException("Duplicate GROUP BY expression: " + exp);
      }
    });
  }

  @Override
  public Optional<WindowExpression> getWindowExpression() {
    return windowExpression;
  }

  void setWindowExpression(final WindowExpression windowExpression) {
    this.windowExpression = Optional.of(windowExpression);
  }

  @Override
  public Optional<Expression> getHavingExpression() {
    return havingExpression;
  }

  void setHavingExpression(final Expression havingExpression) {
    this.havingExpression = Optional.of(havingExpression);
  }

  @Override
  public Optional<Expression> getPartitionBy() {
    return partitionBy;
  }

  void setPartitionBy(final Expression partitionBy) {
    this.partitionBy = Optional.of(partitionBy);
  }

  @Override
  public OptionalInt getLimitClause() {
    return limitClause;
  }

  void setLimitClause(final int limitClause) {
    this.limitClause = OptionalInt.of(limitClause);
  }

  void addJoin(final JoinInfo joinInfo) {
    // we cannot add more joins than we have data sources
    if (fromDataSources.size() < this.joinInfo.size()) {
      throw new IllegalStateException("Join info can only be supplied for joins");
    }

    this.joinInfo.add(joinInfo);
  }

  public List<JoinInfo> getJoin() {
    return joinInfo;
  }

  public boolean isJoin() {
    return !joinInfo.isEmpty();
  }

  @Override
  public List<AliasedDataSource> getFromDataSources() {
    return ImmutableList.copyOf(fromDataSources);
  }

  @Override
  public SourceSchemas getFromSourceSchemas(final boolean postAggregate) {
    final Map<SourceName, LogicalSchema> schemaBySource = fromDataSources.stream()
        .collect(Collectors.toMap(
            AliasedDataSource::getAlias,
            ads -> buildStreamsSchema(ads, postAggregate)
        ));

    return sourceSchemasFactory.apply(schemaBySource);
  }

  void addDataSource(final SourceName alias, final DataSource dataSource) {
    if (!(dataSource instanceof KsqlStream) && !(dataSource instanceof KsqlTable)) {
      throw new IllegalArgumentException("Data source type not supported yet: " + dataSource);
    }

    fromDataSources.add(new AliasedDataSource(alias, dataSource));
  }

  @Override
  public QualifiedColumnReferenceExp getDefaultArgument() {
    final SourceName alias = fromDataSources.get(0).getAlias();
    return new QualifiedColumnReferenceExp(alias, SchemaUtil.ROWTIME_NAME);
  }

  void setProperties(final CreateSourceAsProperties properties) {
    withProperties = requireNonNull(properties, "properties");
  }

  @Override
  public CreateSourceAsProperties getProperties() {
    return withProperties;
  }

  void addTableFunction(final FunctionCall functionCall) {
    this.tableFunctions.add(Objects.requireNonNull(functionCall));
  }

  @Override
  public List<FunctionCall> getTableFunctions() {
    return tableFunctions;
  }

  private LogicalSchema buildStreamsSchema(
      final AliasedDataSource ds,
      final boolean postAggregate
  ) {
    // While the special casing exists to copy WINDOWSTART and WINDOWEND into the value schema
    // for some query types, a matching hack is required here:

    // For windowed sources:
    final boolean windowedSource = ds.getDataSource().getKsqlTopic().getKeyFormat().isWindowed();

    // Post the GROUP BY the window bounds columns are available:
    final boolean windowedGroupBy = postAggregate && windowExpression.isPresent();

    return ds.getDataSource()
        .getSchema()
        .withMetaAndKeyColsInValue(windowedSource || windowedGroupBy);
  }

  @Immutable
  public static final class Into {

    private final SourceName name;
    private final KsqlTopic topic;
    private final boolean create;
    private final ImmutableSet<SerdeOption> defaultSerdeOptions;

    public static Into of(
        final SourceName name,
        final boolean create,
        final KsqlTopic topic,
        final Set<SerdeOption> defaultSerdeOptions
    ) {
      return new Into(name, create, topic, defaultSerdeOptions);
    }

    private Into(
        final SourceName name,
        final boolean create,
        final KsqlTopic topic,
        final Set<SerdeOption> defaultSerdeOptions
    ) {
      this.name = requireNonNull(name, "name");
      this.create = create;
      this.topic = requireNonNull(topic, "topic");
      this.defaultSerdeOptions = ImmutableSet
          .copyOf(requireNonNull(defaultSerdeOptions, "defaultSerdeOptions"));
    }

    public SourceName getName() {
      return name;
    }

    public boolean isCreate() {
      return create;
    }

    public KsqlTopic getKsqlTopic() {
      return topic;
    }

    public ImmutableSet<SerdeOption> getDefaultSerdeOptions() {
      return defaultSerdeOptions;
    }
  }

  @Immutable
  public static final class AliasedDataSource {

    private final SourceName alias;
    private final DataSource dataSource;

    AliasedDataSource(
        final SourceName alias,
        final DataSource dataSource
    ) {
      this.alias = requireNonNull(alias, "alias");
      this.dataSource = requireNonNull(dataSource, "dataSource");
    }

    public SourceName getAlias() {
      return alias;
    }

    public DataSource getDataSource() {
      return dataSource;
    }
  }

  @Immutable
  public static final class JoinInfo {

    private final Expression leftJoinExpression;
    private final Expression rightJoinExpression;
    private final JoinNode.JoinType type;
    private final Optional<WithinExpression> withinExpression;

    JoinInfo(
        final Expression leftJoinExpression,
        final Expression rightJoinExpression,
        final JoinType type,
        final Optional<WithinExpression> withinExpression

    ) {
      this.leftJoinExpression = requireNonNull(leftJoinExpression, "leftJoinExpression");
      this.rightJoinExpression = requireNonNull(rightJoinExpression, "rightJoinExpression");
      this.type = requireNonNull(type, "type");
      this.withinExpression = requireNonNull(withinExpression, "withinExpression");
    }

    public Expression getLeftJoinExpression() {
      return leftJoinExpression;
    }

    public Expression getRightJoinExpression() {
      return rightJoinExpression;
    }

    public JoinType getType() {
      return type;
    }

    public Optional<WithinExpression> getWithinExpression() {
      return withinExpression;
    }
  }
}

