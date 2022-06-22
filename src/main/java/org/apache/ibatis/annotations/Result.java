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
public @interface Result {
  // 用于设置结果集
  // 搭配 @Results 使用
  // 比如
  // @SelectProvider(type=UserProvider.class,method="queryUserById")
  // @Options(useCache=true,flushCache=Options.FlushCachePolicy.FALSE,timeout=10000)
  // @Results({
  //    @Result(column="userId",property="userId",id=true),
  //    @Result(column="userName",property="userName"),
  //    @Result(column="updateDate",property="updateDate",jdbcType=JdbcType.DATE),
  //    @Result(column="gendarId",property="gendar",one=@One(select="com.bwf.dao.IGendarDao.findGendarById"))
  //    @Result(column="userId",property="hobbyList",many=@Many(select="com.bwf.dao.IHobbyDao.findHobbyListByUserId")
  // })

  boolean id() default false; // 该属性/列是否为id

  String column() default ""; // 属性的对应列名

  String property() default ""; // 列名对应的属性名

  Class<?> javaType() default void.class; // java类型

  JdbcType jdbcType() default JdbcType.UNDEFINED; // jdbc类型

  Class<? extends TypeHandler> typeHandler() default UnknownTypeHandler.class; // 指定使用TypeHandler

  One one() default @One; // 多对一

  Many many() default @Many; // 一对多
}
