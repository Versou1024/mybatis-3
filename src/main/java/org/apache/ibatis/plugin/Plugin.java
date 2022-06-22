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
package org.apache.ibatis.plugin;

import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {
  // ❗️❗️❗️
  // Plugin是实现InvocationHandler接口 -- 生成代理对象
  // 作用:
  // 1. 生成Plugin的代理对象 -- 对目标的方法进行拦截和增强

  private final Object target;
  private final Interceptor interceptor;
  private final Map<Class<?>, Set<Method>> signatureMap;

  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  public static Object wrap(Object target, Interceptor interceptor) {
    // 1. 解析拦截器上的@Signature注解信息为Map<Class<?>, Set<Method>>结构
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    // 2. getAllInterfaces() 遍历type的所有超类以及所有的接口
    // 检查其是否在signatureMap.keySet键集合中,在就返回
    Class<?> type = target.getClass();
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    if (interfaces.length > 0) {
      // 3. 为其创建代理对象
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          interfaces,
          // 4. ❗️❗️❗️Plugin是继承的InvocationHandler
          new Plugin(target, interceptor, signatureMap));
    }
    return target;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // 执行目标方法

    try {
      // 1. 检查method的声明类是否在signatureMap中
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());
      if (methods != null && methods.contains(method)) {
        // 2. 检查method是否在Set<Method>需要拦截的方法集合
        // 3. 如果需要拦截,调用  interceptor.intercept(new Invocation(target, method, args))
        return interceptor.intercept(new Invocation(target, method, args));
      }
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    // 用来处理 @Interceptors 注解
    // 返回 Map<Class<?>, Set<Method>>
    // key 就是 需要拦截的类
    // value 就是 需要拦截的类上的指定方法

    // 1. 必须有@Intercepts注解否则报错
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
    if (interceptsAnnotation == null) {
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
    }
    // 2. @Signature 数组
    Signature[] sigs = interceptsAnnotation.value();
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
    for (Signature sig : sigs) {
      // 2.1 sig.type() 作为 signatureMap 的key
      Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
      try {
        // 2.2 根据 sig 的 type/method/args 查找需要拦截的方法
        Method method = sig.type().getMethod(sig.method(), sig.args());
        methods.add(method);
      } catch (NoSuchMethodException e) {
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    return signatureMap;
  }

  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    // 作用: 遍历type的所有超类以及所有的接口
    // 检查其是否在signatureMap.keySet键集合中,在就返回

    Set<Class<?>> interfaces = new HashSet<>();
    while (type != null) {
      for (Class<?> c : type.getInterfaces()) {
        if (signatureMap.containsKey(c)) {
          interfaces.add(c);
        }
      }
      type = type.getSuperclass();
    }
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }

}
