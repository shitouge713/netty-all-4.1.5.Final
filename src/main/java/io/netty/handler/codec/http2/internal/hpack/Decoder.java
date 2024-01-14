/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * Copyright 2014 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.netty.handler.codec.http2.internal.hpack;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.internal.hpack.HpackUtil.IndexType;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.AsciiString;

import java.io.IOException;

import static io.netty.util.AsciiString.EMPTY_STRING;

import static java.lang.Math.min;

public final class Decoder {

    private static final IOException DECODE_DECOMPRESSION_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - decompression failure"), Decoder.class, "decode(...)");
    private static final IOException DECODE_ULE_128_DECOMPRESSION_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - decompression failure"), Decoder.class, "decodeULE128(...)");
    private static final IOException DECODE_ILLEGAL_INDEX_VALUE = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - illegal index value"), Decoder.class, "decode(...)");
    private static final IOException INDEX_HEADER_ILLEGAL_INDEX_VALUE = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - illegal index value"), Decoder.class, "indexHeader(...)");
    private static final IOException READ_NAME_ILLEGAL_INDEX_VALUE = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - illegal index value"), Decoder.class, "readName(...)");
    private static final IOException INVALID_MAX_DYNAMIC_TABLE_SIZE = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - invalid max dynamic table size"), Decoder.class, "setDynamicTableSize(...)");
    private static final IOException MAX_DYNAMIC_TABLE_SIZE_CHANGE_REQUIRED = ThrowableUtil.unknownStackTrace(
            new IOException("HPACK - max dynamic table size change required"), Decoder.class, "decode(...)");

    private final DynamicTable dynamicTable;
    private final HuffmanDecoder huffmanDecoder;
    private final int maxHeaderSize;
    private int maxDynamicTableSize;
    private int encoderMaxDynamicTableSize;
    private boolean maxDynamicTableSizeChangeRequired;

    private long headerSize;
    private State state;
    private IndexType indexType;
    private int index;
    private boolean huffmanEncoded;
    private int skipLength;
    private int nameLength;
    private int valueLength;
    private CharSequence name;

    private enum State {
        READ_HEADER_REPRESENTATION,
        READ_MAX_DYNAMIC_TABLE_SIZE,
        READ_INDEXED_HEADER,
        READ_INDEXED_HEADER_NAME,
        READ_LITERAL_HEADER_NAME_LENGTH_PREFIX,
        READ_LITERAL_HEADER_NAME_LENGTH,
        READ_LITERAL_HEADER_NAME,
        SKIP_LITERAL_HEADER_NAME,
        READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX,
        READ_LITERAL_HEADER_VALUE_LENGTH,
        READ_LITERAL_HEADER_VALUE,
        SKIP_LITERAL_HEADER_VALUE
    }

    /**
     * Creates a new decoder.
     */
    public Decoder(int maxHeaderSize, int maxHeaderTableSize, int initialHuffmanDecodeCapacity) {
        dynamicTable = new DynamicTable(maxHeaderTableSize);
        this.maxHeaderSize = maxHeaderSize;
        maxDynamicTableSize = maxHeaderTableSize;
        encoderMaxDynamicTableSize = maxHeaderTableSize;
        maxDynamicTableSizeChangeRequired = false;
        huffmanDecoder = new HuffmanDecoder(initialHuffmanDecodeCapacity);
        reset();
    }

    private void reset() {
        headerSize = 0;
        state = State.READ_HEADER_REPRESENTATION;
        indexType = IndexType.NONE;
    }

    /**
     * Decode the header block into header fields.
     */
    public void decode(ByteBuf in, Http2Headers headers) throws IOException {
        while (in.isReadable()) {
            switch (state) {
                case READ_HEADER_REPRESENTATION:
                    byte b = in.readByte();
                    if (maxDynamicTableSizeChangeRequired && (b & 0xE0) != 0x20) {
                        // Encoder MUST signal maximum dynamic table size change
                        throw MAX_DYNAMIC_TABLE_SIZE_CHANGE_REQUIRED;
                    }
                    if (b < 0) {
                        // Indexed Header Field
                        index = b & 0x7F;
                        if (index == 0) {
                            throw DECODE_ILLEGAL_INDEX_VALUE;
                        } else if (index == 0x7F) {
                            state = State.READ_INDEXED_HEADER;
                        } else {
                            indexHeader(index, headers);
                        }
                    } else if ((b & 0x40) == 0x40) {
                        // Literal Header Field with Incremental Indexing
                        indexType = IndexType.INCREMENTAL;
                        index = b & 0x3F;
                        if (index == 0) {
                            state = State.READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
                        } else if (index == 0x3F) {
                            state = State.READ_INDEXED_HEADER_NAME;
                        } else {
                            // Index was stored as the prefix
                            readName(index);
                            state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                        }
                    } else if ((b & 0x20) == 0x20) {
                        // Dynamic Table Size Update
                        index = b & 0x1F;
                        if (index == 0x1F) {
                            state = State.READ_MAX_DYNAMIC_TABLE_SIZE;
                        } else {
                            setDynamicTableSize(index);
                            state = State.READ_HEADER_REPRESENTATION;
                        }
                    } else {
                        // Literal Header Field without Indexing / never Indexed
                        indexType = ((b & 0x10) == 0x10) ? IndexType.NEVER : IndexType.NONE;
                        index = b & 0x0F;
                        if (index == 0) {
                            state = State.READ_LITERAL_HEADER_NAME_LENGTH_PREFIX;
                        } else if (index == 0x0F) {
                            state = State.READ_INDEXED_HEADER_NAME;
                        } else {
                            // Index was stored as the prefix
                            readName(index);
                            state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                        }
                    }
                    break;

                case READ_MAX_DYNAMIC_TABLE_SIZE:
                    int maxSize = decodeULE128(in);
                    if (maxSize == -1) {
                        return;
                    }

                    // Check for numerical overflow
                    if (maxSize > Integer.MAX_VALUE - index) {
                        throw DECODE_DECOMPRESSION_EXCEPTION;
                    }

                    setDynamicTableSize(index + maxSize);
                    state = State.READ_HEADER_REPRESENTATION;
                    break;

                case READ_INDEXED_HEADER:
                    int headerIndex = decodeULE128(in);
                    if (headerIndex == -1) {
                        return;
                    }

                    // Check for numerical overflow
                    if (headerIndex > Integer.MAX_VALUE - index) {
                        throw DECODE_DECOMPRESSION_EXCEPTION;
                    }

                    indexHeader(index + headerIndex, headers);
                    state = State.READ_HEADER_REPRESENTATION;
                    break;

                case READ_INDEXED_HEADER_NAME:
                    // Header Name matches an entry in the Header Table
                    int nameIndex = decodeULE128(in);
                    if (nameIndex == -1) {
                        return;
                    }

                    // Check for numerical overflow
                    if (nameIndex > Integer.MAX_VALUE - index) {
                        throw DECODE_DECOMPRESSION_EXCEPTION;
                    }

                    readName(index + nameIndex);
                    state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                    break;

                case READ_LITERAL_HEADER_NAME_LENGTH_PREFIX:
                    b = in.readByte();
                    huffmanEncoded = (b & 0x80) == 0x80;
                    index = b & 0x7F;
                    if (index == 0x7f) {
                        state = State.READ_LITERAL_HEADER_NAME_LENGTH;
                    } else {
                        nameLength = index;

                        // Check name length against max header size
                        if (exceedsMaxHeaderSize(nameLength)) {

                            if (indexType == IndexType.NONE) {
                                // Name is unused so skip bytes
                                name = EMPTY_STRING;
                                skipLength = nameLength;
                                state = State.SKIP_LITERAL_HEADER_NAME;
                                break;
                            }

                            // Check name length against max dynamic table size
                            if (nameLength + HeaderField.HEADER_ENTRY_OVERHEAD > dynamicTable.capacity()) {
                                dynamicTable.clear();
                                name = EMPTY_STRING;
                                skipLength = nameLength;
                                state = State.SKIP_LITERAL_HEADER_NAME;
                                break;
                            }
                        }
                        state = State.READ_LITERAL_HEADER_NAME;
                    }
                    break;

                case READ_LITERAL_HEADER_NAME_LENGTH:
                    // Header Name is a Literal String
                    nameLength = decodeULE128(in);
                    if (nameLength == -1) {
                        return;
                    }

                    // Check for numerical overflow
                    if (nameLength > Integer.MAX_VALUE - index) {
                        throw DECODE_DECOMPRESSION_EXCEPTION;
                    }
                    nameLength += index;

                    // Check name length against max header size
                    if (exceedsMaxHeaderSize(nameLength)) {
                        if (indexType == IndexType.NONE) {
                            // Name is unused so skip bytes
                            name = EMPTY_STRING;
                            skipLength = nameLength;
                            state = State.SKIP_LITERAL_HEADER_NAME;
                            break;
                        }

                        // Check name length against max dynamic table size
                        if (nameLength + HeaderField.HEADER_ENTRY_OVERHEAD > dynamicTable.capacity()) {
                            dynamicTable.clear();
                            name = EMPTY_STRING;
                            skipLength = nameLength;
                            state = State.SKIP_LITERAL_HEADER_NAME;
                            break;
                        }
                    }
                    state = State.READ_LITERAL_HEADER_NAME;
                    break;

                case READ_LITERAL_HEADER_NAME:
                    // Wait until entire name is readable
                    if (in.readableBytes() < nameLength) {
                        return;
                    }

                    name = readStringLiteral(in, nameLength);

                    state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                    break;

                case SKIP_LITERAL_HEADER_NAME:
                    int skip = min(in.readableBytes(), skipLength);
                    in.skipBytes(skip);
                    skipLength -= skip;

                    if (skipLength == 0) {
                        state = State.READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX;
                    }
                    break;

                case READ_LITERAL_HEADER_VALUE_LENGTH_PREFIX:
                    b = in.readByte();
                    huffmanEncoded = (b & 0x80) == 0x80;
                    index = b & 0x7F;
                    if (index == 0x7f) {
                        state = State.READ_LITERAL_HEADER_VALUE_LENGTH;
                    } else {
                        valueLength = index;

                        // Check new header size against max header size
                        long newHeaderSize = (long) nameLength + (long) valueLength;
                        if (exceedsMaxHeaderSize(newHeaderSize)) {
                            // truncation will be reported during endHeaderBlock
                            headerSize = maxHeaderSize + 1;

                            if (indexType == IndexType.NONE) {
                                // Value is unused so skip bytes
                                state = State.SKIP_LITERAL_HEADER_VALUE;
                                break;
                            }

                            // Check new header size against max dynamic table size
                            if (newHeaderSize + HeaderField.HEADER_ENTRY_OVERHEAD > dynamicTable.capacity()) {
                                dynamicTable.clear();
                                state = State.SKIP_LITERAL_HEADER_VALUE;
                                break;
                            }
                        }

                        if (valueLength == 0) {
                            insertHeader(headers, name, EMPTY_STRING, indexType);
                            state = State.READ_HEADER_REPRESENTATION;
                        } else {
                            state = State.READ_LITERAL_HEADER_VALUE;
                        }
                    }

                    break;

                case READ_LITERAL_HEADER_VALUE_LENGTH:
                    // Header Value is a Literal String
                    valueLength = decodeULE128(in);
                    if (valueLength == -1) {
                        return;
                    }

                    // Check for numerical overflow
                    if (valueLength > Integer.MAX_VALUE - index) {
                        throw DECODE_DECOMPRESSION_EXCEPTION;
                    }
                    valueLength += index;

                    // Check new header size against max header size
                    long newHeaderSize = (long) nameLength + (long) valueLength;
                    if (newHeaderSize + headerSize > maxHeaderSize) {
                        // truncation will be reported during endHeaderBlock
                        headerSize = maxHeaderSize + 1;

                        if (indexType == IndexType.NONE) {
                            // Value is unused so skip bytes
                            state = State.SKIP_LITERAL_HEADER_VALUE;
                            break;
                        }

                        // Check new header size against max dynamic table size
                        if (newHeaderSize + HeaderField.HEADER_ENTRY_OVERHEAD > dynamicTable.capacity()) {
                            dynamicTable.clear();
                            state = State.SKIP_LITERAL_HEADER_VALUE;
                            break;
                        }
                    }
                    state = State.READ_LITERAL_HEADER_VALUE;
                    break;

                case READ_LITERAL_HEADER_VALUE:
                    // Wait until entire value is readable
                    if (in.readableBytes() < valueLength) {
                        return;
                    }

                    CharSequence value = readStringLiteral(in, valueLength);
                    insertHeader(headers, name, value, indexType);
                    state = State.READ_HEADER_REPRESENTATION;
                    break;

                case SKIP_LITERAL_HEADER_VALUE:
                    int skipBytes = min(in.readableBytes(), valueLength);
                    in.skipBytes(skipBytes);
                    valueLength -= skipBytes;
                    if (valueLength == 0) {
                        state = State.READ_HEADER_REPRESENTATION;
                    }
                    break;

                default:
                    throw new IllegalStateException("should not reach here");
            }
        }
    }

    /**
     * End the current header block. Returns if the header field has been truncated. This must be
     * called after the header block has been completely decoded.
     */
    public boolean endHeaderBlock() {
        boolean truncated = headerSize > maxHeaderSize;
        reset();
        return truncated;
    }

    /**
     * Set the maximum table size. If this is below the maximum size of the dynamic table used by
     * the encoder, the beginning of the next header block MUST signal this change.
     */
    public void setMaxHeaderTableSize(int maxHeaderTableSize) {
        maxDynamicTableSize = maxHeaderTableSize;
        if (maxDynamicTableSize < encoderMaxDynamicTableSize) {
            // decoder requires less space than encoder
            // encoder MUST signal this change
            maxDynamicTableSizeChangeRequired = true;
            dynamicTable.setCapacity(maxDynamicTableSize);
        }
    }

    /**
     * Return the maximum table size. This is the maximum size allowed by both the encoder and the
     * decoder.
     */
    public int getMaxHeaderTableSize() {
        return dynamicTable.capacity();
    }

    /**
     * Return the number of header fields in the dynamic table. Exposed for testing.
     */
    int length() {
        return dynamicTable.length();
    }

    /**
     * Return the size of the dynamic table. Exposed for testing.
     */
    int size() {
        return dynamicTable.size();
    }

    /**
     * Return the header field at the given index. Exposed for testing.
     */
    HeaderField getHeaderField(int index) {
        return dynamicTable.getEntry(index + 1);
    }

    private void setDynamicTableSize(int dynamicTableSize) throws IOException {
        if (dynamicTableSize > maxDynamicTableSize) {
            throw INVALID_MAX_DYNAMIC_TABLE_SIZE;
        }
        encoderMaxDynamicTableSize = dynamicTableSize;
        maxDynamicTableSizeChangeRequired = false;
        dynamicTable.setCapacity(dynamicTableSize);
    }

    private void readName(int index) throws IOException {
        if (index <= StaticTable.length) {
            HeaderField headerField = StaticTable.getEntry(index);
            name = headerField.name;
        } else if (index - StaticTable.length <= dynamicTable.length()) {
            HeaderField headerField = dynamicTable.getEntry(index - StaticTable.length);
            name = headerField.name;
        } else {
            throw READ_NAME_ILLEGAL_INDEX_VALUE;
        }
    }

    private void indexHeader(int index, Http2Headers headers) throws IOException {
        if (index <= StaticTable.length) {
            HeaderField headerField = StaticTable.getEntry(index);
            addHeader(headers, headerField.name, headerField.value);
        } else if (index - StaticTable.length <= dynamicTable.length()) {
            HeaderField headerField = dynamicTable.getEntry(index - StaticTable.length);
            addHeader(headers, headerField.name, headerField.value);
        } else {
            throw INDEX_HEADER_ILLEGAL_INDEX_VALUE;
        }
    }

    private void insertHeader(Http2Headers headers, CharSequence name, CharSequence value,
                              IndexType indexType) {
        addHeader(headers, name, value);

        switch (indexType) {
            case NONE:
            case NEVER:
                break;

            case INCREMENTAL:
                dynamicTable.add(new HeaderField(name, value));
                break;

            default:
                throw new IllegalStateException("should not reach here");
        }
    }

    private void addHeader(Http2Headers headers, CharSequence name, CharSequence value) {
        long newSize = headerSize + name.length() + value.length();
        if (newSize <= maxHeaderSize) {
            headers.add(name, value);
            headerSize = (int) newSize;
        } else {
            // truncation will be reported during endHeaderBlock
            headerSize = maxHeaderSize + 1;
        }
    }

    private boolean exceedsMaxHeaderSize(long size) {
        // Check new header size against max header size
        if (size + headerSize <= maxHeaderSize) {
            return false;
        }

        // truncation will be reported during endHeaderBlock
        headerSize = maxHeaderSize + 1;
        return true;
    }

    private CharSequence readStringLiteral(ByteBuf in, int length) throws IOException {
        if (huffmanEncoded) {
            return huffmanDecoder.decode(in, length);
        } else {
            byte[] buf = new byte[length];
            in.readBytes(buf);
            return new AsciiString(buf, false);
        }
    }

    // Unsigned Little Endian Base 128 Variable-Length Integer Encoding
    private static int decodeULE128(ByteBuf in) throws IOException {
        in.markReaderIndex();
        int result = 0;
        int shift = 0;
        while (shift < 32) {
            if (!in.isReadable()) {
                // Buffer does not contain entire integer,
                // reset reader index and return -1.
                in.resetReaderIndex();
                return -1;
            }
            byte b = in.readByte();
            if (shift == 28 && (b & 0xF8) != 0) {
                break;
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        // Value exceeds Integer.MAX_VALUE
        in.resetReaderIndex();
        throw DECODE_ULE_128_DECOMPRESSION_EXCEPTION;
    }
}
