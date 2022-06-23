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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.*;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/**
 * @author Iwao AVE!
 */
public class ResultSetWrapper {
  // ResultSet 包装器

  private final ResultSet resultSet;
  private final TypeHandlerRegistry typeHandlerRegistry;

  // ResultSet中每列的列名
  private final List<String> columnNames = new ArrayList<>();
  // ResultSet中每列的对应的Class类型
  private final List<String> classNames = new ArrayList<>();
  // ResultSet中每列的JdbcType
  private final List<JdbcType> jdbcTypes = new ArrayList<>();
  // key 为 columnName
  // value 为 Map<Class<?>, TypeHandler<?>> 中的
  //    key 为 propertyType 属性类型
  //    value 为 columnName和propertyType 对应的TypeHandler
  private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
  private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
  private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

  public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
    // 唯一构造器

    super();
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.resultSet = rs;
    final ResultSetMetaData metaData = rs.getMetaData();
    final int columnCount = metaData.getColumnCount();
    // 1. 从1开始遍历ResultSet的每一列
    for (int i = 1; i <= columnCount; i++) {
      // 2.1 处理ResultSet的元数据信息

      // 从结果集获取每列的别名或正式列名
      columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
      // 从结果集中检索指定列的SQL类型并转为JdbcType -> 这个是准确的因为会从SQL执行结果中获取出来
      jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
      // 从结果集中检索每列的Java类型 -- 比如VARCHAR对应String类型/DATETIME对象LocalDateTime
      // 不一定和最终返回的对象类型相同
      classNames.add(metaData.getColumnClassName(i));
    }
  }

  public ResultSet getResultSet() {
    return resultSet;
  }

  public List<String> getColumnNames() {
    return this.columnNames;
  }

  public List<String> getClassNames() {
    return Collections.unmodifiableList(classNames);
  }

  public List<JdbcType> getJdbcTypes() {
    return jdbcTypes;
  }

  public JdbcType getJdbcType(String columnName) {
    for (int i = 0 ; i < columnNames.size(); i++) {
      if (columnNames.get(i).equalsIgnoreCase(columnName)) {
        return jdbcTypes.get(i);
      }
    }
    return null;
  }

  /**
   * Gets the type handler to use when reading the result set.
   * Tries to get from the TypeHandlerRegistry by searching for the property type.
   * If not found it gets the column JDBC type and tries to get a handler for it.
   *
   * @param propertyType
   * @param columnName
   * @return
   */
  public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
    // 获取读取结果集时要使用的类型处理程序。尝试通过搜索属性类型从 TypeHandlerRegistry 中获取。如果未找到，它将获取列 JDBC 类型并尝试为其获取处理程序。
    // propertyType -> 对应的属性的JavaType
    // columnName -> 对应的列名

    // 1. typeHandlerMap初始化为空的Map结构
    TypeHandler<?> handler = null;
    Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
    // 2. 创建columnName对应的 Map<Class<?>, TypeHandler<?>> 类型的 columnHandlers
    // 实际就是查看缓存是否可以命中 -- columnName + propertyType 对应的 TypeHandler
    if (columnHandlers == null) {
      columnHandlers = new HashMap<>();
      typeHandlerMap.put(columnName, columnHandlers);
    } else {
      handler = columnHandlers.get(propertyType);
    }
    // 3. 缓存未命中 -- 需要重新查询
    if (handler == null) {
      // 3.1 获取指定列名的jdbcType -- 根据执行结果传递过来的
      JdbcType jdbcType = getJdbcType(columnName);
      // 3.2 直接根据 javaType 和 jdbcType 查找 TypeHandler
      handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
      // Replicate logic of UnknownTypeHandler#resolveTypeHandler
      // See issue #59 comment 10
      // 3.3 兜底: 使用javaType/jdbcType去查找吧
      if (handler == null || handler instanceof UnknownTypeHandler) {
        final int index = columnNames.indexOf(columnName);
        final Class<?> javaType = resolveClass(classNames.get(index));
        if (javaType != null && jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType);
        } else if (jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(jdbcType);
        }
      }
      if (handler == null || handler instanceof UnknownTypeHandler) {
        handler = new ObjectTypeHandler();
      }
      columnHandlers.put(propertyType, handler);
    }
    return handler;
  }

  private Class<?> resolveClass(String className) {
    try {
      // #699 className could be null
      if (className != null) {
        return Resources.classForName(className);
      }
    } catch (ClassNotFoundException e) {
      // ignore
    }
    return null;
  }

  private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    List<String> mappedColumnNames = new ArrayList<>();
    List<String> unmappedColumnNames = new ArrayList<>();
    final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
    // mappedColumns 是ResultMap中加上指定前缀后需要映射到列名
    final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
    // columnNames 是结果集ResultSet中每列的列名

    // 比如: 比如结果集为 select id,name,age 那么 columnNames 就是 ["id","name","age"
    // 而 ResultMap 是 <id column="id",property="id>  <result column="name",property="name> <result column="age",property="age> <result column="band",property="band>
    // 没有指定前缀的情况下: mapperColumns 就是 ["id","name","age","band"]
    for (String columnName : columnNames) {
      final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
      if (mappedColumns.contains(upperColumnName)) {
        // 映射成功
        mappedColumnNames.add(upperColumnName);
      } else {
        // 未上去映射
        // ❗️❗️❗️注意:
        // 当 <select id="xx" resultType="com.sdk.developer.SysConfig"> 这种 -- 会创建内联的ResultMap -- 内联的ResultMap中的getMappedColumns就是空集合
        // 因此ResultSet结果集中所有的列都无法直接通过ResultType的mappedColumns,因此在resultType下整个ResultSet的columnNames都会放入到unmappedColumnNames
        unmappedColumnNames.add(columnName);
      }
    }
    // 存入 mappedColumnNamesMap/unMappedColumnNamesMap
    // 其中 key 就是 resultMap和对应的column前缀
    mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
    unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
  }

  public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 获取映射的列名 -- 即从ResultSet的结果集中的列名映射到ResultMap中指定了列名的结果集
    List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (mappedColumnNames == null) {
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return mappedColumnNames;
  }

  public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    // 获取未映射的列名 -- 即从ResultSet的结果集中的列名没有映射到ResultMap中指定了列名的结果集
    List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (unMappedColumnNames == null) {
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return unMappedColumnNames;
  }

  private String getMapKey(ResultMap resultMap, String columnPrefix) {
    // 获取缓存的MapKey
    return resultMap.getId() + ":" + columnPrefix;
  }

  private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
    if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
      return columnNames;
    }
    final Set<String> prefixed = new HashSet<>();
    for (String columnName : columnNames) {
      prefixed.add(prefix + columnName);
    }
    return prefixed;
  }

}
