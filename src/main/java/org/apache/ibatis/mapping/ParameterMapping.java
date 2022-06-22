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

import java.sql.ResultSet;

/**
 * @author Clinton Begin
 */
public class ParameterMapping {
  // 1. 用处一: 存储#{}占位符中的表达式信息 -> 也就是#{}的信息被替换为?后,相关信息都存在ParameterMapping中啦

  private Configuration configuration;

  // 1. #{} 占位符的属性名
  private String property;
  private ParameterMode mode;
  private Class<?> javaType = Object.class;
  private JdbcType jdbcType;
  private Integer numericScale;
  // 注意 每个占位符#{} 对应的TypeHandler已经提前解析存放好啦
  private TypeHandler<?> typeHandler;
  private String resultMapId; // 引用的ResultMap的id
  private String jdbcTypeName;
  private String expression;

  private ParameterMapping() {
  }

  public static class Builder {
    private ParameterMapping parameterMapping = new ParameterMapping();

    public Builder(Configuration configuration, String property, TypeHandler<?> typeHandler) {
      parameterMapping.configuration = configuration;
      parameterMapping.property = property;
      parameterMapping.typeHandler = typeHandler;
      parameterMapping.mode = ParameterMode.IN;
    }

    public Builder(Configuration configuration, String property, Class<?> javaType) {
      parameterMapping.configuration = configuration;
      parameterMapping.property = property;
      parameterMapping.javaType = javaType;
      parameterMapping.mode = ParameterMode.IN;
    }

    public Builder mode(ParameterMode mode) {
      parameterMapping.mode = mode;
      return this;
    }

    public Builder javaType(Class<?> javaType) {
      parameterMapping.javaType = javaType;
      return this;
    }

    public Builder jdbcType(JdbcType jdbcType) {
      parameterMapping.jdbcType = jdbcType;
      return this;
    }

    public Builder numericScale(Integer numericScale) {
      parameterMapping.numericScale = numericScale;
      return this;
    }

    public Builder resultMapId(String resultMapId) {
      parameterMapping.resultMapId = resultMapId;
      return this;
    }

    public Builder typeHandler(TypeHandler<?> typeHandler) {
      parameterMapping.typeHandler = typeHandler;
      return this;
    }

    public Builder jdbcTypeName(String jdbcTypeName) {
      parameterMapping.jdbcTypeName = jdbcTypeName;
      return this;
    }

    public Builder expression(String expression) {
      parameterMapping.expression = expression;
      return this;
    }

    public ParameterMapping build() {
      // ❗️❗️❗️
      // ParameterMapping.Builder.build() -- 即使没有指定TypeHandler,这里也会为其创建一个TypeHandler哦
      resolveTypeHandler();
      validate();
      return parameterMapping;
    }

    private void validate() {
      if (ResultSet.class.equals(parameterMapping.javaType)) {
        if (parameterMapping.resultMapId == null) {
          throw new IllegalStateException("Missing resultmap in property '"
              + parameterMapping.property + "'.  "
              + "Parameters of type java.sql.ResultSet require a resultmap.");
        }
      } else {
        if (parameterMapping.typeHandler == null) {
          throw new IllegalStateException("Type handler was null on parameter mapping for property '"
            + parameterMapping.property + "'. It was either not specified and/or could not be found for the javaType ("
            + parameterMapping.javaType.getName() + ") : jdbcType (" + parameterMapping.jdbcType + ") combination.");
        }
      }
    }

    private void resolveTypeHandler() {
      // ❗️❗️❗️
      // 前提是 -- typeHandler为null,而javaType不为空 [JavaType在SqlSourceBuilder.ParameterMappingTokenHandler.buildParameterMapping()一般都会解析出JavaType哦]
      // 去TypeHandler注册总磷, 根据 JavaType 和 jdbcType 查找 TypeHandler哦
      // 需要知道的是 --
      // javaType 的来源
      // 1. 多参数情况基本都是 Object.class -- 一般都是找到UnknownTypeHandler
      // 2. 单参数且无@Param情况下基本都是 对应的形参的class -- 只有基本如String\Date\Time\Boolean\Int等等基本类型有对应的TypeHandler进行处理哦
      // 3. 或 #{person,javaType=Xxx} 指定javaType
      if (parameterMapping.typeHandler == null && parameterMapping.javaType != null) {
        Configuration configuration = parameterMapping.configuration;
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        // jdbcType需要用户指定,但是一般情况都不会指定JdbcType都是为null值
        parameterMapping.typeHandler = typeHandlerRegistry.getTypeHandler(parameterMapping.javaType, parameterMapping.jdbcType);
      }
    }

  }

  public String getProperty() {
    return property;
  }

  /**
   * Used for handling output of callable statements.
   * @return
   */
  public ParameterMode getMode() {
    return mode;
  }

  /**
   * Used for handling output of callable statements.
   * @return
   */
  public Class<?> getJavaType() {
    return javaType;
  }

  /**
   * Used in the UnknownTypeHandler in case there is no handler for the property type.
   * @return
   */
  public JdbcType getJdbcType() {
    return jdbcType;
  }

  /**
   * Used for handling output of callable statements.
   * @return
   */
  public Integer getNumericScale() {
    return numericScale;
  }

  /**
   * Used when setting parameters to the PreparedStatement.
   * @return
   */
  public TypeHandler<?> getTypeHandler() {
    return typeHandler;
  }

  /**
   * Used for handling output of callable statements.
   * @return
   */
  public String getResultMapId() {
    return resultMapId;
  }

  /**
   * Used for handling output of callable statements.
   * @return
   */
  public String getJdbcTypeName() {
    return jdbcTypeName;
  }

  /**
   * Not used
   * @return
   */
  public String getExpression() {
    return expression;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("ParameterMapping{");
    //sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
    sb.append("property='").append(property).append('\'');
    sb.append(", mode=").append(mode);
    sb.append(", javaType=").append(javaType);
    sb.append(", jdbcType=").append(jdbcType);
    sb.append(", numericScale=").append(numericScale);
    //sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
    sb.append(", resultMapId='").append(resultMapId).append('\'');
    sb.append(", jdbcTypeName='").append(jdbcTypeName).append('\'');
    sb.append(", expression='").append(expression).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
