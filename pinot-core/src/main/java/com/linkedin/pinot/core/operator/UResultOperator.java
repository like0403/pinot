/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.core.operator;

import com.linkedin.pinot.core.common.Block;
import com.linkedin.pinot.core.common.BlockId;
import com.linkedin.pinot.core.common.Operator;
import com.linkedin.pinot.core.operator.blocks.InstanceResponseBlock;


/**
 * UResultOperator now only take one argument, wrap the operator to InstanceResponseBlock.
 * For now it's always MCombineOperator.
 *
 *
 */
public class UResultOperator extends BaseOperator {

  private final Operator _operator;

  public UResultOperator(Operator combinedOperator) {
    _operator = combinedOperator;
  }

  @Override
  public boolean open() {
    _operator.open();
    return true;
  }

  @Override
  public Block getNextBlock() {
    return new InstanceResponseBlock(_operator.nextBlock());
  }

  @Override
  public Block getNextBlock(BlockId blockId) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean close() {
    _operator.close();
    return true;
  }
}
