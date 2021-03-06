/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.dataset.groupby;

import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.physical.crud.GroupByPlan;
import org.apache.iotdb.db.query.aggregation.AggregateResult;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.factory.AggregateResultFactory;
import org.apache.iotdb.db.query.filter.TsFileFilter;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;

public class GroupByWithoutValueFilterDataSet extends GroupByEngineDataSet {

  private static final Logger logger = LoggerFactory
          .getLogger(GroupByWithoutValueFilterDataSet.class);

  private Map<Path, GroupByExecutor> pathExecutors = new HashMap<>();

  /**
   * path -> result index for each aggregation
   *
   * e.g.,
   *
   * deduplicated paths : s1, s2, s1
   * deduplicated aggregations : count, count, sum
   *
   * s1 -> 0, 2
   * s2 -> 1
   */
  private Map<Path, List<Integer>> resultIndexes = new HashMap<>();

  public GroupByWithoutValueFilterDataSet() {
  }

  /**
   * constructor.
   */
  public GroupByWithoutValueFilterDataSet(QueryContext context, GroupByPlan groupByPlan)
          throws StorageEngineException, QueryProcessException {
    super(context, groupByPlan);

    initGroupBy(context, groupByPlan);
  }

  protected void initGroupBy(QueryContext context, GroupByPlan groupByPlan)
          throws StorageEngineException, QueryProcessException {
    IExpression expression = groupByPlan.getExpression();

    Filter timeFilter = null;
    if (expression != null) {
      timeFilter = ((GlobalTimeExpression) expression).getFilter();
    }

    // init resultIndexes, group result indexes by path
    for (int i = 0; i < paths.size(); i++) {
      Path path = paths.get(i);
      if (!pathExecutors.containsKey(path)) {
        //init GroupByExecutor
        pathExecutors.put(path,
                getGroupByExecutor(path, groupByPlan.getAllMeasurementsInDevice(path.getDevice()), dataTypes.get(i), context, timeFilter, null));
        resultIndexes.put(path, new ArrayList<>());
      }
      resultIndexes.get(path).add(i);
      AggregateResult aggrResult = AggregateResultFactory
              .getAggrResultByName(groupByPlan.getDeduplicatedAggregations().get(i), dataTypes.get(i));
      pathExecutors.get(path).addAggregateResult(aggrResult);
    }
  }

  @Override
  protected RowRecord nextWithoutConstraint() throws IOException {
    if (!hasCachedTimeInterval) {
      throw new IOException("need to call hasNext() before calling next() "
              + "in GroupByWithoutValueFilterDataSet.");
    }
    hasCachedTimeInterval = false;
    RowRecord record = new RowRecord(curStartTime);

    AggregateResult[] fields = new AggregateResult[paths.size()];

    try {
      for (Entry<Path, GroupByExecutor> pathToExecutorEntry : pathExecutors.entrySet()) {
        GroupByExecutor executor = pathToExecutorEntry.getValue();
        List<AggregateResult> aggregations = executor.calcResult(curStartTime, curEndTime);
        for (int i = 0; i < aggregations.size(); i++) {
          int resultIndex = resultIndexes.get(pathToExecutorEntry.getKey()).get(i);
          fields[resultIndex] = aggregations.get(i);
        }
      }
    } catch (QueryProcessException e) {
      logger.error("GroupByWithoutValueFilterDataSet execute has error", e);
      throw new IOException(e.getMessage(), e);
    }

    for (AggregateResult res : fields) {
      if (res == null) {
        record.addField(null);
        continue;
      }
      record.addField(res.getResult(), res.getResultDataType());
    }
    return record;
  }

  protected GroupByExecutor getGroupByExecutor(Path path, Set<String> allSensors, TSDataType dataType,
                                               QueryContext context, Filter timeFilter, TsFileFilter fileFilter)
          throws StorageEngineException, QueryProcessException {
    return new LocalGroupByExecutor(path, allSensors, dataType, context, timeFilter, fileFilter);
  }
}