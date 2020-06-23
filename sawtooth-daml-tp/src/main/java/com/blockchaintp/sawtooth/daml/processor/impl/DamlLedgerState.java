/*
 * Copyright 2019 Blockchain Technology Partners Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 * ------------------------------------------------------------------------------
 */
package com.blockchaintp.sawtooth.daml.processor.impl;

import static com.blockchaintp.sawtooth.timekeeper.util.Namespace.TIMEKEEPER_GLOBAL_RECORD;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.blockchaintp.sawtooth.daml.processor.LedgerState;
import com.blockchaintp.sawtooth.daml.util.EventConstants;
import com.blockchaintp.sawtooth.daml.util.Namespace;
import com.blockchaintp.sawtooth.timekeeper.protobuf.TimeKeeperGlobalRecord;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlCommandDedupValue;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateKey;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateValue;
import com.daml.ledger.participant.state.kvutils.KeyValueCommitting;
import com.daml.ledger.validator.LedgerStateOperations;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;

import sawtooth.sdk.processor.Context;
import sawtooth.sdk.processor.exceptions.InternalError;
import sawtooth.sdk.processor.exceptions.InvalidTransactionException;
import scala.Function1;
import scala.Option;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import scala.concurrent.Future;
import scala.runtime.BoxedUnit;

/**
 * An implementation of LedgerState for DAML.
 *
 * @author scealiontach
 */
public final class DamlLedgerState implements LedgerState<String> {

  private static final int COMPRESS_BUFFER_SIZE = 1024;

  private static final Logger LOGGER = Logger.getLogger(DamlLedgerState.class.getName());

  /**
   * The state which this class wraps and delegates to.
   */
  private final Context state;

  /**
   * @param aState the State class which this object wraps.
   */
  public DamlLedgerState(final Context aState) {
    this.state = aState;
  }

  private ByteString getStateOrNull(final String address)
      throws InternalError, InvalidTransactionException {
    final Map<String, ByteString> stateMap = state.getState(List.of(address));
    if (stateMap.containsKey(address)) {
      final ByteString bs = stateMap.get(address);
      if (bs.isEmpty() || bs == null) {
        return null;
      } else {
        return bs;
      }
    } else {
      return null;
    }
  }

  @Override
  public DamlStateValue getDamlState(final DamlStateKey key)
      throws InternalError, InvalidTransactionException {
    final String addr = Namespace.makeAddressForType(key);
    final ByteString bs = getStateOrNull(addr);
    if (bs == null) {
      return null;
    } else {
      if (key.getKeyCase().equals(DamlStateKey.KeyCase.COMMAND_DEDUP)) {
        return DamlStateValue.newBuilder()
            .setCommandDedup(DamlCommandDedupValue.newBuilder().build()).build();
      }
      try {
        return DamlStateValue.parseFrom(uncompressByteString(bs));
      } catch (InvalidProtocolBufferException e) {
        LOGGER.warning(String.format("Invalid protocol buffer at key %s", key.toString()));
        return null;
      }
    }
  }

  @Override
  public Map<DamlStateKey, DamlStateValue> getDamlStates(final Collection<DamlStateKey> keys)
      throws InternalError, InvalidTransactionException {
    return getDamlStates(keys.toArray(new DamlStateKey[] {}));
  }

  @Override
  public Map<DamlStateKey, DamlStateValue> getDamlStates(final DamlStateKey... keys)
      throws InternalError, InvalidTransactionException {
    final Map<DamlStateKey, DamlStateValue> retMap = new HashMap<>();
    for (final DamlStateKey k : keys) {
      final DamlStateValue damlState = getDamlState(k);
      if (null != damlState) {
        retMap.put(k, damlState);
      } else {
        LOGGER.fine(String.format("Skipping key %s since value is null", k));
      }
    }
    return retMap;
  }

  @Override
  public void setDamlStates(final Collection<Entry<DamlStateKey, DamlStateValue>> entries)
      throws InternalError, InvalidTransactionException {
    final Map<String, ByteString> setMap = new HashMap<>();
    for (final Entry<DamlStateKey, DamlStateValue> e : entries) {
      final DamlStateKey key = e.getKey();
      final DamlStateValue val = e.getValue();
      ByteString packDamlStateValue;
      if (key.getKeyCase().equals(DamlStateKey.KeyCase.COMMAND_DEDUP)) {
        LOGGER.fine("Swapping DamlStateKey for DamlStateValue on COMMAND_DEDUP");
        packDamlStateValue = key.toByteString();
      } else {
        packDamlStateValue = val.toByteString();
      }
      assert (packDamlStateValue.size() > 0);
      final String address = Namespace.makeAddressForType(key);
      setMap.put(address, compressByteString(packDamlStateValue));
    }
    state.setState(setMap.entrySet());
  }

  @Override
  public void setDamlState(final DamlStateKey key, final DamlStateValue val)
      throws InternalError, InvalidTransactionException {
    final Map<DamlStateKey, DamlStateValue> setMap = new HashMap<>();
    setMap.put(key, val);
    setDamlStates(setMap.entrySet());
  }

  public String addDamlLogEntry(final ByteString entryId, final ByteString entry)
      throws InternalError, InvalidTransactionException {
    final Map<String, ByteString> setMap = new HashMap<>();
    final String addr = Namespace.makeDamlStateAddress(entryId);
    setMap.put(addr, entry);
    state.setState(setMap.entrySet());
    sendLogEvent(entryId, entry);
    return addr;
  }

  private void sendLogEvent(final ByteString entryId, final ByteString entry)
      throws InternalError, InvalidTransactionException {
    final Map<String, String> attrMap = new HashMap<>();
    attrMap.put(EventConstants.DAML_LOG_ENTRY_ID_EVENT_ATTRIBUTE, entryId.toStringUtf8());
    final ByteString compressedData = compressByteString(entry);
    LOGGER.info(String.format("Sending event for %s, size=%s, compressed=%s",
        entryId.toStringUtf8(), entry.size(), compressedData.size()));
    state.addEvent(EventConstants.DAML_LOG_EVENT_SUBJECT, attrMap.entrySet(), compressedData);
  }

  @Override
  public Timestamp getRecordTime() throws InternalError {
    try {
      LOGGER.fine(String.format("Fetching global time %s", TIMEKEEPER_GLOBAL_RECORD));
      final Map<String, ByteString> stateMap =
          state.getState(Arrays.asList(TIMEKEEPER_GLOBAL_RECORD));
      if (stateMap.containsKey(TIMEKEEPER_GLOBAL_RECORD)) {
        final TimeKeeperGlobalRecord tkgr =
            TimeKeeperGlobalRecord.parseFrom(stateMap.get(TIMEKEEPER_GLOBAL_RECORD));
        LOGGER.fine(String.format("Record Time = %s", tkgr.getLastCalculatedTime()));
        return tkgr.getLastCalculatedTime();
      } else {
        LOGGER.warning("No global time has been set,assuming beginning of epoch");
        return Timestamp.newBuilder().setSeconds(0).setNanos(0).build();
      }
    } catch (final InvalidTransactionException exc) {
      LOGGER.warning(String.format("Error fetching global time, assuming beginning of epoch %s",
          exc.getMessage()));
      return Timestamp.newBuilder().setSeconds(0).setNanos(0).build();
    } catch (InternalError | InvalidProtocolBufferException exc) {
      final InternalError err = new InternalError(exc.getMessage());
      err.initCause(exc);
      throw err;
    }
  }

  private ByteString compressByteString(final ByteString input) throws InternalError {
    final long compressStart = System.currentTimeMillis();
    if (input.size() == 0) {
      return ByteString.EMPTY;
    }
    final Deflater deflater = new Deflater();
    deflater.setLevel(Deflater.BEST_SPEED);
    final byte[] inputBytes = input.toByteArray();

    deflater.setInput(inputBytes);
    deflater.finish();

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream(inputBytes.length);) {
      final byte[] buffer = new byte[COMPRESS_BUFFER_SIZE];
      while (!deflater.finished()) {
        final int bCount = deflater.deflate(buffer);
        baos.write(buffer, 0, bCount);
      }
      deflater.end();

      final ByteString bs = ByteString.copyFrom(baos.toByteArray());
      final long compressStop = System.currentTimeMillis();
      final long compressTime = compressStop - compressStart;
      LOGGER.fine(String.format("Compressed ByteString time=%s, original_size=%s, new_size=%s",
          compressTime, inputBytes.length, baos.size()));
      return bs;
    } catch (final IOException exc) {
      LOGGER.severe("ByteArrayOutputStream.close() has thrown an error which should never happen!");
      throw new InternalError(exc.getMessage());
    }
  }

  private ByteString uncompressByteString(final ByteString compressedInput) throws InternalError {
    final long uncompressStart = System.currentTimeMillis();
    if (compressedInput.size() == 0) {
      return ByteString.EMPTY;
    }
    final Inflater inflater = new Inflater();
    final byte[] inputBytes = compressedInput.toByteArray();
    inflater.setInput(inputBytes);

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream(inputBytes.length)) {
      final byte[] buffer = new byte[COMPRESS_BUFFER_SIZE];
      try {
        while (!inflater.finished()) {
          final int bCount = inflater.inflate(buffer);
          baos.write(buffer, 0, bCount);
        }
        inflater.end();

        final ByteString bs = ByteString.copyFrom(baos.toByteArray());
        final long uncompressStop = System.currentTimeMillis();
        final long uncompressTime = uncompressStop - uncompressStart;
        LOGGER.fine(String.format("Uncompressed ByteString time=%s, original_size=%s, new_size=%s",
            uncompressTime, inputBytes.length, baos.size()));
        return bs;
      } catch (final DataFormatException exc) {
        LOGGER.severe(String.format("Error uncompressing stream, throwing InternalError! %s",
            exc.getMessage()));
        throw new InternalError(exc.getMessage());
      }
    } catch (final IOException exc) {
      LOGGER.severe("ByteArrayOutputStream.close() has thrown an error which should never happen!");
      throw new InternalError(exc.getMessage());
    }
  }

  @Override
  public Future<String> appendToLog(final ByteString logEntryId, final ByteString logEntry) {
    try {
      return Future.successful(this.addDamlLogEntry(logEntryId, logEntry));
    } catch (InternalError | InvalidTransactionException e) {
      LOGGER.severe(String.format("Exception sending log event %s", e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  @Override
  public Future<Option<ByteString>> readState(final ByteString oneKey) {
    final String key = Namespace.makeDamlStateAddress(oneKey);
    List<String> keyList = List.of(key);
    try {
      Map<String, ByteString> stateVals = state.getState(keyList);
      for (String k : keyList) {
        if (stateVals.containsKey(key)) {
          return Future.successful(Option.apply(stateVals.get(k)));
        }
      }
      return Future.successful(Option.empty());
    } catch (InternalError | InvalidTransactionException e) {
      // Any errors here and we need to crash
      LOGGER.severe(String.format("Exception reading state %s", e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  @Override
  public Future<Seq<Option<ByteString>>> readState(final Seq<ByteString> keys) {
    List<String> keyList = new ArrayList<>();
    for (ByteString keybytes : JavaConverters.asJavaCollection(keys)) {
      final String key = Namespace.makeDamlStateAddress(keybytes);
      keyList.add(key);
    }
    try {
      Map<String, ByteString> stateVals = state.getState(keyList);
      List<Option<ByteString>> retState = new ArrayList<>();
      for (String key : keyList) {
        if (stateVals.containsKey(key)) {
          retState.add(Option.apply(stateVals.get(key)));
        } else {
          retState.add(Option.empty());
        }
      }
      return Future.successful(JavaConverters.asScalaBuffer(retState).toList());
    } catch (InternalError | InvalidTransactionException e) {
      // Any errors here and we need to crash
      LOGGER.severe(String.format("Exception reading state %s", e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  @Override
  public Future<BoxedUnit> writeState(final Seq<Tuple2<ByteString, ByteString>> keyValuePairs) {
    final Map<String, ByteString> setMap = new HashMap<>();

    for (final Tuple2<ByteString, ByteString> t : JavaConverters.asJavaCollection(keyValuePairs)) {
      final ByteString keybytes = t._1();
      final ByteString valbytes = t._2();
      final String key = Namespace.makeDamlStateAddress(keybytes);
      setMap.put(key, valbytes);
    }
    try {
      state.setState(setMap.entrySet());
    } catch (InternalError | InvalidTransactionException e) {
      LOGGER.log(Level.SEVERE, "Fatal exception writing state!", e);
      throw new RuntimeException(e);
    }
    return Future.successful(BoxedUnit.UNIT);
  }

  @Override
  public Future<BoxedUnit> writeState(final ByteString keybytes, final ByteString value) {
    Seq<Tuple2<ByteString, ByteString>> seq =
        JavaConversions.asScalaBuffer(List.of(Tuple2.apply(keybytes, value))).toList();
    return writeState(seq);
  }

  @Override
  public <T> Future<T> inTransaction(Function1<LedgerStateOperations<String>, Future<T>> body) {
    return body.apply(this);
  }

}
