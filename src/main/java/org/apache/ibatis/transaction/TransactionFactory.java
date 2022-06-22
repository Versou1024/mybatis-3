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
package org.apache.ibatis.transaction;

import org.apache.ibatis.session.TransactionIsolationLevel;

import java.sql.Connection;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * Creates {@link Transaction} instances.
 *
 * @author Clinton Begin
 */
public interface TransactionFactory {
  // 用于创建 事务Transaction 实例
  // 有两种实现:
  // 第一种 JDBC     - JDBCTransactionFactory    - 生成 JDBCTransaction
  // 第二种 Managed  - ManagedTransactionFactory - 生成 ManagedTransaction
  // 可在如下中配置
  // <environments default="development">
  //        <environment id="development">
  //            <transactionManager type="JDBC"/>
  //            <dataSource type="POOLED">
  //                <!-- 数据库连接信息 -->
  //                <property name="driver" value="${driver}"/>
  //                <property name="url" value="${url}"/>
  //                <property name="username" value="${username}"/>
  //                <property name="password" value="${password}"/>
  //            </dataSource>
  //        </environment>
  //    </environments>

  /**
   * Sets transaction factory custom properties.
   * @param props
   */
  default void setProperties(Properties props) {
    // NOP
  }

  /**
   * Creates a {@link Transaction} out of an existing connection.
   * @param conn Existing database connection
   * @return Transaction
   * @since 3.1.0
   */
  Transaction newTransaction(Connection conn);
  // 从现有连接创建Transaction

  /**
   * Creates a {@link Transaction} out of a datasource.
   * @param dataSource DataSource to take the connection from
   * @param level Desired isolation level
   * @param autoCommit Desired autocommit
   * @return Transaction
   * @since 3.1.0
   */
  Transaction newTransaction(DataSource dataSource, TransactionIsolationLevel level, boolean autoCommit);
  // 从数据源创建Transaction
  // dataSource -- 从中获取连接的数据源
  // level - 所需的隔离级别
  // autoCommit – 所需的自动提交

}
