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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * @author Clinton Begin
 */
public interface TypeHandler<T> {
  // MyBatis的核心之一: TypeHandler

  // 存入 -- 向ps第i个位置如何写入T类型的参数
  void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

  // 结果集rs根据列名columnName获取对应的值,然后转换为T类型
  T getResult(ResultSet rs, String columnName) throws SQLException;

  // 结果集rs根据索引位置columnIndex获取对应的值,然后转换为T类型
  T getResult(ResultSet rs, int columnIndex) throws SQLException;

  // 存储过程使用较少
  T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}
