/*
 * Copyright 2019 Blockchain Technology Partners Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 * ------------------------------------------------------------------------------
 */
package com.blockchaintp.sawtooth.daml.processor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntry;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlLogEntryId;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateKey;
import com.daml.ledger.participant.state.kvutils.DamlKvutils.DamlStateValue;
import com.daml.ledger.validator.LedgerStateOperations;
import com.google.protobuf.Timestamp;

import sawtooth.sdk.processor.exceptions.InternalError;
import sawtooth.sdk.processor.exceptions.InvalidTransactionException;

/**
 * An interface to keep the coupling to the context implementation loose.
 *
 * @author scealiontach
 */
public interface LedgerState extends LedgerStateOperations<LogResult> {
  /**
   * Fetch a single DamlStateValue from the ledger state.
   *
   * @param key DamlStateKey identifying the operation
   * @return the DamlStateValue for this key or null
   * @throws InvalidTransactionException when there is an error relating to the client input
   * @throws InternalError               when there is an unexpected back end error.
   */
  DamlStateValue getDamlState(DamlStateKey key) throws InternalError, InvalidTransactionException;

  /**
   * Fetch a collection of DamlStateValues from the ledger state.
   *
   * @param keys a collection DamlStateKeys identifying the object
   * @return a map of DamlStateKey to DamlStateValue
   * @throws InvalidTransactionException when there is an error relating to the client input
   * @throws InternalError               when there is an unexpected back end error.
   */
  Map<DamlStateKey, DamlStateValue> getDamlStates(Collection<DamlStateKey> keys)
      throws InternalError, InvalidTransactionException;

  /**
   * Fetch a collection of DamlStateValues from the ledger state.
   *
   * @param keys one or more DamlStateKeys identifying the values to be fetches
   * @return a map of DamlStateKeys to DamlStateValues
   * @throws InvalidTransactionException when there is an error relating to the client input
   * @throws InternalError               when there is an unexpected back end error.
   */
  Map<DamlStateKey, DamlStateValue> getDamlStates(DamlStateKey... keys)
      throws InternalError, InvalidTransactionException;

  /**
   * @param key The key identifying this DamlStateValue
   * @param val the DamlStateValue
   * @throws InvalidTransactionException when there is an error relating to the client input
   * @throws InternalError               when there is an unexpected back end error.
   */
  void setDamlState(DamlStateKey key, DamlStateValue val)
      throws InternalError, InvalidTransactionException;

  /**
   * Store a collection of DamlStateValues at the logical keys provided.
   *
   * @param entries a collection of tuples of DamlStateKey to DamlStateValue mappings
   * @throws InvalidTransactionException when there is an error relating to the client input
   * @throws InternalError               when there is an unexpected back end error.
   */
  void setDamlStates(Collection<Entry<DamlStateKey, DamlStateValue>> entries)
      throws InternalError, InvalidTransactionException;

  /**
   * @param entryId Id of this log entry
   * @param entry   the log entry to set
   * @return the new entry list after the addition
   * @throws InvalidTransactionException when there is an error relating to the client input
   * @throws InternalError               when there is an unexpected back end error.
   */
  List<String> addDamlLogEntry(DamlLogEntryId entryId, DamlLogEntry entry)
      throws InternalError, InvalidTransactionException;

  /**
   * Fetch the current global record time.
   *
   * @return a Timestamp
   * @throws InternalError when there is an unexpected back end error.
   */
  Timestamp getRecordTime() throws InternalError;

}
