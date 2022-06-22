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
package org.apache.ibatis.annotations;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;

import java.lang.annotation.*;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CacheNamespace {
  // 类级别的注解
  // 用来开启某个Mapper接口下的二级缓存

  Class<? extends Cache> implementation() default PerpetualCache.class; // 使用的缓存

  Class<? extends Cache> eviction() default LruCache.class; // 驱除策略

  long flushInterval() default 0; // 刷新间隔

  int size() default 1024; // 大小

  boolean readWrite() default true; // 是否只读

  boolean blocking() default false; // 是否阻塞

  /**
   * Property values for a implementation object.
   * @since 3.4.2
   */
  Property[] properties() default {};

}
