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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.session.Configuration;

import java.util.*;

/**
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {
  // 对应 <trim> 的 sqlNode

  private final SqlNode contents;
  // 四大属性
  private final String prefix;
  private final String suffix;
  private final List<String> prefixesToOverride;
  private final List<String> suffixesToOverride;
  private final Configuration configuration;

  public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, String prefixesToOverride, String suffix, String suffixesToOverride) {
    // prefixesToOverride 和 suffixesToOverride 可以通过 | 作为分隔符配置多个
    this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix, parseOverrides(suffixesToOverride));
  }

  protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, List<String> prefixesToOverride, String suffix, List<String> suffixesToOverride) {
    this.contents = contents;
    this.prefix = prefix;
    this.prefixesToOverride = prefixesToOverride;
    this.suffix = suffix;
    this.suffixesToOverride = suffixesToOverride;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 如何将 TrimSqlNode 应用到 context 上

    // 1. 使用定制的 -- FilteredDynamicContext  帮助 trim 标签做过滤即可
    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
    // 2. 对 contents即MixedSqlNode 应用到 filteredDynamicContext 上去
    // 因此 contents即MixedSqlNode 中节点的Sql最终是创建到 filteredDynamicContext的成员变阿玲 sqlBuffer上
    boolean result = contents.apply(filteredDynamicContext);
    // 3. 上面是将 content 应用到 filteredDynamicContext 的 sqlBuffer上
    // 而 FilteredDynamicContext 是封装了 真正的context 的
    // 需要将创建sql片段最终追加到 context 上才是完整的
    filteredDynamicContext.applyAll();
    return result;
  }

  private static List<String> parseOverrides(String overrides) {
    if (overrides != null) {
      final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
      final List<String> list = new ArrayList<>(parser.countTokens());
      while (parser.hasMoreTokens()) {
        list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
      }
      return list;
    }
    return Collections.emptyList();
  }

  private class FilteredDynamicContext extends DynamicContext {
    // DynamicContext包装器 -- 主要是提供Filter功能的能力 -- 符合trim的需求

    // 真实的DynamicContext
    private DynamicContext delegate;
    // 标志位 --
    // prefixApplied - 是否已经处理过前缀 - 即<trim>标签的prefix和prefixOverride属性
    // suffixApplied - 是否已经处理过后缀 - 即<trim>标签的suffix和suffixOverride属性
    private boolean prefixApplied;
    private boolean suffixApplied;

    // 当前Trim标签的sqlBuffer -- 不和delegate的sqlBuilder搅合
    // 当处理完后,通过FilteredDynamicContext.apply()将其sqlBuffer追加到delegate上
    private StringBuilder sqlBuffer;

    public FilteredDynamicContext(DynamicContext delegate) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefixApplied = false;
      this.suffixApplied = false;
      this.sqlBuffer = new StringBuilder();
    }

    public void applyAll() {
      // 1. 获取到<trim>内部标签的转换出来的最终的sql即 trimmedUppercaseSql
      sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
      String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
      // 2. 向 trimmedUppercaseSql 处理<trim>标签的要求
      if (trimmedUppercaseSql.length() > 0) {
        applyPrefix(sqlBuffer, trimmedUppercaseSql); // 添加<trim>标签的prefix/以及移除prefixOverride -- 处理前缀添加和移除
        applySuffix(sqlBuffer, trimmedUppercaseSql); // 添加<trim>标签的suffix/以及移除suffixOverride -- 处理后缀添加和移除
      }
      // 3. ❗️❗️❗️ 追加到 delegate即真实的context 上
      delegate.appendSql(sqlBuffer.toString());
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

    @Override
    public void appendSql(String sql) {
      sqlBuffer.append(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
      // 以前缀为例 ~~ 粗略查看

      if (!prefixApplied) {
        prefixApplied = true;
        // 1. 是否有 prefixesToOverride 属性 -- prefixesToOverride可以是一个数组,其中通过 "|"分割开来
        if (prefixesToOverride != null) {
          for (String toRemove : prefixesToOverride) {
            // 1.1 遍历 prefixesToOverride 检查 trimmedUppercaseSql 是否以其中某个元素开头
            // 如果满足 -- 就需要删除这个前缀
            // 一旦满足 -- 就会立即break
            if (trimmedUppercaseSql.startsWith(toRemove)) {
              sql.delete(0, toRemove.trim().length());
              break;
            }
          }
        }
        // 2. 是否有prefix属性,有的话就插入 " " + prefix
        if (prefix != null) {
          sql.insert(0, " ");
          sql.insert(0, prefix);
        }
      }
    }

    private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
      if (!suffixApplied) {
        suffixApplied = true;
        if (suffixesToOverride != null) {
          for (String toRemove : suffixesToOverride) {
            if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
              int start = sql.length() - toRemove.trim().length();
              int end = sql.length();
              sql.delete(start, end);
              break;
            }
          }
        }
        if (suffix != null) {
          sql.append(" ");
          sql.append(suffix);
        }
      }
    }

  }

}
