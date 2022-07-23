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
package org.apache.ibatis.type;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.util.*;

/**
 * @author Clinton Begin
 */
public class TypeAliasRegistry {
  // 类型别名注册表

  // 场景:
  // 别名的使用,比如在mapper.xml中insert标签中resultType为"com.sdk.developer.User"
  // 如果为 User.class 注册别名 -> 那么就可以在insert总共resultType为"user" [还有其他场景,也类似]

  // 别名:
  // 别名可以是: @Alias的value值 > @Alias不存在时的class.getSimpleName()

  // 即通过名字找到对应的Class
  private final Map<String, Class<?>> typeAliases = new HashMap<>();

  public TypeAliasRegistry() {
    // 默认注册 -- 基本类型/基本类型数组/包装类型/包装类型数组/
    // 日期/浮点数/
    // map/hashmap/list/arraylist/collection/iterator/resultSet
    registerAlias("string", String.class);

    registerAlias("byte", Byte.class);
    registerAlias("long", Long.class);
    registerAlias("short", Short.class);
    registerAlias("int", Integer.class);
    registerAlias("integer", Integer.class);
    registerAlias("double", Double.class);
    registerAlias("float", Float.class);
    registerAlias("boolean", Boolean.class);

    registerAlias("byte[]", Byte[].class);
    registerAlias("long[]", Long[].class);
    registerAlias("short[]", Short[].class);
    registerAlias("int[]", Integer[].class);
    registerAlias("integer[]", Integer[].class);
    registerAlias("double[]", Double[].class);
    registerAlias("float[]", Float[].class);
    registerAlias("boolean[]", Boolean[].class);

    registerAlias("_byte", byte.class);
    registerAlias("_long", long.class);
    registerAlias("_short", short.class);
    registerAlias("_int", int.class);
    registerAlias("_integer", int.class);
    registerAlias("_double", double.class);
    registerAlias("_float", float.class);
    registerAlias("_boolean", boolean.class);

    registerAlias("_byte[]", byte[].class);
    registerAlias("_long[]", long[].class);
    registerAlias("_short[]", short[].class);
    registerAlias("_int[]", int[].class);
    registerAlias("_integer[]", int[].class);
    registerAlias("_double[]", double[].class);
    registerAlias("_float[]", float[].class);
    registerAlias("_boolean[]", boolean[].class);

    registerAlias("date", Date.class);
    registerAlias("decimal", BigDecimal.class);
    registerAlias("bigdecimal", BigDecimal.class);
    registerAlias("biginteger", BigInteger.class);
    registerAlias("object", Object.class);

    registerAlias("date[]", Date[].class);
    registerAlias("decimal[]", BigDecimal[].class);
    registerAlias("bigdecimal[]", BigDecimal[].class);
    registerAlias("biginteger[]", BigInteger[].class);
    registerAlias("object[]", Object[].class);

    registerAlias("map", Map.class);
    registerAlias("hashmap", HashMap.class);
    registerAlias("list", List.class);
    registerAlias("arraylist", ArrayList.class);
    registerAlias("collection", Collection.class);
    registerAlias("iterator", Iterator.class);

    registerAlias("ResultSet", ResultSet.class);
  }

  @SuppressWarnings("unchecked")
  // throws class cast exception as well if types cannot be assigned
  public <T> Class<T> resolveAlias(String string) {
    // 核心一 -- 解析别名

    try {
      if (string == null) {
        return null;
      }
      String key = string.toLowerCase(Locale.ENGLISH);
      Class<T> value;
      // 1. 简单别名,例如 string\int\_int
      if (typeAliases.containsKey(key)) {
        value = (Class<T>) typeAliases.get(key);
      } else {
        // 2. 全限定类型, 例如 java.lang.String
        value = (Class<T>) Resources.classForName(string);
      }
      return value;
    } catch (ClassNotFoundException e) {
      throw new TypeException("Could not resolve type alias '" + string + "'.  Cause: " + e, e);
    }
  }

  public void registerAliases(String packageName) {
    // 注册别名 -> 为某个package下的所有class注册别名
    registerAliases(packageName, Object.class);
  }

  public void registerAliases(String packageName, Class<?> superType) {
    // 1. 找到package下的所有class
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
    // 2. 忽略内部类和接口（包括 package-info.java）,为其注册别名
    for (Class<?> type : typeSet) {
      if (!type.isAnonymousClass() && !type.isInterface() && !type.isMemberClass()) {
        registerAlias(type);
      }
    }
  }

  public void registerAlias(Class<?> type) {
    // 当没有使用@Alias注解的别名时,直接使用type.getSimpleName作为别名使用

    String alias = type.getSimpleName();
    Alias aliasAnnotation = type.getAnnotation(Alias.class);
    if (aliasAnnotation != null) {
      alias = aliasAnnotation.value();
    }
    registerAlias(alias, type);
  }

  public void registerAlias(String alias, Class<?> value) {
    // 核心二: 注册指定别名alias对应的class值
    // 别名是可以: @Alias的value值,也可以是@Alias不存在时的class.getSimpeName()

    if (alias == null) {
      throw new TypeException("The parameter alias cannot be null");
    }
    // 0. 别名需要转换为小写的
    String key = alias.toLowerCase(Locale.ENGLISH);
    // 1. 不允许覆盖,否则报错
    if (typeAliases.containsKey(key) && typeAliases.get(key) != null && !typeAliases.get(key).equals(value)) {
      throw new TypeException("The alias '" + alias + "' is already mapped to the value '" + typeAliases.get(key).getName() + "'.");
    }
    typeAliases.put(key, value);
  }

  public void registerAlias(String alias, String value) {
    try {
      registerAlias(alias, Resources.classForName(value));
    } catch (ClassNotFoundException e) {
      throw new TypeException("Error registering type alias " + alias + " for " + value + ". Cause: " + e, e);
    }
  }

  /**
   * @since 3.2.2
   */
  public Map<String, Class<?>> getTypeAliases() {
    return Collections.unmodifiableMap(typeAliases);
  }

}
