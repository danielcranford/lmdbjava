/*-
 * #%L
 * LmdbJava
 * %%
 * Copyright (C) 2016 The LmdbJava Open Source Project
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.lmdbjava;

import static jnr.ffi.Memory.allocateDirect;
import static jnr.ffi.NativeType.ADDRESS;
import jnr.ffi.Pointer;
import jnr.ffi.provider.MemoryManager;
import static org.lmdbjava.BufferProxy.MDB_VAL_STRUCT_SIZE;
import static org.lmdbjava.BufferProxy.STRUCT_FIELD_OFFSET_SIZE;
import static org.lmdbjava.Env.SHOULD_CHECK;
import static org.lmdbjava.Library.LIB;
import static org.lmdbjava.Library.RUNTIME;
import static org.lmdbjava.MaskedFlag.isSet;
import static org.lmdbjava.MaskedFlag.mask;
import static org.lmdbjava.ResultCodeMapper.checkRc;
import static org.lmdbjava.Txn.State.DONE;
import static org.lmdbjava.Txn.State.READY;
import static org.lmdbjava.Txn.State.RELEASED;
import static org.lmdbjava.Txn.State.RESET;
import static org.lmdbjava.TxnFlags.MDB_RDONLY_TXN;

/**
 * LMDB transaction.
 *
 * @param <T> buffer type
 */
public final class Txn<T> implements AutoCloseable {
  
  private static final MemoryManager MEM_MGR = RUNTIME.getMemoryManager();
  private final Env<T> env;
  private final T k;
  private final Txn<T> parent;
  private final BufferProxy<T> proxy;
  private final Pointer ptr;
  private final Pointer ptrKey;
  private final long ptrKeyAddr;
  private final Pointer ptrVal;
  private final long ptrValAddr;
  private final boolean readOnly;
  private State state;
  private final T v;
  
  Txn(final Env<T> env, final Txn<T> parent, final BufferProxy<T> proxy,
      final TxnFlags... flags) {
    this.env = env;
    this.proxy = proxy;
    final int flagsMask = mask(flags);
    this.readOnly = isSet(flagsMask, MDB_RDONLY_TXN);
    if (env.isReadOnly() && !this.readOnly) {
      throw new EnvIsReadOnly();
    }
    this.parent = parent;
    if (parent != null && parent.isReadOnly() != this.readOnly) {
      throw new IncompatibleParent();
    }
    final Pointer txnPtr = allocateDirect(RUNTIME, ADDRESS);
    final Pointer txnParentPtr = parent == null ? null : parent.ptr;
    checkRc(LIB.mdb_txn_begin(env.pointer(), txnParentPtr, flagsMask, txnPtr));
    ptr = txnPtr.getPointer(0);
    
    this.k = proxy.allocate();
    this.v = proxy.allocate();
    ptrKey = MEM_MGR.allocateTemporary(MDB_VAL_STRUCT_SIZE, false);
    ptrKeyAddr = ptrKey.address();
    ptrVal = MEM_MGR.allocateTemporary(MDB_VAL_STRUCT_SIZE, false);
    ptrValAddr = ptrVal.address();
    
    state = READY;
  }

  /**
   * Aborts this transaction.
   */
  public void abort() {
    checkReady();
    state = DONE;
    LIB.mdb_txn_abort(ptr);
  }

  /**
   * Closes this transaction by aborting if not already committed.
   *
   * <p>
   * Closing the transaction will invoke
   * {@link BufferProxy#deallocate(java.lang.Object)} for each read-only buffer
   * (ie the key and value).
   */
  @Override
  public void close() {
    if (state == RELEASED) {
      return;
    }
    if (state == READY) {
      LIB.mdb_txn_abort(ptr);
    }
    proxy.deallocate(k);
    proxy.deallocate(v);
    state = RELEASED;
    env.txnRelease(this);
  }

  /**
   * Commits this transaction.
   */
  public void commit() {
    checkReady();
    state = DONE;
    checkRc(LIB.mdb_txn_commit(ptr));
  }

  /**
   * Return the transaction's ID.
   *
   * @return A transaction ID, valid if input is an active transaction
   */
  public long getId() {
    return LIB.mdb_txn_id(ptr);
  }

  /**
   * Obtains this transaction's parent.
   *
   * @return the parent transaction (may be null)
   */
  public Txn<T> getParent() {
    return parent;
  }

  /**
   * Whether this transaction is read-only.
   *
   * @return if read-only
   */
  public boolean isReadOnly() {
    return readOnly;
  }

  /**
   * Fetch the buffer which holds a read-only view of the LMDI allocated memory.
   * Any use of this buffer must comply with the standard LMDB C "mdb_get"
   * contract (ie do not modify, do not attempt to release the memory, do not
   * use once the transaction or cursor closes, do not use after a write etc).
   *
   * @return the key buffer (never null)
   */
  public T key() {
    return k;
  }

  /**
   * Renews a read-only transaction previously released by {@link #reset()}.
   */
  public void renew() {
    if (state != RESET) {
      throw new NotResetException();
    }
    state = DONE;
    checkRc(LIB.mdb_txn_renew(ptr));
    state = READY;
  }

  /**
   * Aborts this read-only transaction and resets the transaction handle so it
   * can be reused upon calling {@link #renew()}.
   */
  public void reset() {
    checkReadOnly();
    if (state != READY && state != DONE) {
      throw new ResetException();
    }
    state = RESET;
    LIB.mdb_txn_reset(ptr);
  }

  /**
   * Fetch the buffer which holds a read-only view of the LMDI allocated memory.
   * Any use of this buffer must comply with the standard LMDB C "mdb_get"
   * contract (ie do not modify, do not attempt to release the memory, do not
   * use once the transaction or cursor closes, do not use after a write etc).
   *
   * @return the value buffer (never null)
   */
  public T val() {
    return v;
  }
  
  void checkReadOnly() throws ReadOnlyRequiredException {
    if (!readOnly) {
      throw new ReadOnlyRequiredException();
    }
  }
  
  void checkReady() throws NotReadyException {
    if (state != READY) {
      throw new NotReadyException();
    }
  }
  
  void checkWritesAllowed() throws ReadWriteRequiredException {
    if (readOnly) {
      throw new ReadWriteRequiredException();
    }
  }

  /**
   * Return the state of the transaction.
   *
   * @return the state
   */
  State getState() {
    return state;
  }
  
  void keyIn(final T key) {
    proxy.in(key, ptrKey, ptrKeyAddr);
    if (SHOULD_CHECK) {
      final long size = ptrKey.getLong(STRUCT_FIELD_OFFSET_SIZE);
      if (size <= 0) {
        throw new IndexOutOfBoundsException("Keys must be > 0 bytes");
      }
      if (size > env.getMaxKeySize()) {
        throw new IndexOutOfBoundsException("Key too many bytes for Env");
      }
    }
  }
  
  void keyOut() {
    proxy.out(k, ptrKey, ptrKeyAddr);
  }
  
  Pointer pointer() {
    return ptr;
  }
  
  Pointer pointerKey() {
    return ptrKey;
  }
  
  Pointer pointerVal() {
    return ptrVal;
  }
  
  void valIn(final T val, final boolean zeroLenValAllowed) {
    proxy.in(val, ptrVal, ptrValAddr);
    if (SHOULD_CHECK && !zeroLenValAllowed) {
      final long size = ptrVal.getLong(STRUCT_FIELD_OFFSET_SIZE);
      if (size <= 0) {
        throw new IndexOutOfBoundsException("Values must be > 0 bytes");
      }
    }
  }
  
  void valIn(final int size) {
    if (size <= 0) {
      throw new IndexOutOfBoundsException("Reservation must be > 0 bytes");
    }
    proxy.in(v, size, ptrVal, ptrValAddr);
  }
  
  void valOut() {
    proxy.out(v, ptrVal, ptrValAddr);
  }

  /**
   * Transaction must abort, has a child, or is invalid.
   */
  public static final class BadException extends LmdbNativeException {
    
    static final int MDB_BAD_TXN = -30_782;
    private static final long serialVersionUID = 1L;
    
    BadException() {
      super(MDB_BAD_TXN, "Transaction must abort, has a child, or is invalid");
    }
  }

  /**
   * Invalid reuse of reader locktable slot.
   */
  public static final class BadReaderLockException extends LmdbNativeException {
    
    static final int MDB_BAD_RSLOT = -30_783;
    private static final long serialVersionUID = 1L;
    
    BadReaderLockException() {
      super(MDB_BAD_RSLOT, "Invalid reuse of reader locktable slot");
    }
  }

  /**
   * The proposed R-W transaction is incompatible with a R-O Env.
   */
  public static class EnvIsReadOnly extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public EnvIsReadOnly() {
      super("Read-write Txn incompatible with read-only Env");
    }
  }

  /**
   * The proposed transaction is incompatible with its parent transaction.
   */
  public static class IncompatibleParent extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public IncompatibleParent() {
      super("Transaction incompatible with its parent transaction");
    }
  }

  /**
   * Transaction is not in a READY state.
   */
  public static final class NotReadyException extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public NotReadyException() {
      super("Transaction is not in ready state");
    }
  }

  /**
   * The current transaction has not been reset.
   */
  public static class NotResetException extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public NotResetException() {
      super("Transaction has not been reset");
    }
  }

  /**
   * The current transaction is not a read-only transaction.
   */
  public static class ReadOnlyRequiredException extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public ReadOnlyRequiredException() {
      super("Not a read-only transaction");
    }
  }

  /**
   * The current transaction is not a read-write transaction.
   */
  public static class ReadWriteRequiredException extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public ReadWriteRequiredException() {
      super("Not a read-write transaction");
    }
  }

  /**
   * The current transaction has already been reset.
   */
  public static class ResetException extends LmdbException {
    
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new instance.
     */
    public ResetException() {
      super("Transaction has already been reset");
    }
  }

  /**
   * Transaction has too many dirty pages.
   */
  public static final class TxFullException extends LmdbNativeException {
    
    static final int MDB_TXN_FULL = -30_788;
    private static final long serialVersionUID = 1L;
    
    TxFullException() {
      super(MDB_TXN_FULL, "Transaction has too many dirty pages");
    }
  }

  /**
   * Transaction states.
   */
  enum State {
    READY, DONE, RESET, RELEASED
  }
  
}
