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
package org.apache.ibatis.binding;

import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {
  // 为Mapper接口创建Proxy代理对象

  // mapper接口的class对象
  private final Class<T> mapperInterface;

  // 由于每次通过是通过newInstance()重新创建的一个Mapper接口的代理对象
  // 那么就需要每次都去解析Mapper接口的方法 -- 通过将 methodCache 传递给 MapperProxy
  // 以此告诉对方将第一个解析出的MapperMethod给缓存起来
  private final Map<Method, MapperMethod> methodCache = new ConcurrentHashMap<>();

  public MapperProxyFactory(Class<T> mapperInterface) {
    // 必须指定给哪一个mapper接口创建代理
    this.mapperInterface = mapperInterface;
  }

  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  public Map<Method, MapperMethod> getMethodCache() {
    return methodCache;
  }

  // 核心一: 为mapperInterface创建代理对象的方法

  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    // 累加值啊       ClassLoader
    // 代理的接口     mapperInterface
    // 引用的Handler reflect.InvocationHandler
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  public T newInstance(SqlSession sqlSession) {
    // 创建MapperProxy交给上面的newInstance()
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
    return newInstance(mapperProxy);
  }

}
