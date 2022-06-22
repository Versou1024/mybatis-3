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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.StatementType;

/**
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Options {
  // 搭配

  /**
   * The options for the {@link Options#flushCache()}.
   * The default is {@link FlushCachePolicy#DEFAULT}
   */
  enum FlushCachePolicy {
    /** <code>false</code> for select statement; <code>true</code> for insert/update/delete statement. */
    DEFAULT, // 选择语句为false ； true插入/更新/删除语句为真。
    /** Flushes cache regardless of the statement type. */
    TRUE, // 无论语句类型如何，都会刷新缓存
    /** Does not flush cache regardless of the statement type. */
    FALSE // 无论语句类型如何，都不会刷新缓存。
  }

  boolean useCache() default true;

  FlushCachePolicy flushCache() default FlushCachePolicy.DEFAULT;

  ResultSetType resultSetType() default ResultSetType.DEFAULT;

  StatementType statementType() default StatementType.PREPARED; // 语句类型

  int fetchSize() default -1; // 抓取大小

  int timeout() default -1; // 超时时间

  boolean useGeneratedKeys() default false; // 是否使用生成的key -- 搭配@Insert/@Update

  String keyProperty() default ""; // key 放在那个属性上

  String keyColumn() default ""; // key 从那个列上获取出来

  String resultSets() default ""; // 结果集
}
