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
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author Clinton Begin
 */
public class SqlSourceBuilder extends BaseBuilder {
  // SqlSource的Builder

  // 参数属性
  private static final String PARAMETER_PROPERTIES = "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

  public SqlSourceBuilder(Configuration configuration) {
    super(configuration);
  }

  public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
    // 获取参数的来源的解析器 -- 参数来源additionalParameters,参数类型parameterType

    // 1. 注意: ParameterMappingTokenHandler 会将 #{} 中提取出来的expression 解析为 ? 哦
    ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);
    // 2. 解析占位符 #{} -- 将占位符中的expression传递给和上面的handler处理
    GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    // 3. 开始解析出sql
    // 解析出的sql可能就是这种的
    // originalSql = select * from t_card where id = #{id}
    // sql = select * from t_card where id = ?
    String sql = parser.parse(originalSql);
    // 最终将添加到StaticSqlSource中
    // 有 待预编译的sql
    // 有 解析后的content转换而成的ParameterMapping
    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
  }

  private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {
    // 它的核心在于 -- 将#{}解析为?
    // 实际的表达式信息 #{} 通过 buildParameterMapping() 解析为 ParameterMapping
    // 然后加入到 List<ParameterMapping>
    // 因此比如: select * from t_card where id = #{id,jdbcType=Varchar}
    // parameterMappings 的 size 就是 1, 里面存储的就是表达式id,jdbcType=Varchar的ParameterMapping

    private List<ParameterMapping> parameterMappings = new ArrayList<>();
    private Class<?> parameterType;
    private MetaObject metaParameters;

    public ParameterMappingTokenHandler(Configuration configuration, Class<?> parameterType, Map<String, Object> additionalParameters) {
      super(configuration);
      this.parameterType = parameterType;
      // 好家伙 -- additionalParameters 再次转为 metaObject
      // 一般就两种情况:
      // 1. additionalParameters 为空的 -- 提前预存
      // 2. additionalParameters 只有 _parameter\_databaseId -- 开始执行解析
      this.metaParameters = configuration.newMetaObject(additionalParameters);
    }

    public List<ParameterMapping> getParameterMappings() {
      return parameterMappings;
    }

    @Override
    public String handleToken(String content) {
      // 会将#{Xxx}中的xxx替换为?
      // 这是一个预编译的sql -> 所以是替换为?

      parameterMappings.add(buildParameterMapping(content));
      return "?";
    }

    private ParameterMapping buildParameterMapping(String content) {
      // 将content构建为ParameterMapping --
      // 比如常见的 #{userName,jdbcType=VARCHAR}

      // 1. 解析content中内联的属性
      Map<String, String> propertiesMap = parseParameterMapping(content);
      // 2. 获取指定的属性 --
      //  比如 #{person,jdbcType=VARCHAR} 结果为 property="person",jdbcType="VARCHAR"
      //  比如 #{person|VARCHAR} 结果为 property="person",jdbcType="VARCHAR"
      //  比如 #{person,jdbcType=VARCHAR,javaType=int} 结果为 property="person",jdbcType="VARCHAR",javaType="int"
      String property = propertiesMap.get("property");
      Class<?> propertyType;
      if (metaParameters.hasGetter(property)) { // issue #448 get type from additional params
        // 一帮情况都不会生效
        propertyType = metaParameters.getGetterType(property);
      } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
        // here: 注意一下
        // parameterType 在mapper接口的方法是多参数情况下是Map类型的 -- 是无法找到对应的 TypeHandler
        // parameterType 在单参数且无@Param修饰且非List/Array就是形参值 -- 是有机会找到对应的 TypeHandler 的
        // 如果找到TypeHandler,那么对应的propertyType就是形参类型
        propertyType = parameterType;
      } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
        // 很少的情况
        propertyType = java.sql.ResultSet.class;
      } else if (property == null || Map.class.isAssignableFrom(parameterType)) {
        // here:
        // parameterType 在mapper接口的方法是多参数情况下是Map类型的 -- 会导致进入下面这行代码使得 propertyType 为 Object 类型哦
        // parameterType 在单参数且无@Param修饰且非List/Array就是形参值 -- 在上面第一个else-if 中被有机会解析出来
        propertyType = Object.class;
      } else {
        // 其余情况周这里:
        MetaClass metaClass = MetaClass.forClass(parameterType, configuration.getReflectorFactory());
        if (metaClass.hasGetter(property)) {
          propertyType = metaClass.getGetterType(property);
        } else {
          propertyType = Object.class;
        }
      }
      // 3. 开始构建 ParameterMapping
      // 开始 javaType/jdbcType/mode/resultMap/typeHandler/jdbcTypeName/property等属性
      // ❗️❗️❗️ 这里传入的 propertyType 就是分析出来的JavaType
      // 但是可以被后面的builder.javaType(javaType);
      ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, propertyType);
      Class<?> javaType = propertyType;
      String typeHandlerAlias = null;
      for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
        String name = entry.getKey();
        String value = entry.getValue();
        if ("javaType".equals(name)) {
          javaType = resolveClass(value);
          builder.javaType(javaType);
        } else if ("jdbcType".equals(name)) {
          builder.jdbcType(resolveJdbcType(value));
        } else if ("mode".equals(name)) {
          builder.mode(resolveParameterMode(value));
        } else if ("numericScale".equals(name)) {
          builder.numericScale(Integer.valueOf(value));
        } else if ("resultMap".equals(name)) { // 是否resultMap
          builder.resultMapId(value);
        } else if ("typeHandler".equals(name)) { // 可以指定TypeHandler
          typeHandlerAlias = value;
        } else if ("jdbcTypeName".equals(name)) {
          builder.jdbcTypeName(value);
        } else if ("property".equals(name)) {
          // Do Nothing
        } else if ("expression".equals(name)) {
          throw new BuilderException("Expression based parameters are not supported yet");
        } else {
          throw new BuilderException("An invalid property '" + name + "' was found in mapping #{" + content + "}.  Valid properties are " + PARAMETER_PROPERTIES);
        }
      }
      if (typeHandlerAlias != null) {
        // 可以 99%的 情况都不会去指定一个typeHandler的属性值
        // 比如  #{person,typeHandler=BooleanTypeHandler}
        // 虽然99%的情况不会指定TypeHandler,但是ParameterMappingBuilder会根据javaType和jdbcTyp做判断产生一个typeHandler哦
        // ❗️❗️❗️ --- TypeHandler的来源非常重要
        builder.typeHandler(resolveTypeHandler(javaType, typeHandlerAlias));
      }
      return builder.build();
    }

    private Map<String, String> parseParameterMapping(String content) {
      try {
        return new ParameterExpression(content);
      } catch (BuilderException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new BuilderException("Parsing error was found in mapping #{" + content + "}.  Check syntax #{property|(expression), var1=value1, var2=value2, ...} ", ex);
      }
    }
  }

}
