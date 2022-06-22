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

/**
 * This annotation can be used when a @Select method is using a
 * ResultHandler.  Those methods must have void return type, so
 * this annotation can be used to tell MyBatis what kind of object
 * it should build for each row.
 *
 * @since 3.2.0
 * @author Jeff Butler
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ResultType {
  // 此注解在使用了结果处理器的情况下使用。
  // 在这种情况下，返回类型为 void，所以 Mybatis 必须有一种方式决定对象的类型，用于构造每行数据。
  // 如果有 XML 的结果映射，请使用 @ResultMap 注解。
  // 如果结果类型在 XML 的 <select> 节点中指定了，就不需要其他的注解了。
  // 其他情况下则使用此注解。
  // 比如，如果 @Select 注解在一个将使用结果处理器的方法上，那么返回类型必须是 void 并且这个注解（或者@ResultMap）必选。这个注解仅在方法返回类型是 void 的情况下生效。
  Class<?> value();
}
