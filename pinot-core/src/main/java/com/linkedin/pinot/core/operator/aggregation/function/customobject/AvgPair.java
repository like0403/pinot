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
package com.linkedin.pinot.core.operator.aggregation.function.customobject;

import com.linkedin.pinot.core.segment.creator.impl.V1Constants;
import java.nio.ByteBuffer;
import javax.annotation.Nonnull;


public class AvgPair implements Comparable<AvgPair> {
  private double _sum;
  private long _count;

  public AvgPair(double sum, long count) {
    _sum = sum;
    _count = count;
  }

  public void apply(double sum, long count) {
    _sum += sum;
    _count += count;
  }

  public void apply(@Nonnull AvgPair avgPair) {
    _sum += avgPair._sum;
    _count += avgPair._count;
  }

  public double getSum() {
    return _sum;
  }

  public long getCount() {
    return _count;
  }

  @Nonnull
  public byte[] toBytes() {
    ByteBuffer byteBuffer = ByteBuffer.allocate(V1Constants.Numbers.DOUBLE_SIZE + V1Constants.Numbers.LONG_SIZE);
    byteBuffer.putDouble(_sum);
    byteBuffer.putLong(_count);
    return byteBuffer.array();
  }

  @Nonnull
  public static AvgPair fromBytes(byte[] bytes) {
    ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
    return new AvgPair(byteBuffer.getDouble(), byteBuffer.getLong());
  }

  @Override
  public int compareTo(@Nonnull AvgPair avgPair) {
    if (_count == 0) {
      if (avgPair._count == 0) {
        return 0;
      } else {
        return -1;
      }
    } else {
      if (avgPair._count == 0) {
        return 1;
      } else {
        double avg1 = _sum / _count;
        double avg2 = avgPair._sum / avgPair._count;
        if (avg1 > avg2) {
          return 1;
        }
        if (avg1 < avg2) {
          return -1;
        }
        return 0;
      }
    }
  }
}
