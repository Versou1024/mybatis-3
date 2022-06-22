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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {
  // 专门用来构建 mapper.xml 中的 select/update/delete/insert 标签构成的 statement
  // 下面我们统一将 DML标签认为是select/update/delete/insert标签

  private final MapperBuilderAssistant builderAssistant;
  // 代表 select/update/delete/insert 标签构成的XNode
  private final XNode context;
  // 代表传递过来的DatabaseId,可以为null,表示任务无DatabaseId的DML标签都会生效
  // 非null,表示DML标签必须带有相同的DML标签
  private final String requiredDatabaseId;


  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  public void parseStatementNode() {
    // 1. DML标签的id/databaseId
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    // 2. 校验DML标签的databaseId,和requiredDatabaseId是否一致
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    // 3. 获取DML标签名 -- 即select/update/delete/insert之一,然后转换为SqlCommandType
    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    // 4. isSelect 是否为查询语句
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    // 5. 是否强制刷新缓存,未指定的情况下 -- 默认是非Select语句会强制刷新缓存,select语句不会强制刷新缓存
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    // 6. 是否使用缓存,未指定的情况下 -- 默认是Select语句会启用二级缓存,非select语句不会启用缓存
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    // 7. 是否使用 resultOrdered
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // Include Fragments before parsing
    // 8. 解析Inculde标签 -- SQL标签即SQLFragment已经提前解析后存放在configuration中辣= -- 主要就是两件工作
    // 将引用的sql标签替换inculde镖旗
    // 将引用的sql标签中的{}括起来的变量替换调
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // 9. 解析 parameterType
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);

    // 10. 解析 lang
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);

    // Parse selectKey after includes and remove them.
    // 11. 处理<selectKey>标签 ❗️❗️❗️ -- 这里处理的SelectKey标签 -- 和下面的keyGenerator有关
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    // 12. 开始解析 SQL（前面的代码已经将: <selectKey> 和 <include> 被解析并移除）
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    // 13. 是否有 <selectKey>标签 对应的 keyGenerator
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      // 14. 没有<selectKey>标签 -- 那就看是否有使用useGeneratedKeys
      // 有的话 -- Jdbc3KeyGenerator 就会其来帮助向形参中回填id等指定列到属性中
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }
    // 15. 接续构建 -- 下面就是简单的对DML标签中的属性获取进行构建即可
    // langDriver 一般都默认是 XMLLanguageDriver.createSqlSource()
    // context是DML标签的XNode
    // parameterTypeClass是DML标签的paramType属性对应的Class ❗️❗️❗️
    // MappedStatement 中的sqlSource来源
    // parameterTypeClass 一般为 null  -- 除非指定DML标签中的 parameterType 属性
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String resultType = context.getStringAttribute("resultType");
    // 结果集相关的两个注解: resultTypeClass\resultMap的id
    Class<?> resultTypeClass = resolveClass(resultType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultSetType = context.getStringAttribute("resultSetType");
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    if (resultSetTypeEnum == null) {
      resultSetTypeEnum = configuration.getDefaultResultSetType();
    }
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    String resultSets = context.getStringAttribute("resultSets");

    // 构建并添加 MapperStatement
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    // 解析 selectKey 标签
    // 这里的id是 selectKey标签的父标签DML的id值

    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    removeSelectKeyNodes(selectKeyNodes);
  }

  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      // 1. 构建selectKey的id = 父DML标签ID + "!selectKey"
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      // 2. 检查dataBaseID是否符合
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    // 这里的id是 父DML标签ID + ".selectKey"

    // 1. 解析基本属性 -- resultType\statementType\keyProperty\keyColumn\order\
    String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    // 2. 将MappedStatement其余属性设置为默认的defaults
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    // 3. 创建SqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    // 4. 构建 selectKey 标签对应的 MappedStatement
    // 注意这里面会将构建陈的 MappedStatement 加入到 Configuration
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    id = builderAssistant.applyCurrentNamespace(id, false);

    // 5. 步骤4中创建SelectKey对应的MapperStatement并且添加到Configuration中,因此这里是可以取出来的
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);

    // 6.最终:将SelectKeyGenerator添加到Configuration中
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));

    // 因此: <selectKey>标签被解析为MappedStatement然后加入到Configuration的mappedStatements
    // 并且也会生成一个对应的 SelectKeyGenerator 加入到Configuration的keyGenerator方便后续使用
  }

  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    id = builderAssistant.applyCurrentNamespace(id, false);
    if (!this.configuration.hasStatement(id, false)) {
      return true;
    }
    // skip this statement if there is a previous one with a not null databaseId
    MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
    return previous.getDatabaseId() == null;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    // 1. 一般情况都不会指定lang属性哦
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    // 2. 99%的情况langClass都是null的
    return configuration.getLanguageDriver(langClass);
  }

}
