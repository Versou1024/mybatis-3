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
import org.apache.ibatis.cache.CacheException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple blocking decorator
 *
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {
  // 简单阻塞装饰器 EhCache 的 BlockingCache 装饰器的简单和低效版本。
  // 当在缓存中找不到元素时，它会在cacheKey上设置lock。这样，其他线程将等待阻塞直到该元素被其他线程 填充，而不是访问数据库。

  // 阻塞策略型的Cache -- 需要在@CacheNamespace或<cache>标签中通过blocking属性来指定


  // 装饰器模式

  private long timeout;
  private final Cache delegate;
  private final ConcurrentHashMap<Object, ReentrantLock> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object value) {
    // 1. 向委托类存入key-value后,就需要释放key相关的锁
    try {
      delegate.putObject(key, value);
    } finally {
      releaseLock(key);
    }
  }

  @Override
  public Object getObject(Object key) {
    // ❗️❗️❗️
    // 1. 向目标缓存获取key的value值,需要先去获取key的锁
    acquireLock(key);
    Object value = delegate.getObject(key);
    if (value != null) {
      // 2. 如果可以获取对应的value,就释放掉锁
      releaseLock(key);
    }
    // 3. 但是如果线程没有通过getObject()获取到对应的值,这个锁是不会被释放掉的
    // 而是需要这个线程去取数据库执行访问操作,然后将值putObject()填充到cache中时释放掉锁
    // 否则其他线程去getObject()同一个key时会阻塞直到
    return value;
  }

  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  private ReentrantLock getLockForKey(Object key) {
    // 为每把key创建一个可重入锁ReentrantLock
    return locks.computeIfAbsent(key, k -> new ReentrantLock());
  }

  private void acquireLock(Object key) {
    // 1. 获取指定key对应的lock,如果有timeout,那么就在指定时间获取锁 lock.trylock()
    // 否则永久阻塞等待获取锁 lock.lock()
    Lock lock = getLockForKey(key);
    if (timeout > 0) {
      try {
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        if (!acquired) {
          throw new CacheException("Couldn't get a lock in " + timeout + " for the key " +  key + " at the cache " + delegate.getId());
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    } else {
      lock.lock();
    }
  }

  private void releaseLock(Object key) {
    // 1. 释放锁
    ReentrantLock lock = locks.get(key);
    // 2. 若被当前线程给持有锁,就立即释放锁
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
