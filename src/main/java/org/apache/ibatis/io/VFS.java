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
package org.apache.ibatis.io;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Provides a very simple API for accessing resources within an application server.
 *
 * @author Ben Gunter
 */
public abstract class VFS {
  // 位于:  org.apache.ibatis.io

  // 作用:
  // 提供一种非常简单的API来访问一个应用服务的资源
  private static final Log log = LogFactory.getLog(VFS.class);

  // 两个内置实现
  public static final Class<?>[] IMPLEMENTATIONS = { JBoss6VFS.class, DefaultVFS.class };

  // 最终使用的实现类
  public static final List<Class<? extends VFS>> USER_IMPLEMENTATIONS = new ArrayList<>();

  /** Singleton instance holder. */
  private static class VFSHolder {
    // 单例Bean的Holder
    static final VFS INSTANCE = createVFS();

    @SuppressWarnings("unchecked")
    static VFS createVFS() {
      // 1. 先尝试用户实现，然后是内置
      List<Class<? extends VFS>> impls = new ArrayList<>();
      impls.addAll(USER_IMPLEMENTATIONS); // 用户的VFS
      impls.addAll(Arrays.asList((Class<? extends VFS>[]) IMPLEMENTATIONS)); // 内建的VFS

      // 2. 尝试每个实现类，直到找到一个有效的类
      VFS vfs = null;
      for (int i = 0; vfs == null || !vfs.isValid(); i++) { // 直到: vfs非空 并且 vfs有效的
        Class<? extends VFS> impl = impls.get(i);
        try {
          // 2.1 创建vfs
          vfs = impl.newInstance();
          // 2.2 vfs为空 || vfs非空,但vfs也失效 -> 日志输出: 表示当前VFS无效
          if (vfs == null || !vfs.isValid()) {
            if (log.isDebugEnabled()) {
              log.debug("VFS implementation " + impl.getName() +
                  " is not valid in this environment.");
            }
          }
        } catch (InstantiationException | IllegalAccessException e) {
          log.error("Failed to instantiate " + impl, e);
          return null;
        }
      }

      if (log.isDebugEnabled()) {
        log.debug("Using VFS adapter " + vfs.getClass().getName());
      }

      // 返回vfs
      return vfs;
    }
  }

  /**
   * Get the singleton {@link VFS} instance. If no {@link VFS} implementation can be found for the
   * current environment, then this method returns null.
   */
  public static VFS getInstance() {
    // 获取VFS实例
    return VFSHolder.INSTANCE;
  }

  /**
   * Adds the specified class to the list of {@link VFS} implementations. Classes added in this
   * manner are tried in the order they are added and before any of the built-in implementations.
   *
   * @param clazz The {@link VFS} implementation class to add.
   */
  public static void addImplClass(Class<? extends VFS> clazz) {
    // 添加: 实现类 ->
    if (clazz != null) {
      USER_IMPLEMENTATIONS.add(clazz);
    }
  }

  /** Get a class by name. If the class is not found then return null. */
  protected static Class<?> getClass(String className) {
    try {
      return Thread.currentThread().getContextClassLoader().loadClass(className);
//      return ReflectUtil.findClass(className);
    } catch (ClassNotFoundException e) {
      if (log.isDebugEnabled()) {
        log.debug("Class not found: " + className);
      }
      return null;
    }
  }

  /**
   * Get a method by name and parameter types. If the method is not found then return null.
   *
   * @param clazz The class to which the method belongs.
   * @param methodName The name of the method.
   * @param parameterTypes The types of the parameters accepted by the method.
   */
  protected static Method getMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
    // 获取指定method
    if (clazz == null) {
      return null;
    }
    try {
      return clazz.getMethod(methodName, parameterTypes);
    } catch (SecurityException e) {
      log.error("Security exception looking for method " + clazz.getName() + "." + methodName + ".  Cause: " + e);
      return null;
    } catch (NoSuchMethodException e) {
      log.error("Method not found " + clazz.getName() + "." + methodName + "." + methodName + ".  Cause: " + e);
      return null;
    }
  }

  /**
   * Invoke a method on an object and return whatever it returns.
   *
   * @param method The method to invoke.
   * @param object The instance or class (for static methods) on which to invoke the method.
   * @param parameters The parameters to pass to the method.
   * @return Whatever the method returns.
   * @throws IOException If I/O errors occur
   * @throws RuntimeException If anything else goes wrong
   */
  @SuppressWarnings("unchecked")
  protected static <T> T invoke(Method method, Object object, Object... parameters) throws IOException, RuntimeException {
    // 引用目标对象的方法
    try {
      return (T) method.invoke(object, parameters);
    } catch (IllegalArgumentException | IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof IOException) {
        throw (IOException) e.getTargetException();
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Get a list of {@link URL}s from the context classloader for all the resources found at the
   * specified path.
   *
   * @param path The resource path.
   * @return A list of {@link URL}s, as returned by {@link ClassLoader#getResources(String)}.
   * @throws IOException If I/O errors occur
   */
  protected static List<URL> getResources(String path) throws IOException {
    return Collections.list(Thread.currentThread().getContextClassLoader().getResources(path));
  }

  /** Return true if the {@link VFS} implementation is valid for the current environment. */
  public abstract boolean isValid();
  // 如果VFS实现对当前环境有效，则返回 true

  /**
   * Recursively list the full resource path of all the resources that are children of the
   * resource identified by a URL.
   *
   * @param url The URL that identifies the resource to list.
   * @param forPath The path to the resource that is identified by the URL. Generally, this is the
   *            value passed to {@link #getResources(String)} to get the resource URL.
   * @return A list containing the names of the child resources.
   * @throws IOException If I/O errors occur
   */
  protected abstract List<String> list(URL url, String forPath) throws IOException;

  /**
   * Recursively list the full resource path of all the resources that are children of all the
   * resources found at the specified path.
   *
   * @param path The path of the resource(s) to list.
   * @return A list containing the names of the child resources.
   * @throws IOException If I/O errors occur
   */
  public List<String> list(String path) throws IOException {
    // 递归列出作为在指定路径中找到的所有资源的子级的所有资源的完整资源路径

    List<String> names = new ArrayList<>();
    for (URL url : getResources(path)) {
      names.addAll(list(url, path));
    }
    return names;
  }
}
