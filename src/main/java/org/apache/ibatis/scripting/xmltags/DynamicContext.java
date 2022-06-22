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
package org.apache.ibatis.scripting.xmltags;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * @author Clinton Begin
 */
public class DynamicContext {
  // 动态Context -- 用来各种SqlNode中apply方法中应用起来
  // 组装最终的SQL哦

  public static final String PARAMETER_OBJECT_KEY = "_parameter";
  public static final String DATABASE_ID_KEY = "_databaseId";

  static {
    // 会使用到OgnlRuntime哦
    OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
  }

  // binding是环境 -- 绑定了需要上下文变量 -- 可从中根据key获取对应的value
  private final ContextMap bindings;
  // sql的构建器
  // 字符串"[George:Sally:Fred]"可以按如下方式构造：
  // StringJoiner sj = new StringJoiner(":", "[", "]");
  // sj.add("George").add("Sally").add("Fred");
  // String desiredString = sj.toString();
  private final StringJoiner sqlBuilder = new StringJoiner(" ");
  // 对应 foreach 的起始值
  private int uniqueNumber = 0;

  public DynamicContext(Configuration configuration, Object parameterObject) {
    // 1. 非单个参数且无@Param注解修饰,且非list/array/collection时.就是Map结构哦
    // 即 ParamMap/StrictMap
    if (parameterObject != null && !(parameterObject instanceof Map)) {
      // 1.1 为当前的形参对象parameterObject创建MetaObject -- MetaObject可以用访问属性.解析占位符等等
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      // 1.2 从这个TypeHandler中去查找是否有对应的TypeHandler记下来处理
      boolean existsTypeHandler = configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
      bindings = new ContextMap(metaObject, existsTypeHandler);
    } else {
      // 2. 单个参数且无@Param注解修饰,且非list/array/collection
      bindings = new ContextMap(null, false);
    }
    // 3. 向 bindings 写入 parameterObject/databaseId 等基本属性到上下文中
    bindings.put(PARAMETER_OBJECT_KEY, parameterObject);          //  "_parameter"
    bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId()); //  "_databaseId"
  }

  public Map<String, Object> getBindings() {
    return bindings;
  }

  public void bind(String name, Object value) {
    bindings.put(name, value);
  }

  public void appendSql(String sql) {
    // 附加SQL片段
    sqlBuilder.add(sql);
  }

  public String getSql() {
    // 转换为最终的执行的sql
    return sqlBuilder.toString().trim();
  }

  public int getUniqueNumber() {
    return uniqueNumber++;
  }

  static class ContextMap extends HashMap<String, Object> {
    // DML标签中的Bind标签需要绑定的变量
    // 当然不止bind标签有需要绑定的变量
    // 还包括形参

    private static final long serialVersionUID = 2977601501966151582L;
    // 为parameterObject对象创建的参数元对象
    private final MetaObject parameterMetaObject;
    // parameterObject在TypeHandlerRegister中是否有对应的TypeHandler
    private final boolean fallbackParameterObject;

    public ContextMap(MetaObject parameterMetaObject, boolean fallbackParameterObject) {
      this.parameterMetaObject = parameterMetaObject;
      this.fallbackParameterObject = fallbackParameterObject;
    }

    @Override
    public Object get(Object key) {

      // 1. bind中是否有直接指定key
      // 比如
      // <bind name="age",value="18">
      // 传递key为age,就可以获取到18
      String strKey = (String) key;
      if (super.containsKey(strKey)) {
        return super.get(strKey);
      }

      if (parameterMetaObject == null) {
        return null;
      }

      // 2. 从对象中获取指定的属性
      // 比如 使用 where #{person.age} -> 最终实际上是从 BeanWrapper/MapWrapper 中根据strKey获取值
      if (fallbackParameterObject && !parameterMetaObject.hasGetter(strKey)) {
        return parameterMetaObject.getOriginalObject();
      } else {
        // 获取指定的strKey的value
        return parameterMetaObject.getValue(strKey);
      }
    }
  }

  static class ContextAccessor implements PropertyAccessor {

    @Override
    public Object getProperty(Map context, Object target, Object name) {
      Map map = (Map) target;

      Object result = map.get(name);
      if (map.containsKey(name) || result != null) {
        return result;
      }

      Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
      if (parameterObject instanceof Map) {
        return ((Map)parameterObject).get(name);
      }

      return null;
    }

    @Override
    public void setProperty(Map context, Object target, Object name, Object value) {
      Map<Object, Object> map = (Map<Object, Object>) target;
      map.put(name, value);
    }

    @Override
    public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }

    @Override
    public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }
  }
}
