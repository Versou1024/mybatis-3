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

import org.apache.ibatis.session.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 */
public class ParameterMap {
  /**
   *
   <!ELEMENT parameterMap (parameter+)?>
   <!ATTLIST parameterMap
   id CDATA #REQUIRED
   type CDATA #REQUIRED
   >

   <!ELEMENT parameter EMPTY>
   <!ATTLIST parameter
   property CDATA #REQUIRED
   javaType CDATA #IMPLIED
   jdbcType CDATA #IMPLIED
   mode (IN | OUT | INOUT) #IMPLIED
   resultMap CDATA #IMPLIED
   scale CDATA #IMPLIED
   typeHandler CDATA #IMPLIED
   >
   */

  // ParameterMap 属性引用的id -- 三种情况
  // 1. DML标签parameterType属性的值 + "-Inline" 作为id
  // 2. 使用<parameterMap>标签的情况                           -- 该方法基本上已经废弃,被DML标签的parameterType和autoMapping替换掉
  // 3. 上述两种情况都没有使用时, 其 id = "defaultParameterMap"
  private String id;
  // 归属类型 -- 对应上述三种情况
  // 1. 就是DML标签parameterType属性引用的全限定类名
  // 2. <parameterMap>标签的type属性值的全限定类名
  // 3. 上述两种情况都没有使用时, type = null
  private Class<?> type;
  // <parameterMap>标签下的子标签<parameter>的集合
  private List<ParameterMapping> parameterMappings;

  private ParameterMap() {
  }

  public static class Builder {
    private ParameterMap parameterMap = new ParameterMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ParameterMapping> parameterMappings) {
      parameterMap.id = id;
      parameterMap.type = type;
      parameterMap.parameterMappings = parameterMappings;
    }

    public Class<?> type() {
      return parameterMap.type;
    }

    public ParameterMap build() {
      //lock down collections
      parameterMap.parameterMappings = Collections.unmodifiableList(parameterMap.parameterMappings);
      return parameterMap;
    }
  }

  public String getId() {
    return id;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ParameterMapping> getParameterMappings() {
    return parameterMappings;
  }

}
