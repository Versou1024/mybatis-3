/**
 *    Copyright 2009-2018 the original author or authors.
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
public class BooleanTypeHandler extends BaseTypeHandler<Boolean> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Boolean parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setBoolean(i, parameter);
  }

  @Override
  public Boolean getNullableResult(ResultSet rs, String columnName)
      throws SQLException {
    // rs.wasNull()
    // 报告最后读取的列是否具有 SQL NULL 。请注意，您必须首先对列调用其中一个 getter 方法以尝试读取其值，然后调用方法wasNull以查看读取的值是否为 SQL NULL 。
    // return：true 最后读取的列值是 SQL NULL ，则为false


    boolean result = rs.getBoolean(columnName);
    return !result && rs.wasNull() ? null : result;
  }

  @Override
  public Boolean getNullableResult(ResultSet rs, int columnIndex)
      throws SQLException {
    boolean result = rs.getBoolean(columnIndex);
    return !result && rs.wasNull() ? null : result;
  }

  @Override
  public Boolean getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    boolean result = cs.getBoolean(columnIndex);
    return !result && cs.wasNull() ? null : result;
  }
}
