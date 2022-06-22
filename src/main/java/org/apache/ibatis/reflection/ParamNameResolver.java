/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.reflection;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class ParamNameResolver {
  // 参数名解析器 -- ❗️❗️❗️
  // 每个 ParamNameResolver 实例对应一个 method
  // 持有该method的解析结果 names

  private static final String GENERIC_NAME_PREFIX = "param";

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   */
  private final SortedMap<Integer, String> names;
  // 键是索引，值是参数的名称。
  // 如果指定，则从Param获取名称。未指定Param时，使用参数索引。请注意，当方法具有特殊参数（即RowBounds或ResultHandler ）时，此索引可能与实际索引不同。
  //    aMethod(@Param("M") int a, @Param("N") int b) -> {{0, "M"}, {1, "N"}}
  //    aMethod(int a, int b) -> {{0, "0"}, {1, "1"}}
  //    aMethod(int a, RowBounds rb, int b) -> {{0, "0"}, {2, "1"}}
  // value的值: @Param.value的优先级 > 真实的参数名[要求支持参数名] > 参数所在的形参的位置

  private boolean hasParamAnnotation;

  public ParamNameResolver(Configuration config, Method method) {
    // 核心解析Mapper的method上的type值

    // 1. 准备 -- paramTypes形参类型数组/paramAnnotations形参注解数组
    final Class<?>[] paramTypes = method.getParameterTypes();
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<>();
    int paramCount = paramAnnotations.length;
    // 1. 从0开始遍历每一个形参位置的名字
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      // 1.1 特殊形参,例如RowBounds\ResultHandler,就跳过
      if (isSpecialParameter(paramTypes[paramIndex])) {
        continue;
      }
      // 1.2 是否有@Param形参,有的话,就获取@Param的value值,作为该形参对应的名字
      String name = null;
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        if (annotation instanceof Param) {
          hasParamAnnotation = true;
          name = ((Param) annotation).value();
          break;
        }
      }
      // 1.3 如果name仍然为null,表示@Param没有被使用
      if (name == null) {
        // 1.4 全局配置开启使用真实的形参名 -- 默认是开启的
        // 那么就直接通过getActualParamName(method, paramIndex)
        if (config.isUseActualParamName()) {
          name = getActualParamName(method, paramIndex);
        }
        // 1.5 如果name仍然为空的话
        if (name == null) {
          // name 就只能是数字 0-1-2-3 这种
          name = String.valueOf(map.size());
        }
      }
      // 2. 存到该method对应的ParamNameResolver中的map中
      // name的值: @Param.value的优先级 > 真实的参数名[要求支持参数名] > 参数所在的形参的位置
      map.put(paramIndex, name);
    }
    // 3. 转为不可变的集合
    names = Collections.unmodifiableSortedMap(map);
  }

  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  private static boolean isSpecialParameter(Class<?> clazz) {
    // 特殊性形参 -- 即 RowBounds 以及 ResultHandler
    // 所以 RowBounds 的子类也会被认为是特殊的类型哦
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   */
  public Object getNamedParams(Object[] args) {
    // names 中的key就是形参所在位置,value就是形参对应的解析出来的名字

    final int paramCount = names.size();
    if (args == null || paramCount == 0) {
      return null;
    }
    // 1. 如果没有@Param注解,并且只有一个形参
    // 注意:这里的一个形参是排除了特殊参数比如ResultHandler/RowBounds这种的
    else if (!hasParamAnnotation && paramCount == 1) {
      // 1.1 返回的args[names.firstKey()] -- 对象
      return args[names.firstKey()];
    }
    // ❗️❗️❗️ -> 这里是使用的 ParamMap
    // 2. 注意注意 param
    else {
      final Map<String, Object> param = new ParamMap<>();
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        // 2.1 存入到param中
        param.put(entry.getValue(), args[entry.getKey()]);
        // 2.2 添加通用参数名称（param1，param2，...）
        // GENERIC_NAME_PREFIX 就是 param
        final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);
        // 确保不覆盖以 @Param 命名的参数
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      // 返回的param
      // key - 形参名 -- 如@Param指定的形参名
      // Object - 形参值 -- 从args中根据index获取到的
      return param;
    }
  }
}
