/*
 * Copyright 2019 Confluent Inc.
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

package io.confluent.ksql.execution.streams;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.builder.KsqlQueryBuilder;
import io.confluent.ksql.execution.codegen.CodeGenRunner;
import io.confluent.ksql.execution.codegen.ExpressionMetadata;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.KGroupedTableHolder;
import io.confluent.ksql.execution.plan.KTableHolder;
import io.confluent.ksql.execution.plan.TableGroupBy;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KGroupedTable;
import org.apache.kafka.streams.kstream.KeyValueMapper;

public final class TableGroupByBuilder {
  private TableGroupByBuilder() {
  }

  public static <K> KGroupedTableHolder build(
      final KTableHolder<K> table,
      final TableGroupBy<K> step,
      final KsqlQueryBuilder queryBuilder,
      final GroupedFactory groupedFactory
  ) {
    final LogicalSchema sourceSchema = table.getSchema();
    final QueryContext queryContext =  step.getProperties().getQueryContext();
    final Formats formats = step.getInternalFormats();

    final List<ExpressionMetadata> groupBy = CodeGenRunner.compileExpressions(
        step.getGroupByExpressions().stream(),
        "Group By",
        sourceSchema,
        queryBuilder.getKsqlConfig(),
        queryBuilder.getFunctionRegistry()
    );

    final ProcessingLogger logger = queryBuilder.getProcessingLogger(queryContext);

    final GroupByParams params = GroupByParamsFactory
        .build(sourceSchema, groupBy, logger, queryBuilder.getKsqlConfig());

    final PhysicalSchema physicalSchema = PhysicalSchema.from(
        params.getSchema(),
        formats.getOptions()
    );
    final Serde<Struct> keySerde = queryBuilder.buildKeySerde(
        formats.getKeyFormat(),
        physicalSchema,
        queryContext
    );
    final Serde<GenericRow> valSerde = queryBuilder.buildValueSerde(
        formats.getValueFormat(),
        physicalSchema,
        queryContext
    );
    final Grouped<Struct, GenericRow> grouped = groupedFactory.create(
        StreamsUtil.buildOpName(queryContext),
        keySerde,
        valSerde
    );

    final KGroupedTable<Struct, GenericRow> groupedTable = table.getTable()
        .filter((k, v) -> v != null)
        .groupBy(new TableKeyValueMapper<>(params.getMapper()), grouped);

    return KGroupedTableHolder.of(groupedTable, params.getSchema());
  }

  public static final class TableKeyValueMapper<K>
      implements KeyValueMapper<K, GenericRow, KeyValue<Struct, GenericRow>> {

    private final Function<GenericRow, Struct> groupByMapper;

    private TableKeyValueMapper(final Function<GenericRow, Struct> groupByMapper) {
      this.groupByMapper = Objects.requireNonNull(groupByMapper, "groupByMapper");
    }

    @Override
    public KeyValue<Struct, GenericRow> apply(final K key, final GenericRow value) {
      return new KeyValue<>(groupByMapper.apply(value), value);
    }
  }
}
