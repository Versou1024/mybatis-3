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
package org.apache.ibatis.mapping;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.*;

/**
 * @author Clinton Begin
 */
public class ResultMap {
  // 用来映射到 ResultMap 标签上哦

  private Configuration configuration;

  // resultMap的id
  private String id;
  // resultMap整体的对象的Class的type
  private Class<?> type;
  // <ResultMap>标签所有的子标签集合
  private List<ResultMapping> resultMappings;
  // <ResultMap>标签下的<id> 或者  <ResultMap>的<Constructor>标签下的<idArg>标签 集合
  private List<ResultMapping> idResultMappings;
  // <ResultMap>的<Constructor>标签下的<idArg>和<arg>标签集合 -- 如果有的话,还将被处理为构造器的形参顺序进行排列
  private List<ResultMapping> constructorResultMappings;
  // <ResultMap>标签下的<id>和<result>标签集合
  private List<ResultMapping> propertyResultMappings;
  // 映射的列名 column 属性 -- 包括 id/result/collection/association 的普通column属性
  // 而当collection/association标签使用了嵌套查询select属性后 -- column将被作为特殊的属性,形式上为{column1=param1,column2=param2}
  private Set<String> mappedColumns;
  // 映射的属性 property 属性
  private Set<String> mappedProperties;
  // 鉴别器
  private Discriminator discriminator;
  // 是否嵌套 映射resultMap-- 即association/collection中有resultmap属性
  private boolean hasNestedResultMaps;
  // 是否嵌套查询 -- 即association/collection中有select属性
  private boolean hasNestedQueries;
  // 是否自动映射 -- 即将列名自动映射到同名的驼峰命名的属性上的setter方法
  private Boolean autoMapping;

  private ResultMap() {
  }

  public static class Builder {
    private static final Log log = LogFactory.getLog(Builder.class);

    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      // 提前将 Configuration\id\type\resultMappings\autoMapping配置上去
      resultMap.configuration = configuration;
      // resultType 属性的的id 是 MappedStatement.id + "-inline"
      // resultMap 属性的id 是用户定义的
      resultMap.id = id;
      resultMap.type = type;
      // ❗️❗️❗️
      resultMap.resultMappings = resultMappings;
      resultMap.autoMapping = autoMapping;
    }

    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    public Class<?> type() {
      return resultMap.type;
    }

    public ResultMap build() {
      // 1. resultMap的id必须指定
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }
      // 2. 开始构造 resultMap 的集合
      resultMap.mappedColumns = new HashSet<>(); // <ResultMap>所有子标签中的column集合 以及 collection/association标签指定select后的特殊select属性值
      resultMap.mappedProperties = new HashSet<>(); // <ResultMap>所有子标签中的property集合 -- 当然对于<Constructor>标签下的idArg或Arg标签指的是name属性
      resultMap.idResultMappings = new ArrayList<>();  // <ResultMap>标签下的<id> 或者  <ResultMap>的<Constructor>标签下的<idArg>标签 集合
      resultMap.constructorResultMappings = new ArrayList<>(); // <ResultMap>的<Constructor>标签下的<idArg>和<arg>标签集合
      resultMap.propertyResultMappings = new ArrayList<>();   // <ResultMap>标签下的<id>和<result>标签集合
      final List<String> constructorArgNames = new ArrayList<>(); // 构造器的形参名
      // 3. 在构造器中,已经解析过的所有的ResultMappings放入打resultMap.resultMappings
      for (ResultMapping resultMapping : resultMap.resultMappings) {
        // 3.1 是否嵌套的查询 -- 比如有ResultMap标签的collection标签的select属性
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
        // 3.2 是否嵌套的ResultMap --  比如ResultMap标签的collection标签的resultMap属性
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
        final String column = resultMapping.getColumn();
        if (column != null) {
          // 3.2 全部添加到 mappedColumns 中
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
        } else if (resultMapping.isCompositeResult()) {
          // 3.3 是否有组合的特殊的column属性情况
          // 一般是 collection/association 标签中指定了select属性,然后通过column={column1=param1,column2=param2}
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            final String compositeColumn = compositeResultMapping.getColumn(); // 获取比如上面的column1\column2
            if (compositeColumn != null) {
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }
        // 3.4 <ResultMap>所有子标签中的property集合 -- 当然对于<Constructor>标签下的idArg或Arg标签指的是name属性
        final String property = resultMapping.getProperty();
        if (property != null) {
          resultMap.mappedProperties.add(property);
        }
        // 3.5 构造器标 - <Constructor>标签下的<idArg>和<arg>标签
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          resultMap.constructorResultMappings.add(resultMapping);
          if (resultMapping.getProperty() != null) {
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          // 3.6 <id>或<result>标签
          resultMap.propertyResultMappings.add(resultMapping);
        }
        // 3.7 id标签或idArg标签
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          resultMap.idResultMappings.add(resultMapping);
        }
      }
      if (resultMap.idResultMappings.isEmpty()) {
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }
      // 4. 如果使用构造器标签 -- 就需要查看type上是否有对应数量且形参名相同的构造器
      // 有的话,就按照构造器的顺序对resultMap.constructorResultMappings进行排序
      if (!constructorArgNames.isEmpty()) {
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
        if (actualArgNames == null) {
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }
        resultMap.constructorResultMappings.sort((o1, o2) -> {
          int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
          int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
          return paramIdx1 - paramIdx2;
        });
      }
      // lock down collections
      // 5. 全部修改为不可修改的List
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (constructorArgNames.size() == paramTypes.length) {
          List<String> paramNames = getArgNames(constructor);
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames;
          }
        }
      }
      return null;
    }

    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }

    private List<String> getArgNames(Constructor<?> constructor) {
      List<String> paramNames = new ArrayList<>();
      List<String> actualParamNames = null;
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length;
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          if (annotation instanceof Param) {
            name = ((Param) annotation).value();
            break;
          }
        }
        if (name == null && resultMap.configuration.isUseActualParamName()) {
          if (actualParamNames == null) {
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          if (actualParamNames.size() > paramIndex) {
            name = actualParamNames.get(paramIndex);
          }
        }
        paramNames.add(name != null ? name : "arg" + paramIndex);
      }
      return paramNames;
    }
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public boolean hasNestedQueries() {
    return hasNestedQueries;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ResultMapping> getResultMappings() {
    return resultMappings;
  }

  public List<ResultMapping> getConstructorResultMappings() {
    return constructorResultMappings;
  }

  public List<ResultMapping> getPropertyResultMappings() {
    return propertyResultMappings;
  }

  public List<ResultMapping> getIdResultMappings() {
    return idResultMappings;
  }

  public Set<String> getMappedColumns() {
    return mappedColumns;
  }

  public Set<String> getMappedProperties() {
    return mappedProperties;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public void forceNestedResultMaps() {
    hasNestedResultMaps = true;
  }

  public Boolean getAutoMapping() {
    return autoMapping;
  }

}
