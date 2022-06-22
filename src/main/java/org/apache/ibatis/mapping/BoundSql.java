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

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An actual SQL String got from an {@link SqlSource} after having processed any dynamic content.
 * The SQL may have SQL placeholders "?" and an list (ordered) of an parameter mappings
 * with the additional information for each parameter (at least the property name of the input object to read
 * the value from).
 * <p>
 * Can also have additional parameters that are created by the dynamic language (for loops, bind...).
 *
 * @author Clinton Begin
 */
public class BoundSql {
  // ❗️❗️❗️
  // BoundSql -- 处理任何动态内容后从SqlSource获得的实际 SQL 字符串。 SQL 可能有 SQL 占位符“？”以及一个参数映射列表（有序），其中包含每个参数的附加信息（至少是要从中读取值的输入对象的属性名称）。

  // sql可能存在占位符?哦
  // 其中 #{} 已经被解析 ?
  // 而#{}的信息被存放到parameterMappings中
  private final String sql;

  // parameterMappings 是 sql中的需要的占位符信息 -- ❗️❗️❗️
  // 即#{}信息
  // 它的传递过程如下
  // DynamicSqlSource.getBoundSql() ->
  // 其中 parameterMappings 就是在 SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings()); 处理出来的
  private final List<ParameterMapping> parameterMappings;

  // 本次SQL传进来的 -- 包装的parameterObject
  private final Object parameterObject;

  // 在DynamicSqlSource.getBoundSql()获取了BoundSql后
  // 调用了 context.getBindings().forEach(boundSql::setAdditionalParameter);
  // 因此additionalParameters绑定的就是context总共的bindings数据
  private final Map<String, Object> additionalParameters;

  // 为additionalParameters创建的形参元数据
  private final MetaObject metaParameters;

  public BoundSql(Configuration configuration, String sql, List<ParameterMapping> parameterMappings, Object parameterObject) {
    // 唯一构造函数 -- 重点是在 XMLScriptBuilder 中构建出来 SqlSource
    this.sql = sql;
    this.parameterMappings = parameterMappings;
    this.parameterObject = parameterObject;
    this.additionalParameters = new HashMap<>();
    this.metaParameters = configuration.newMetaObject(additionalParameters);
  }

  public String getSql() {
    return sql;
  }

  public List<ParameterMapping> getParameterMappings() {
    return parameterMappings;
  }

  public Object getParameterObject() {
    return parameterObject;
  }

  public boolean hasAdditionalParameter(String name) {
    // 还是需要通过 PropertyTokenizer 进行 属性令牌分词器 -- 获取最终的name
    // 比如 person[1].hobbies[2] 解析后的 getName() 返回 person
    String paramName = new PropertyTokenizer(name).getName();
    // 查看额外属性中是否可以找到这个paramName
    return additionalParameters.containsKey(paramName);
  }

  public void setAdditionalParameter(String name, Object value) {
    metaParameters.setValue(name, value);
  }

  public Object getAdditionalParameter(String name) {
    return metaParameters.getValue(name);
  }
}
