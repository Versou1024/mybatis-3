/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Eduardo Macarron
 */
public class XMLLanguageDriver implements LanguageDriver {
  // ❗️❗️❗️
  // 默认注册的XMLLanguageDriver
  // 当前仍然支持的唯一的内置实现 -- 关注关注

  @Override
  public ParameterHandler createParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    // 在执行SQL中使用到ParameterHandler
    return new DefaultParameterHandler(mappedStatement, parameterObject, boundSql);
  }

  @Override
  public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
    // script 一般都是 DML 标签 -- 主要是解析DML标签中的<if>/<foreach>/<where>/<when>等插件的解析哦
    // 创建SqlSource是交给XMLScriptBuilder完成的
    XMLScriptBuilder builder = new XMLScriptBuilder(configuration, script, parameterType);
    return builder.parseScriptNode();
  }

  @Override
  public SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType) {
    // 使用当前方法创建SqlSource的情况比较少

    // 1. @Select/@Update/@Insert/@Delete的value可以使用<script>开头
    if (script.startsWith("<script>")) {
      // 1.1 复杂的情况可以使用比如
      // @Select(value={"<script>"},
      // {"<select id = "selectMethodName", resultType = "PO.java" > "},
      // {"select id,name from t_e_card"},
      // {"when #{id}"},
      // {"</select>"},
      // )

      // 1.2 将@Select/@Update/@Insert/@Delete的value以<script>开头时,将整个script作为XPathParser的xml传递进去
      // parser.evalNode("/script") 然后根据路径 /script作为根开始解析
      XPathParser parser = new XPathParser(script, false, configuration.getVariables(), new XMLMapperEntityResolver());
      return createSqlSource(configuration, parser.evalNode("/script"), parameterType);
    }
    // 2. @Select/@Update/@Insert/@Delete的value没有使用<script>开头
    // 然后用configuration.getVariables()解析script中的占位符 -- 占位符中的表达式需要configuration.getVariables()来解析
    // configuration.getVariables() 可以来自 mybatis.xml 中的 <properties> 标签的键值对
    else {
      script = PropertyParser.parse(script, configuration.getVariables());
      // 3. 直接创建为 TextSqlNode
      TextSqlNode textSqlNode = new TextSqlNode(script);
      if (textSqlNode.isDynamic()) {
        // 3.1 是否为动态的标准就是 script 中是否 #{} 占位符号
        return new DynamicSqlSource(configuration, textSqlNode);
      } else {
        return new RawSqlSource(configuration, script, parameterType);
      }
    }
  }

}
