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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {
  // Mapper接口中的方法
  // 创建出来的MapperProxy即Mapper接口的代理对象在执行接口方法时都是创建并调用MapperMethod#execute()方法来执行
  // 因此核心就在MapperMethod

  // sql命令  -- 持有MapperMethod对应的Mapper.xml中的标签的id,以及对应的解析出来的MappedStatement
  private final SqlCommand command;
  // 方法签名 --  各种方法签名信息
  private final MethodSignature method;

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  public Object execute(SqlSession sqlSession, Object[] args) {
    // 正式开始执行Mapper方法--
    // ❗️❗️❗️核心:  method.convertArgsToSqlCommandParam(args) 转换 args 参数

    Object result;
    switch (command.getType()) {
      case INSERT: {
        // 1. 插入 convertArgsToSqlCommandParam()
        // convertArgsToSqlCommandParam() 作用
        // 将传递进去的参数数组 -- 获取对应的结果
        // 1. 如果没有使用@Param,且只有一个形参时,将直接返回对应的形参值
        // 2. 其余情况,将返回一个Map<String,Object> = key是解析出来的形参名[根据@Param\形参真实名情况] + value就是对应的形参值[从args中获取的]

        // 准备执行insert操作
        Object param = method.convertArgsToSqlCommandParam(args);
        // rowCountResult处理返回结果为行数rowCounts的情况 00 如何适配到mapper接口的返回值类型上
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        // 1. 更新
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        // 1. 删除
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        // 1. 方法是否为Void并且有ResultHandler参数
        if (method.returnsVoid() && method.hasResultHandler()) {
          executeWithResultHandler(sqlSession, args);
          result = null;
        }
        // 2. 方法是否返回的是数组和集合
        else if (method.returnsMany()) {
          result = executeForMany(sqlSession, args);
        }
        // 3. 方法是否返回的是Map
        else if (method.returnsMap()) {
          result = executeForMap(sqlSession, args);
        }
        // 4. 方法是否返回的是Cursor
        else if (method.returnsCursor()) {
          result = executeForCursor(sqlSession, args);
        }
        // 5. 普通的情况
        else {
          Object param = method.convertArgsToSqlCommandParam(args);
          // command.getName() 返回的是 对应的 MappedStatement的id
          result = sqlSession.selectOne(command.getName(), param);
          // method返回的是Optional类型时,且result为null或者类型不匹配
          // 那就用Optional.ofNullable(result)包装reuslt吧
          if (method.returnsOptional() && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        // 1. 刷新接口
        // Mapper接口上的方法有@Flush注解,且该方法没有对应的Mapper标签和Mapper的DML注解
        // method本身不会被执行,会直接执行 sqlSession.flushStatements()
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }

    // 验证[基本类型无法接受null的情况] -- 是否执行失败,比如结果为null,返回值非void且为基本类型
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  private Object rowCountResult(int rowCount) {
    // 处理 增删改 后的返回数据 -- 即int 型的rowCount
    // 如何适配到 method 的返回值类型上

    final Object result;

    // 1. mapper接口返回值为Void -- 表示不关注增删改的结果 -- 那么返回null即可
    if (method.returnsVoid()) {
      result = null;
    }
    // 2. mapper接口返回值为Integer包装类型或int基本类型 -- 返回rowCount即可
    else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    }
    // 3. mapper接口的返回为Long或long -- 强转rowCount为long后返回即可
    else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long)rowCount;
    }
    // 4. mapper接口的返回值Boolean或boolean值 -- 只要rowCount大于0就返回true
    else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    }
    // 5. 其余情况不接受
    else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[])array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {
    // 特别的用来存储 Mapper接口中的多形参,或者单形参且无@Param情况下的 [形参名 -> 形参值]
    // 这样的键值对哦

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  public static class SqlCommand {

    // mapperMethod对应的mapper.xml标签的id
    private final String name;
    // sql语句的类型 -- 增删改查
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {

      // 0. 根据相关信息解析出MappedStatement
      final String methodName = method.getName();
      final Class<?> declaringClass = method.getDeclaringClass();
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);
      // 1. 如果没有 MappedStatement
      if (ms == null) {
        // 1.1 method是否有Flush注解 -- 有的话,就表示这个方法被调用时就仅仅用来刷新缓存
        // 但是并不需要真的执行的method
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      }
      // 2. 如果Configuration中有MappedStatement,就准备直接返回吧
      else {
        // 2.1 name就是ms的id; type就是ms的Sql类型
        name = ms.getId();
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName, Class<?> declaringClass, Configuration configuration) {

      // 1. 转为statementId然后去Configuration中判断是否存在对应的MapperStatement
      // statementId = mapper接口的全限定名 + mapper接口的方法名
      String statementId = mapperInterface.getName() + "." + methodName;
      // 2. 如果Configuration中有对应statementId的MapperStatement获取出来就返回
      if (configuration.hasStatement(statementId)) {
        return configuration.getMappedStatement(statementId);
      }
      // 3. 如果mapper接口和待执行的mapperMethod声明的类是相等,就返回null吧
      // 如果不是 -- 就需要进入下面的代码块递归查找mapper接口的超类哦
      else if (mapperInterface.equals(declaringClass)) {
        return null;
      }
      // 4. ❗️❗️❗️
      // 递归查询mapper接口的超类 -- resolveMappedStatement
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          MappedStatement ms = resolveMappedStatement(superInterface, methodName, declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {
    // 各种方法签名信息

    // 返回值是否为集合类型
    private final boolean returnsMany;
    // 返回值是否为Map类型
    private final boolean returnsMap;
    // 返回值是否Void
    private final boolean returnsVoid;
    // 返回值是否为Cursor
    private final boolean returnsCursor;
    // 返回值是否为 Optional
    private final boolean returnsOptional;
    // 返回值类型returnType
    private final Class<?> returnType;
    // 当返回值是Map时,查看方法上是否有@MapKey注解,有的话,就获取@MapKey的value值传给mapKey
    private final String mapKey;
    // 查看method中是否有ResultHandler形参,有的话就返回该形参在method中的索引位置
    private final Integer resultHandlerIndex;
    // 查看method中是否有RowBounds形参,有的话就返回该形参在method中的索引位置
    private final Integer rowBoundsIndex;
    private final ParamNameResolver paramNameResolver;

    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 1. 待执行方法的返回值类型
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      // 2. 分为 Class \ ParameterizedType \ 默认的method.getReturnType()
      // 返回类型时List等情况会在(Class<?>) ((ParameterizedType) resolvedReturnType).getRawType()获取生Type
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }
      // 3. 返回值的判断
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      this.returnsCursor = Cursor.class.equals(this.returnType);
      this.returnsOptional = Optional.class.equals(this.returnType);
      this.mapKey = getMapKey(method); // 当返回值是Map时,查看方法上是否有@MapKey注解,有的话,就获取@MapKey的value值传给mapKey
      this.returnsMap = this.mapKey != null;
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class); // 查看method中是否有RowBounds形参,有的话就返回该形参在method中的索引位置 -- 否则就是null
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class); // 查看method中是否有ResultHandler形参,有的话就返回该形参在method中的索引位置 -- 否则就是null
      // 4. 参数名解析器 -- ❗️❗️❗ParamNameResolver️创建的过程中,就会去解析method的形参名,例如@Param指定形参名等情况
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    public Object convertArgsToSqlCommandParam(Object[] args) {
      // 将传递进去的参数数组 -- 获取对应的结果
      // 1. 如果没有使用@Param,且只有一个形参时,将直接返回对应的形参值
      // 2. 其余情况,将返回一个Map<String,Object> = key是解析出来的形参名[根据@Param\形参真实名情况] + value就是对应的形参值[从args中获取的]
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}.
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      // 查看method中是否有paramType形参,有的话就返回该形参在method中的索引位置

      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {
            index = i;
          } else {
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    private String getMapKey(Method method) {
      // 当返回值是Map时,查看方法上是否有@MapKey注解,有的话,就获取@MapKey的value值传给mapKey

      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
