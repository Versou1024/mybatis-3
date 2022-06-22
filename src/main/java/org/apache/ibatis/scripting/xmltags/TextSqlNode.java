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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

import java.util.regex.Pattern;

/**
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {

  // 文本
  private final String text;
  private final Pattern injectionFilter;

  public TextSqlNode(String text) {
    this(text, null);
  }

  public TextSqlNode(String text, Pattern injectionFilter) {
    this.text = text;
    this.injectionFilter = injectionFilter;
  }

  public boolean isDynamic() {
    // 检查是否为动态的TextNode

    // 1. 检查文本text是否为动态文本哦
    DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
    // 2. 创建 -- 通用的令牌解析器
    GenericTokenParser parser = createParser(checker);
    // 3. 解析text -- 处理其中的占位符 ${}
    // 只要有符号 ${} 就认为是动态的哦
    parser.parse(text);
    return checker.isDynamic();
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 将当前的TextSqlNode应用到context中
    // 如何应用 -- 动态的文本的SqlNode,需要通过BindingTokenParser解析之后再追加到context上
    // 这里主要关注的是: BindingTokenParser 是如何将expression解析为对应的value值的

    GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
    context.appendSql(parser.parse(text));
    return true;
  }

  private GenericTokenParser createParser(TokenHandler handler) {
    // 创建通用的令牌解析器
    // 设置令牌的openToken为${,关闭Token为}
    return new GenericTokenParser("${", "}", handler);
  }

  private static class BindingTokenParser implements TokenHandler {

    private DynamicContext context;
    private Pattern injectionFilter;

    public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
      this.context = context;
      this.injectionFilter = injectionFilter;
    }

    @Override
    public String handleToken(String content) {
      // 如何解析${expression}中的表达式

      // 1. 从context的绑定中获取_parameter对象
      Object parameter = context.getBindings().get("_parameter");
      // 2. Mapper的方法是空参的情况,就会出现parameter为null的情况
      if (parameter == null) {
        context.getBindings().put("value", null);
      }
      // 3. 单形参且无@Param注解,且形参值为简单类型
      else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
        // ❗️❗️❗️ 会向bindings中添加一个键值对 value 和 parameter
        context.getBindings().put("value", parameter);
      }
      // 4. 老样子 - 使用Ognl表达式进行解析
      Object value = OgnlCache.getValue(content, context.getBindings());
      String srtValue = value == null ? "" : String.valueOf(value); // issue #274 return "" instead of "null"
      checkInjection(srtValue);
      return srtValue;
    }

    private void checkInjection(String value) {
      if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
        throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
      }
    }
  }

  private static class DynamicCheckerTokenParser implements TokenHandler {
    // 有一个动态表示 isDynamic

    private boolean isDynamic;

    public DynamicCheckerTokenParser() {
      // Prevent Synthetic Access
    }

    public boolean isDynamic() {
      return isDynamic;
    }

    @Override
    public String handleToken(String content) {
      // 只要调用 DynamicCheckerTokenParser.handleToken 将 isDynamic = true
      this.isDynamic = true;
      return null;
    }
  }

}
