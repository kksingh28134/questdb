/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.ops;

import io.questdb.cairo.*;
import io.questdb.cairo.vm.MemoryFCRImpl;
import io.questdb.cairo.vm.api.MemoryA;
import io.questdb.cairo.vm.api.MemoryCR;
import io.questdb.cairo.wal.MetadataService;
import io.questdb.griffin.QueryRegistry;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.log.LogRecord;
import io.questdb.std.Chars;
import io.questdb.std.LongList;
import io.questdb.std.Mutable;
import io.questdb.std.ObjList;
import io.questdb.std.str.DirectString;
import io.questdb.tasks.TableWriterTask;

public class AlterOperation extends AbstractOperation implements Mutable {
    public final static String CMD_NAME = "ALTER TABLE";
    public final static short DO_NOTHING = 0;
    public final static short ADD_COLUMN = DO_NOTHING + 1; // 1
    public final static short DROP_PARTITION = ADD_COLUMN + 1; // 2
    public final static short ATTACH_PARTITION = DROP_PARTITION + 1; // 3
    public final static short ADD_INDEX = ATTACH_PARTITION + 1; // 4
    public final static short DROP_INDEX = ADD_INDEX + 1; // 5
    public final static short ADD_SYMBOL_CACHE = DROP_INDEX + 1; // 6
    public final static short REMOVE_SYMBOL_CACHE = ADD_SYMBOL_CACHE + 1; // 7
    public final static short DROP_COLUMN = REMOVE_SYMBOL_CACHE + 1; // 8
    public final static short RENAME_COLUMN = DROP_COLUMN + 1; // 9
    public final static short SET_PARAM_MAX_UNCOMMITTED_ROWS = RENAME_COLUMN + 1; // 10
    public final static short SET_PARAM_COMMIT_LAG = SET_PARAM_MAX_UNCOMMITTED_ROWS + 1; // 11
    public final static short DETACH_PARTITION = SET_PARAM_COMMIT_LAG + 1; // 12
    public final static short SQUASH_PARTITIONS = DETACH_PARTITION + 1; // 13
    public final static short RENAME_TABLE = SQUASH_PARTITIONS + 1; // 14
    public final static short SET_DEDUP_ENABLE = RENAME_TABLE + 1; // 15
    public final static short SET_DEDUP_DISABLE = SET_DEDUP_ENABLE + 1; // 16
    public final static short CHANGE_COLUMN_TYPE = SET_DEDUP_DISABLE + 1; // 17
    private static final long BIT_INDEXED = 0x1L;
    private static final long BIT_DEDUP_KEY = BIT_INDEXED << 1;
    private final static Log LOG = LogFactory.getLog(AlterOperation.class);
    private final DirectCharSequenceList directExtraStrInfo = new DirectCharSequenceList();
    // This is only used to serialize partition name in form 2020-02-12 or 2020-02 or 2020
    // to exception message using TableUtils.setSinkForPartition.
    private final LongList extraInfo;
    private final ObjCharSequenceList extraStrInfo;
    private CharSequenceList activeExtraStrInfo;
    private short command;
    private MemoryFCRImpl deserializeMem;

    public AlterOperation() {
        this(new LongList(), new ObjList<>());
    }

    public AlterOperation(LongList extraInfo, ObjList<CharSequence> charSequenceObjList) {
        this.extraInfo = extraInfo;
        this.extraStrInfo = new ObjCharSequenceList(charSequenceObjList);
        this.command = DO_NOTHING;
    }

    public static long getFlags(boolean indexed, boolean dedupKey) {
        long flags = 0;
        if (indexed) {
            flags |= BIT_INDEXED;
        }
        if (dedupKey) {
            flags |= BIT_DEDUP_KEY;
        }
        return flags;
    }

    @Override
    // todo: supply bitset to indicate which ops are supported and which arent
    //     "structural changes" doesn't cover is as "add column" is supported
    public long apply(MetadataService svc, boolean contextAllowsAnyStructureChanges) throws AlterTableContextException {
        QueryRegistry queryRegistry = sqlExecutionContext != null ? sqlExecutionContext.getCairoEngine().getQueryRegistry() : null;
        long queryId = -1;
        try {
            if (queryRegistry != null) {
                queryId = queryRegistry.register(sqlText, sqlExecutionContext);
            }
            switch (command) {
                case ADD_COLUMN:
                    applyAddColumn(svc);
                    break;
                case DROP_COLUMN:
                    if (!contextAllowsAnyStructureChanges) {
                        throw AlterTableContextException.INSTANCE;
                    }
                    applyDropColumn(svc);
                    break;
                case RENAME_COLUMN:
                    if (!contextAllowsAnyStructureChanges) {
                        throw AlterTableContextException.INSTANCE;
                    }
                    applyRenameColumn(svc);
                    break;
                case DROP_PARTITION:
                    applyDropPartition(svc);
                    break;
                case DETACH_PARTITION:
                    applyDetachPartition(svc);
                    break;
                case ATTACH_PARTITION:
                    applyAttachPartition(svc);
                    break;
                case ADD_INDEX:
                    applyAddIndex(svc);
                    break;
                case DROP_INDEX:
                    applyDropIndex(svc);
                    break;
                case ADD_SYMBOL_CACHE:
                    applySetSymbolCache(svc, true);
                    break;
                case REMOVE_SYMBOL_CACHE:
                    applySetSymbolCache(svc, false);
                    break;
                case SET_PARAM_MAX_UNCOMMITTED_ROWS:
                    applyParamUncommittedRows(svc);
                    break;
                case SET_PARAM_COMMIT_LAG:
                    applyParamO3MaxLag(svc);
                    break;
                case RENAME_TABLE:
                    applyRenameTable(svc);
                    break;
                case SQUASH_PARTITIONS:
                    squashPartitions(svc);
                    break;
                case SET_DEDUP_ENABLE:
                    enableDeduplication(svc);
                    break;
                case SET_DEDUP_DISABLE:
                    svc.disableDeduplication();
                    break;
                case CHANGE_COLUMN_TYPE:
                    if (!contextAllowsAnyStructureChanges) {
                        throw AlterTableContextException.INSTANCE;
                    }
                    changeColumnType(svc);
                    break;
                default:
                    LOG.error()
                            .$("invalid alter table command [code=").$(command)
                            .$(" ,table=").$(svc.getTableToken())
                            .I$();
                    throw CairoException.critical(0).put("invalid alter table command [code=").put(command).put(']');
            }
        } catch (EntryUnavailableException ex) {
            throw ex;
        } catch (CairoException e) {
            final LogRecord log = e.isCritical() ? LOG.critical() : LOG.error();
            log.$("could not alter table [table=").$(svc.getTableToken())
                    .$(", command=").$(command)
                    .$(", errno=").$(e.getErrno())
                    .$(", message=`").$(e.getFlyweightMessage()).$('`')
                    .I$();
            throw e;
        } finally {
            if (queryRegistry != null) {
                queryRegistry.unregister(queryId, sqlExecutionContext);
            }
        }
        return 0;
    }

    @Override
    public void clear() {
        command = DO_NOTHING;
        sqlExecutionContext = null;
        securityContext = null;
        extraStrInfo.clear();
        directExtraStrInfo.clear();
        activeExtraStrInfo = extraStrInfo;
        extraInfo.clear();
        clearCommandCorrelationId();
    }

    @Override
    public AlterOperation deserialize(TableWriterTask event) {
        clear();

        tableToken = event.getTableToken();
        long readPtr = event.getData();
        long length = event.getDataSize();
        final long hi = readPtr + length;

        // This is not hot path, do safe deserialization
        if (readPtr + 10 >= hi) {
            throw CairoException.critical(0).put("invalid alter statement serialized to writer queue [1]");
        }

        if (deserializeMem == null) {
            deserializeMem = new MemoryFCRImpl();
        }
        deserializeMem.of(readPtr, length);
        deserializeBody(deserializeMem, 0L, length);
        return this;
    }

    public void deserializeBody(MemoryCR buffer, long offsetLo, long offsetHi) {
        clear();
        long readPtr = offsetLo;
        if (readPtr + 10 > offsetHi) {
            throw CairoException
                    .critical(0)
                    .put("cannot read alter statement serialized, data is too short to read 10 bytes header [offset=")
                    .put(offsetLo)
                    .put(", size=")
                    .put(offsetHi - offsetLo)
                    .put(']');
        }
        command = buffer.getShort(readPtr);
        readPtr += 2;
        tableNamePosition = buffer.getInt(readPtr);
        readPtr += 4;
        int longSize = buffer.getInt(readPtr);
        readPtr += 4;
        if (longSize < 0 || readPtr + longSize * 8L >= offsetHi) {
            throw CairoException.critical(0).put("invalid alter statement serialized to writer queue [2]");
        }
        extraInfo.clear();
        for (int i = 0; i < longSize; i++) {
            extraInfo.add(buffer.getLong(readPtr));
            readPtr += 8;
        }
        directExtraStrInfo.of(buffer, readPtr, offsetHi);
        activeExtraStrInfo = directExtraStrInfo;
    }

    public short getCommand() {
        return command;
    }

    @Override
    public boolean isStructural() {
        switch (command) {
            case ADD_COLUMN:
            case RENAME_COLUMN:
            case DROP_COLUMN:
            case RENAME_TABLE:
            case SET_DEDUP_DISABLE:
            case SET_DEDUP_ENABLE:
            case CHANGE_COLUMN_TYPE:
                return true;
            default:
                return false;
        }
    }

    public AlterOperation of(
            short command,
            TableToken tableToken,
            int tableId,
            int tableNamePosition
    ) {
        init(TableWriterTask.CMD_ALTER_TABLE, CMD_NAME, tableToken, tableId, -1, tableNamePosition);
        this.command = command;
        this.activeExtraStrInfo = this.extraStrInfo;
        return this;
    }

    public void ofAddColumn(
            int tableId,
            TableToken tableToken,
            int tableNamePosition,
            CharSequence columnName,
            int columnNamePosition,
            int columnType,
            int symbolCapacity,
            boolean cache,
            boolean indexed,
            int indexValueBlockCapacity,
            boolean dedupKey
    ) {
        of(AlterOperation.ADD_COLUMN, tableToken, tableId, tableNamePosition);
        assert columnName != null && columnName.length() > 0;
        extraStrInfo.strings.add(Chars.toString(columnName));
        extraInfo.add(columnType);
        extraInfo.add(symbolCapacity);
        extraInfo.add(cache ? 1 : -1);
        extraInfo.add(getFlags(indexed, dedupKey));
        extraInfo.add(indexValueBlockCapacity);
        extraInfo.add(columnNamePosition);
    }

    public void ofRenameTable(TableToken fromTableToken, CharSequence toTableName) {
        of(AlterOperation.RENAME_TABLE, fromTableToken, fromTableToken.getTableId(), 0);
        assert toTableName != null && toTableName.length() > 0;
        extraStrInfo.strings.add(fromTableToken.getTableName());
        extraStrInfo.strings.add(toTableName);
    }

    @Override
    public void serialize(TableWriterTask event) {
        super.serialize(event);
        event.putShort(command);
        event.putInt(tableNamePosition);
        event.putInt(extraInfo.size());
        for (int i = 0, n = extraInfo.size(); i < n; i++) {
            event.putLong(extraInfo.getQuick(i));
        }

        event.putInt(extraStrInfo.size());
        for (int i = 0, n = extraStrInfo.size(); i < n; i++) {
            event.putStr(extraStrInfo.getStrA(i));
        }
    }

    public void serializeBody(MemoryA sink) {
        sink.putShort(command);
        sink.putInt(tableNamePosition);
        sink.putInt(extraInfo.size());
        for (int i = 0, n = extraInfo.size(); i < n; i++) {
            sink.putLong(extraInfo.getQuick(i));
        }

        sink.putInt(extraStrInfo.size());
        for (int i = 0, n = extraStrInfo.size(); i < n; i++) {
            sink.putStr(extraStrInfo.getStrA(i));
        }
    }

    @Override
    public void startAsync() {
    }

    private void applyAddColumn(MetadataService svc) {
        int lParam = 0;
        for (int i = 0, n = activeExtraStrInfo.size(); i < n; i++) {
            CharSequence columnName = activeExtraStrInfo.getStrA(i);
            int type = (int) extraInfo.get(lParam++);
            int symbolCapacity = (int) extraInfo.get(lParam++);
            boolean symbolCacheFlag = extraInfo.get(lParam++) > 0;
            long flags = extraInfo.get(lParam++);
            boolean isIndexed = (flags & BIT_INDEXED) == BIT_INDEXED;
            boolean isDedupKey = (flags & BIT_DEDUP_KEY) == BIT_DEDUP_KEY;
            assert !isDedupKey; // adding column as dedup key is not supported in SQL yet.
            int indexValueBlockCapacity = (int) extraInfo.get(lParam++);
            int columnNamePosition = (int) extraInfo.get(lParam++);
            try {
                svc.addColumn(
                        columnName,
                        type,
                        symbolCapacity,
                        symbolCacheFlag,
                        isIndexed,
                        indexValueBlockCapacity,
                        false,
                        securityContext
                );
            } catch (CairoException e) {
                e.position(columnNamePosition);
                throw e;
            }
        }
    }

    private void applyAddIndex(MetadataService svc) {
        final CharSequence columnName = activeExtraStrInfo.getStrA(0);
        try {
            svc.addIndex(columnName, (int) extraInfo.get(0));
        } catch (CairoException e) {
            // augment exception with table position
            e.position(tableNamePosition);
            throw e;
        }
    }

    private void applyAttachPartition(MetadataService svc) {
        for (int i = 0, n = extraInfo.size() / 2; i < n; i++) {
            final long partitionTimestamp = extraInfo.getQuick(i * 2);
            AttachDetachStatus attachDetachStatus = svc.attachPartition(partitionTimestamp);
            if (AttachDetachStatus.OK != attachDetachStatus) {
                throw attachDetachStatus.getException(
                        (int) extraInfo.getQuick(i * 2 + 1),
                        attachDetachStatus,
                        tableToken,
                        svc.getPartitionBy(),
                        partitionTimestamp
                );
            }
        }
    }

    private void applyDetachPartition(MetadataService svc) {
        for (int i = 0, n = extraInfo.size() / 2; i < n; i++) {
            final long partitionTimestamp = extraInfo.getQuick(i * 2);
            AttachDetachStatus attachDetachStatus = svc.detachPartition(partitionTimestamp);
            if (AttachDetachStatus.OK != attachDetachStatus) {
                throw attachDetachStatus.getException(
                        (int) extraInfo.getQuick(i * 2 + 1),
                        attachDetachStatus,
                        tableToken,
                        svc.getPartitionBy(),
                        partitionTimestamp
                );
            }
        }
    }

    private void applyDropColumn(MetadataService svc) {
        for (int i = 0, n = activeExtraStrInfo.size(); i < n; i++) {
            svc.removeColumn(activeExtraStrInfo.getStrA(i));
        }
    }

    private void applyDropIndex(MetadataService svc) {
        final CharSequence columnName = activeExtraStrInfo.getStrA(0);
        final int columnNamePosition = (int) extraInfo.get(0);
        try {
            svc.dropIndex(columnName);
        } catch (CairoException e) {
            e.position(columnNamePosition);
            throw e;
        }
    }

    private void applyDropPartition(MetadataService svc) {
        // long list is a set of two longs per partition - (timestamp, partitionNamePosition)
        for (int i = 0, n = extraInfo.size() / 2; i < n; i++) {
            long partitionTimestamp = extraInfo.getQuick(i * 2);
            if (!svc.removePartition(partitionTimestamp)) {
                throw CairoException.partitionManipulationRecoverable()
                        .put("could not remove partition [table=").put(tableToken != null ? tableToken.getTableName() : "<null>")
                        .put(", partitionTimestamp=").ts(partitionTimestamp)
                        .put(", partitionBy=").put(PartitionBy.toString(svc.getPartitionBy()))
                        .put(']')
                        .position((int) extraInfo.getQuick(i * 2 + 1));
            }
        }
    }

    private void applyParamO3MaxLag(MetadataService svc) {
        long o3MaxLag = extraInfo.get(0);
        try {
            svc.setMetaO3MaxLag(o3MaxLag);
        } catch (CairoException e) {
            LOG.error().$("could not change o3MaxLag [table=").utf8(tableToken != null ? tableToken.getTableName() : "<null>")
                    .$(", errno=").$(e.getErrno())
                    .$(", error=").$(e.getFlyweightMessage())
                    .I$();
            throw e;
        }
    }

    private void applyParamUncommittedRows(MetadataService svc) {
        int maxUncommittedRows = (int) extraInfo.get(0);
        try {
            svc.setMetaMaxUncommittedRows(maxUncommittedRows);
        } catch (CairoException e) {
            e.position(tableNamePosition);
            throw e;
        }
    }

    private void applyRenameColumn(MetadataService svc) {
        // To avoid storing 2 var len fields, store only new name as CharSequence
        // and index of existing column.
        int i = 0, n = activeExtraStrInfo.size();
        while (i < n) {
            CharSequence columnName = activeExtraStrInfo.getStrA(i++);
            CharSequence newName = activeExtraStrInfo.getStrB(i++);
            svc.renameColumn(columnName, newName, securityContext);
        }
    }

    private void applyRenameTable(MetadataService svc) {
        svc.renameTable(activeExtraStrInfo.getStrA(0), activeExtraStrInfo.getStrB(1));
    }

    private void applySetSymbolCache(MetadataService svc, boolean isCacheOn) {
        CharSequence columnName = activeExtraStrInfo.getStrA(0);
        svc.changeCacheFlag(
                svc.getMetadata().getColumnIndex(columnName),
                isCacheOn
        );
    }

    private void changeColumnType(MetadataService svc) {
        if (activeExtraStrInfo.size() != 1) {
            throw CairoException.nonCritical().put("invalid change column type alter statement");
        }
        CharSequence columnName = activeExtraStrInfo.getStrA(0);
        int lParam = 0;
        int newType = (int) extraInfo.get(lParam++);
        int symbolCapacity = (int) extraInfo.get(lParam++);
        boolean symbolCacheFlag = extraInfo.get(lParam++) > 0;
        long flags = extraInfo.get(lParam++);
        boolean isIndexed = (flags & BIT_INDEXED) == BIT_INDEXED;
        boolean isDedupKey = (flags & BIT_DEDUP_KEY) == BIT_DEDUP_KEY;
        assert !isDedupKey; // adding column as dedup key is not supported in SQL yet.
        int indexValueBlockCapacity = (int) extraInfo.get(lParam++);
        int columnNamePosition = (int) extraInfo.get(lParam);

        try {
            svc.changeColumnType(
                    columnName,
                    newType,
                    symbolCapacity,
                    symbolCacheFlag,
                    isIndexed,
                    indexValueBlockCapacity,
                    false,
                    securityContext
            );
        } catch (CairoException e) {
            e.position(columnNamePosition);
            throw e;
        }
    }

    private void enableDeduplication(MetadataService svc) {
        assert extraInfo.size() > 0;
        svc.enableDeduplicationWithUpsertKeys(extraInfo);
    }

    private void squashPartitions(MetadataService svc) {
        svc.squashPartitions();
    }

    interface CharSequenceList extends Mutable {
        CharSequence getStrA(int i);

        CharSequence getStrB(int i);

        int size();
    }

    private static class DirectCharSequenceList implements CharSequenceList {
        private final LongList offsets = new LongList();
        private final DirectString strA = new DirectString();
        private final DirectString strB = new DirectString();

        @Override
        public void clear() {
            offsets.clear();
        }

        public CharSequence getStrA(int i) {
            long lo = offsets.get(i * 2);
            long hi = offsets.get(i * 2 + 1);
            strA.of(lo, hi);
            return strA;
        }

        @Override
        public CharSequence getStrB(int i) {
            long lo = offsets.get(i * 2);
            long hi = offsets.get(i * 2 + 1);
            strB.of(lo, hi);
            return strB;
        }

        public void of(MemoryCR buffer, long lo, long hi) {
            if (lo + Integer.BYTES > hi) {
                throw CairoException.critical(0).put("invalid alter statement serialized to writer queue [11]");
            }
            int size = buffer.getInt(lo);
            lo += 4;
            for (int i = 0; i < size; i++) {
                if (lo + Integer.BYTES > hi) {
                    throw CairoException.critical(0).put("invalid alter statement serialized to writer queue [12]");
                }
                int stringSize = 2 * buffer.getInt(lo);
                lo += 4;
                if (lo + stringSize > hi) {
                    throw CairoException.critical(0).put("invalid alter statement serialized to writer queue [13]");
                }
                long address = buffer.addressOf(lo);
                offsets.add(address, address + stringSize);
                lo += stringSize;
            }
        }

        @Override
        public int size() {
            return offsets.size() / 2;
        }
    }

    private static class ObjCharSequenceList implements CharSequenceList {
        private final ObjList<CharSequence> strings;

        public ObjCharSequenceList(ObjList<CharSequence> strings) {
            this.strings = strings;
        }

        @Override
        public void clear() {
            strings.clear();
        }

        public CharSequence getStrA(int i) {
            return strings.get(i);
        }

        @Override
        public CharSequence getStrB(int i) {
            return strings.get(i);
        }

        @Override
        public int size() {
            return strings.size();
        }
    }
}
