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
package org.apache.ibatis.session;

/**
 * @author Clinton Begin
 */
public interface ResultHandler<T> {

  // 回调实际
  // DefaultResultSetHandler中每处理完ResultSet中的一行数据 [即将一行数据映射为指定的ResultMap或ResultType的对象后]
  // 并且这个对象存入到ResultContext中 -- 然后调用ResultHandler进行处理
  //  可以从ResultContext获取当前行转换出来的java对象 -- ResultContext.getResultObject()
  //  也可以从ResultContext获取当前处理的第几行数据 -- ResultContext.getResultCount()
  void handleResult(ResultContext<? extends T> resultContext);

}
