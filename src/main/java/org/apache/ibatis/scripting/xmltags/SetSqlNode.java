/**
 *    Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.session.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 */
public class SetSqlNode extends TrimSqlNode {
  // 处理 <set> 标签的sql如何追加到contents中
  // note: SetSqlNode也是继承的TrimSqlNode哦

  private static final List<String> COMMA = Collections.singletonList(",");

  public SetSqlNode(Configuration configuration,SqlNode contents) {
    // 同理 -- 使用TrimSqlNode的能力,
    // 前缀 -- SET
    // 前缀覆盖/后缀覆盖 -- ,
    super(configuration, contents, "SET", COMMA, null, COMMA);
  }

}
