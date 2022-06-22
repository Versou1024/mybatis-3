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
package org.apache.ibatis.scripting;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;

public interface LanguageDriver {
  // LanguageDriver 作用 -- 从mapper.xml或注解中获取要读取的语句,即作为SqlSource
  // 以及获取 ParameterHandler
  // 非常重要哦 -- 我丢 好厉害的LanguageDriver

  /**
   * Creates a {@link ParameterHandler} that passes the actual parameters to the the JDBC statement.
   *
   * @param mappedStatement The mapped statement that is being executed
   * @param parameterObject The input parameter object (can be null)
   * @param boundSql The resulting SQL once the dynamic language has been executed.
   * @return
   * @author Frank D. Martinez [mnesarco]
   * @see DefaultParameterHandler
   */
  ParameterHandler createParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql);
  // 创建一个将实际ParameterHandler传递给 JDBC 语句的 ParameterHandler。

  /**
   * Creates an {@link SqlSource} that will hold the statement read from a mapper xml file.
   * It is called during startup, when the mapped statement is read from a class or an xml file.
   *
   * @param configuration The MyBatis configuration
   * @param script XNode parsed from a XML file
   * @param parameterType input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
   * @return
   */
  SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType);
  // 创建一个 SqlSource ，它将保存从  mapper.xml  文件中读取的语句。
  // context是DML标签的XNode
  // parameterTypeClass是DML标签的paramType属性对应的Class

  // 使用处 -- 会在 XMLStatementBuilder 中创建SqlSource -- 用来解析<Select>/<Insert>等标签上的脚本script

  /**
   * Creates an {@link SqlSource} that will hold the statement read from an annotation.
   * It is called during startup, when the mapped statement is read from a class or an xml file.
   *
   * @param configuration The MyBatis configuration
   * @param script The content of the annotation
   * @param parameterType input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
   * @return
   */
  SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType);
  // 创建一个SqlSource来保存从  注解  中读取的语句。
  // 它在启动期间被调用，当从类或 xml 文件中读取映射语句时

  // 使用处 -- 会在 MapperAnnotationBuilder 中创建SqlSource -- 用来解析@Select/@Insert等注解上的脚本script

}
