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
package org.apache.ibatis.builder.annotation;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {
  // Mapper接口的注解构建器
  // MapperAnnotationBuilder 不想 MapperXmlBuilder 中很早将开始解析
  // 而是在 MapperRegistry中addMapper()的时候会为其Mapper接口创建一个MapperAnnotationBuilder
  // 然后调用其parse()解析方法 -- 解析Mapper接口上的注解哦

  // 增删改查的注解
  private static final Set<Class<? extends Annotation>> SQL_ANNOTATION_TYPES = new HashSet<>();
  // 增删改查的Provider注解
  private static final Set<Class<? extends Annotation>> SQL_PROVIDER_ANNOTATION_TYPES = new HashSet<>();

  private final Configuration configuration;
  private final MapperBuilderAssistant assistant;
  private final Class<?> type;

  static {
    // 增删改查的注解
    SQL_ANNOTATION_TYPES.add(Select.class);
    SQL_ANNOTATION_TYPES.add(Insert.class);
    SQL_ANNOTATION_TYPES.add(Update.class);
    SQL_ANNOTATION_TYPES.add(Delete.class);

    // 增删改查的提供的注解
    SQL_PROVIDER_ANNOTATION_TYPES.add(SelectProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(InsertProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(UpdateProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(DeleteProvider.class);
  }

  public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
    // 资源名字改为 -- com/sdk/developer/UserMapper.java (best guess) -> 表示当前处理的是 Mapper接口资源
    // 这个 resource 并不是的 configuration.addLoadedResource(resource)
    String resource = type.getName().replace('.', '/') + ".java (best guess)";
    this.assistant = new MapperBuilderAssistant(configuration, resource); // 注意: 助理类中的resource为 com/sdk/developer/UserMapper.java (best guess)
    this.configuration = configuration;
    this.type = type;
  }

  public void parse() {
    // 唯一的调用处:
    // MapperRegistry.addMapper()
    // 用来解析 Mapper接口上的注解

    // 1. configuration中加载的mapper接口的resource名字为 type.toString() 的值 -> 表示Configuration已经解析过对应的Mapper接口的注解
    // 而不是上面MapperBuilderAssistant中的resource哦
    String resource = type.toString();
    if (!configuration.isResourceLoaded(resource)) {
      // 2. 先去加载Mapper接口对应的XmlResource
      loadXmlResource();
      // 3. 标注mapper接口的注解被解析 -- type.toString() 作为标志信息 -- 即Class com.sdk.developer.UserMapper
      configuration.addLoadedResource(resource);
      // 4. 设置当前命名空间
      assistant.setCurrentNamespace(type.getName());
      // 5. 开始解析类上的 @CacheNamespace 注解
      parseCache();
      // 6. 开始解析类上的 @CacheNamespaceRef  注解 -- CacheNamespaceRef会覆盖CacheNamespace
      parseCacheRef();
      Method[] methods = type.getMethods();
      for (Method method : methods) {
        try {
          // issue #237
          if (!method.isBridge()) {
            // 7. 开始解析mapper接口上的非桥接方法上的注解信息
            parseStatement(method);
          }
        } catch (IncompleteElementException e) {
          // 8. 捕获不完整元素异常,并将其注入到Configuration.incompleteMethods中
          // 并使用MethodResolver封装起来哦
          configuration.addIncompleteMethod(new MethodResolver(this, method));
        }
      }
    }
    parsePendingMethods();
  }

  private void parsePendingMethods() {
    Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
    synchronized (incompleteMethods) {
      Iterator<MethodResolver> iter = incompleteMethods.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // This method is still missing a resource
        }
      }
    }
  }

  private void loadXmlResource() {
    // Spring may not know the real resource name so we check a flag
    // to prevent loading again a resource twice
    // this flag is set at XMLMapperBuilder#bindMapperForNamespace

    //  1. 通过 "namespace:" + type.getName() 去Configuration中检查是否已经被加载过相应的XML文件
    // 在XMLMapperBuilder中解析完后,会将Configuration中的loadedResources添加 "namespace:" + type.getName()
    // 其中type.getName()就是mapper.xml中nameSpace - 也就是对应的mapper接口的type.getName() -> 防止重复解析哦
    if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
      // 2. 没有加载过对应的Mapper.xml文件的话 -- 转换为 xmlResource 比如 com.sdk.developer.UserMapper 转换为 com/sdk/developer/UserMapper.xml
      String xmlResource = type.getName().replace('.', '/') + ".xml";
      // 3. 获取Resource的输入流: /com/sdk/developer/UserMapper.xml
      InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
      if (inputStream == null) {
        // 3.1显而易见: 这要求mapper接口和mapper.xml必须在同一个package中
        // 如果没有mapper.xml就不需要去解析啦
        // Search XML mapper that is not in the module but in the classpath.
        try {
          // 3.2. 获取xml输入流
          inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
        } catch (IOException e2) {
          // ignore, resource is not required
        }
      }
      if (inputStream != null) {
        // 3.3 使用 XMLMapperBuilder进行构建解析
        XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
        xmlParser.parse();
      }
    }
  }

  private void parseCache() {

    // 1. 解析@CacheNamespace
    // 2. 包括属性size\flushInterval/properties
    CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
    if (cacheDomain != null) {
      Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
      Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
      Properties props = convertToProperties(cacheDomain.properties());
      // 3. 将创建的缓存cache添加到MapperBuilderAssistant中
      assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
    }
  }

  private Properties convertToProperties(Property[] properties) {
    if (properties.length == 0) {
      return null;
    }
    Properties props = new Properties();
    for (Property property : properties) {
      props.setProperty(property.name(),
          PropertyParser.parse(property.value(), configuration.getVariables()));
    }
    return props;
  }

  private void parseCacheRef() {
    // 解析 @CacheNamespaceRef 注解

    // 1. 获取@CacheNamespaceRef
    CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
    if (cacheDomainRef != null) {
      Class<?> refType = cacheDomainRef.value();
      String refName = cacheDomainRef.name();
      if (refType == void.class && refName.isEmpty()) {
        throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
      }
      if (refType != void.class && !refName.isEmpty()) {
        throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
      }
      String namespace = (refType != void.class) ? refType.getName() : refName;
      try {
        // 2. 获取引用的cache的命名空间
        // 因为Cache对象的id都是所处Mapper接口的命名空间中
        assistant.useCacheRef(namespace);
      } catch (IncompleteElementException e) {
        configuration.addIncompleteCacheRef(new CacheRefResolver(assistant, namespace));
      }
    }
  }

  private String parseResultMap(Method method) {
    // 没有@ResultMap注解,调用 parseResultMap(method) 解析该方法需要使用的ResultMap的id

    // 1. 获取返回类型/@ConstructorArgs/@Results/@TypeDiscriminator
    Class<?> returnType = getReturnType(method);
    ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
    Results results = method.getAnnotation(Results.class);
    TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
    // 2. 为指定的method生成resultMap的Name
    String resultMapId = generateResultMapName(method);
    applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results), typeDiscriminator);
    return resultMapId;
  }

  private String generateResultMapName(Method method) {
    // 为某个方法生成ResultMap的名字也就是id

    // 1. 使用 type.getName() + "." + @Results.id() 作为 ResultMap 的 name
    Results results = method.getAnnotation(Results.class);
    if (results != null && !results.id().isEmpty()) {
      return type.getName() + "." + results.id();
    }
    // 2.
    // 步骤1不满足时,使用下面的方式
    // type.getName() + "." + method.getName() + "-" + "形参名1" + "-" + " 形参名2" == 多个参数
    // type.getName() + "." + method.getName() + "-void" = 单个参数
    StringBuilder suffix = new StringBuilder();
    for (Class<?> c : method.getParameterTypes()) {
      suffix.append("-");
      suffix.append(c.getSimpleName());
    }
    if (suffix.length() < 1) {
      suffix.append("-void");
    }
    return type.getName() + "." + method.getName() + suffix;
  }

  private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
    List<ResultMapping> resultMappings = new ArrayList<>();
    // 1. 将@ConstructorArgs中的Arg[]适配为ResultMapping加入到resultMappings
    applyConstructorArgs(args, returnType, resultMappings);
    // 2. 将@Results中的Result[]适配为ResultMapping加入到resultMappings
    applyResults(results, returnType, resultMappings);
    Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
    // TODO add AutoMappingBehaviour
    assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
    // 3. 创建鉴别器
    createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
  }

  private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      for (Case c : discriminator.cases()) {
        String caseResultMapId = resultMapId + "-" + c.value();
        List<ResultMapping> resultMappings = new ArrayList<>();
        // issue #136
        applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
        applyResults(c.results(), resultType, resultMappings);
        // TODO add AutoMappingBehaviour
        assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
      }
    }
  }

  private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    if (discriminator != null) {
      String column = discriminator.column();
      Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
      JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
      Case[] cases = discriminator.cases();
      Map<String, String> discriminatorMap = new HashMap<>();
      for (Case c : cases) {
        String value = c.value();
        String caseResultMapId = resultMapId + "-" + value;
        discriminatorMap.put(value, caseResultMapId);
      }
      return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
    }
    return null;
  }

  void parseStatement(Method method) {
    // 1. 获取形参类型:
    // 接口方法只有单个有效形参为 - 形参的type
    // 接口方法有多个形参 - ParamMap
    Class<?> parameterTypeClass = getParameterType(method);
    // 2. 获取指定的LanguageDriver
    LanguageDriver languageDriver = getLanguageDriver(method);
    // 3. 使用languageDriver获取相应的SqlSource
    SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass, languageDriver);
    if (sqlSource != null) {
      // 4.1 有@Xxx和@XxxProvider的DML注解时,就可以查看看@Options注解的配置
      Options options = method.getAnnotation(Options.class);
      // 4.2 mappedStatement的Id就是 类名.方法名
      final String mappedStatementId = type.getName() + "." + method.getName(); // mappedStatement的Id
      Integer fetchSize = null; // 抓取大小为null -- 可被@Options指定给覆盖
      Integer timeout = null; // 超时时间为null -- 可被@Options指定给覆盖
      StatementType statementType = StatementType.PREPARED; // 默认为Prepared-- 可被@Options指定给覆盖
      ResultSetType resultSetType = configuration.getDefaultResultSetType(); // 默认的ResultSetType
      SqlCommandType sqlCommandType = getSqlCommandType(method); // method的类型属于增删改查哪一种
      boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
      boolean flushCache = !isSelect; // 非Select语句的执行 -- 默认会刷新缓存
      boolean useCache = isSelect; // select 语句默认使用缓存 -- 可被@Options指定给覆盖

      KeyGenerator keyGenerator;
      String keyProperty = null;
      String keyColumn = null;
      // 4.3
      // insert或update注解的语句上有使用@SelectKey语句 -> 就为这里@SelectKey注解生成KeyGenerator
      // insert或update注解的语句上没有使用@SelectKey语句并且没有@Options注解 -> 使用全局的配置吧
      // insert或update注解的语句上没有使用@SelectKey语句并但@Options注解 -> 根据@Options做判断
      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
        // first check for SelectKey annotation - that overrides everything else
        SelectKey selectKey = method.getAnnotation(SelectKey.class);
        if (selectKey != null) {
          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
          keyProperty = selectKey.keyProperty();
        } else if (options == null) {
          keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        } else {
          keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
          keyProperty = options.keyProperty();
          keyColumn = options.keyColumn();
        }
      } else {
        // 4.4 非insert或者update语句,是没有keyGenerator的
        keyGenerator = NoKeyGenerator.INSTANCE;
      }

      // 4.5 检查cache情况
      if (options != null) {
        if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
          flushCache = true;
        } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
          flushCache = false;
        }
        useCache = options.useCache();
        fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null; //issue #348
        timeout = options.timeout() > -1 ? options.timeout() : null;
        statementType = options.statementType();
        if (options.resultSetType() != ResultSetType.DEFAULT) {
          resultSetType = options.resultSetType();
        }
      }

      // 4.6 解析 @ResultMap 注解
      String resultMapId = null;
      ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
      if (resultMapAnnotation != null) {
        // 有@ResultMap注解,那么resultMapId就是该注解的value通过逗号分割开
        resultMapId = String.join(",", resultMapAnnotation.value());
      } else if (isSelect) {
        // 没有@ResultMap注解,调用 parseResultMap(method) 解析
        resultMapId = parseResultMap(method);
      }

      assistant.addMappedStatement(
          mappedStatementId,
          sqlSource,
          statementType,
          sqlCommandType,
          fetchSize,
          timeout,
          // ParameterMapID
          null,
          parameterTypeClass,
          resultMapId,
          getReturnType(method),
          resultSetType,
          flushCache,
          useCache,
          // TODO gcode issue #577
          false,
          keyGenerator,
          keyProperty,
          keyColumn,
          // DatabaseID
          null,
          languageDriver,
          // ResultSets
          options != null ? nullOrEmpty(options.resultSets()) : null);
    }
  }

  private LanguageDriver getLanguageDriver(Method method) {
    // 1. 使用@Land注解指定使用的LanguageDirver
    Lang lang = method.getAnnotation(Lang.class);
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = lang.value();
    }
    return configuration.getLanguageDriver(langClass);
  }

  private Class<?> getParameterType(Method method) {
    // 获取形参类型
    // 结果的如下:
    // 1. 忽略参数中的RowBounds和ResultHandler类型,如果是方法是单形参 -- 单个形参的type
    // 2. 否则方法就是多个形参,对应的就是 -- ParamMap
    Class<?> parameterType = null;
    Class<?>[] parameterTypes = method.getParameterTypes();
    for (Class<?> currentParameterType : parameterTypes) {
      if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class.isAssignableFrom(currentParameterType)) {
        if (parameterType == null) {
          parameterType = currentParameterType;
        } else {
          // issue #135
          parameterType = ParamMap.class;
        }
      }
    }
    return parameterType;
  }

  private Class<?> getReturnType(Method method) {
    Class<?> returnType = method.getReturnType();
    Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
    if (resolvedReturnType instanceof Class) {
      returnType = (Class<?>) resolvedReturnType;
      if (returnType.isArray()) {
        returnType = returnType.getComponentType();
      }
      // gcode issue #508
      if (void.class.equals(returnType)) {
        ResultType rt = method.getAnnotation(ResultType.class);
        if (rt != null) {
          returnType = rt.value();
        }
      }
    } else if (resolvedReturnType instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
      Class<?> rawType = (Class<?>) parameterizedType.getRawType();
      if (Collection.class.isAssignableFrom(rawType) || Cursor.class.isAssignableFrom(rawType)) {
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          Type returnTypeParameter = actualTypeArguments[0];
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue #443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          } else if (returnTypeParameter instanceof GenericArrayType) {
            Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
            // (gcode issue #525) support List<byte[]>
            returnType = Array.newInstance(componentType, 0).getClass();
          }
        }
      } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
        // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 2) {
          Type returnTypeParameter = actualTypeArguments[1];
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
          } else if (returnTypeParameter instanceof ParameterizedType) {
            // (gcode issue 443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          }
        }
      } else if (Optional.class.equals(rawType)) {
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        Type returnTypeParameter = actualTypeArguments[0];
        if (returnTypeParameter instanceof Class<?>) {
          returnType = (Class<?>) returnTypeParameter;
        }
      }
    }

    return returnType;
  }

  private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType, LanguageDriver languageDriver) {
    try {
      // 1. 检查是@Select/@Update/@Insert/@Delete注解中的哪一个
      Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
      // 2. 检查是@SelectProvider/@UpdateProvider/@InsertProvider/@DeleteProvider注解中的哪一个
      Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(method);
      // 3. Xxx和XxxProvider不可以同时使用
      if (sqlAnnotationType != null) {
        if (sqlProviderAnnotationType != null) {
          throw new BindingException("You cannot supply both a static SQL and SqlProvider to method named " + method.getName());
        }
        // 3.1 sqlAnnotationType存在就以其为准
        Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
        // 3.2 获取 @Select/@Update/@Delete/@Insert 注解的value值 -- 数组
        final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
        // 3.3 ❗️❗️❗️
        // 尝试构建SqlSource
        return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
      } else if (sqlProviderAnnotationType != null) {
        Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
        return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation, type, method);
      }
      return null;
    } catch (Exception e) {
      throw new BuilderException("Could not find value method on SQL annotation.  Cause: " + e, e);
    }
  }

  private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass, LanguageDriver languageDriver) {

    // 1. 开始构建SqlSource
    final StringBuilder sql = new StringBuilder();
    for (String fragment : strings) {
      sql.append(fragment);
      sql.append(" ");
    }
    // 2. 调用 LanguageDriver 的 createSqlSource(Configuration configuration, String script, Class<?> parameterType);
    // 注意: 这个是从注解中创建SqlSource的哦
    return languageDriver.createSqlSource(configuration, sql.toString().trim(), parameterTypeClass);
  }

  private SqlCommandType getSqlCommandType(Method method) {
    // 判断sql类型
    // 取决于使用的注解 @Select/@Update/@Insert/@Delete 或 @SelectProvider/@UpdateProvider/@InsertProvider/@DeleteProvider
    Class<? extends Annotation> type = getSqlAnnotationType(method);

    if (type == null) {
      type = getSqlProviderAnnotationType(method);

      if (type == null) {
        return SqlCommandType.UNKNOWN;
      }

      if (type == SelectProvider.class) {
        type = Select.class;
      } else if (type == InsertProvider.class) {
        type = Insert.class;
      } else if (type == UpdateProvider.class) {
        type = Update.class;
      } else if (type == DeleteProvider.class) {
        type = Delete.class;
      }
    }

    return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
  }

  private Class<? extends Annotation> getSqlAnnotationType(Method method) {
    return chooseAnnotationType(method, SQL_ANNOTATION_TYPES);
  }

  private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
    return chooseAnnotationType(method, SQL_PROVIDER_ANNOTATION_TYPES);
  }

  private Class<? extends Annotation> chooseAnnotationType(Method method, Set<Class<? extends Annotation>> types) {
    for (Class<? extends Annotation> type : types) {
      Annotation annotation = method.getAnnotation(type);
      if (annotation != null) {
        return type;
      }
    }
    return null;
  }

  private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
    for (Result result : results) {
      List<ResultFlag> flags = new ArrayList<>();
      if (result.id()) {
        flags.add(ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              ((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(result.property()),
          nullOrEmpty(result.column()),
          result.javaType() == void.class ? null : result.javaType(),
          result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
          hasNestedSelect(result) ? nestedSelectId(result) : null,
          null,
          null,
          null,
          typeHandler,
          flags,
          null,
          null,
          isLazy(result));
      resultMappings.add(resultMapping);
    }
  }

  private String nestedSelectId(Result result) {
    String nestedSelect = result.one().select();
    if (nestedSelect.length() < 1) {
      nestedSelect = result.many().select();
    }
    if (!nestedSelect.contains(".")) {
      nestedSelect = type.getName() + "." + nestedSelect;
    }
    return nestedSelect;
  }

  private boolean isLazy(Result result) {
    boolean isLazy = configuration.isLazyLoadingEnabled();
    if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
      isLazy = result.one().fetchType() == FetchType.LAZY;
    } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
      isLazy = result.many().fetchType() == FetchType.LAZY;
    }
    return isLazy;
  }

  private boolean hasNestedSelect(Result result) {
    if (result.one().select().length() > 0 && result.many().select().length() > 0) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    return result.one().select().length() > 0 || result.many().select().length() > 0;
  }

  private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
    // 遍历处理@Arg[]数组
    for (Arg arg : args) {
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if (arg.id()) {
        // 标识为id
        flags.add(ResultFlag.ID);
      }
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(arg.name()),
          nullOrEmpty(arg.column()),
          arg.javaType() == void.class ? null : arg.javaType(),
          arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
          nullOrEmpty(arg.select()),
          nullOrEmpty(arg.resultMap()),
          null,
          nullOrEmpty(arg.columnPrefix()),
          typeHandler,
          flags,
          null,
          null,
          false);
      resultMappings.add(resultMapping);
    }
  }

  private String nullOrEmpty(String value) {
    return value == null || value.trim().length() == 0 ? null : value;
  }

  private Result[] resultsIf(Results results) {
    // 如果有@Results的value()就返回
    return results == null ? new Result[0] : results.value();
  }

  private Arg[] argsIf(ConstructorArgs args) {
    // 如果有@ConstructorArgs的value()就返回
    return args == null ? new Arg[0] : args.value();
  }

  private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    Class<?> resultTypeClass = selectKeyAnnotation.resultType();
    StatementType statementType = selectKeyAnnotation.statementType();
    String keyProperty = selectKeyAnnotation.keyProperty();
    String keyColumn = selectKeyAnnotation.keyColumn();
    boolean executeBefore = selectKeyAnnotation.before();

    // defaults
    boolean useCache = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass, languageDriver);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
        flushCache, useCache, false,
        keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

    id = assistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
    configuration.addKeyGenerator(id, answer);
    return answer;
  }

}
