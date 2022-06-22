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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lru (least recently used) cache decorator.
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {
  // 最近最少使用的Cache缓存
  // 驱除策略型的Cache -- 需要在@CacheNamespace或<cache>标签中通过eviction属性来指定
  // 还有一个FifoCache也是通过上面来指定的

  // 代理模式
  private final Cache delegate;
  // 缓存key
  private Map<Object, Object> keyMap;
  // 最近最少使用的key -- 当超过用户指定的size或默认的size=1024时
  private Object eldestKey;

  public LruCache(Cache delegate) {
    // 大小时默认的1024哦
    this.delegate = delegate;
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(final int size) {
    // 重写LinkedHashMap的removeEldestEntry()方法
    // 当size超过比如默认的1024时,表示超过缓存大小,需要清除最近最少使用的哦
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      // 如果此映射应删除其最旧的条目，则返回true 。
      // 在将新条目插入映射后， put和putAll调用此方法。它为实现者提供了在每次添加新条目时删除最旧条目的机会。
      // 如果映射表示缓存，这很有用：它允许映射通过删除过时的条目来减少内存消耗
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        if (tooBig) {
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  @Override
  public void putObject(Object key, Object value) {
    // 添加一对键值对

    // 1. 添加到delegate中
    delegate.putObject(key, value);
    // 2. 循环键列表
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {
    keyMap.get(key); //touch
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    // 清空 delegate\keyMap

    delegate.clear();
    keyMap.clear();
  }

  private void cycleKeyList(Object key) {
    // 在 keyMap.put(key,key)的过程中会触发

    // 2. 再向keyMap中存入这个key其重写的removeEldestEntry()方法
    // 如果超过默认值1024,就会是的eldestKey不为空,然后将其移除出去
    keyMap.put(key, key);
    // 3. 如果 eldestKey 不为空,就调用 delegate.removeObject(eldestKey) -- 移除出去
    if (eldestKey != null) {
      delegate.removeObject(eldestKey);
      eldestKey = null;
    }
  }

}
