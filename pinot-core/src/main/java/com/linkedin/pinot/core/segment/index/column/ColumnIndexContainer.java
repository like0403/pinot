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
package com.linkedin.pinot.core.segment.index.column;

import com.linkedin.pinot.common.data.FieldSpec;
import com.linkedin.pinot.common.metadata.segment.IndexLoadingConfigMetadata;
import com.linkedin.pinot.core.io.compression.ChunkCompressorFactory;
import com.linkedin.pinot.core.io.compression.ChunkDecompressor;
import com.linkedin.pinot.core.io.reader.DataFileReader;
import com.linkedin.pinot.core.io.reader.ReaderContext;
import com.linkedin.pinot.core.io.reader.SingleColumnMultiValueReader;
import com.linkedin.pinot.core.io.reader.SingleColumnSingleValueReader;
import com.linkedin.pinot.core.io.reader.impl.FixedByteSingleValueMultiColReader;
import com.linkedin.pinot.core.io.reader.impl.v1.FixedBitMultiValueReader;
import com.linkedin.pinot.core.io.reader.impl.v1.FixedBitSingleValueReader;
import com.linkedin.pinot.core.io.reader.impl.v1.FixedByteChunkSingleValueReader;
import com.linkedin.pinot.core.io.reader.impl.v1.FixedByteSingleValueReader;
import com.linkedin.pinot.core.io.reader.impl.v1.VarByteChunkSingleValueReader;
import com.linkedin.pinot.core.segment.creator.impl.fwd.SingleValueFixedByteRawIndexCreator;
import com.linkedin.pinot.core.segment.index.ColumnMetadata;
import com.linkedin.pinot.core.segment.index.readers.BitmapInvertedIndexReader;
import com.linkedin.pinot.core.segment.index.readers.DoubleDictionary;
import com.linkedin.pinot.core.segment.index.readers.FloatDictionary;
import com.linkedin.pinot.core.segment.index.readers.ImmutableDictionaryReader;
import com.linkedin.pinot.core.segment.index.readers.IntDictionary;
import com.linkedin.pinot.core.segment.index.readers.InvertedIndexReader;
import com.linkedin.pinot.core.segment.index.readers.LongDictionary;
import com.linkedin.pinot.core.segment.index.readers.StringDictionary;
import com.linkedin.pinot.core.segment.memory.PinotDataBuffer;
import com.linkedin.pinot.core.segment.store.ColumnIndexType;
import com.linkedin.pinot.core.segment.store.SegmentDirectory;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ColumnIndexContainer {
  private static final Logger LOGGER = LoggerFactory.getLogger(ColumnIndexContainer.class);

  public static ColumnIndexContainer init(SegmentDirectory.Reader segmentReader, ColumnMetadata metadata,
      IndexLoadingConfigMetadata indexLoadingConfigMetadata)
      throws IOException {
    String column = metadata.getColumnName();
    boolean loadInverted = false;
    if (indexLoadingConfigMetadata != null) {
      if (indexLoadingConfigMetadata.getLoadingInvertedIndexColumns() != null) {
        loadInverted = indexLoadingConfigMetadata.getLoadingInvertedIndexColumns().contains(metadata.getColumnName());
      }
    }

    ImmutableDictionaryReader dictionary = null;
    if (metadata.hasDictionary()) {
      PinotDataBuffer dictionaryBuffer = segmentReader.getIndexFor(column, ColumnIndexType.DICTIONARY);
      dictionary = load(metadata, dictionaryBuffer);
    }

    if (metadata.isSorted() && metadata.isSingleValue()) {
      return loadSorted(column, segmentReader, metadata, dictionary);
      //return loadSorted(column, indexDir, metadata, dictionary, mode);
    }

    if (metadata.isSingleValue()) {
      return loadUnsorted(column, segmentReader, metadata, dictionary, loadInverted);
      //return loadUnsorted(column, indexDir, metadata, dictionary, mode, loadInverted);
    }
    //return loadMultiValue(column, indexDir, metadata, dictionary, mode, loadInverted);
    return loadMultiValue(column, segmentReader, metadata, dictionary, loadInverted);
  }

  private static ColumnIndexContainer loadMultiValue(String column, SegmentDirectory.Reader segmentReader,
      ColumnMetadata metadata, ImmutableDictionaryReader dictionary, boolean loadInverted)
      throws IOException {

    PinotDataBuffer fwdIndexBuffer = segmentReader.getIndexFor(column, ColumnIndexType.FORWARD_INDEX);

    SingleColumnMultiValueReader<? extends ReaderContext> fwdIndexReader =
        new FixedBitMultiValueReader(fwdIndexBuffer, metadata.getTotalDocs(),
            metadata.getTotalNumberOfEntries(), metadata.getBitsPerElement(), false);

    BitmapInvertedIndexReader invertedIndex = null;

    if (loadInverted) {
      PinotDataBuffer invertedIndexBuffer = segmentReader.getIndexFor(column, ColumnIndexType.INVERTED_INDEX);
      invertedIndex = new BitmapInvertedIndexReader(invertedIndexBuffer, metadata.getCardinality());
    }

    return new UnSortedMVColumnIndexContainer(column, metadata, fwdIndexReader, dictionary,
        invertedIndex);
  }

  private static ColumnIndexContainer loadUnsorted(String column, SegmentDirectory.Reader segmentReader, ColumnMetadata metadata,
      ImmutableDictionaryReader dictionary, boolean loadInverted)
      throws IOException {

    PinotDataBuffer fwdIndexBuffer = segmentReader.getIndexFor(column, ColumnIndexType.FORWARD_INDEX);
    SingleColumnSingleValueReader fwdIndexReader;
    if (dictionary != null) {
      fwdIndexReader =
          new FixedBitSingleValueReader(fwdIndexBuffer, metadata.getTotalDocs(), metadata.getBitsPerElement(), metadata.hasNulls());
    } else {
      // TODO: Replace hard-coded compressor with getting information from meta-data.
      fwdIndexReader =
          getRawIndexReader(fwdIndexBuffer, metadata.getDataType());
    }

    BitmapInvertedIndexReader invertedIndex = null;

    if (loadInverted) {
      PinotDataBuffer invertedIndexBuffer = segmentReader.getIndexFor(column, ColumnIndexType.INVERTED_INDEX);
      invertedIndex = new BitmapInvertedIndexReader(invertedIndexBuffer, metadata.getCardinality());
    }

    return new UnsortedSVColumnIndexContainer(column, metadata, fwdIndexReader, dictionary,
        invertedIndex);
  }

  private static ColumnIndexContainer loadSorted(String column, SegmentDirectory.Reader segmentReader, ColumnMetadata metadata,
      ImmutableDictionaryReader dictionary)
      throws IOException {
    PinotDataBuffer dataBuffer = segmentReader.getIndexFor(column, ColumnIndexType.FORWARD_INDEX);
    FixedByteSingleValueMultiColReader indexReader = new FixedByteSingleValueMultiColReader(
        dataBuffer, metadata.getCardinality(), 2, new int[] {
        4, 4
    });
    return new SortedSVColumnIndexContainer(column, metadata, indexReader, dictionary);
  }

  private static ImmutableDictionaryReader load(ColumnMetadata metadata, PinotDataBuffer dictionaryBuffer) {
    switch (metadata.getDataType()) {
      case INT:
        return new IntDictionary(dictionaryBuffer, metadata);
      case LONG:
        return new LongDictionary(dictionaryBuffer, metadata);
      case FLOAT:
        return new FloatDictionary(dictionaryBuffer, metadata);
      case DOUBLE:
        return new DoubleDictionary(dictionaryBuffer, metadata);
      case STRING:
      case BOOLEAN:
        return new StringDictionary(dictionaryBuffer, metadata);
    }

    throw new UnsupportedOperationException("unsupported data type : " + metadata.getDataType());
  }

  public static SingleColumnSingleValueReader getRawIndexReader(PinotDataBuffer fwdIndexBuffer,
      FieldSpec.DataType dataType)
      throws IOException {
    SingleColumnSingleValueReader reader;

    // TODO: Make compression/decompression configurable.
    ChunkDecompressor decompressor = ChunkCompressorFactory.getDecompressor("snappy");

    switch (dataType) {
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
        reader = new FixedByteChunkSingleValueReader(fwdIndexBuffer, decompressor);
        break;

      case STRING:
        reader = new VarByteChunkSingleValueReader(fwdIndexBuffer, decompressor);
        break;

      default:
        throw new IllegalArgumentException("Illegal data type for raw index reader: " + dataType);
    }

    return reader;
  }

  /**
   * @return
   */
  public abstract InvertedIndexReader getInvertedIndex();

  /**
   * @return
   */
  public abstract DataFileReader getForwardIndex();

  /**
   * @return True if index has dictionary, false otherwise
   */
  public abstract boolean hasDictionary();

  /**
   * @return
   */
  public abstract ImmutableDictionaryReader getDictionary();

  /**
   * @return
   */
  public abstract ColumnMetadata getColumnMetadata();

  /**
   * @return
   * @throws Exception
   */
  public abstract boolean unload() throws Exception;
}
