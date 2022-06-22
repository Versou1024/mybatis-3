/**
 *    Copyright 2009-2015 the original author or authors.
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

public interface ReflectorFactory {
  // 创建 反射器 的工厂

  // 是否已启用类缓存
  boolean isClassCacheEnabled();

  // 设置类缓存已启用
  void setClassCacheEnabled(boolean classCacheEnabled);

  // 为指定type创建一个Reflector来缓存各个访问器
  Reflector findForClass(Class<?> type);
}
