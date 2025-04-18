/*
 * Copyright (c) 2000, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

package sun.jvm.hotspot.code;

import java.util.*;
import sun.jvm.hotspot.debugger.*;
import sun.jvm.hotspot.memory.*;
import sun.jvm.hotspot.runtime.*;
import sun.jvm.hotspot.types.*;
import sun.jvm.hotspot.utilities.*;
import sun.jvm.hotspot.utilities.Observable;
import sun.jvm.hotspot.utilities.Observer;

public class CodeCache {
  private static GrowableArray<CodeHeap> heapArray;

  static {
    VM.registerVMInitializedObserver(new Observer() {
        public void update(Observable o, Object data) {
          initialize(VM.getVM().getTypeDataBase());
        }
      });
  }

  private static synchronized void initialize(TypeDataBase db) {
    Type type = db.lookupType("CodeCache");

    // Get array of CodeHeaps
    AddressField heapsField = type.getAddressField("_heaps");
    heapArray = GrowableArray.create(heapsField.getValue(), new StaticBaseConstructor<>(CodeHeap.class));
  }

  public boolean contains(Address p) {
    for (int i = 0; i < heapArray.length(); ++i) {
      if (heapArray.at(i).contains(p)) {
        return true;
      }
    }
    return false;
  }

  /** When VM.getVM().isDebugging() returns true, this behaves like
      findBlobUnsafe */
  public CodeBlob findBlob(Address addr) {
    CodeBlob result = findBlobUnsafe(addr);
    if (result == null) return null;
    if (VM.getVM().isDebugging()) {
      return result;
    }
    // We could potientially look up non_entrant methods
    // NOTE: this is effectively a "guarantee", and is slightly different from the one in the VM
    return result;
  }

  public CodeBlob findBlobUnsafe(Address addr) {
    CodeHeap containing_heap = null;
    for (int i = 0; i < heapArray.length(); ++i) {
      if (heapArray.at(i).contains(addr)) {
        containing_heap = heapArray.at(i);
        break;
      }
    }
    if (containing_heap == null) {
      return null;
    }

    Address cbStart = containing_heap.findStart(addr);
    if (cbStart == null) return null;

    return createCodeBlobWrapper(cbStart, addr);
  }

  // cbStart - address of a code blob
  // addr    - address inside of a code blob
  public CodeBlob createCodeBlobWrapper(Address cbStart, Address addr) {
    Class<?> cbClass = CodeBlob.getClassFor(cbStart);
    CodeBlob result = (CodeBlob) VMObjectFactory.newObject(cbClass, cbStart);
    if (Assert.ASSERTS_ENABLED) {
      // The pointer to the HeapBlock that contains this blob is outside of the blob,
      // but it shouldn't be an error to find a blob based on the pointer to the HeapBlock.
      // The heap block header is padded out to an 8-byte boundary. See heap.hpp. The
      // simplest way to compute the header size is just 2 * addressSize.
      Assert.that(result.blobContains(addr) ||
                  result.blobContains(addr.addOffsetTo(2 * VM.getVM().getAddressSize())),
                  "found wrong CodeBlob");
    }
    return result;
  }

  public NMethod findNMethod(Address addr) {
    CodeBlob cb = findBlob(addr);
    if (Assert.ASSERTS_ENABLED) {
      Assert.that(cb == null || cb.isNMethod(), "did not find an nmethod");
    }
    return (NMethod) cb;
  }

  public NMethod findNMethodUnsafe(Address addr) {
    CodeBlob cb = findBlobUnsafe(addr);
    if (Assert.ASSERTS_ENABLED) {
      Assert.that(cb == null || cb.isNMethod(), "did not find an nmethod");
    }
    return (NMethod) cb;
  }

  public void iterate(CodeCacheVisitor visitor) {
    visitor.prologue(lowBound(), highBound());
    for (int i = 0; i < heapArray.length(); ++i) {
      CodeHeap current_heap = heapArray.at(i);
      current_heap.iterate(visitor, this);
    }
    visitor.epilogue();
  }

  //--------------------------------------------------------------------------------
  // Internals only below this point
  //

  private Address lowBound() {
    Address low = heapArray.at(0).begin();
    for (int i = 1; i < heapArray.length(); ++i) {
      if (heapArray.at(i).begin().lessThan(low)) {
        low = heapArray.at(i).begin();
      }
    }
    return low;
  }

  private Address highBound() {
    Address high = heapArray.at(0).end();
    for (int i = 1; i < heapArray.length(); ++i) {
      if (heapArray.at(i).end().greaterThan(high)) {
        high = heapArray.at(i).end();
      }
    }
    return high;
  }
}
