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
import java.time.LocalDateTime;

/**
 * @since 3.4.5
 * @author Tomas Rohovsky
 */
public class LocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {
  // 支持转换LocalDateTime

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
          throws SQLException {
    ps.setObject(i, parameter);
  }

  @Override
  public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
    // rs.getObject()
    // 检索此ResultSet对象的当前行中指定列的值，如果支持转换，则将从列的 SQL 类型转换为请求的 Java 数据类型。如果不支持转换或该类型指定了null，则抛出SQLException 。
    // 至少，实现必须支持附录 B 表 B-3 中定义的转换，以及将适当的用户定义的 SQL 类型转换为实现SQLData或Struct的 Java 类型。可能支持其他转换并且由供应商定义
    return rs.getObject(columnName, LocalDateTime.class);
  }

  @Override
  public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return rs.getObject(columnIndex, LocalDateTime.class);
  }

  @Override
  public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return cs.getObject(columnIndex, LocalDateTime.class);
  }
}
