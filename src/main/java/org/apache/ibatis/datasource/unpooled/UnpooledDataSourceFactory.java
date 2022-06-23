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
package org.apache.ibatis.datasource.unpooled;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.util.Properties;
import javax.sql.DataSource;

/**
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

  private static final String DRIVER_PROPERTY_PREFIX = "driver.";
  private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

  protected DataSource dataSource;

  public UnpooledDataSourceFactory() {
    // UnpooledDataSourceFactory 的 dataSource 是 UnpooledDataSource
    // 其子类 pooledDataSourceFactory 的 dataSource 是 pooledDataSource
    // 其实这里设计有问题 -> 应该是 创建一个 BaseDataSourceFactory 包含所有功能
    // 然后 UnpooledDataSourceFactory 和 PooledDataSourceFactory 都继承他
    // 然后再构造函数中指定 dataSource
    this.dataSource = new UnpooledDataSource();
  }

  @Override
  public void setProperties(Properties properties) {
    // ❗️❗️❗️ -- driver/url/username/password等基本信息如何填充到DataSource中

    Properties driverProperties = new Properties();
    // 1. 利用元数据访问器metaDataSource将<dataSource>标签下的多个子标签<property>构成的properties中的属性填充到即将生成的dataSource中
    MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
    for (Object key : properties.keySet()) {
      String propertyName = (String) key;
      // 2. 以"driver."开头的,认为设置到驱动器的属性,而不是设置到DataSource
      if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
        String value = properties.getProperty(propertyName);
        driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
      } else if (metaDataSource.hasSetter(propertyName)) {
        // 3.1 根据DataSource中是否有对应的propertyName的set方法
        // 有的话,就调用set方法
        String value = (String) properties.get(propertyName);
        // 3.2 转换值
        Object convertedValue = convertValue(metaDataSource, propertyName, value);
        // 3.3 向 metaDataSource 设置属性名和属性值
        metaDataSource.setValue(propertyName, convertedValue);
      } else {
        throw new DataSourceException("Unknown DataSource property: " + propertyName);
      }
    }
    if (driverProperties.size() > 0) {
      // 4. 设置 driverProperties -- 99%的情况都是空的
      metaDataSource.setValue("driverProperties", driverProperties);
    }
  }

  @Override
  public DataSource getDataSource() {
    // 返回DataSource
    return dataSource;
  }

  private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
    Object convertedValue = value;
    // 1. 获取setter方法的class
    Class<?> targetType = metaDataSource.getSetterType(propertyName);
    // 2. 将String类型的Value转换为对应的Integer\Long\Boolean
    // 其余类型不支持转换哦
    if (targetType == Integer.class || targetType == int.class) {
      convertedValue = Integer.valueOf(value);
    } else if (targetType == Long.class || targetType == long.class) {
      convertedValue = Long.valueOf(value);
    } else if (targetType == Boolean.class || targetType == boolean.class) {
      convertedValue = Boolean.valueOf(value);
    }
    return convertedValue;
  }

}
