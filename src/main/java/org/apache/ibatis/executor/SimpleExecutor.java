/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.executor;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {
  // SimpleExecutor 没有一级缓存
  // 简单执行器
  // 源码中主要两点
  // 1. 如何创建StatementHandler
  // 2. 从MappedStatement准备一下Statement
  //      MappedStatement 是对应DML标签的信息
  //      Statement 需要执行的sql
  // 3. 使用StatementHandler的update/query/queryCursor/

  public SimpleExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Statement stmt = null;
    try {
      Configuration configuration = ms.getConfiguration();
      // ❗️❗️❗️ 创建StatementHandler的过程很重要哦 -- 需要掌握
      StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
      // ❗️❗️❗️ 初始化Statement会创建Statement/设置Statement的超时时间和FetchType/调用ParameterHandler向数据库中Statement设置数据哦
      stmt = prepareStatement(handler, ms.getStatementLog());
      // ❗️❗️❗️ 开始执行update/insert/delete操作的stmt -> Executor.prepareStatement() 完成啦[借助ParameterHandler的能力]
      return handler.update(stmt);
    } finally {
      closeStatement(stmt);
    }
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    // ❗️❗️❗️
    // 开始执行查询操作
    Statement stmt = null;
    try {
      Configuration configuration = ms.getConfiguration();
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
      stmt = prepareStatement(handler, ms.getStatementLog());
      return handler.query(stmt, resultHandler);
    } finally {
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    stmt.closeOnCompletion();
    return handler.queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    return Collections.emptyList();
  }

  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    // ❗️❗️❗️
    // 准备产出 Statement

    // 1. 获取连接 - 从事务即transaction.getConnection()获取连接
    Statement stmt;
    Connection connection = getConnection(statementLog);
    // 2. 调用statementHandler的prepare方法
    // 作用: 从connection获取Statement/向Statement设置超时时间/向Statement设置FetchSize
    stmt = handler.prepare(connection, transaction.getTimeout());
    // 3. 对stmt参数进行参数化
    // 🇫🇯🇫🇯🇫🇯 ParameterHandler将被执行哦
    // ❗️❗️❗️ DefaultParameterHandler.setParameters(PreparedStatement ps)
    handler.parameterize(stmt);

    // 注意 -- 参数已经被设置到 Statement 中 -- 但是还没有开始执行哦
    return stmt;
  }

}
