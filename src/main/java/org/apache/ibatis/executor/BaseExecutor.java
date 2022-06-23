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
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

  private static final Log log = LogFactory.getLog(BaseExecutor.class);

  protected Transaction transaction;
  protected Executor wrapper;

  protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
  // SqlSession的一级本地永久缓存
  protected PerpetualCache localCache;
  // SqlSession的一级本地输出形参缓存
  protected PerpetualCache localOutputParameterCache;
  // 配置
  protected Configuration configuration;

  protected int queryStack;
  private boolean closed;

  protected BaseExecutor(Configuration configuration, Transaction transaction) {
    // 唯一构造器
    // 至少需要传递: Configuration以及对应的事务transaction
    this.transaction = transaction;
    this.deferredLoads = new ConcurrentLinkedQueue<>();
    this.localCache = new PerpetualCache("LocalCache"); // 本地缓存
    this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
    this.closed = false;
    this.configuration = configuration;
    this.wrapper = this;
  }

  @Override
  public Transaction getTransaction() {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return transaction;
  }

  @Override
  public void close(boolean forceRollback) {
    // 关闭操作
    try {
      try {
        rollback(forceRollback);
      } finally {
        if (transaction != null) {
          // 调用的transaction.close()方法
          // 实际就是调用 connection.close()
          transaction.close();
        }
      }
    } catch (SQLException e) {
      // Ignore.  There's nothing that can be done at this point.
      log.warn("Unexpected exception on closing transaction.  Cause: " + e);
    } finally {
      // 清空BaseExecutor中的基本组件
      transaction = null;
      deferredLoads = null;
      localCache = null;
      localOutputParameterCache = null;
      closed = true;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public int update(MappedStatement ms, Object parameter) throws SQLException {
    // 1. ms.getResource()返回的Mapper.xml的资源标记为激活状态
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
    // 2. 若sqlSession已经关闭,则无法执行closed方法
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 3. 可以发现 -- 执行update操作都会导致本地缓存被清除
    clearLocalCache();
    // 4. 核心执行 doUpdate(ms,parameter)
    return doUpdate(ms, parameter);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    // 将通过 DefaultSqlSession.flush() 方法间接执行

    // 一级缓存的刷新
    return flushStatements(false);
  }

  public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return doFlushStatements(isRollBack);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter);
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    // 1. 执行器已关闭,报错
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 2. 查询栈为0,且当前语句执行是否会强制刷新,默认情况下非select语句会导致刷新
    // 用户可以在 <select> 标签中指定 flushCache 属性为 false
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      // 2.1 可以发现: 在执行之前就会导致cache被清空哦
      clearLocalCache();
    }
    List<E> list;
    try {
      // 3.1 添加查询栈深度
      queryStack++;
      // 3.2 查询缓存是否可以命中 -- 前提是乜有ResultHandler
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      if (list != null) {
        // 3.2.1 缓存命中 -- 处理ms为CallableStatement下的处理情况
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
      } else {
        // 3.2.2 缓存未命中 -- 去数据库中查询
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      // 3. 查询栈深度自减
      queryStack--;
    }
    if (queryStack == 0) {
      for (DeferredLoad deferredLoad : deferredLoads) {
        deferredLoad.load();
      }
      // issue #601
      deferredLoads.clear();
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        // issue #482
        clearLocalCache();
      }
    }
    return list;
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter);
    return doQueryCursor(ms, parameter, rowBounds, boundSql);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 1. 创建DeferredLoad
    // 传入 对象的MetaObject/传入对应的属性property名/传入CacheKey/传入本地缓存localCache/传入配置/传入目标属性的java类型
    DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
    // 2. 可以load()就调用
    if (deferredLoad.canLoad()) {
      deferredLoad.load();
    } else {
      // 3. 否则加入到 deferredLoads
      deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    //  默认对于查询语句而言 -- 需要生成CacheKey -- 先去查看缓存是否命中/缓存未命中去数据库执行/将执行结果存入缓存中
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 1. 创建cacheKey
    CacheKey cacheKey = new CacheKey();
    // 2. cacheKey更新id\offset\limit\sql
    cacheKey.update(ms.getId());
    cacheKey.update(rowBounds.getOffset());
    cacheKey.update(rowBounds.getLimit());
    cacheKey.update(boundSql.getSql());
    // 3. 更新 parameterMappings
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
    // mimic DefaultParameterHandler logic
    for (ParameterMapping parameterMapping : parameterMappings) {
      if (parameterMapping.getMode() != ParameterMode.OUT) {
        Object value;
        String propertyName = parameterMapping.getProperty();
        // boundSql中有这个属性名
        if (boundSql.hasAdditionalParameter(propertyName)) {
          value = boundSql.getAdditionalParameter(propertyName);
        }
        // 没有传递形参parameterObject
        else if (parameterObject == null) {
          value = null;
        }
        // ParamMap与StrictMap是没有对应的TypeHandler的
        // 因此这里在单形参且无@Param时,默认会将单形参的值parameterObject给value值
        else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
          value = parameterObject;
        }
        // 为paramObject创建metaObject检查getValue()
        else {
          // ❗️❗️❗️ 99%的情况都会在这里进行判断并找出对应的value值哦
          MetaObject metaObject = configuration.newMetaObject(parameterObject);
          value = metaObject.getValue(propertyName);
        }
        cacheKey.update(value);
      }
    }
    if (configuration.getEnvironment() != null) {
      // 环境id
      cacheKey.update(configuration.getEnvironment().getId());
    }
    // 所以cacheKey MappedStatement的id\offset\limit\boundSql.getSql()\占位符的参数\数据库id 有关
    // 任何一项不同,缓存都不会被命中
    return cacheKey;
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return localCache.getObject(key) != null;
  }

  @Override
  public void commit(boolean required) throws SQLException {
    if (closed) {
      throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    clearLocalCache(); // 清空本地缓存
    flushStatements(); // 刷新statement
    if (required) {
      transaction.commit();
    }
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    if (!closed) {
      try {
        // 没有关闭的情况下: 可以清除本地缓存\刷新缓存执行过的Statement
        clearLocalCache();
        flushStatements(true);
      } finally {
        // 强制执行transaction的回滚
        if (required) {
          transaction.rollback();
        }
      }
    }
  }

  @Override
  public void clearLocalCache() {
    // 清空: localCache/localOutputParameterCache 缓存
    if (!closed) {
      localCache.clear();
      localOutputParameterCache.clear();
    }
  }

  // ❗️❗️❗️
  // 子类必须实现的方法
  // doUpdate\doFlushStatements\doQuery\doQueryCursor

  protected abstract int doUpdate(MappedStatement ms, Object parameter)
      throws SQLException;

  protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
      throws SQLException;

  protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException;

  protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException;

  protected void closeStatement(Statement statement) {
    // 关闭Statement

    // 1. 判空 _ statement.close()
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /**
   * Apply a transaction timeout.
   * @param statement a current statement
   * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
   * @since 3.4.0
   * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
   */
  protected void applyTransactionTimeout(Statement statement) throws SQLException {
    StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
  }

  private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
    // 处理Statement类型为CallableStatement的输出值的缓存 -- 可忽略
    if (ms.getStatementType() == StatementType.CALLABLE) {
      final Object cachedParameter = localOutputParameterCache.getObject(key);
      if (cachedParameter != null && parameter != null) {
        final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
        final MetaObject metaParameter = configuration.newMetaObject(parameter);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
          if (parameterMapping.getMode() != ParameterMode.IN) {
            final String parameterName = parameterMapping.getProperty();
            final Object cachedValue = metaCachedParameter.getValue(parameterName);
            metaParameter.setValue(parameterName, cachedValue);
          }
        }
      }
    }
  }

  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;
    // 1. 先向本地缓存存入当前cacheKey的值占位符
    localCache.putObject(key, EXECUTION_PLACEHOLDER);
    try {
      // 2. 执行查询操作
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      // 3. 移除本地缓存的值占位符
      localCache.removeObject(key);
    }
    // 4. 将最终的结果缓存起来
    localCache.putObject(key, list);
    // 5. 特殊情况: StatementType.CALLABLE
    if (ms.getStatementType() == StatementType.CALLABLE) {
      localOutputParameterCache.putObject(key, parameter);
    }
    return list;
  }

  protected Connection getConnection(Log statementLog) throws SQLException {
    Connection connection = transaction.getConnection();
    if (statementLog.isDebugEnabled()) {
      return ConnectionLogger.newInstance(connection, statementLog, queryStack);
    } else {
      return connection;
    }
  }

  @Override
  public void setExecutorWrapper(Executor wrapper) {
    this.wrapper = wrapper;
  }

  private static class DeferredLoad {

    private final MetaObject resultObject;
    private final String property;
    private final Class<?> targetType;
    private final CacheKey key;
    private final PerpetualCache localCache;
    private final ObjectFactory objectFactory;
    private final ResultExtractor resultExtractor;

    // issue #781
    public DeferredLoad(MetaObject resultObject,
                        String property,
                        CacheKey key,
                        PerpetualCache localCache,
                        Configuration configuration,
                        Class<?> targetType) {
      this.resultObject = resultObject;
      this.property = property;
      this.key = key;
      this.localCache = localCache;
      this.objectFactory = configuration.getObjectFactory();
      this.resultExtractor = new ResultExtractor(configuration, objectFactory);
      this.targetType = targetType;
    }

    public boolean canLoad() {
      // 1. 本地缓存有指定的cacheKey,并且本地缓存的值不是占位符即EXECUTION_PLACEHOLDER
      return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
    }

    public void load() {
      // 1. 获取指定cacheKey的List<Object>
      List<Object> list = (List<Object>) localCache.getObject(key);
      // 2. 提取指定的值
      Object value = resultExtractor.extractObjectFromList(list, targetType);
      // 3. set到resultObject中去的置顶property属性名上
      resultObject.setValue(property, value);
    }

  }

}
