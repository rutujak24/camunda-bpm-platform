/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
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
package org.camunda.bpm.dmn.feel.impl.scala;

import org.camunda.bpm.dmn.feel.impl.FeelEngine;
import org.camunda.bpm.dmn.feel.impl.scala.function.CustomFunctionTransformer;
import org.camunda.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;
import org.camunda.bpm.dmn.feel.impl.scala.spin.SpinValueMapperFactory;
import org.camunda.bpm.engine.variable.context.VariableContext;
import org.camunda.feel.FeelEngine.Builder;
import org.camunda.feel.context.CustomContext;
import org.camunda.feel.context.JavaVariableProvider;
import org.camunda.feel.context.VariableProvider;
import org.camunda.feel.impl.JavaValueMapper;
import org.camunda.feel.valuemapper.CustomValueMapper;
import org.camunda.feel.valuemapper.JavaCustomValueMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ScalaFeelEngine implements FeelEngine {

  protected static final ScalaFeelLogger LOGGER = ScalaFeelLogger.LOGGER;

  protected org.camunda.feel.FeelEngine feelEngine;

  public ScalaFeelEngine(java.util.List<FeelCustomFunctionProvider> functionProviders) {
    List<CustomValueMapper> valueMappers = getValueMappers();

    JavaCustomValueMapper.CompositeValueMapper compositeValueMapper =
      new JavaCustomValueMapper.CompositeValueMapper(valueMappers);

    CustomFunctionTransformer customFunctionTransformer =
      new CustomFunctionTransformer(functionProviders, compositeValueMapper);

    feelEngine = buildFeelEngine(customFunctionTransformer, compositeValueMapper);
  }

  public <T> T evaluateSimpleExpression(String expression, VariableContext variableContext) {

    CustomContext context = new CustomContext() {
      public VariableProvider variableProvider() {
        return new ContextVariableWrapper(variableContext);
      }
    };

    try {
      return feelEngine.evalExpressionAsValue(expression, context);
    } catch (RuntimeException e) {
      throw LOGGER.evaluationException(e.getMessage());
    }
  }

  public boolean evaluateSimpleUnaryTests(String expression,
                                          String inputVariable,
                                          VariableContext variableContext) {
    JavaVariableProvider inputVariableProvider = new InputVariableProvider(inputVariable);
    JavaVariableProvider contextVariableWrapper = new ContextVariableWrapper(variableContext);

    CustomContext context = new CustomContext() {
      public VariableProvider variableProvider() {
        return new CompositeVariableProvider(inputVariableProvider, contextVariableWrapper);
      }
    };

    try {
      return feelEngine.evalUnaryTestsAsBoolean(expression, context);
    } catch (RuntimeException e) {
      throw LOGGER.evaluationException(e.getMessage());
    }
  }

  protected List<CustomValueMapper> getValueMappers() {
    SpinValueMapperFactory spinValueMapperFactory = new SpinValueMapperFactory();

    CustomValueMapper javaValueMapper = new JavaValueMapper();

    CustomValueMapper spinValueMapper = spinValueMapperFactory.createInstance();
    if (spinValueMapper != null) {
      return Arrays.asList(javaValueMapper, spinValueMapper);

    } else {
      return Collections.singletonList(javaValueMapper);

    }
  }

  protected org.camunda.feel.FeelEngine buildFeelEngine(CustomFunctionTransformer transformer,
                                                        JavaCustomValueMapper.CompositeValueMapper valueMapper) {
    return new Builder()
      .functionProvider(transformer)
      .valueMapper(valueMapper)
      .enableExternalFunctions(false)
      .build();
  }

}

