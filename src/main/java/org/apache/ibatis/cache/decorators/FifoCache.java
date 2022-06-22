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

import java.util.Deque;
import java.util.LinkedList;

/**
 * FIFO (first in, first out) cache decorator.
 *
 * @author Clinton Begin
 */
public class FifoCache implements Cache {
  // 先进先出的Cache缓存
  // 驱除策略型的Cache -- 需要在@CacheNamespace或<cache>标签中通过eviction属性来指定
  // 还有一个LruCache也是通过上面来指定的

  // 代理模式
  private final Cache delegate;
  // 用于保存cacheKey的先后顺序
  private final Deque<Object> keyList;
  private int size;

  public FifoCache(Cache delegate) {
    this.delegate = delegate;
    this.keyList = new LinkedList<>();
    // 默认是1025个
    this.size = 1024;
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.size = size;
  }

  @Override
  public void putObject(Object key, Object value) {
    // 1. 是否需要FIFO
    cycleKeyList(key);
    // 2. 然后再存入元素
    delegate.putObject(key, value);
  }

  @Override
  public Object getObject(Object key) {
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyList.clear();
  }

  private void cycleKeyList(Object key) {
    // ❗️❗️❗️

    // 1. 向keyList尾部添加key -- 注意是添加到Last上
    keyList.addLast(key);
    // 2. 如果添加后,超过委托对象的size,就需要从keyList中移除第一个[先进先出原则]
    // 然后从delegate中移除这个对象
    if (keyList.size() > size) {
      Object oldestKey = keyList.removeFirst();
      delegate.removeObject(oldestKey);
    }
    // 3. 显而易见
    // 实际的key和value是存储在delegate中
    // fifoCache只是确定FIFO的key的list集合
  }

}
