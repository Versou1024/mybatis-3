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
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed; // 是否已经完成解析
  private final XPathParser parser; // 解析器 -- 和XML有关
  private String environment; // 当前默认的数据库的环境 dev/qa/prd 这种
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory(); // 反射工厂

  // XMLConfigBuilder是对XML格式的Mybatis.xml进行解析
  // 传递进来的Reader/InputStream都是针对Mybatis.xml

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // ❗️❗️❗️ 创建了一个新的Mapper配置
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 向Mapper配置中设置变量来源
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    // 1. 不允许重复解析
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 2. 正式解析 -- 核心
    parseConfiguration(parser.evalNode("/configuration")); // 根节点 -- configuration
    return configuration;
  }

  // XNode 就是 XML 中的一个节点
  private void parseConfiguration(XNode root) {
    // ❗️❗️❗️

    try {
      // 1. properties 节点解析
      propertiesElement(root.evalNode("properties"));
      // 2. settings 节点解析
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings);
      loadCustomLogImpl(settings);
      // 3. typeAliases 节点解析
      typeAliasesElement(root.evalNode("typeAliases"));
      // 4. plugins 节点解析
      pluginElement(root.evalNode("plugins"));
      // 5. objectFactory
      objectFactoryElement(root.evalNode("objectFactory"));
      // 6. objectWrapperFactory
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 7. reflectorFactory
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      // 8. environments -- 包括jdbcClass\url\username\password等等信息哦
      environmentsElement(root.evalNode("environments"));
      // 9. databaseIdProvider
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 10. typeHandlers
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 11. mappers -- 重要的哦 ❗️❗️❗️
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    // 获取setting标签中的logImpl对应别名的logImpl的class对象
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    // 设置到Configuration中
    configuration.setLogImpl(logImpl);
  }

  private void typeAliasesElement(XNode parent) {
    // 解析 <typeAliases> 标签

    if (parent != null) {
      // <typeAliases> 标签 有 <package> 和 <typeAlias> 两种子标签
      for (XNode child : parent.getChildren()) {
        // 1. 使用 <package> 标签注册别名
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          // 对指定 package 下的所有的类型注册别名
          // 底层都调用 --  typeAliasRegistry.registerAlias(clazz); 进行注册哦
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        }
        // 2. 使用 <typeAlias> 标签注册别名
        else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              // 2.1 未指定别名时,可以使用@Alias在类上标注别名,或者使用clazz的getSimpleName()
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    // 解析 plugins 标签中的 interceptor 属性
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 1. 用户自定义的 interceptor
        String interceptor = child.getStringAttribute("interceptor");
        // 1.1 <plugin>标签中的子标签<property>作为properties返回
        Properties properties = child.getChildrenAsProperties();
        // 2. 向 Interceptor 中设置属性 -- 反射
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        // 3. 将 interceptorInstance 添加到 configuration上
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    // 解析<objectFactory>标签

    if (context != null) {
      // 1. 获取<objectFactory>标签的type属性
      String type = context.getStringAttribute("type");
      // 2.  获取<objectFactory>标签的子标签<property>解析为properties
      Properties properties = context.getChildrenAsProperties();
      // 3. 实例化type,并且将properties设置进去
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    // 解析<objectWrapperFactory>标签

    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    // 解析<reflectorFactory>标签

    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    // 解析 mybatis.xml 中的 properties 标签

    // 1. 开始解析
    if (context != null) {
      // 2. 子标签 <Properties> 中的 name和value属性作为 Properties 写到 defaults 中
      Properties defaults = context.getChildrenAsProperties();
      // 2. <properties>标签的至少存在一个 resource 或 url 属性值
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 3. 合并到 configuration 中已有的 properties 的 variables中
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      parser.setVariables(defaults);
      // 4. 存入全局变量中
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) {
    // 1. 对<settings>标签进行解析

    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    // 默认使用缓存,用户可以在setting标签中通过cacheEnabled更改
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    // 默认的全局的useGeneratedKeys是false的
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    // 默认的使用ExecutorType.SIMPLE,用户可以在setting标签中通过defaultExecutorType更改
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    // 全局配置的默认Statement执行的超时时间 -- 默认为null值
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    // 全局配置 -- 日志前缀,在没有配置的情况下日志前缀为null
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  private void environmentsElement(XNode context) throws Exception {
    // context 是 Mybatis.xml 中的 <environments> 镖旗
    if (context != null) {
      // 1. 用户没有在SqlSessionFactoryBuilder中指定需要使用的environment时,默认使用mybatis.xml中配置的default的environment
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      // 2. 每个environment都有一个id,比如一般都是dev/qa/pre/prd等等
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        if (isSpecifiedEnvironment(id)) {
          // 3. 找到目标的environment -- 开始创建Configuration中的environment

          // 3.1 tx工厂 -- tx 获取连接/提交/回滚/超时时间
          //    typeAliasRegistry.registerAlias("JDBC", JdbcTransactionFactory.class);
          //    typeAliasRegistry.registerAlias("MANAGED", ManagedTransactionFactory.class);
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 3.2 数据源工厂 -- 数据源 url/password/username
          // 数据源工厂支持以下几种:
          //    typeAliasRegistry.registerAlias("JNDI", JndiDataSourceFactory.class);
          //    typeAliasRegistry.registerAlias("POOLED", PooledDataSourceFactory.class);
          //    typeAliasRegistry.registerAlias("UNPOOLED", UnpooledDataSourceFactory.class);
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // 3.3 构建 Environment -- DataSource\TransactionFactory
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    // 配置 databaseIdProvider类型

    // 1. 创建 DatabaseIdProvider
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      // 1.1 databaseIdProvider 标签的 type 属性
      // 指定需要使用的 databaseIdProvider
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      // 保持向后兼容性的糟糕补丁
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      // 3. 解析出对应的DatabaseIdProvider,将properties设置进去
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    // 4. 使用databaseIdProvider获取databaseId
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    // 必须在 mybatis.xml 中配置 Environment 时指定 transactionManager

    // 1. 解析transactionManager标签中type值 -- 可以为 JDBC/MANAGED
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // 2. 通过alias别名解析为TransactionFactory
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      // 3. 设置属性
      factory.setProperties(props);     // 可以忽略不计哦,因为默认都是空实现的
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    // 根据 mybatis.xml 中的 Configuration->environments->environment->dataSource 标签

    if (context != null) {
      // 1. 获取dataSource的type属性
      // 数据源工厂支持以下几种:
      //    typeAliasRegistry.registerAlias("JNDI", JndiDataSourceFactory.class);
      //    typeAliasRegistry.registerAlias("POOLED", PooledDataSourceFactory.class);
      //    typeAliasRegistry.registerAlias("UNPOOLED", UnpooledDataSourceFactory.class);
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // 2. 解析为 DataSourceFactory
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      // 3. 设置属性上去哦 -- 比如设置 url/password/username
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 1. 是否以包扫描的方式 -- 查找Mapper,即 package 标签
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          // 2. 不以包扫描的方式
          // 获取mapper标签的resource\url\class三个属性 -- 三个里面只能存在一个属性哦
          String resource = child.getStringAttribute("resource"); // mapper.xml的位置
          String url = child.getStringAttribute("url"); //
          String mapperClass = child.getStringAttribute("class"); // mapper的Class
          if (resource != null && url == null && mapperClass == null) {
            // 2.1 99%的情况,只允许指定resource接口
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            // 2.1 转为 XMLMapperBuilder 即可,需要和 XMLConfigBuild 分开
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            // 2.1 开始解析
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    // 当前正在解析的environment是否就是目的environment,不是的话的就返回false,就不需要继续解析啦
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
