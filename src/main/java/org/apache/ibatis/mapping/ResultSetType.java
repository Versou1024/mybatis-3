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
package org.apache.ibatis.mapping;

import java.sql.ResultSet;

/**
 * @author Clinton Begin
 */
public enum ResultSetType {
  // <select> 标签 resultSetType 属性用于控制jdbc中ResultSet对象的行为,他的取值对应着ResultSetType枚举

  /**
   * behavior with same as unset (driver dependent).
   *
   * @since 3.5.0
   */
  // 默认行为
  DEFAULT(-1),
  // ResultSet结果期只能是向前一行一行的读取
  // ResultSet对象只能前进不能后退,即,在处理结果集时,我们可以由第一行滚动到第二行,但是不能从第二行滚动到第一 -- 阉割了部分数据访问功能
  FORWARD_ONLY(ResultSet.TYPE_FORWARD_ONLY),
  // ResultSet结果集可以前后查看,整个结果集都被缓存下俩,对底层不敏感[使用update更新操作,不会导致ResultSet缓存变化]
  // ResultSet对象不仅可以前进,还可以后退,甚至还能通过相对坐标或者绝对坐标跳转到指定行.
  // 但是TYPE_SCROLL_INSENSITIVE模式下的ResultSet对象会将数据库查询结果缓存起来,在下次操作时,直接读取缓存中的数据,所以,该模式下的ResultSet对象对底层数据的变化不敏感.
  // 因此,如果在读取时,底层数据被其他线程修改,ResultSet对象依然会读取到之前获取到的数据
  SCROLL_INSENSITIVE(ResultSet.TYPE_SCROLL_INSENSITIVE),
  // ResultSet结果集可以前后查看,整个结果集的rowId都被缓存下俩,对底层敏感[使用update更新操作,ResultSet每次使用其去查询时会根据RowId去数据库查询出来]
  // TYPE_SCROLL_SENSITIVE模式下的ResultSet对象同样可以前进后退,可以跳转到任意行.
  // 但是和TYPE_SCROLL_INSENSITIVE模式不同的是,TYPE_SCROLL_SENSITIVE模式缓存的是数据记录的rowid,在下次操作时ResultSet会根据缓存的rowid重新从数据库读取数据,所以TYPE_SCROLL_SENSITIVE模式能够实时感知底层数据的变化.
  SCROLL_SENSITIVE(ResultSet.TYPE_SCROLL_SENSITIVE); // 前后滚动 -- 大小敏感

  private final int value;

  ResultSetType(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }
}
