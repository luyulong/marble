/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.linq4j.function.Predicate2;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelNodes;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.EquiJoin;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgramBuilder;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of {@link Join} in
 * {@link EnumerableConvention enumerable calling convention}
 * that allows conditions that are not just {@code =} (equals) and have equal
 * join keys.
 */
public class EnumerableThetaHashJoin extends Join implements EnumerableRel {
  protected EnumerableThetaHashJoin(RelOptCluster cluster, RelTraitSet traits,
      RelNode left, RelNode right, RexNode condition,
      Set<CorrelationId> variablesSet, JoinRelType joinType)
      throws InvalidRelException {
    super(cluster, traits, left, right, condition, variablesSet, joinType);
  }

  @Override public EnumerableThetaHashJoin copy(RelTraitSet traitSet,
      RexNode condition, RelNode left, RelNode right, JoinRelType joinType,
      boolean semiJoinDone) {
    try {
      return new EnumerableThetaHashJoin(getCluster(), traitSet, left, right,
          condition, variablesSet, joinType);
    } catch (InvalidRelException e) {
      // Semantic error not possible. Must be a bug. Convert to
      // internal error.
      throw new AssertionError(e);
    }
  }

  @Override public RelOptCost computeSelfCost(RelOptPlanner planner,
      RelMetadataQuery mq) {
    double rowCount = mq.getRowCount(this);

    // Joins can be flipped, and for many algorithms, both versions are viable
    // and have the same cost. To make the results stable between versions of
    // the planner, make one of the versions slightly more expensive.
    switch (joinType) {
    case RIGHT:
      rowCount = addEpsilon(rowCount);
      break;
    default:
      if (RelNodes.COMPARATOR.compare(left, right) > 0) {
        rowCount = addEpsilon(rowCount);
      }
    }

    // Cheaper if the smaller number of rows is coming from the LHS.
    // Model this by adding L log L to the cost.
    final double rightRowCount = right.estimateRowCount(mq);
    final double leftRowCount = left.estimateRowCount(mq);
    if (Double.isInfinite(leftRowCount)) {
      rowCount = leftRowCount;
    } else {
      rowCount += Util.nLogN(leftRowCount);
    }
    if (Double.isInfinite(rightRowCount)) {
      rowCount = rightRowCount;
    } else {
      rowCount += rightRowCount;
    }
    return planner.getCostFactory().makeCost(rowCount, 0, 0);
  }

  private double addEpsilon(double d) {
    assert d >= 0d;
    final double d0 = d;
    if (d < 10) {
      // For small d, adding 1 would change the value significantly.
      d *= 1.001d;
      if (d != d0) {
        return d;
      }
    }
    // For medium d, add 1. Keeps integral values integral.
    ++d;
    if (d != d0) {
      return d;
    }
    // For large d, adding 1 might not change the value. Add .1%.
    // If d is NaN, this still will probably not change the value. That's OK.
    d *= 1.001d;
    return d;
  }

  public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final JoinInfo info = JoinInfo.of(left, right, condition);
    RexNode remainCondition = info.getRemaining(getCluster().getRexBuilder());
    BlockBuilder builder = new BlockBuilder();
    final Result leftResult =
        implementor.visitChild(this, 0, (EnumerableRel) left, pref);
    Expression leftExpression =
        builder.append(
            "left", leftResult.block);
    final Result rightResult =
        implementor.visitChild(this, 1, (EnumerableRel) right, pref);
    Expression rightExpression =
        builder.append(
            "right", rightResult.block);
    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(), getRowType(), pref.preferArray());
    final PhysType keyPhysType =
        leftResult.physType.project(
            info.leftKeys, JavaRowFormat.LIST);
    //if leftKeys classes are not equals to rightKeys classes,we can try to
    // convert them to same classes
    List<Class> leftKeysClassList = info.leftKeys.stream()
        .map(leftResult.physType::fieldClass)
        .collect(
            Collectors.toList());
    List<Class> rightKeysClassList = info.rightKeys.stream()
        .map(rightResult.physType::fieldClass)
        .collect(
            Collectors.toList());
    List<Class> targetFieldClassList = EquiJoin.consistentKeyTypes(
        leftKeysClassList,
        rightKeysClassList);
    return implementor.result(
        physType,
        builder.append(
            Expressions.call(
                BuiltInMethod.THETA_HASH_JOIN.method,
                Expressions.list(
                    leftExpression,
                    rightExpression,
                    leftResult.physType.generateAccessor(info.leftKeys,
                        targetFieldClassList == null
                            ? leftKeysClassList
                            : targetFieldClassList),
                    rightResult.physType.generateAccessor(info.rightKeys,
                        targetFieldClassList == null
                            ? rightKeysClassList
                            : targetFieldClassList),
                    predicate(implementor,
                        leftResult.physType,
                        rightResult.physType,
                        remainCondition),
                    EnumUtils.joinSelector(joinType,
                        physType,
                        ImmutableList.of(
                            leftResult.physType, rightResult.physType)))
                    .append(
                        Util.first(keyPhysType.comparer(),
                            Expressions.constant(null)))
                    .append(
                        Expressions.constant(joinType.generatesNullsOnLeft()))
                    .append(
                        Expressions.constant(
                            joinType.generatesNullsOnRight())))).toBlock());

  }

  Expression predicate(EnumerableRelImplementor implementor,
      PhysType leftPhysType, PhysType rightPhysType,
      RexNode condition) {
    BlockBuilder builder = new BlockBuilder();
    final ParameterExpression left_ =
        Expressions.parameter(leftPhysType.getJavaRowType(), "left");
    final ParameterExpression right_ =
        Expressions.parameter(rightPhysType.getJavaRowType(), "right");
    final RexProgramBuilder program =
        new RexProgramBuilder(
            implementor.getTypeFactory().builder()
                .addAll(left.getRowType().getFieldList())
                .addAll(right.getRowType().getFieldList())
                .build(),
            getCluster().getRexBuilder());
    program.addCondition(condition);
    builder.add(
        Expressions.return_(null,
            RexToLixTranslator.translateCondition(program.getProgram(),
                implementor.getTypeFactory(),
                builder,
                new RexToLixTranslator.InputGetterImpl(
                    ImmutableList.of(Pair.of((Expression) left_, leftPhysType),
                        Pair.of((Expression) right_, rightPhysType))),
                implementor.allCorrelateVariables,
                implementor.getConformance())));
    return Expressions.lambda(Predicate2.class, builder.toBlock(), left_,
        right_);
  }
}

// End EnumerableThetaHashJoin.java
