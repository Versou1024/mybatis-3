/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;

import java.util.List;

/**
 * @author Eduardo Macarron
 */
public class ResultMapResolver {
  // ResultMap的解析器

  private final MapperBuilderAssistant assistant; // Mapper构建过程中的辅助器
  private final String id; // resultMap的id
  private final Class<?> type; // 该resultMap的java类型时
  private final String extend; // 继承的父resultMap的id
  private final Discriminator discriminator; // 鉴别器
  // 其余id-result-collection-assocation-idArg-arg等标签都被解析为ResultMapping被加入到resultMapping是中
  // resultMap标签下的所有子标签,例如id/idArg/arg/result/collection/assocation 都被解析对应的ResultMapping加入到resultMappings集合中
  private final List<ResultMapping> resultMappings;
  private final Boolean autoMapping; // 是否自动映射

  public ResultMapResolver(MapperBuilderAssistant assistant, String id, Class<?> type, String extend, Discriminator discriminator, List<ResultMapping> resultMappings, Boolean autoMapping) {
    this.assistant = assistant;
    this.id = id;
    this.type = type;
    this.extend = extend;
    this.discriminator = discriminator;
    this.resultMappings = resultMappings;
    this.autoMapping = autoMapping;
  }

  public ResultMap resolve() {
    // 核心之一 --
    return assistant.addResultMap(this.id, this.type, this.extend, this.discriminator, this.resultMappings, this.autoMapping);
  }

}
