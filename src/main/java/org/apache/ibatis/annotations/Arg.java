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
package org.apache.ibatis.annotations;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Arg {

  // 构造结果期ResultMap时,可以使用构造器来构造
  // 单参数构造方法，是 ConstructorArgs 集合的一部分。属性有：id, column, javaType, jdbcType, typeHandler, select 和 resultMap。
  // id 属性是布尔值，来标识用于比较的属性，和<idArg> XML 元素相似。

  boolean id() default false; // 是否为id主键列

  String column() default ""; // 列名

  Class<?> javaType() default void.class; // java类型 -- 形参类型

  JdbcType jdbcType() default JdbcType.UNDEFINED; // jdbc类型 -- 列的jdbc类型

  Class<? extends TypeHandler> typeHandler() default UnknownTypeHandler.class; // 转换器

  String select() default "";

  String resultMap() default "";

  String name() default ""; // 对应的形参名

  /**
   * @since 3.5.0
   */
  String columnPrefix() default ""; // 在相同时添加的列前缀
}
