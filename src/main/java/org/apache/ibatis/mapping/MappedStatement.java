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
package org.apache.ibatis.mapping;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 */
public final class MappedStatement {
  // 对应 Select/Update/Insert/Delete/selectKey 标签的语句MappedStatement
  // 也可以构建 selectKey标签 中的可执行SQL语句

  private String resource; // 对应的Resource
  private Configuration configuration; // 唯一的配置中心
  private String id; // 该statement的id
  private Integer fetchSize; // select的结果集大小
  private Integer timeout; // 超时时间
  private StatementType statementType; // 使用的Statement类型 -- STATEMENT, PREPARED, CALLABLE
  private ResultSetType resultSetType; // ResultSet的使用类型 -- 用于控制jdbc中ResultSet对象的取值行为
  private SqlSource sqlSource; // ❗️❗️❗️
  private Cache cache; // 当前MappedStatement使用的缓存 -- 比如刷新\获取值\数据库执行后回填值等操作都在这个Cache中完成篇
  private ParameterMap parameterMap; // 忽略不计
  private List<ResultMap> resultMaps; // mapper中对应的ResultMap引用的ResultMap的id/ResultType标签-内联的ResultMap的结果
  private boolean flushCacheRequired; // 当前MappedStatement -- 是否刷新二级缓存和一级缓存
  private boolean useCache; // 当前MappedStatement -- 是否使用二级缓存
  private boolean resultOrdered;
  private SqlCommandType sqlCommandType; // 当前statement的类型:  UNKNOWN, INSERT, UPDATE, DELETE, SELECT, FLUSH 之一
  private KeyGenerator keyGenerator; // key 生成器 -- 可以搭配在 selectKey 标签使用 / 也可以是insert/update语句默认使用的Jdbc3KeyGenerator
  private String[] keyProperties; // keyGenerator --
  private String[] keyColumns;    // keyGenerator --
  private boolean hasNestedResultMaps;
  private String databaseId; // 数据库id
  private Log statementLog; // 日志
  private LanguageDriver lang; // 忽略
  private String[] resultSets; // 多数据集结果,即返回多个ResultSet一般很少使用

  MappedStatement() {
    // constructor disabled
  }

  public static class Builder {
    private MappedStatement mappedStatement = new MappedStatement();

    public Builder(Configuration configuration, String id, SqlSource sqlSource, SqlCommandType sqlCommandType) {
      mappedStatement.configuration = configuration;
      mappedStatement.id = id;
      mappedStatement.sqlSource = sqlSource;
      mappedStatement.statementType = StatementType.PREPARED;
      mappedStatement.resultSetType = ResultSetType.DEFAULT;
      // parameterMap每次都会被创建 -- 因此即使没有使用<parameterType>标签也会有对应的ParameterMap对象即 id = "defaultParameterMap"
      // 如果有<parameterType>标签可通过Builder.parameterMap(ParameterMap parameterMap)重写
      mappedStatement.parameterMap = new ParameterMap.Builder(configuration, "defaultParameterMap", null, new ArrayList<>()).build();
      mappedStatement.resultMaps = new ArrayList<>();
      mappedStatement.sqlCommandType = sqlCommandType;
      // 1. mybatis.xml中useGeneratedKeys为false -- 因此会使用 NoKeyGenerator
      // 否则开启全局的useGeneratedKeys并且当前的MappedStatement为Insert类型,就会创建Jdbc3KeyGenerator
      // 2. 其次在当前builder.keyGenerator()中可以由mapper.xml中的insert/update标签中的指定的<selectKey>标签指定完成的
      mappedStatement.keyGenerator = configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType) ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
      String logId = id;
      // 全局的logPrefix默认是为null,除非在mybatis.xml中<setting>中配置logPrefix属性
      if (configuration.getLogPrefix() != null) {
        logId = configuration.getLogPrefix() + id;
      }
      // 可简单关注 -- 源码不难
      mappedStatement.statementLog = LogFactory.getLog(logId);
      mappedStatement.lang = configuration.getDefaultScriptingLanguageInstance();
    }

    public Builder resource(String resource) {
      mappedStatement.resource = resource;
      return this;
    }

    public String id() {
      return mappedStatement.id;
    }

    public Builder parameterMap(ParameterMap parameterMap) {
      mappedStatement.parameterMap = parameterMap;
      return this;
    }

    public Builder resultMaps(List<ResultMap> resultMaps) {
      mappedStatement.resultMaps = resultMaps;
      for (ResultMap resultMap : resultMaps) {
        mappedStatement.hasNestedResultMaps = mappedStatement.hasNestedResultMaps || resultMap.hasNestedResultMaps();
      }
      return this;
    }

    public Builder fetchSize(Integer fetchSize) {
      mappedStatement.fetchSize = fetchSize;
      return this;
    }

    public Builder timeout(Integer timeout) {
      mappedStatement.timeout = timeout;
      return this;
    }

    public Builder statementType(StatementType statementType) {
      mappedStatement.statementType = statementType;
      return this;
    }

    public Builder resultSetType(ResultSetType resultSetType) {
      mappedStatement.resultSetType = resultSetType == null ? ResultSetType.DEFAULT : resultSetType;
      return this;
    }

    public Builder cache(Cache cache) {
      mappedStatement.cache = cache;
      return this;
    }

    public Builder flushCacheRequired(boolean flushCacheRequired) {
      mappedStatement.flushCacheRequired = flushCacheRequired;
      return this;
    }

    public Builder useCache(boolean useCache) {
      mappedStatement.useCache = useCache;
      return this;
    }

    public Builder resultOrdered(boolean resultOrdered) {
      mappedStatement.resultOrdered = resultOrdered;
      return this;
    }

    public Builder keyGenerator(KeyGenerator keyGenerator) {
      mappedStatement.keyGenerator = keyGenerator;
      return this;
    }

    public Builder keyProperty(String keyProperty) {
      mappedStatement.keyProperties = delimitedStringToArray(keyProperty);
      return this;
    }

    public Builder keyColumn(String keyColumn) {
      mappedStatement.keyColumns = delimitedStringToArray(keyColumn);
      return this;
    }

    public Builder databaseId(String databaseId) {
      mappedStatement.databaseId = databaseId;
      return this;
    }

    public Builder lang(LanguageDriver driver) {
      mappedStatement.lang = driver;
      return this;
    }

    public Builder resultSets(String resultSet) {
      mappedStatement.resultSets = delimitedStringToArray(resultSet);
      return this;
    }

    /**
     * @deprecated Use {@link #resultSets}
     */
    @Deprecated
    public Builder resulSets(String resultSet) {
      mappedStatement.resultSets = delimitedStringToArray(resultSet);
      return this;
    }

    public MappedStatement build() {
      assert mappedStatement.configuration != null;
      assert mappedStatement.id != null;
      assert mappedStatement.sqlSource != null;
      assert mappedStatement.lang != null;
      mappedStatement.resultMaps = Collections.unmodifiableList(mappedStatement.resultMaps);
      return mappedStatement;
    }
  }

  public KeyGenerator getKeyGenerator() {
    return keyGenerator;
  }

  public SqlCommandType getSqlCommandType() {
    return sqlCommandType;
  }

  public String getResource() {
    return resource;
  }

  public Configuration getConfiguration() {
    return configuration;
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public Integer getFetchSize() {
    return fetchSize;
  }

  public Integer getTimeout() {
    return timeout;
  }

  public StatementType getStatementType() {
    return statementType;
  }

  public ResultSetType getResultSetType() {
    return resultSetType;
  }

  public SqlSource getSqlSource() {
    return sqlSource;
  }

  public ParameterMap getParameterMap() {
    return parameterMap;
  }

  public List<ResultMap> getResultMaps() {
    return resultMaps;
  }

  public Cache getCache() {
    return cache;
  }

  public boolean isFlushCacheRequired() {
    return flushCacheRequired;
  }

  public boolean isUseCache() {
    return useCache;
  }

  public boolean isResultOrdered() {
    return resultOrdered;
  }

  public String getDatabaseId() {
    return databaseId;
  }

  public String[] getKeyProperties() {
    return keyProperties;
  }

  public String[] getKeyColumns() {
    return keyColumns;
  }

  public Log getStatementLog() {
    return statementLog;
  }

  public LanguageDriver getLang() {
    return lang;
  }

  public String[] getResultSets() {
    return resultSets;
  }

  /**
   * @deprecated Use {@link #getResultSets()}
   */
  @Deprecated
  public String[] getResulSets() {
    return resultSets;
  }

  public BoundSql getBoundSql(Object parameterObject) {
    // ❗️❗️❗️ 是无法直接存储BoundSql的,需要每次动态解析的哦

    // 获取BoundSql -- 注意BoundSql已经提前处理好啦 --
    // 位置: SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    // 在解析Mapper.xml的时候,就已经解析后并存入MappedStatement中去啦
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    // 从boundSql获取ParameterMappings -- 即sql中的占位符信息
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    // 很少的情况会是空的 -- 一般都不会进去这里
    if (parameterMappings == null || parameterMappings.isEmpty()) {
      boundSql = new BoundSql(configuration, boundSql.getSql(), parameterMap.getParameterMappings(), parameterObject);
    }

    // check for nested result maps in parameter mappings (issue #30)
    // 检查参数映射中的嵌套结果映射（问题 30）
    // rmId 需要 #{person,resultMap="xx"}
    // 说实话一般都不会有这种sb情况 -- 我都不知道干啥用的,who know tell me
    for (ParameterMapping pm : boundSql.getParameterMappings()) {
      String rmId = pm.getResultMapId();
      if (rmId != null) {
        ResultMap rm = configuration.getResultMap(rmId);
        if (rm != null) {
          hasNestedResultMaps |= rm.hasNestedResultMaps();
        }
      }
    }

    return boundSql;
  }

  private static String[] delimitedStringToArray(String in) {
    if (in == null || in.trim().length() == 0) {
      return null;
    } else {
      return in.split(",");
    }
  }

}
