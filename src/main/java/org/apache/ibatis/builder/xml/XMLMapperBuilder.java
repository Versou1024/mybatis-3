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
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {
  // 用来构建 Mapper.xml

  private final XPathParser parser;
  private final MapperBuilderAssistant builderAssistant;
  private final Map<String, XNode> sqlFragments; // 用于存储 sql 标签, key为sql标签的id,value为整个sql标签的XNode
  private final String resource; // com/sdk/developer/UserMapper.xml 这种

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource); // MapperBuilder助理 -- 可用于
    this.parser = parser; // 解析器
    this.sqlFragments = sqlFragments; // Configuration中的sqlFragments
    this.resource = resource; // 资源
  }

  public void parse() {
    // 1. 是否已经被加载过
    if (!configuration.isResourceLoaded(resource)) {
      // 2. 解析mapper.xml中的mapper的Node
      configurationElement(parser.evalNode("/mapper"));
      // 3. 添加到Configuration中已加载的resource集合中
      configuration.addLoadedResource(resource);
      // 4. 绑定Mapper为当前命名空间
      bindMapperForNamespace();
    }

    // 2. 解析未完成的的ResultMaps/CacheRefs/Statement
    // 比如 ResultMapResolver 解析的 <resultMap> 标签中有extend属性,其指定的extend的父ResultMap的id不存在,就会抛出IncompleteElementException后加入到
    // Configuration中IncompleteResultMaps放在这里继续执行
    parsePendingResultMaps();
    // 如果 <cacheRef> 标签引用的指定命名空间的cache不存在,说明还没有加载
    // 先抛出IncompleteElementException异常,然后被 XMLMapperBuilder.cacheRefElement() 解析中捕获到
    // 然后加入到 configuration.addIncompleteCacheRef(cacheRefResolver);
    // 待后续解析完毕,调用 XMLMapperBuilder.parse()-> parsePendingCacheRefs() 来完成这里未完成的解析器
    parsePendingCacheRefs();
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  private void configurationElement(XNode context) {
    try {
      // 1. 获取命名空间 -- 必须存在哦
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      // 2. 使用 builderAssistant 设置当前命名空间
      builderAssistant.setCurrentNamespace(namespace);
      // 2.1 解析 cache-ref
      cacheRefElement(context.evalNode("cache-ref"));
      // 2.2 解析 cache
      cacheElement(context.evalNode("cache"));
      // 2.3 解析 parameterMap -- 可以跳过,先阶段几乎都不使用parameterMap啦
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      // 2.4 解析 resultMap -- 重要的哦
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      // 2.5 解析 sql
      sqlElement(context.evalNodes("/mapper/sql"));
      // 2.6 解析 select/insert/update/delete
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  private void buildStatementFromContext(List<XNode> list) {
    // 解析 select/insert/update/delete

    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    // 开始构建 -- statement

    // 遍历所有的 select标签/insert/update/delete
    for (XNode context : list) {
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          // 1. 尝试继续解析
          // 比如
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  private void cacheRefElement(XNode context) {
    // 解析cache-ref标签
    if (context != null) {
      // 1. 加入到Configuration的cacheRefMap中,key为当前mapper的namespace - value为cache-ref引用的命名空间
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      // 2. 创建 CacheRefResolver 解析器 并调用 resolveCacheRef 进行解析
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        // 目的是 -- 验证引用的对方nameSpace是有cache,并且将这个cache放在assistant中
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        //
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) {
    // 解析 cache 标签

    if (context != null) {
      // 1.  获取缓存的属性 - type缓存类型/eviction驱除策略/flushInterval缓存刷新间隔/size缓存大小/readonly是否只读/blocking是否阻塞
      String type = context.getStringAttribute("type", "PERPETUAL");
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type); // 根据别名找出对应的Cache --
      String eviction = context.getStringAttribute("eviction", "LRU"); // 默认使用的驱除策略是 LRU 最近最少未使用
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);// 根据别名找出对应的驱除策略 --
      Long flushInterval = context.getLongAttribute("flushInterval");
      Integer size = context.getIntAttribute("size");
      boolean readWrite = !context.getBooleanAttribute("readOnly", false); // 默认false
      boolean blocking = context.getBooleanAttribute("blocking", false); // 默认false
      Properties props = context.getChildrenAsProperties();
      // 2. 使用assistant创建一个新的cache
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) {
    // 解析 <ParameterMap> 镖旗
    for (XNode parameterMapNode : list) {
      // 1. 解析共同属性 -- id/type/parameter/
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) throws Exception {
    // 1. 遍历解决所有的 resultMap 标签
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.emptyList(), null);
  }

  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    // 1. 获取 type 属性, 以 type-ofType-resultType-javaType 属性顺序加载,直到其中一个非null
    // 因为这个方法不单单使用对resultMap解析的 -- 因为 Collection/Association标签也是类ResultMap的标签
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    // 2. 解析处resultMap对应的typeClass -- 大部分情况都是对应的PO类
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      // 3. -- 一般不存在这种情况哦
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    Discriminator discriminator = null; // 鉴别器
    List<ResultMapping> resultMappings = new ArrayList<>(); // ResultMapping的集合 -- 递归处理的
    resultMappings.addAll(additionalResultMappings);
    // 4. 对resultMapNode中的子标签进行遍历
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      // 4.1 constructor 构造器标签
      if ("constructor".equals(resultChild.getName())) {
        // 将处理的结果放入 resultMappings 中
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        // 4.2 discriminator 鉴别器标签
        // 创建鉴别器
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        // 4.3 其余标签, 入ResultMap的子标签 -- id标签/result标签/collection标签/association标签都会执行到这里的
        List<ResultFlag> flags = new ArrayList<>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    // 5. 处理resultMap的id属性 -- 当不指定resultMap的id时会通过getValueBasedIdentifier()
    String id = resultMapNode.getStringAttribute("id",
            resultMapNode.getValueBasedIdentifier());
    // 6. 处理extends属性
    String extend = resultMapNode.getStringAttribute("extends");
    // 7. 处理autoMapping属性
    // 自动映射很强大的哦 -- 能够将结果中的列名自动映射到resultType上的驼峰命名的属性上
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    // 到此: <resultMap>标签的 id/type的class/extends的父类ResultMap/鉴别器discriminator/其余id-result-collection-assocation-idArg-arg等标签都被解析为ResultMapping被加入到resultMapping是中
    // autoMapping属性
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      // 8. 解析处对应的ResultMap
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      // ❗️❗️❗️
      // 出现异常的时候 -- 会将resultMapResolver加入到Configuration中IncompleteResultMap
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    // 继承封闭类型
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      String property = resultMapNode.getStringAttribute("property");
      if (property != null && enclosingType != null) {
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      return enclosingType;
    }
    return null;
  }

  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    // 处理 Constructor 元素标签

    // 1. Constructor 有 idArg/arg 标签
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<>();
      // 1.1 arg标签 - ResultFlag.CONSTRUCTOR
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        // 1.2 idArg标签 - ResultFlag.CONSTRUCTOR + ResultFlag.ID
        flags.add(ResultFlag.ID);
      }
      // 2. 为<Constructor>标签的下的每一个<idArg>或者<arg>标签生成相应的ResultMapping并加入到resultMappings
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {

    // 1. 处理鉴别器中的 column/javaType/jdbcType/typeHandler属性
    // 鉴别器中的column指定使用哪一个返回的列作为值来判断走哪一个case
    String column = context.getStringAttribute("column");
    // 鉴别器中的javaType用来确保将指定的column转换为哪种java类型进行比较
    String javaType = context.getStringAttribute("javaType");
    // jdbcType可有可无
    String jdbcType = context.getStringAttribute("jdbcType");
    // TypeHandler可有可无
    String typeHandler = context.getStringAttribute("typeHandler");
    // 实际上: 可以仅指定javaType -- jdbcType和javaType仅仅用来确定TypeHandler的

    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    // 2. 处理鉴别器中的case标签
    for (XNode caseChild : context.getChildren()) {
      // 2.1 case标签的value值 -- 就是和上面的column取出来的值做比较
      String value = caseChild.getStringAttribute("value");
      // 2.2 case标签引用的resultMap -- id
      // 2.2.1 这里需要注意: 不一定需要指定引用的resultMap的id <case>标签下还可以继续使用id/result/association/Collection这些标签
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) {
    // 解析SQL标签

    // 1. 如果指定了databaseId,就需要对应的sql标签有相同的databaseId哦
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    // 2. 没有指定databaseId的sql标签,可以直接被加载
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) {

    // 1. 获取sql标签的databaseId属性以及id属性
    for (XNode context : list) {
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      // 2. id加上当前命名空间前缀
      id = builderAssistant.applyCurrentNamespace(id, false);
      // 3. sql标签的databaseId是否和要求的requiredDatabaseId兼容,如果满足就存储到sqlFragments中
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        sqlFragments.put(id, context);
      }
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {

    // 1. requiredDatabaseId不为空,就必须相等才兼容
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    // 2. requiredDatabaseId为空,但是标签上的databaseId不为空
    // 那么这个标签也是不符合的
    if (databaseId != null) {
      return false;
    }
    // 3. requiredDatabaseId/databaseId都为空,sqlFragments存储sql的片段中没有这个sql片段的id
    // 就返回true
    if (!this.sqlFragments.containsKey(id)) {
      return true;
    }
    // skip this fragment if there is a previous one with a not null databaseId
    // 4. 如果前一个片段的 databaseId 不为空，则跳过此片段
    XNode context = this.sqlFragments.get(id);
    return context.getStringAttribute("databaseId") == null;
  }

  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    // 构建结果集ResultMapping中的属性
    // context 可以是 <idArg>  <arg>   <result>  <id>  <collection>  <result>

    String property;
    // 1. <Constructor>下的<idArg>/<arg>是有name属性 -- 对应构造器的形参名
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      // 2. 而对于其他的 result标签/id标签/association标签/collection标签都是用property的 -- 对应对象的形参名
      property = context.getStringAttribute("property");
    }
    // 3. 通用的属性 column/javaType/jdbcType
    // 相应的列名/属性的javaType/列的jdbcType/
    // 注意:association元素的column属性的作用和result元素中的稍有不同，association元素的column属性可以是普通的列名称定义，比如column="id",也可以是一个复合的属性描述，比如:column="{prop1=col1,prop2=col2}"。
    // 复合属性描述的语法定义为：以{开始，}结尾，中间通过,分隔多个属性描述，每个属性描述均由行内参数映射名,=,列名称三部分构成。
    // 行内参数映射名对应的是select语句中的行内参数映射，列名称则对应着父对象中的数据列名称。
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");

    // association/collection元素提供了三种方式来描述如何处理一个子对象，他们分别是：
    // 嵌套查询语句 -- association/collection元素指定子对象的select语句,通过父select语句传递过来的column作为新的形参调用子对象的select创建度下令
    // 嵌套结果映射 -- association/collection元素指定子对象的resultMap,通过父select语句是联表的效果导致将一部分映射给子对象的resultMap中
    // 多结果集配置

    // <association>和<collection>独有的select属性 -- 嵌套的select属性
    String nestedSelect = context.getStringAttribute("select");
    // <association>和<collection>独有的resultMap属性 -- 嵌套的resultMap
    // 负责配置嵌套结果映射的是四个可选的属性resultMap,columnPrefix,notNullColumn以及autoMapping。
    String nestedResultMap = context.getStringAttribute("resultMap",
        processNestedResultMappings(context, Collections.emptyList(), resultType));
    // association元素还有一个可选的notNullColumn属性，
    // 默认情况下，只有在至少一个属性不为空的前提下才会创建子对象，
    // 但是我们可以通过notNullColumn属性来控制这一行为，notNullColumn属性的取值是以,分隔的多个属性名称，只有在这些属性均不为空的前提下，子对象才会被创建。
    String notNullColumn = context.getStringAttribute("notNullColumn");
    // 同名的列时,可以通过 columnPrefix 通过前缀区分出来
    // columnPrefix属性的值将会作用在被引用的resultMap配置上，在匹配其column属性时，会先添加统一的前缀，之后再进行匹配操作。
    String columnPrefix = context.getStringAttribute("columnPrefix");
    // 指定使用的鉴别器
    String typeHandler = context.getStringAttribute("typeHandler");
    // <association>与<collection> 的 resultSet
    // 用于描述多结果集的属性有三个,他们分别是column,foreignColumn以及resultSet.
    // 通常来讲,我们一次数据库操作只能得到一个ResultSet对象,但是部分数据库支持在一次查询中返回多个结果集.
    // 还有部分数据库支持在存储过程中返回多个结果集,或者支持一次性执行多个语句,每个语句都对应一个结果集
    // 在了解resultSet属性之前,我们需要简单补充一下select元素的resultSets属性相关的知识.
    // 默认情况下,一条select语句对应一个结果集,因此我们不需要关注结果集相关的问题.
    // 但是,通过实验,我们已经成功的在一条select语句中返回了多个结果集,如果我们想操作不同的结果集的数据,我们就有必要区分出每个结果集对象.
    // mybaits为这种场景提供了一个解决方案,它允许我们在配置select元素的时候,通过配置其resultSets属性来为每个结果集指定名称.
    // 结果集的名称和resultSets属性定义顺序对应.如果有多个结果集的名称需要配置,名称之间使用,进行分隔.
    //<resultMap id="userRoleWithResultSet" type="org.apache.learning.result_map.association.User" autoMapping="true">
    //    <association property="role" resultSet="roles" column="role_id" foreignColumn="id"
    //                    javaType="org.apache.learning.result_map.association.Role"/>
    //</resultMap>
    //
    //<select id="selectAllUserAndRole" resultSets="users,roles" resultMap="userRoleWithResultSet"
    //        statementType="CALLABLE">
    //    {call getAllUserAndRoles() }
    //</select>
    // association元素提供的resultSet属性读取的就是select标签中的resultSets属性定义的名称
    // 当前association元素[resultMap="userRoleWithResultSet"]将会使用resultSet属性对应的ResultSet对象来加载.
    String resultSet = context.getStringAttribute("resultSet");
    // <association>与<collection> 的 foreignColumn
    String foreignColumn = context.getStringAttribute("foreignColumn");
    // fetchType属性用于控制子对象的加载行为，他有lazy和eager两个取值，分别对应着懒加载和立即加载。
    // fetchType属性的优先级要高于配置全局懒加载的属性lazyLoadingEnabled,当指定了fetchType属性之后，lazyLoadingEnabled的配置将会被忽略。
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    // 4. 构建
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) throws Exception {
    //  如果嵌套有 association\collection\case 就有可能继续调用resultMapElement()方法
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        validateCollection(context, enclosingType);
        ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
        return resultMap.getId();
      }
    }
    return null;
  }

  protected void validateCollection(XNode context, Class<?> enclosingType) {
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
        && context.getStringAttribute("javaType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      String property = context.getStringAttribute("property");
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
          "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
      }
    }
  }

  private void bindMapperForNamespace() {
    // 为mapper.xml中的nameSpace绑定mapper

    // 1. 首先mapper.xml的namespace就是对应的mapper的class路径哦
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        // 2. 根据namespace即mapper的class转换
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
      }
      if (boundType != null) {
        if (!configuration.hasMapper(boundType)) {
          // 当 Configuration 还未拥有对应的Mapper接口的代理工厂 -- 因此我们设置一个标志以防止从映射器界面再次加载此资源查看
          // 即 MapperAnnotationBuilder#loadXmlResource() 有一句 if (!configuration.isResourceLoaded("namespace:" + type.getName())) 作为判断依据检查是否已经解析过Mapper.xml
          // 所以mapper.xml的namespace其实都是用"namespace:"作为前缀
          configuration.addLoadedResource("namespace:" + namespace); // 因此这是一种标记操作 -- 防止被MapperAnnotationBuilder再次解析对应的Mapper.xml
          // 添加到 MapperRegistry
          // boundType 是Mapper接口的Class
          // ❗️❗️❗️是无法实例化的,因此还需要借助 MapperProxyFactory 的代理功能能力来进行创建哦
          // 这里可不是简单的addMapper哦 -- 还是会根据给定boundType去创建对应的MapperProxyFactory哦
          // 而且还会对Mapper接口的注解进行解析哦 -- 即创建MapperAnnotationBuilder.parse()解析
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
