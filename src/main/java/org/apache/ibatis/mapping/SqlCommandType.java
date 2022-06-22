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
package org.apache.ibatis.mapping;

/**
 * @author Clinton Begin
 */
public enum SqlCommandType {
  // sql语句类型
  // Unknown 表示错误的情况 -- 会提出异常
  // FLUSH 表示Mapper接口中的方法 -- 仅仅是用来做刷新操作的,不需要执行真的方法
  UNKNOWN, INSERT, UPDATE, DELETE, SELECT, FLUSH;
}
