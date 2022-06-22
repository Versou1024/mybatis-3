/**
 *    Copyright 2009-2016 the original author or authors.
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

import org.apache.ibatis.mapping.StatementType;

/**
 * @author Clinton Begin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SelectKey {
  // 等价与 <SelectKey> 标签
  // @SelectKey(statement="select truncate(rand()*100.0)",keyProperty="id",before="true',keyProperty="id",resultType='int")
  // 即表示在SQL执行之前 --
  // 先执行 select truncate(rand()*100.0) 生成一个值
  // 该值对应的属性为id,列名也为id
  // 且生成值的类型是int类型

  // 可以用于在insert之前生成id,并且在插入的对象中可以获取到这个id

  String[] statement();

  String keyProperty();

  String keyColumn() default "";

  boolean before(); // statement 是否需要在正式SQL之前执行

  Class<?> resultType();

  StatementType statementType() default StatementType.PREPARED;
}
