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
package org.apache.ibatis.mapping;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * Vendor DatabaseId provider.
 *
 * It returns database product name as a databaseId.
 * If the user provides a properties it uses it to translate database product name
 * key="Microsoft SQL Server", value="ms" will return "ms".
 * It can return null, if no database product name or
 * a properties was specified and no translation was found.
 *
 * @author Eduardo Macarron
 */
public class VendorDatabaseIdProvider implements DatabaseIdProvider {
  // DatabaseIdProvider 的唯一有效内置实现 -- 根据数据库提供商返回的自家数据库产品名字DatabaseProductName作为databaseId
  // 它以 databaseId 形式返回数据库产品名称
  // 。如果用户提供了一个属性，它使用它来翻译数据库产品名称 key="Microsoft SQL Server"，value="ms" 将返回 "ms"。如果未指定数据库产品名称或属性且未找到翻译，则它可以返回 null。

  private Properties properties;

  @Override
  public String getDatabaseId(DataSource dataSource) {
    if (dataSource == null) {
      throw new NullPointerException("dataSource cannot be null");
    }
    try {
      return getDatabaseName(dataSource);
    } catch (Exception e) {
      LogHolder.log.error("Could not get a databaseId from dataSource", e);
    }
    return null;
  }

  @Override
  public void setProperties(Properties p) {
    this.properties = p;
  }

  private String getDatabaseName(DataSource dataSource) throws SQLException {
    String productName = getDatabaseProductName(dataSource);
    if (this.properties != null) {
      for (Map.Entry<Object, Object> property : properties.entrySet()) {
        if (productName.contains((String) property.getKey())) {
          // 可以通过Properties,将数据库提供商的 DatabaseProductName 转为自定义的 property.getValue()
          return (String) property.getValue();
        }
      }
      // no match, return null
      return null;
    }
    return productName;
  }

  private String getDatabaseProductName(DataSource dataSource) throws SQLException {
    // 核心:

    Connection con = null;
    try {
      // dataSource.getConnection().getMetaDatda().getDatabaseProductName() -- 数据库产品类型 -- 比如MySQL/Oracle/SQL Server等等
      con = dataSource.getConnection();
      DatabaseMetaData metaData = con.getMetaData();
      return metaData.getDatabaseProductName();
    } finally {
      if (con != null) {
        try {
          con.close();
        } catch (SQLException e) {
          // ignored
        }
      }
    }
  }

  private static class LogHolder {
    private static final Log log = LogFactory.getLog(VendorDatabaseIdProvider.class);
  }

}
