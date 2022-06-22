/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.parsing;

import java.util.Properties;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class PropertyParser {
  // 属性解析器
  // 提供但不限于以下的功能:
  // 根据Properties提供解析能力

  private static final String KEY_PREFIX = "org.apache.ibatis.parsing.PropertyParser.";
  /**
   * The special property key that indicate whether enable a default value on placeholder.
   * <p>
   *   The default value is {@code false} (indicate disable a default value on placeholder)
   *   If you specify the {@code true}, you can specify key and default value on placeholder (e.g. {@code ${db.username:postgres}}).
   * </p>
   * @since 3.4.2
   */
  public static final String KEY_ENABLE_DEFAULT_VALUE = KEY_PREFIX + "enable-default-value";
  // KEY_ENABLE_DEFAULT_VALUE 指示是否在占位符上启用默认值的特殊属性键。
  // 默认值为false （表示禁用占位符的默认值）
  // 如果指定true ，则可以在占位符上指定键和默认值（例如${db.username:postgres} ）

  /**
   * The special property key that specify a separator for key and default value on placeholder.
   * <p>
   *   The default separator is {@code ":"}.
   * </p>
   * @since 3.4.2
   */
  public static final String KEY_DEFAULT_VALUE_SEPARATOR = KEY_PREFIX + "default-value-separator";
  // 为占位符指定键和默认值的分隔符的特殊属性键。
  // 默认分隔符是":"

  private static final String ENABLE_DEFAULT_VALUE = "false";
  private static final String DEFAULT_VALUE_SEPARATOR = ":";

  private PropertyParser() {
    // Prevent Instantiation
  }

  public static String parse(String string, Properties variables) {
    // 目的:解决占位符${}填充
    // 例如
    //    string    为   select ${xx}
    //    variables 中   xx=id
    //    返回       为   select id
    // 一旦xx在variables中无法解析出来,且没有在占位符中指定默认值,是不会报错的,而是返回原来的样式

    VariableTokenHandler handler = new VariableTokenHandler(variables);
    GenericTokenParser parser = new GenericTokenParser("${", "}", handler);
    return parser.parse(string);
  }

  private static class VariableTokenHandler implements TokenHandler {
    private final Properties variables;
    private final boolean enableDefaultValue;
    private final String defaultValueSeparator;

    private VariableTokenHandler(Properties variables) {
      this.variables = variables;
      // 1.  用户可以指定org.apache.ibatis.parsing.PropertyParser.enable-default-value的值
      // enableDefaultValue 默认是false
      this.enableDefaultValue = Boolean.parseBoolean(getPropertyValue(KEY_ENABLE_DEFAULT_VALUE, ENABLE_DEFAULT_VALUE));
      // 2. 为占位符内指定键和默认值的分隔符的特殊属性键。
      // 默认分隔符是":"
      this.defaultValueSeparator = getPropertyValue(KEY_DEFAULT_VALUE_SEPARATOR, DEFAULT_VALUE_SEPARATOR);
    }

    private String getPropertyValue(String key, String defaultValue) {
      return (variables == null) ? defaultValue : variables.getProperty(key, defaultValue);
    }

    @Override
    public String handleToken(String content) {
      // 处理${}中的占位符

      // 1. 表达式的来源是variables
      if (variables != null) {
        String key = content;
        // 2. 是否允许给定默认值:例如 ${db.username:postgres}
        if (enableDefaultValue) {
          final int separatorIndex = content.indexOf(defaultValueSeparator);
          String defaultValue = null;
          if (separatorIndex >= 0) {
            // 2.1 key和defaultValue比如上面的db.username就是key,postgres就是默认的值
            key = content.substring(0, separatorIndex);
            defaultValue = content.substring(separatorIndex + defaultValueSeparator.length());
          }
          // 2.2 给定了默认值,还是会去variables中查看key的值,如果有的话,默认值就会失效
          // 而是从variables中获取
          if (defaultValue != null) {
            return variables.getProperty(key, defaultValue);
          }
        }
        // 2.3 未开启占位符中填写默认值的功能,那么key就是content
        // 直接从variables中获取
        if (variables.containsKey(key)) {
          return variables.getProperty(key);
        }
      }
      // 3. ❗️❗️❗️
      // 注意: 如果解析不了,就远路返回
      return "${" + content + "}";
    }
  }

}
