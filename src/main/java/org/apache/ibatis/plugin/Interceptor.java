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

import java.util.Properties;

/**
 * @author Clinton Begin
 */
public interface Interceptor {
  // 拦截器 Interceptor
  // 可以通过 plugin() 对 ParameterHandler\ResultHandler\StatementHandler\Executor进行插件扩展
  // 对执行方法进行拦截intercept()

  // 该接口没有内置实现,完全供用户实现哦

  // 子类必须实现的方法
  // 对@Interceptor的@Signature表示拦截的方法 -- 进行拦截处理
  // 需要用户定义增强逻辑
  Object intercept(Invocation invocation) throws Throwable;

  default Object plugin(Object target) {
    // target可以是 ParameterHandler/ResultSetHandler/StatementHandler/Executor 四个对象之一
    return Plugin.wrap(target, this);
  }

  // 用来将解析处的xml文件的属性作为properties设置到当前 Interceptor 的属性上
  default void setProperties(Properties properties) {
    // NOP
  }

}
