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
package org.apache.ibatis.scripting.defaults;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {
  // ❗️❗️❗ 这是一个非常重要的类
  // 作用:
  // 1.
  // 2.
  // 3.
  // 唯一实现:

  // 基本五个组件 -- 不做过多阐述
  private final TypeHandlerRegistry typeHandlerRegistry;

  private final MappedStatement mappedStatement;
  private final Object parameterObject;
  private final BoundSql boundSql;
  private final Configuration configuration;

  public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    this.mappedStatement = mappedStatement;
    this.configuration = mappedStatement.getConfiguration();
    this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
    this.parameterObject = parameterObject;
    this.boundSql = boundSql;
  }

  @Override
  public Object getParameterObject() {
    // 获取修饰的后形参对象
    return parameterObject;
  }

  @Override
  public void setParameters(PreparedStatement ps) {
    // ❗️❗️❗️开始我的表演
    // 对PreparedStatement进行占位符填充吧

    ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
    // 1. 拿出当前boundSql需要填充的占位符处的信息
    // 即#{}中的信息
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    // 2. parameterMappings为null,说明不需要做占位符填充 -- 立即结束
    if (parameterMappings != null) {
      for (int i = 0; i < parameterMappings.size(); i++) {
        ParameterMapping parameterMapping = parameterMappings.get(i);
        // 3. 必须是入参模式,否则跳过当前parameterMapping
        if (parameterMapping.getMode() != ParameterMode.OUT) {
          Object value; // 传递给TypeHandler处理的对象哦
          // 4. 获取对应#{}的属性名
          String propertyName = parameterMapping.getProperty();
          // 5. 根据 propertyName 查找对应的 value
          if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
            // 5.1 从additionalParameter中获取
            value = boundSql.getAdditionalParameter(propertyName);
          } else if (parameterObject == null) {
            // 5.2 没有传递形参 -- 那肯定是无法从形参获取value的 [老规矩--注意parameterObject的结构,到底是一个啥]
            value = null;
          } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
            // 5.3 老规矩 -- 先看看parameterObject咋样
            // parameterObject 为 ParamMap或StrictMap : 当前mapper接口执行的方法是非单形参或者使用了@Param修饰的 -- 就为Map结构
            // parameterObject 为 JavaBean               : 当前mapper接口执行的方法是单形参且无@Param修饰的 -- 就为对应的形参
            // 一般来说: 是没有对应的typeHandler的
            // 因为parameterObject中没有注册Map的TypeHandler哦
            // 只有在单参数且无@Param有可能生效,比如是一些基本类型String/Boolean等类型
            // 这种情况反正都是单形参就直接设置给value吧
            value = parameterObject;
          } else {
            // 5.4 99% 还是为parameterObject创建MetaObject,然后从里面getValue吧
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            value = metaObject.getValue(propertyName);
          }
          // 6. 是否指定了typeHandler,比如#{person,typeHandler=com.xx.yy.zz.MyCustomTypeHandler}
          // 最差也会是一个 UnknownTypeHandler
          // 其指定的TypeHandler来自 SqlSourceBuilder.ParameterMappingTokenHandler.buildParameterMapping()
          TypeHandler typeHandler = parameterMapping.getTypeHandler();
          // 7. 是否指定jdbcType,比如#{person,jdbcType=VARCHAR}或者#{person:VARCHAR}
          JdbcType jdbcType = parameterMapping.getJdbcType();
          // 8. 默认处理 javaType -- 如果没有找到对应的value,且jdbcType为null
          if (value == null && jdbcType == null) {
            jdbcType = configuration.getJdbcTypeForNull(); // 默认是JdbcType.OTHER
          }
          try {
            // 9. ❗️❗️❗️
            // 最终落实到使用对应 typeHandler 向ps中设置值哦
            typeHandler.setParameter(ps, i + 1, value, jdbcType);
          } catch (TypeException | SQLException e) {
            throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
          }
        }
      }
    }
  }

}
