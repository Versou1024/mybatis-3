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
package org.apache.ibatis.executor.loader.cglib;

import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.apache.ibatis.executor.loader.*;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 */
public class CglibProxyFactory implements ProxyFactory {

  private static final String FINALIZE_METHOD = "finalize";
  private static final String WRITE_REPLACE_METHOD = "writeReplace";

  public CglibProxyFactory() {
    try {
      Resources.classForName("net.sf.cglib.proxy.Enhancer");
    } catch (Throwable e) {
      throw new IllegalStateException("Cannot enable lazy loading because CGLIB is not available. Add CGLIB to your classpath.", e);
    }
  }

  @Override
  public Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    // target 目标对象
    // lazyLoader 懒加载器
    // constructorArgTypes 是target创造器的形参class数组
    // constructorArgs 是target创造器的形参值数组
    return EnhancedResultObjectProxyImpl.createProxy(target, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
  }

  public Object createDeserializationProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    return EnhancedDeserializationProxyImpl.createProxy(target, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
  }

  static Object crateProxy(Class<?> type, Callback callback, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    // 1. 使用Cglib创建代理对象
    // Enhancer -> setCallback() -> setSuperClass() ->
    Enhancer enhancer = new Enhancer();
    enhancer.setCallback(callback);
    enhancer.setSuperclass(type);
    try {
      // 2. 获取目标中 名为"writeReplace",形参为空 的方法
      type.getDeclaredMethod(WRITE_REPLACE_METHOD);
      // ObjectOutputStream will call writeReplace of objects returned by writeReplace
      if (LogHolder.log.isDebugEnabled()) {
        LogHolder.log.debug(WRITE_REPLACE_METHOD + " method was found on bean " + type + ", make sure it returns this");
      }
    } catch (NoSuchMethodException e) {
      // 3. 没有指定名为"writeReplace"且为空形参的方法
      // 对Enhancer额外实现WriteReplaceInterface接口
      enhancer.setInterfaces(new Class[]{WriteReplaceInterface.class});
    } catch (SecurityException e) {
      // nothing to do here
    }
    // 4. 根据 constructorArgTypes/constructorArgs 合理创建代理对象
    Object enhanced;
    if (constructorArgTypes.isEmpty()) {
      enhanced = enhancer.create(); // 空参构造
    } else {
      Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
      Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
      enhanced = enhancer.create(typesArray, valuesArray); // 有参构造
    }
    return enhanced;
  }

  private static class EnhancedResultObjectProxyImpl implements MethodInterceptor {
    // 回调的拦截器

    private final Class<?> type;
    private final ResultLoaderMap lazyLoader;
    private final boolean aggressive;
    private final Set<String> lazyLoadTriggerMethods;
    private final ObjectFactory objectFactory;
    private final List<Class<?>> constructorArgTypes;
    private final List<Object> constructorArgs;

    private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      this.type = type;
      this.lazyLoader = lazyLoader;
      // ❗️❗️❗️ 是否为积极的延迟加载 -- 默认为false
      this.aggressive = configuration.isAggressiveLazyLoading();
      // ❗️❗️❗️ 获取延迟加载触发方法 --  默认是 equals\clone\hashCode\toString
      this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
      this.objectFactory = objectFactory;
      // 构造器的形参Class数组/构造器的形参值数组
      this.constructorArgTypes = constructorArgTypes;
      this.constructorArgs = constructorArgs;
    }

    // ❗️❗️❗️
    // 静态方法 -- 创建target的代理对象
    // 这个代理对象 -- 主要是在通过ResultSet构建返回的对象target后,如果有属性指定嵌套查询且懒加载Lazy的,那么就需要创建代理对象
    // 在获取这个属性的值时才去触发嵌套查询语句
    public static Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      // 1. 获取代理对象的type,以及实现MethodInterceptor的回调EnhancedResultObjectProxyImpl
      final Class<?> type = target.getClass();
      EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
      // 2. 创建代理对象 -- ❗️❗️❗️
      // 期间可能会让代理类实现一个WriteReplaceInterface的接口
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      // 3. 复制属性 -- 代理类创建之后 -- 将已经创建好的target中已有的属性给复制到代理类上去
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      // 4. 返回代理对虾干
      return enhanced;
    }

    @Override
    public Object intercept(Object enhanced, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
      final String methodName = method.getName();
      try {
        synchronized (lazyLoader) {
          // 1. 方法名等于 "writeReplace" -- 当代理类执行方法writeReplace
          if (WRITE_REPLACE_METHOD.equals(methodName)) {
            // 1.1 创建一个新的目标对象
            Object original;
            if (constructorArgTypes.isEmpty()) {
              original = objectFactory.create(type); // 无参
            } else {
              original = objectFactory.create(type, constructorArgTypes, constructorArgs); // 有参
            }
            // 1.2 将代理对象上现有的属性复制到original上
            PropertyCopier.copyBeanProperties(type, enhanced, original);
            // 1.3 lazyLoader.size() 等于0时将直接返回original
            if (lazyLoader.size() > 0) {
              return new CglibSerialStateHolder(original, lazyLoader.getProperties(), objectFactory, constructorArgTypes, constructorArgs);
            } else {
              return original;
            }
            // 结论1: 通过代理对象的writeReplace()方法可以获取原始对象的值 -- 不含有Lazy加载的属性哦
          }
          // 2 大部分情况执行到这里
          else {
            if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) { // why lazyLoader.size()
              // 2.1 如果是积极的触发懒加载 -- 那么直接调用lazyLoader.loadAll()
              // 如果不是积极的触发懒加载时只有方法为equals\clone\hashCode\toString
              if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
                lazyLoader.loadAll();
              }
              // 2.2 调用的方法为set方法时,获取要set的属性,然后从lazyLoader中移除出去
              else if (PropertyNamer.isSetter(methodName)) {
                final String property = PropertyNamer.methodToProperty(methodName);
                lazyLoader.remove(property);
              }
              // 2.3 调用的方法为set方法时,获取要get的属性,检查LazyLoader中是否有对应的属性,有的话,启动LazyLoader.load()方法
              // 实际触发到 ResultLoader.load() 方法
              else if (PropertyNamer.isGetter(methodName)) {
                final String property = PropertyNamer.methodToProperty(methodName);
                if (lazyLoader.hasLoader(property)) {
                  lazyLoader.load(property);
                }
              }
            }
          }
        }
        // 调用目标类的原始方法
        return methodProxy.invokeSuper(enhanced, args);
      } catch (Throwable t) {
        throw ExceptionUtil.unwrapThrowable(t);
      }
    }
  }

  private static class EnhancedDeserializationProxyImpl extends AbstractEnhancedDeserializationProxy implements MethodInterceptor {

    private EnhancedDeserializationProxyImpl(Class<?> type, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }

    public static Object createProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      final Class<?> type = target.getClass();
      EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      return enhanced;
    }

    @Override
    public Object intercept(Object enhanced, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
      final Object o = super.invoke(enhanced, method, args);
      return o instanceof AbstractSerialStateHolder ? o : methodProxy.invokeSuper(o, args);
    }

    @Override
    protected AbstractSerialStateHolder newSerialStateHolder(Object userBean, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      return new CglibSerialStateHolder(userBean, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }
  }

  private static class LogHolder {
    private static final Log log = LogFactory.getLog(CglibProxyFactory.class);
  }

}
