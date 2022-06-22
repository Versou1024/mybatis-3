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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * @author Clinton Begin
 */
public interface ObjectWrapper {
  // 对象包装器 -- 主要是直接提供各个访问器的API
  // 实现类如下
  // CollectionWrapper
  //    BaseWrapper
  //      BeanWrapper
  //      MapWrapper

  Object get(PropertyTokenizer prop);

  void set(PropertyTokenizer prop, Object value);

  // 查找指定name的属性

  String findProperty(String name, boolean useCamelCaseMapping);

  // get/set方法的名

  String[] getGetterNames();

  String[] getSetterNames();

  // 指定name的get/set的形参类型/返回类型

  Class<?> getSetterType(String name);

  Class<?> getGetterType(String name);

  // 检查是否有指定name的set/get

  boolean hasSetter(String name);

  boolean hasGetter(String name);

  MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

  // 是否为集合/添加元素/添加集合

  boolean isCollection();

  void add(Object element);

  <E> void addAll(List<E> element);

}
