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

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @author Clinton Begin
 */
public class ResultMapping {
  // 对应 <resultMap>标签下 的 id标签/result标签/association标签/collection标签/以及Constructor标签下的每一个<idArg>或者<arg>
  // 这些标签 -- 每个都会被映射为一个ResultMapping

  private Configuration configuration;
  private String property; // 属性
  private String column; // 列
  private Class<?> javaType; // java类型
  private JdbcType jdbcType; // jdbc类型
  private TypeHandler<?> typeHandler; // 指定的typeHandler
  // 嵌套的结果映射 ID -- 指的是  association标签/collection标签中的select属性指定的查询方法的id [注意,select和resultMap指定一个就可以啦]
  private String nestedResultMapId;
  // 嵌套查询 ID -- 指的是  association标签/collection标签中的select属性指定的查询方法的id
  private String nestedQueryId;
  // 用于association标签/collection标签,并且搭配ResultMap
  // 可选的notNullColumn属性，默认情况下，只有在至少一个属性不为空的前提下才会创建子对象，
  // 但是我们可以通过notNullColumn属性来控制这一行为，notNullColumn属性的取值是以,分隔的多个属性名称，只有在这些属性均不为空的前提下，子对象才会被创建。
  // <association property="role" column="role_id" resultMap="role" columnPrefix="role_" notNullColumn="name"/>
  // 只有在ROLE表的name列不为null时才会实例化User对象的role对象属性
  private Set<String> notNullColumns;
  private String columnPrefix; // idArg标签/arg标签/association标签/collection标签的都有列前缀 -- 用来将columnPrefix赋予给引用的ResultMap标签中column的前缀使用 -- 保证区分相同的列
  private List<ResultFlag> flags; // ResultFlag 有 id/Constructor 两种类型

  // 在 collection或association标签中,可以通过select属性获取关联的对象
  // 而 select 属性指定的 查询方法的入参是需要 column 属性来指定的
  // column的形式可以是: column1=param1,column2=param2 即 查询出的结果的某个列column1/2作为分布查询即select指定的方法的入参param1/2
  // 其中每个ResultMapping都是代表一个column中用逗号分割开来的column1:param1
  private List<ResultMapping> composites;
  private String resultSet; // association标签/collection标签都有结果集 -- 搭配多结果多Result使用[很少用到]
  private String foreignColumn; // association标签/collection标签都有是否foreignColumn属性 -- 搭配多结果多Result使用[很少用到]
  private boolean lazy; // association标签/collection标签都有是否懒加载属性 -- 搭配select属性使用

  ResultMapping() {
  }

  public static class Builder {
    private ResultMapping resultMapping = new ResultMapping();

    public Builder(Configuration configuration, String property, String column, TypeHandler<?> typeHandler) {
      this(configuration, property);
      resultMapping.column = column;
      resultMapping.typeHandler = typeHandler;
    }

    public Builder(Configuration configuration, String property, String column, Class<?> javaType) {
      this(configuration, property);
      resultMapping.column = column;
      resultMapping.javaType = javaType;
    }

    public Builder(Configuration configuration, String property) {
      resultMapping.configuration = configuration;
      resultMapping.property = property;
      resultMapping.flags = new ArrayList<>();
      resultMapping.composites = new ArrayList<>();
      resultMapping.lazy = configuration.isLazyLoadingEnabled();
    }

    public Builder javaType(Class<?> javaType) {
      resultMapping.javaType = javaType;
      return this;
    }

    public Builder jdbcType(JdbcType jdbcType) {
      resultMapping.jdbcType = jdbcType;
      return this;
    }

    public Builder nestedResultMapId(String nestedResultMapId) {
      resultMapping.nestedResultMapId = nestedResultMapId;
      return this;
    }

    public Builder nestedQueryId(String nestedQueryId) {
      resultMapping.nestedQueryId = nestedQueryId;
      return this;
    }

    public Builder resultSet(String resultSet) {
      resultMapping.resultSet = resultSet;
      return this;
    }

    public Builder foreignColumn(String foreignColumn) {
      resultMapping.foreignColumn = foreignColumn;
      return this;
    }

    public Builder notNullColumns(Set<String> notNullColumns) {
      resultMapping.notNullColumns = notNullColumns;
      return this;
    }

    public Builder columnPrefix(String columnPrefix) {
      resultMapping.columnPrefix = columnPrefix;
      return this;
    }

    public Builder flags(List<ResultFlag> flags) {
      resultMapping.flags = flags;
      return this;
    }

    public Builder typeHandler(TypeHandler<?> typeHandler) {
      resultMapping.typeHandler = typeHandler;
      return this;
    }

    public Builder composites(List<ResultMapping> composites) {
      resultMapping.composites = composites;
      return this;
    }

    public Builder lazy(boolean lazy) {
      resultMapping.lazy = lazy;
      return this;
    }

    public ResultMapping build() {
      // lock down collections
      resultMapping.flags = Collections.unmodifiableList(resultMapping.flags);
      resultMapping.composites = Collections.unmodifiableList(resultMapping.composites);
      // ResultMapping也会去尝试解析TypeHandler
      resolveTypeHandler();
      validate();
      return resultMapping;
    }

    private void validate() {
      // Issue #697: cannot define both nestedQueryId and nestedResultMapId
      // <association>和<collection>中的 select属性和resultMap属性 不可同时指定
      // 因为 一个是描述嵌套查询语句/描述嵌套结果映射 只能用一种方式确定最终映射的结果
      if (resultMapping.nestedQueryId != null && resultMapping.nestedResultMapId != null) {
        throw new IllegalStateException("Cannot define both nestedQueryId and nestedResultMapId in property " + resultMapping.property);
      }
      // Issue #5: there should be no mappings without typehandler
      if (resultMapping.nestedQueryId == null && resultMapping.nestedResultMapId == null && resultMapping.typeHandler == null) {
        throw new IllegalStateException("No typehandler found for property " + resultMapping.property);
      }
      // Issue #4 and GH #39: column is optional only in nested resultmaps but not in the rest
      if (resultMapping.nestedResultMapId == null && resultMapping.column == null && resultMapping.composites.isEmpty()) {
        throw new IllegalStateException("Mapping is missing column attribute for property " + resultMapping.property);
      }
      // 使用 <association>和<collection>中的 ResultSet时,必须确保 column和foreignColumn 是同样的大小 -- 可用分隔符分开
      if (resultMapping.getResultSet() != null) {
        int numColumns = 0;
        if (resultMapping.column != null) {
          numColumns = resultMapping.column.split(",").length;
        }
        int numForeignColumns = 0;
        if (resultMapping.foreignColumn != null) {
          numForeignColumns = resultMapping.foreignColumn.split(",").length;
        }
        if (numColumns != numForeignColumns) {
          throw new IllegalStateException("There should be the same number of columns and foreignColumns in property " + resultMapping.property);
        }
      }
    }

    private void resolveTypeHandler() {
      // 1. ResultMapping没有指定TypeHandler 比如 <resultMap> 中的 <result column="id",property="userId">
      // 但是如果在支持RequestMapping的标签指定 比如 <resultMap> 中的 <result column="id",property="userId",typeHandler="StringTypeHandler"> 就是指定TypeHandler
      // javaType如果不为空,就去 TypeHandlerRegistry 中根据 javaType 和 jdbcType 查找 TypeHandler
      if (resultMapping.typeHandler == null && resultMapping.javaType != null) {
        Configuration configuration = resultMapping.configuration;
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        resultMapping.typeHandler = typeHandlerRegistry.getTypeHandler(resultMapping.javaType, resultMapping.jdbcType);
      }
    }

    public Builder column(String column) {
      resultMapping.column = column;
      return this;
    }
  }

  public String getProperty() {
    return property;
  }

  public String getColumn() {
    return column;
  }

  public Class<?> getJavaType() {
    return javaType;
  }

  public JdbcType getJdbcType() {
    return jdbcType;
  }

  public TypeHandler<?> getTypeHandler() {
    return typeHandler;
  }

  public String getNestedResultMapId() {
    return nestedResultMapId;
  }

  public String getNestedQueryId() {
    return nestedQueryId;
  }

  public Set<String> getNotNullColumns() {
    return notNullColumns;
  }

  public String getColumnPrefix() {
    return columnPrefix;
  }

  public List<ResultFlag> getFlags() {
    return flags;
  }

  public List<ResultMapping> getComposites() {
    return composites;
  }

  public boolean isCompositeResult() {
    return this.composites != null && !this.composites.isEmpty();
  }

  public String getResultSet() {
    return this.resultSet;
  }

  public String getForeignColumn() {
    return foreignColumn;
  }

  public void setForeignColumn(String foreignColumn) {
    this.foreignColumn = foreignColumn;
  }

  public boolean isLazy() {
    return lazy;
  }

  public void setLazy(boolean lazy) {
    this.lazy = lazy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ResultMapping that = (ResultMapping) o;

    if (property == null || !property.equals(that.property)) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    if (property != null) {
      return property.hashCode();
    } else if (column != null) {
      return column.hashCode();
    } else {
      return 0;
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ResultMapping{");
    //sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
    sb.append("property='").append(property).append('\'');
    sb.append(", column='").append(column).append('\'');
    sb.append(", javaType=").append(javaType);
    sb.append(", jdbcType=").append(jdbcType);
    //sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
    sb.append(", nestedResultMapId='").append(nestedResultMapId).append('\'');
    sb.append(", nestedQueryId='").append(nestedQueryId).append('\'');
    sb.append(", notNullColumns=").append(notNullColumns);
    sb.append(", columnPrefix='").append(columnPrefix).append('\'');
    sb.append(", flags=").append(flags);
    sb.append(", composites=").append(composites);
    sb.append(", resultSet='").append(resultSet).append('\'');
    sb.append(", foreignColumn='").append(foreignColumn).append('\'');
    sb.append(", lazy=").append(lazy);
    sb.append('}');
    return sb.toString();
  }

}
