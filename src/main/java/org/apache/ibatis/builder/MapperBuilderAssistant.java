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

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {
  // MapperBuilder的辅助类 MapperBuilderAssistant
  // MapperBuilder -- 可以是XMLMapperBuilder也可以是MapperAnnotationBuilder

  // mapper 标签中的 nameSpace 属性
  private String currentNamespace; // 当前mapper.xml的命名空间
  // 当前的mapper.xml -- com/sdk/developer/UserMapper.xml
  // 当前的mapper的注解 -- com/sdk/developer/UserMapper.java (bese guess)
  private final String resource;
  private Cache currentCache; // 当前使用的cache
  private boolean unresolvedCacheRef; // issue #676

  public MapperBuilderAssistant(Configuration configuration, String resource) {
    super(configuration);
    ErrorContext.instance().resource(resource);
    this.resource = resource;
  }

  public String getCurrentNamespace() {
    return currentNamespace;
  }

  public void setCurrentNamespace(String currentNamespace) {
    if (currentNamespace == null) {
      throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
    }

    if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
      throw new BuilderException("Wrong namespace. Expected '"
          + this.currentNamespace + "' but found '" + currentNamespace + "'.");
    }

    this.currentNamespace = currentNamespace;
  }

  public String applyCurrentNamespace(String base, boolean isReference) {
    if (base == null) {
      return null;
    }
    if (isReference) {
      // is it qualified with any namespace yet?
      if (base.contains(".")) {
        return base;
      }
    } else {
      // is it qualified with this namespace yet?
      if (base.startsWith(currentNamespace + ".")) {
        return base;
      }
      if (base.contains(".")) {
        throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
      }
    }

    // 目的就是 -- 对于在当前mapper.xml引用当前命名空间的id时不需要使用当前的命名空间
    // 这里会帮助添加当前命名空间的
    return currentNamespace + "." + base;
  }

  public Cache useCacheRef(String namespace) {
    // 解析cache-ref标签中的namespace
    // 或解析@CacheNamespaceRef标签的推出的命名空间

    if (namespace == null) {
      throw new BuilderException("cache-ref element requires a namespace attribute.");
    }
    try {
      // 1. 从 configuration 获取对应nameSpace的cache对象
      unresolvedCacheRef = true;
      Cache cache = configuration.getCache(namespace);
      if (cache == null) {
        // ❗️❗️❗️
        // 如果 <cacheRef> 标签引用的指定命名空间的cache不存在,说明还没有加载
        // 先抛出IncompleteElementException异常,然后被 XMLMapperBuilder.cacheRefElement() 解析中捕获到
        // 然后加入到 configuration.addIncompleteCacheRef(cacheRefResolver);
        // 待后续解析完毕,调用 XMLMapperBuilder.parse()-> parsePendingCacheRefs() 来完成这里未完成的解析器
        throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
      }
      // 2. 向 MapperBuilderAssistant 设置 currentCache
      currentCache = cache;
      unresolvedCacheRef = false;
      return cache;
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
    }
  }

  public Cache useNewCache(Class<? extends Cache> typeClass,
      Class<? extends Cache> evictionClass,
      Long flushInterval,
      Integer size,
      boolean readWrite,
      boolean blocking,
      Properties props) {
    // 1. 构建 CacheBuilder.build()
    Cache cache = new CacheBuilder(currentNamespace) // id就是当前命名空间
        .implementation(valueOrDefault(typeClass, PerpetualCache.class))
        .addDecorator(valueOrDefault(evictionClass, LruCache.class))
        .clearInterval(flushInterval)
        .size(size)
        .readWrite(readWrite)
        .blocking(blocking)
        .properties(props)
        .build();
    // 2. cache添加到Configuration的caches中
    configuration.addCache(cache);
    // 3. 设置到Assistant上
    currentCache = cache;
    return cache;
  }

  public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
    id = applyCurrentNamespace(id, false);
    ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
    configuration.addParameterMap(parameterMap);
    return parameterMap;
  }

  public ParameterMapping buildParameterMapping(
      Class<?> parameterType,
      String property,
      Class<?> javaType,
      JdbcType jdbcType,
      String resultMap,
      ParameterMode parameterMode,
      Class<? extends TypeHandler<?>> typeHandler,
      Integer numericScale) {
    resultMap = applyCurrentNamespace(resultMap, true);

    // Class parameterType = parameterMapBuilder.type();
    Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

    return new ParameterMapping.Builder(configuration, property, javaTypeClass)
        .jdbcType(jdbcType)
        .resultMapId(resultMap)
        .mode(parameterMode)
        .numericScale(numericScale)
        .typeHandler(typeHandlerInstance)
        .build();
  }

  public ResultMap addResultMap(
      String id, // resultMap标签的id
      Class<?> type,  // resultMap标签的归属java类型
      String extend,  // resultMap标签继承自哪一个resultMap
      Discriminator discriminator,  // resultMap标签中的鉴别器
      List<ResultMapping> resultMappings, // resultMap标签中的子标签例如id/result/idArg/arg/collection/association标签构成的ResultMapping的集合
      Boolean autoMapping) {  // resultMap标签的自动映射属性
    // 添加一个ResultMap

    // 1. 处理当前ResultMap的id,给其加上当前命名空间作为前缀
    id = applyCurrentNamespace(id, false);
    // 2. 处理 extends 若为当前Mapper的resultMap,就需要加上当前命名空间前缀
    extend = applyCurrentNamespace(extend, true);

    // 3. 有扩展extend的父的resultMap的id
    // 但是父ResultMap的id不存在Configuration中
    // 抛出IncompleteElementException后会被加入到 configuration.addIncompleteResultMap(resultMapResolver)
    // 后续又会在  XMLMapperBuild.parsePendingResultMaps()中的 从 configuration.getIncompleteResultMaps() 获取这里未完成的继续尝试执行解析哦
    if (extend != null) {
      if (!configuration.hasResultMap(extend)) {
        throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
      }
      // 3.1 将继承的resultMap的中的ResultMappings取出来,剔除当前有的resultMappings,做一个覆盖潮州
      // 需要认识到: ResultMapping就相当于是ResultMap标签下的每个子标签映射的结果
      ResultMap resultMap = configuration.getResultMap(extend);
      List<ResultMapping> extendedResultMappings = new ArrayList<>(resultMap.getResultMappings());
      extendedResultMappings.removeAll(resultMappings);
      // Remove parent constructor if this resultMap declares a constructor.
      boolean declaresConstructor = false;
      // 3.2 当前的resultMap如果有使用到构造器标签注入,那么extend的resultMap的所有构造器标签会失效
      for (ResultMapping resultMapping : resultMappings) {
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          declaresConstructor = true;
          break;
        }
      }
      if (declaresConstructor) {
        extendedResultMappings.removeIf(resultMapping -> resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR));
      }
      resultMappings.addAll(extendedResultMappings);
    }
    // 4. 构建ResultMap
    ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
        .discriminator(discriminator)
        .build();
    // 5. 最终 -- 添加到ResultMap总共
    configuration.addResultMap(resultMap);
    return resultMap;
  }

  public Discriminator buildDiscriminator(
      Class<?> resultType,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      Class<? extends TypeHandler<?>> typeHandler,
      Map<String, String> discriminatorMap) {
    // 构建鉴别器:
    ResultMapping resultMapping = buildResultMapping(
        resultType,
        null,
        column,
        javaType,
        jdbcType,
        null,
        null,
        null,
        null,
        typeHandler,
        new ArrayList<>(),
        null,
        null,
        false);
    // case标签的value为key,case标签的resultMap为value
    // 存入到namespaceDiscriminatorMap中
    Map<String, String> namespaceDiscriminatorMap = new HashMap<>();
    for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
      String resultMap = e.getValue();
      resultMap = applyCurrentNamespace(resultMap, true); // 根据情况添加 当前命名空间 作为前缀
      namespaceDiscriminatorMap.put(e.getKey(), resultMap);
    }
    // resultMapping 是 <Discriminator> 标签中的属性构成的
    // namespaceDiscriminatorMap 是 <Discriminator>的只标签<case>构成的Map结构
    return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
  }

  public MappedStatement addMappedStatement(
      String id,
      SqlSource sqlSource,
      StatementType statementType,
      SqlCommandType sqlCommandType,
      Integer fetchSize,
      Integer timeout,
      String parameterMap,
      Class<?> parameterType,
      String resultMap,
      Class<?> resultType,
      ResultSetType resultSetType,
      boolean flushCache,
      boolean useCache,
      boolean resultOrdered,
      KeyGenerator keyGenerator,
      String keyProperty,
      String keyColumn,
      String databaseId,
      LanguageDriver lang,
      String resultSets) {
    // 构建 MappedStatement 加入到 Configuration 中

    if (unresolvedCacheRef) {
      throw new IncompleteElementException("Cache-ref not yet resolved");
    }

    // 1. 给id适配当前命名空间
    id = applyCurrentNamespace(id, false);
    // 2. 是否select语句
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

    // 3. 借助 MappedStatement的builder
    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
        .resource(resource)
        .fetchSize(fetchSize)
        .timeout(timeout)
        .statementType(statementType)
        .keyGenerator(keyGenerator) // 用户可以在
        .keyProperty(keyProperty)
        .keyColumn(keyColumn)
        .databaseId(databaseId)
        .lang(lang)
        .resultOrdered(resultOrdered)
        .resultSets(resultSets)
        .resultMaps(getStatementResultMaps(resultMap, resultType, id)) // 当前的MappedStatement需要的根据resultMap的id或者resultType找到对应的ResultMap集合哦
        .resultSetType(resultSetType)
        .flushCacheRequired(valueOrDefault(flushCache, !isSelect)) // 非select语句会导致刷新
        .useCache(valueOrDefault(useCache, isSelect))
        .cache(currentCache);

    // note: new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType) 中会默认创建一个ParameterMap对象使用
    // 4. 当实际有使用<parameterMap>标签或者DML标签的parameterType属性 -- 就需哟啊构建新ParameterMap -- 替换前面默认的ParameterMap
    ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
    if (statementParameterMap != null) {
      statementBuilder.parameterMap(statementParameterMap);
    }

    MappedStatement statement = statementBuilder.build();
    configuration.addMappedStatement(statement);
    return statement;
  }

  private <T> T valueOrDefault(T value, T defaultValue) {
    // value or defaultValue
    return value == null ? defaultValue : value;
  }

  private ParameterMap getStatementParameterMap(
      String parameterMapName,
      Class<?> parameterTypeClass,
      String statementId) {

    // 1. parameterMapName适配命名空间后的id
    parameterMapName = applyCurrentNamespace(parameterMapName, true);
    ParameterMap parameterMap = null;

    // note: <parameterMap>标签 与 parameterType属性 的使用只能二选一
    // 两个同时存在时 -- parameterMapName 是有效的

    // 2.1 使用 <parameterMap> 标签
    if (parameterMapName != null) {
      try {
        // 2.1.1 直接从Configuration中获取 -- 因为在解析DML标签之前已经解析过<paramMap>标签
        parameterMap = configuration.getParameterMap(parameterMapName);
      } catch (IllegalArgumentException e) {
        throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
      }
    }
    // 2.2 DML标签中使用的 parameterType 属性
    else if (parameterTypeClass != null) {
      // 2.2.1 注意它的id是: 所属MDL标签的id + "-Inline"
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      parameterMap = new ParameterMap.Builder(
          configuration,
          statementId + "-Inline",
          parameterTypeClass,
          parameterMappings).build();
    }
    // 2.1 和 2.2 都没有使用到时,返回的就是null哦
    return parameterMap;
  }

  private List<ResultMap> getStatementResultMaps(
      String resultMap,
      Class<?> resultType,
      String statementId) {
    // ❗️❗️❗️❗️❗️❗️
    // DML标签中的resultMap属性指定的resultMap的id 可以用逗号分割开来
    // resultType 属性指定的返回的class
    // statementId 是MappedStatement的id值

    // 1. resultMap适配命名空间的id
    resultMap = applyCurrentNamespace(resultMap, true);

    List<ResultMap> resultMaps = new ArrayList<>();

    // 2.1 使用的 resultMap 属性
    if (resultMap != null) {
      // 2.1.1 从这里可以看出来 -- resultMap 竟然是可以使用多个引用,并用逗号分割开来 ❗️❗️❗️
      String[] resultMapNames = resultMap.split(",");
      for (String resultMapName : resultMapNames) {
        try {
          // 2.1.2 直接根据 resultMap属性的值 去 configuration 中查找对应的 ResultMap
          // 实际上: Mapper.xml中的ResultMap早就在XMLMapperBuilder.parse() ->
          resultMaps.add(configuration.getResultMap(resultMapName.trim()));
        } catch (IllegalArgumentException e) {
          throw new IncompleteElementException("Could not find result map '" + resultMapName + "' referenced from '" + statementId + "'", e);
        }
      }
    }
    // 2.2 使用的是 resultType 属性
    else if (resultType != null) {
      // 2.2.1 resultType - 会去构建内联的ResultMap
      // ❗️❗️❗️
      // 内联的情况下: 需要注意id\以及autoMapping为null\List<ResultMapping>为new ArrayList<>()
      ResultMap inlineResultMap = new ResultMap.Builder(
          configuration,
          statementId + "-Inline", // 内联的
          resultType,
          new ArrayList<>(),
          null).build();
      resultMaps.add(inlineResultMap);
    }
    return resultMaps;
  }

  public ResultMapping buildResultMapping(
      Class<?> resultType,
      String property,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      String nestedSelect,
      String nestedResultMap,
      String notNullColumn,
      String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler,
      List<ResultFlag> flags,
      String resultSet,
      String foreignColumn,
      boolean lazy) {
    // 构建 ResultMapping 类
    // 关于ResultMapping总共的属性都已经被读取出来啦

    Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
    // 1. 实例化TypeHandler
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
    // 2. 额外注册 ResultMapping的composites 属性
    List<ResultMapping> composites;
    if ((nestedSelect == null || nestedSelect.isEmpty()) && (foreignColumn == null || foreignColumn.isEmpty())) {
      // 2.1 如果 没有嵌套的select,并且没有外键列,composites就为空
      composites = Collections.emptyList();
    } else {
      // 2.2 解析Composite
      // ❗️❗️❗️
      // 用于在<collection>和<association>标签中有select属性,同时又column属性
      // 此刻column={column1=param1,column2=param2}表示将父查询的结果总共你的column1和column2的值传递此处selet属性引用的方法的形参
      // 解析结果就是: List<ResultMapping> -- 其中每个ResultMapping都代表一个column1=param1
      composites = parseCompositeColumnName(column);
    }
    // 构建并返回ResultMapping
    return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
        .jdbcType(jdbcType)
        .nestedQueryId(applyCurrentNamespace(nestedSelect, true))
        .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true))
        .resultSet(resultSet)
        .typeHandler(typeHandlerInstance)
        .flags(flags == null ? new ArrayList<>() : flags)
        .composites(composites)
        .notNullColumns(parseMultipleColumnNames(notNullColumn))
        .columnPrefix(columnPrefix)
        .foreignColumn(foreignColumn)
        .lazy(lazy)
        .build();
  }

  private Set<String> parseMultipleColumnNames(String columnName) {
    // 将columnName解析出来 -- 通过逗号分割开来

    Set<String> columns = new HashSet<>();
    if (columnName != null) {
      if (columnName.indexOf(',') > -1) {
        StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
        while (parser.hasMoreTokens()) {
          String column = parser.nextToken();
          columns.add(column);
        }
      } else {
        columns.add(columnName);
      }
    }
    return columns;
  }

  private List<ResultMapping> parseCompositeColumnName(String columnName) {
    // 解析column,因为在 collection或association标签中 -- 可以通过select属性获取关联的对象
    // 而 select 属性指定的 查询方法的入参是需要 column 属性来指定的
    // column的形式可以是: column1=param1 即 查询出的结果的某个列column1作为分布查询即select指定的方法的入参param1

    List<ResultMapping> composites = new ArrayList<>();
    if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
      // 指定分隔符 {}=,
      // 传递到分步查询的列名结果，如果是多列则按格式 {列名1=分步查询的形参名1,列名2=分步查询的形参名2} ,该属性用于分步查询。
      //
      StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
      while (parser.hasMoreTokens()) {
        // 属性
        String property = parser.nextToken();
        // 列
        String column = parser.nextToken();
        // 组装为 ResultMapping
        ResultMapping complexResultMapping = new ResultMapping.Builder(
            configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
        composites.add(complexResultMapping);
      }
    }
    // 返回 composites
    return composites;
  }

  private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
    // 乜有指定JavaType 且 property 非空
    if (javaType == null && property != null) {
      try {
        //  就需要从 resultType 判断 指定 property 的javaType啦
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        // 指定的resultType必须有对应的 setXxx方法 用于 property -- 其返回值就是这里的JavaType
        javaType = metaResultType.getSetterType(property);
      } catch (Exception e) {
        //ignore, following null check statement will deal with the situation
      }
    }
    // 否则就是Object类型的哦
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

  private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
    if (javaType == null) {
      if (JdbcType.CURSOR.equals(jdbcType)) {
        javaType = java.sql.ResultSet.class;
      } else if (Map.class.isAssignableFrom(resultType)) {
        javaType = Object.class;
      } else {
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getGetterType(property);
      }
    }
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

  /** Backward compatibility signature. */
  public ResultMapping buildResultMapping(Class<?> resultType, String property, String column, Class<?> javaType,
      JdbcType jdbcType, String nestedSelect, String nestedResultMap, String notNullColumn, String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler, List<ResultFlag> flags) {
    return buildResultMapping(
      resultType, property, column, javaType, jdbcType, nestedSelect,
      nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
  }

  /**
   * @deprecated Use {@link Configuration#getLanguageDriver(Class)}
   */
  @Deprecated
  public LanguageDriver getLanguageDriver(Class<? extends LanguageDriver> langClass) {
    return configuration.getLanguageDriver(langClass);
  }

  /** Backward compatibility signature. */
  public MappedStatement addMappedStatement(String id, SqlSource sqlSource, StatementType statementType,
      SqlCommandType sqlCommandType, Integer fetchSize, Integer timeout, String parameterMap, Class<?> parameterType,
      String resultMap, Class<?> resultType, ResultSetType resultSetType, boolean flushCache, boolean useCache,
      boolean resultOrdered, KeyGenerator keyGenerator, String keyProperty, String keyColumn, String databaseId,
      LanguageDriver lang) {
    return addMappedStatement(
      id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
      parameterMap, parameterType, resultMap, resultType, resultSetType,
      flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
      keyColumn, databaseId, lang, null);
  }

}
