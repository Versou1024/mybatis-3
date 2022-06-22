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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Clinton Begin
 */
public class MapWrapper extends BaseWrapper {

  // 额外存储map对象
  private final Map<String, Object> map;

  public MapWrapper(MetaObject metaObject, Map<String, Object> map) {
    super(metaObject);
    this.map = map;
  }

  @Override
  public Object get(PropertyTokenizer prop) {
    // 比如 persons[1] 那么 prop.getName()就是persons + prop.getIndexedName()就是person[1]
    if (prop.getIndex() != null) {
      // 1. 从map中根据prop.getName()即查找persons名解析出对应的persons集合集合对象
      Object collection = resolveCollection(prop, map);
      // 2. 从集合collection即persons中根据 prop.getIndex()即1 调用 collection.get(1)即可
      // 最后返回 person[1] 的对象
      return getCollectionValue(prop, collection);
    } else {
      // 3. 对于没有Index
      // 假设 person 那么 prop.getName()就是person + prop.getIndex为0
      return map.get(prop.getName());
    }
  }

  @Override
  public void set(PropertyTokenizer prop, Object value) {
    if (prop.getIndex() != null) {
      Object collection = resolveCollection(prop, map);
      setCollectionValue(prop, collection, value);
    } else {
      map.put(prop.getName(), value);
    }
  }

  @Override
  public String findProperty(String name, boolean useCamelCaseMapping) {
    return name;
  }

  @Override
  public String[] getGetterNames() {
    return map.keySet().toArray(new String[map.keySet().size()]);
  }

  @Override
  public String[] getSetterNames() {
    return map.keySet().toArray(new String[map.keySet().size()]);
  }

  @Override
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return Object.class;
      } else {
        return metaValue.getSetterType(prop.getChildren());
      }
    } else {
      if (map.get(name) != null) {
        return map.get(name).getClass();
      } else {
        return Object.class;
      }
    }
  }

  @Override
  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return Object.class;
      } else {
        return metaValue.getGetterType(prop.getChildren());
      }
    } else {
      if (map.get(name) != null) {
        return map.get(name).getClass();
      } else {
        return Object.class;
      }
    }
  }

  @Override
  public boolean hasSetter(String name) {
    return true;
  }

  @Override
  public boolean hasGetter(String name) {
    // PropertyTokenizer 用来解析 person.hobbies 这种标签值
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 有"."一般就会进入这里面
    if (prop.hasNext()) {
      // 以上面为例,先判断是否有 person 对象,有就继续检查是否有
      // 注意一点: person[1].hobbies[2] 在 PropertyTokenizer 解析后 IndexedName 就是 person[1]
      // 但是需要知道一点:这是MapWrapper,你在Map结构中是不够直接这样获取的哦 -- 只能是在 CollectionWrapper中完成
      // 实际上在Mapper.xml中不难发现其实已经是无法使用 person.hobbies[1] -> 必须是去使用foreach标签进行遍历才可以哦
      if (map.containsKey(prop.getIndexedName())) {
        // 继续为person构建MetaObject
        MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
        if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
          return true;
        } else {
          // 继续递归检查
          // 仍然以上面为例, metaValue 就是 Person 对象 那么解析为 BeanWrapper
          // 最终跳转到 BeanWrapper.hasGetter() 做判断, 其中 children 就是 hobbies
          return metaValue.hasGetter(prop.getChildren());
        }
      } else {
        return false;
      }
    } else {
      // 只有没有"."的时候会进入这里比如直接就是
      // 比如 Mapper的Method是 selectOne(@Param("age") int age,@Param("person") Person person)
      // 对应的ParamObject就是Map结构的: age: age值 - person: person对象
      // 然后在SQL中使用 #{age} 就是直接从map结构中获取出来
      return map.containsKey(prop.getName());
    }
  }

  @Override
  public MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory) {
    HashMap<String, Object> map = new HashMap<>();
    set(prop, map);
    return MetaObject.forObject(map, metaObject.getObjectFactory(), metaObject.getObjectWrapperFactory(), metaObject.getReflectorFactory());
  }

  @Override
  public boolean isCollection() {
    return false;
  }

  @Override
  public void add(Object element) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <E> void addAll(List<E> element) {
    throw new UnsupportedOperationException();
  }

}
