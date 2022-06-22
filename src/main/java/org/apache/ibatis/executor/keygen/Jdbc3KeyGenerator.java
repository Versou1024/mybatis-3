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
package org.apache.ibatis.executor.keygen;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ArrayUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSession.StrictMap;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.Map.Entry;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {
  // 当 <insert>/<update> 中指定useGeneratedKeys属性为true
  // 或者 mybatis.xml中已经开启全局的useGeneratedKeys为ture
  // 都会使用到 Jdbc3KeyGenerator 作为 KeyGenerator
  // 用来在 insert/update 执行结束之后,调用 processAfter() 将主键查询出来复制给对象parameter

  /**
   * A shared instance.
   *
   * @since 3.4.3
   */
  public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

  private static final String MSG_TOO_MANY_KEYS = "Too many keys are generated. There are only %d target objects. "
      + "You either specified a wrong 'keyProperty' or encountered a driver bug like #1523.";

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    // do nothing
    // 不会执行
  }

  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    // 用于获取住家 -- 在之后进行执行

    processBatch(ms, stmt, parameter);
  }

  public void processBatch(MappedStatement ms, Statement stmt, Object parameter) {
    // 整个处理是比较复杂的哦
    // 举例
    // <insert id="insert" parameterType="org.apache.learning.sql.select.use_generaed_keys.User"
    //        useGeneratedKeys="true"
    //        keyProperty="id,role"
    //        keyColumn="name,role_id" -- connection.prepareStatement(sql, keyColumnNames)
    // >
    //    insert into USER (name, role_id)
    //    values (#{name}, #{roleId})
    //</insert>

    // 或
    // <insert id="insert" parameterType="org.apache.learning.sql.select.use_generaed_keys.User"
    //        useGeneratedKeys="true"
    //        keyProperty="id"  -- 对应connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)
    // >
    //    insert into USER (name, role_id)
    //    values (#{name}, #{roleId})
    //</insert>

    // 1. 需要映射回去的属性,上面就是keyProperty="id,role"中的id,role
    final String[] keyProperties = ms.getKeyProperties();
    if (keyProperties == null || keyProperties.length == 0) {
      return;
    }
    // 2. 检索由于执行此Statement对象而创建的任何自动生成的键。如果此Statement对象没有生成任何键，则返回一个空的ResultSet对象
    // 在之前:
    // Executor.prepareStatement() ->
    // StatementHandler.prepare(Connection connection, Integer transactionTimeout) ->
    // PreparedStatementHandler.instantiateStatement(Connection connection) ->
    // 当 keyColumnNames 不为空 connection.prepareStatement(sql, keyColumnNames)
    // 或者创建 connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)
    // 因此这里 stmt,getGeneratedKeys() 可以获取要求返回的数据哦
    try (ResultSet rs = stmt.getGeneratedKeys()) {
      final ResultSetMetaData rsmd = rs.getMetaData();
      final Configuration configuration = ms.getConfiguration();
      // 2.1 如果 ResultSetMetaData 检查结果中的列数小于 keyProperties 中的值 -- 说明 keyProperties 属性 和  keyColumns 属性的数量不一致
      if (rsmd.getColumnCount() < keyProperties.length) {
        // Error?
      } else {
        // 2.2 开始赋值 -- UI中赋回到形参上哦
        // 主要是:如何将rs中的结果写入到parameter上指定keyProperties属性上去
        assignKeys(configuration, rs, rsmd, keyProperties, parameter);
      }
    } catch (Exception e) {
      throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e, e);
    }
  }

  @SuppressWarnings("unchecked")
  private void assignKeys(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd, String[] keyProperties, Object parameter) throws SQLException {
    // 1.0  mapper接口方法的多参数用ParamMap
    // mapper接口方法的单形参无@Param时为List/Array/Collection的情况下, 使用StrictMap
    if (parameter instanceof ParamMap || parameter instanceof StrictMap) {
      // Multi-param or single param with @Param
      assignKeysToParamMap(configuration, rs, rsmd, keyProperties, (Map<String, ?>) parameter);
    }
    // 1.1 批量操作中带有@Param的多参数或单参数
    else if (parameter instanceof ArrayList && !((ArrayList<?>) parameter).isEmpty()
        && ((ArrayList<?>) parameter).get(0) instanceof ParamMap) {
      // Multi-param or single param with @Param in batch operation
      assignKeysToParamMapList(configuration, rs, rsmd, keyProperties, ((ArrayList<ParamMap<?>>) parameter));
    }
    // 1.2 没有 @Param 的单个参数
    else {
      // Single param without @Param
      assignKeysToParam(configuration, rs, rsmd, keyProperties, parameter);
    }
  }

  private void assignKeysToParam(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Object parameter) throws SQLException {
    Collection<?> params = collectionize(parameter);
    if (params.isEmpty()) {
      return;
    }
    List<KeyAssigner> assignerList = new ArrayList<>();
    for (int i = 0; i < keyProperties.length; i++) {
      assignerList.add(new KeyAssigner(configuration, rsmd, i + 1, null, keyProperties[i]));
    }
    Iterator<?> iterator = params.iterator();
    while (rs.next()) {
      if (!iterator.hasNext()) {
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, params.size()));
      }
      Object param = iterator.next();
      assignerList.forEach(x -> x.assign(rs, param));
    }
  }

  private void assignKeysToParamMapList(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, ArrayList<ParamMap<?>> paramMapList) throws SQLException {
    Iterator<ParamMap<?>> iterator = paramMapList.iterator();
    List<KeyAssigner> assignerList = new ArrayList<>();
    long counter = 0;
    while (rs.next()) {
      if (!iterator.hasNext()) {
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
      }
      ParamMap<?> paramMap = iterator.next();
      if (assignerList.isEmpty()) {
        for (int i = 0; i < keyProperties.length; i++) {
          assignerList
              .add(getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i], keyProperties, false)
                  .getValue());
        }
      }
      assignerList.forEach(x -> x.assign(rs, paramMap));
      counter++;
    }
  }

  private void assignKeysToParamMap(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Map<String, ?> paramMap) throws SQLException {
    // 1.没有形参,不需要注入,直接返回吧
    if (paramMap.isEmpty()) {
      return;
    }
    // 2. 解析 keyProperties 属性
    // assignerMap
    // 比如 person.name/person.age
    // key 为 person
    // value 为 name/age解析出来的KeyAssigner
    Map<String, Entry<Iterator<?>, List<KeyAssigner>>> assignerMap = new HashMap<>();
    for (int i = 0; i < keyProperties.length; i++) {
      // 2.1 获取对应的 Entry<String, KeyAssigner>
      Entry<String, KeyAssigner> entry = getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i],
          keyProperties, true);
      // 2.2 将entry放入到assignerMap
      // 注意:
      // a. paramMap.get(k)是根据key从paramMap中获取对应的形参
      // b. collectionize() 用来将获取到的形参转换为集合,即形参就是Array/List的就直接强转,否则就是只是作为List中的一个元素后返回List
      Entry<Iterator<?>, List<KeyAssigner>> iteratorPair = assignerMap.computeIfAbsent(entry.getKey(),
          k -> entry(collectionize(paramMap.get(k)).iterator(), new ArrayList<>()));
      iteratorPair.getValue().add(entry.getValue());
    }
    long counter = 0;
    // 3. 开始遍历rs -- stmt.getGeneratedKeys()
    while (rs.next()) {
      for (Entry<Iterator<?>, List<KeyAssigner>> pair : assignerMap.values()) {
        if (!pair.getKey().hasNext()) {
          throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
        }
        // 3.1 param集合key值 -- 仅仅是从 key中获取第一个元素
        Object param = pair.getKey().next();
        // 3.2 其中pair.getValue()返回的就是KeyAssigner的集合
        // x.assign(rs,param)
        pair.getValue().forEach(x -> x.assign(rs, param));
      }
      counter++;
    }
  }

  private Entry<String, KeyAssigner> getAssignerForParamMap(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, String[] keyProperties, boolean omitParamName) {
    // 获取参数映射的分配器

    // columnPosition 是 当前遍历到resultSet的位置
    // paramMap 是 形参paramMap
    // keyProperty 是 keyProperties[columnPosition-1] 对应将rs中对应的列写入Bean中的属性
    // omitParamName 是 省略参数名

    // 1. singleParam 表示是否只有一个key-value
    boolean singleParam = paramMap.values().stream().distinct().count() == 1;
    // 2. "." 的存在即表示有子属性
    int firstDot = keyProperty.indexOf('.');
    if (firstDot == -1) {
      if (singleParam) {
        // 3. 没有子属性,且为单个值 ->
        // 调用 getAssignerForSingleParam 传递出 entry
        return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
      }
      throw new ExecutorException("Could not determine which parameter to assign generated keys to. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
    // 4. 获取形参名 -- 比如 keyProperty 为 person.name
    // 那么 paramName 就是 person
    String paramName = keyProperty.substring(0, firstDot);
    if (paramMap.containsKey(paramName)) {
      // 4.1 omitParamName为true,表示省略参数名,那么argParamName就是null,否则就是解析出来的paramName
      // 一般情况都是使用的name,直接赋予到PO对象的name上 -- 因此不需要argParamName,因为默认就是PO
      String argParamName = omitParamName ? null : paramName;
      // 4.2  argKeyProperty 就是 name
      String argKeyProperty = keyProperty.substring(firstDot + 1);
      // 4.3 创建一个键值对哦
      return entry(paramName, new KeyAssigner(config, rsmd, columnPosition, argParamName, argKeyProperty));
    } else if (singleParam) {
      return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
    } else {
      throw new ExecutorException("Could not find parameter '" + paramName + "'. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
  }

  private Entry<String, KeyAssigner> getAssignerForSingleParam(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, boolean omitParamName) {
    // 获取单个参数的分配器

    // 1. 假设“keyProperty”是单个参数的属性,获取其第一个值的key -- 即形参名
    String singleParamName = nameOfSingleParam(paramMap);
    // 2. omitParamName为false,argParamName=singleParamName,否则就是null
    String argParamName = omitParamName ? null : singleParamName;
    // 3. 返回entry
    return entry(singleParamName, new KeyAssigner(config, rsmd, columnPosition, argParamName, keyProperty));
  }

  private static String nameOfSingleParam(Map<String, ?> paramMap) {
    // There is virtually one parameter, so any key works.
    return paramMap.keySet().iterator().next();
  }

  private static Collection<?> collectionize(Object param) {
    // 如果param是集合 -> 将其转换为Collection类型的
    // 就是不是集合类型 -> 也会用Arrays.asList(param)包装起来
    if (param instanceof Collection) {
      return (Collection<?>) param;
    } else if (param instanceof Object[]) {
      return Arrays.asList((Object[]) param);
    } else {
      return Arrays.asList(param);
    }
  }

  private static <K, V> Entry<K, V> entry(K key, V value) {
    // Replace this with Map.entry(key, value) in Java 9.
    // 用 Java 9 中的 Map.entry(key, value) 替换它
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  private class KeyAssigner {
    // 键分配对象

    private final Configuration configuration;
    // 指定需要返回的列的结果集的元数据
    // 返回的结果集是需要赋予给形参中的某个属性 -- 比如常见的插入后将id列的值回填到PO的id属性上
    private final ResultSetMetaData rsmd;
    private final TypeHandlerRegistry typeHandlerRegistry;
    // 当前statement.getGeneratedKeys()返回的结果集
    // 所在结果集的处理位置
    private final int columnPosition;

    // 比如 person.name
    // 表示将当前列值回填到 person形参的name属性上去

    // 父形参名
    private final String paramName;
    // 子属性
    private final String propertyName;
    private TypeHandler<?> typeHandler;

    protected KeyAssigner(Configuration configuration, ResultSetMetaData rsmd, int columnPosition, String paramName,
        String propertyName) {
      super();
      this.configuration = configuration;
      this.rsmd = rsmd;
      this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
      this.columnPosition = columnPosition;
      this.paramName = paramName;
      this.propertyName = propertyName;
    }

    protected void assign(ResultSet rs, Object param) {
      // 1. 只有在omitParamName为ture时,paramName才会一直为null
      if (paramName != null) {
        // If paramName is set, param is ParamMap
        // 2. 如果omitParamName为false时,paramName就会是 person.name 中的 person
        param = ((ParamMap<?>) param).get(paramName);
      }
      // 2. 为根param创建MetaObject
      MetaObject metaParam = configuration.newMetaObject(param);
      try {
        if (typeHandler == null) {
          // 2.1 查看根对象是否有指定的属性的set方法
          if (metaParam.hasSetter(propertyName)) {
            // 2.1.1 肯定会有,没有都报错啦,然后会set的形参类型
            Class<?> propertyType = metaParam.getSetterType(propertyName);
            // 2.1.2 根据set的形参乐行,以及jdbcType,获取一个TypeHandler
            typeHandler = typeHandlerRegistry.getTypeHandler(propertyType,
                JdbcType.forCode(rsmd.getColumnType(columnPosition)));
          } else {
            throw new ExecutorException("No setter found for the keyProperty '" + propertyName + "' in '"
                + metaParam.getOriginalObject().getClass().getName() + "'.");
          }
        }
        if (typeHandler == null) {
          // Error?
        } else {
          // 3.1 使用typeHandler从rs中获取指定位置columnPosition的结果并且转换为对应的javaType,并以Object来接受
          Object value = typeHandler.getResult(rs, columnPosition);
          // 3.2 设置到根对象的metaParam的propertyName属性上
          metaParam.setValue(propertyName, value);
        }
      } catch (SQLException e) {
        throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e,
            e);
      }
    }
  }
}
