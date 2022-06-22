/**
 *    Copyright 2009-2016 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor.statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {
  // StatementHandler的基本实现

  // 将各个属性字段封装起来 -- 避免污染子类的的具体实现哦

  // 配置中心
  protected final Configuration configuration;
  // 对象工厂 -- 提供实例化能力
  protected final ObjectFactory objectFactory;
  // TypeHandler注册表
  protected final TypeHandlerRegistry typeHandlerRegistry;
  // ResultSetHandler
  protected final ResultSetHandler resultSetHandler;
  // ParameterHandler
  protected final ParameterHandler parameterHandler;

  // 执行器 Executor
  protected final Executor executor;
  // DML标签的 MappedStatement
  protected final MappedStatement mappedStatement;
  // 偏移量和限制 RowBounds
  protected final RowBounds rowBounds;

  // 绑定SQL boundSql
  protected BoundSql boundSql;

  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    this.configuration = mappedStatement.getConfiguration();
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;

    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();

    // boundSql为null时,就会创建boundSql
    if (boundSql == null) { // issue #435, get the key before calculating the statement
      generateKeys(parameterObject);
      // Mapper.xml解析后有对应SqlSource -- 需要调用其 mappedStatement.getBoundSql(parameterObject) 获取 boundSql
      // 后续就不需要再次调用该方法
      // 使用 mappedStatement中sqlSource.getBoundSql()去获取 boundSql
      boundSql = mappedStatement.getBoundSql(parameterObject);
    }

    this.boundSql = boundSql; // DML标签解析后对应的SQL

    // 可以发现 -- StatementHandler 实际上是包含 parameterHandler/resultSetHandler/executor
    // 而 mybatis 的 Interceptor 可以拦截的对象也就只有 StatementHandler ParameterHandler ResultSetHandler Executor
    // 可见StatementHandler的强大哦

    // ❗️❗️❗️
    // 下面两个处理器Handler都是非常重要的
    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
  }

  @Override
  public BoundSql getBoundSql() {
    return boundSql;
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return parameterHandler;
  }

  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    // 作用: 连接和超时时间准备
    // 1. 从连接中初始化statement
    // 2. 设置超时时间
    // 3. 设置抓取大小

    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
      // 1. 根据KeyGenerator\keyColumn\ResultType创建statement
      statement = instantiateStatement(connection);
      // 2. 设置超时时间
      setStatementTimeout(statement, transactionTimeout);
      // 3. 设置 FetchSize
      setFetchSize(statement);
      return statement;
    } catch (SQLException e) {
      closeStatement(statement);
      throw e;
    } catch (Exception e) {
      closeStatement(statement);
      throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
  }

  protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

  protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
    // queryTimeout = MappedStatement是否指定超时时间的优先级 > Configuration全局默认超时时间的优先级
    // transactionTimeout = 事务时间
    Integer queryTimeout = null;
    if (mappedStatement.getTimeout() != null) {
      queryTimeout = mappedStatement.getTimeout();
    } else if (configuration.getDefaultStatementTimeout() != null) {
      queryTimeout = configuration.getDefaultStatementTimeout();
    }
    if (queryTimeout != null) {
      stmt.setQueryTimeout(queryTimeout);
    }
    StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
  }

  protected void setFetchSize(Statement stmt) throws SQLException {
    // 老样子 局部的FetchSize优先级 > 全局的Configuration的FetchSize
    Integer fetchSize = mappedStatement.getFetchSize();
    if (fetchSize != null) {
      stmt.setFetchSize(fetchSize);
      return;
    }
    Integer defaultFetchSize = configuration.getDefaultFetchSize();
    if (defaultFetchSize != null) {
      stmt.setFetchSize(defaultFetchSize);
    }
  }

  protected void closeStatement(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException e) {
      //ignore
    }
  }

  protected void generateKeys(Object parameter) {
    // 前置处理 -- KeyGenerator.processBefore()

    // 1. 获取KeyGenerator,并做前置处理
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    ErrorContext.instance().store();
    keyGenerator.processBefore(executor, mappedStatement, null, parameter);
    ErrorContext.instance().recall();
  }

}
