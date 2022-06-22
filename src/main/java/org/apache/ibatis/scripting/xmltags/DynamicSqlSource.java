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

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {
  // 动态的sql的数据源
  // 动态的含义 --
  // 文本Node用有使用${}进行填充
  // 或者有<if><foreach>等动态标签
  // 实际上99%的情况都是DynamicSqlSource

  private final Configuration configuration;
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  // 根据 DML标签 解析出的 MixedSqlNode
  // 再去解析出 BoundSql
  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    // 就是形参对象
    // 无@Param且单参数的方法时, parameterObject 就是对应的参数
    // 其余情况都是HashMap<String,Object>对象 -- String为解析后的形参名[比如@Param指定的] / Object为对应的对象

    // 1. 创建 DynamicContext -- DynamicContext 能够综合MetaClass/BeanWrapper等等
    // 动态上下文的数据来源于: configuration 与 parameterObject
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    // 2. 开始将MixedSqlNode中所有的sql片段在context中组装起来吧
    // 注意通过这一步的时候${}已经被解析为对应的数据
    rootSqlNode.apply(context);
    // 3. 创建SqlSourceBuilder,并SqlSourceBuilder.parse()解析处SqlSource哦
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    // ❗️❗️❗❗️❗️❗❗️❗️❗
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    // context.getSql() 获取完整的sql -- 注意这里的sql里面有#{}待解析 -- 因此使用也传入了context.getBindings()即绑定的参数上下文 -- 有形参parameterObject/_databaseId
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    // 4. 将 context.getBinds()都写入boundSql的metaParameters中
    // ❗️❗️❗️ 这里还将context中的binds信息也写进去来
    context.getBindings().forEach(boundSql::setAdditionalParameter);
    return boundSql;
  }

}
