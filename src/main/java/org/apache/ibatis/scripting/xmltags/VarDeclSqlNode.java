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

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class VarDeclSqlNode implements SqlNode {
  // 用来存储<bind>标签的name和value[对应当前类的expression]

  private final String name;
  private final String expression;

  public VarDeclSqlNode(String var, String exp) {
    name = var;
    expression = exp;
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 将当前的VarDeclSqlNode应用到context中
    // 如何应用: 向context中绑定name和value接口
    // 注意:expression会经过Ognl处理

    // 这里我们就需要注意一点:
    // ❗️❗️❗️
    // 在XMLScriptBuilder.parseDynamicTags()如果有<bind>标签
    // 那么返回结果的MixedSqlNode中的List<SqlNode>就会有当前这个<bind>标签的VarDeclSqlNode
    // 其中MixedSqlNode.apply()就是遍历他的List<SqlNode>的成员 -- 因此建议<bind>标签放在DML标签中的第一个位置
    // 比如
    // <select ...>
    //    select ${name}                          // 先执行 -- 先解析,但是context.getBindins()可能并不存在我们的name,需要后面的先执行才可以哦
    //    <bind name = "name", value ="value />   // 后执行 -- 后绑定
    // </select>
    final Object value = OgnlCache.getValue(expression, context.getBindings());
    context.bind(name, value);
    return true;
  }

}
