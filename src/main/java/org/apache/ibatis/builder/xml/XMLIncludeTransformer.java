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
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {
  // 用来解析在 DML标签 中可以使用的Include标签

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  public void applyIncludes(Node source) {
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    Optional.ofNullable(configurationVariables).ifPresent(variablesContext::putAll);
    applyIncludes(source, variablesContext, false);
  }

  /**
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    // 通过所有 SQL 片段递归应用包含
    // 主要就是将DML标签中的Inculde标签替换为对应的SQL标签

    // 1. 解析include标签
    if (source.getNodeName().equals("include")) {
      // 2. 查找对应refid的sql标签的XNode
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      // 3.解析sql标签的properties,并且合并到variablesContext -- 主要就是针对 bind标签的name和value
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      // 4. 重复递归 -- 因为 sql标签中 允许使用 inculde 标签 - 因此传递included参数为true
      applyIncludes(toInclude, toIncludeContext, true);
      // 5. 当不属于同一个Mapper,需要将sql片段导入source文档中这个sql的Xnode
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      // 6.将DML标签中的include标签替换为sql标签
      source.getParentNode().replaceChild(toInclude, source);
      // 7. sql标签如果有子节点,可能回家信息插入动作
      while (toInclude.hasChildNodes()) {
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      // 8. 删除sql标签
      toInclude.getParentNode().removeChild(toInclude);

      // 对应上面的4.步骤 -- 元素节点
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        // 替换属性值中的变量 -- 变量使用 {} 扣起来
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        // 继续递归手游children节点
        applyIncludes(children.item(i), variablesContext, included);
      }

      // 对应上面的4.步骤 -- 文本节点
    } else if (included && source.getNodeType() == Node.TEXT_NODE
        && !variablesContext.isEmpty()) {
      // 替换属性值中的变量 -- 变量使用 {} 扣起来
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  private Node findSqlFragment(String refid, Properties variables) {
    // 1. 解析refId中的占位符{}
    refid = PropertyParser.parse(refid, variables);
    // 2. 对refId判断是否需要添加当前命名空间前缀
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      // 3. 从configuration提前解析的sql标签的SQLFragment中获取对应的sql Xnode
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition.
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    // 从包含节点定义中读取占位符及其值。
    //  参数：
    //    节点——包括节点实例
    //    inheritVariablesContext - 用于替换新变量值中的变量的当前上下文

    // 同时会将name和value读取出俩,合并到inheritedVariablesContext然后返回


    // 1. 遍历sql标签中的所有子标签
    Map<String, String> declaredProperties = null;
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        // 不难看出 -- 主要是针对bind标签的name和value属性值
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        // value值的占位符解析
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          // 第一次会进来 --
          declaredProperties = new HashMap<>();
        }
        // 存入declaredProperties
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      // 合并到 inheritedVariablesContext 中
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
