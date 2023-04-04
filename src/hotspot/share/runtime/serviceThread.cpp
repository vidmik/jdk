/*
 * Copyright (c) 2012, 2023, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "classfile/classLoaderDataGraph.inline.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/protectionDomainCache.hpp"
#include "classfile/stringTable.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmClasses.hpp"
#include "gc/shared/oopStorage.hpp"
#include "gc/shared/oopStorageSet.hpp"
#include "memory/universe.hpp"
#include "oops/oopHandle.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/java.hpp"
#include "runtime/javaCalls.hpp"
#include "runtime/jniHandles.hpp"
#include "runtime/serviceThread.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/os.hpp"
#include "prims/jvmtiImpl.hpp"
#include "prims/jvmtiTagMap.hpp"
#include "prims/resolvedMethodTable.hpp"
#include "services/diagnosticArgument.hpp"
#include "services/diagnosticFramework.hpp"
#include "services/finalizerService.hpp"
#include "services/gcNotifier.hpp"
#include "services/lowMemoryDetector.hpp"
#include "services/threadIdTable.hpp"

DEBUG_ONLY(JavaThread* ServiceThread::_instance = nullptr;)
JvmtiDeferredEvent* ServiceThread::_jvmti_event = nullptr;
// The service thread has it's own static deferred event queue.
// Events can be posted before JVMTI vm_start, so it's too early to call JvmtiThreadState::state_for
// to add this field to the per-JavaThread event queue.  TODO: fix this sometime later
JvmtiDeferredEventQueue ServiceThread::_jvmti_service_queue;

void ServiceThread::initialize() {
  EXCEPTION_MARK;

  const char* name = "Service Thread";
  Handle thread_oop = JavaThread::create_system_thread_object(name, false /* not visible */, CHECK);

  ServiceThread* thread = new ServiceThread(&service_thread_entry);
  JavaThread::vm_exit_on_osthread_failure(thread);

  JavaThread::start_internal_daemon(THREAD, thread, thread_oop, NearMaxPriority);
  DEBUG_ONLY(_instance = thread;)
}

static void cleanup_oopstorages() {
  for (OopStorage* storage : OopStorageSet::Range<OopStorageSet::Id>()) {
    storage->delete_empty_blocks();
  }
}

enum ServiceWorkType {
  SENSORS_CHANGED,
  HAS_JVMTI_EVENTS,
  HAS_GC_NOTIFICATION_EVENT,
  HAS_DCMD_NOTIFICATION_EVENT,
  STRINGTABLE_WORK,
  SYMBOLTABLE_WORK,
  FINALIZERSERVICE_WORK,
  RESOLVED_METHOD_TABLE_WORK,
  THREAD_ID_TABLE_WORK,
  PROTECTION_DOMAIN_TABLE_WORK,
  OOPSTORAGE_WORK,
  OOP_HANDLES_TO_RELEASE,
  CLDG_CLEANUP_WORK,
  JVMTI_TAGMAP_WORK,

  SERVICE_WORK_TYPE_NOOF
};

class ServiceWork {
 private:
  bool _pending[SERVICE_WORK_TYPE_NOOF];
  bool _any_pending;
  JvmtiDeferredEvent _jvmti_event;

 public:
  ServiceWork() : _any_pending(false) {
    memset(_pending, 0, sizeof(_pending));
  }

  void set_pending(ServiceWorkType type, bool pending) {
    _pending[type] = pending;
    if (pending) {
      _any_pending = true;
    }
  }

  bool any_pending() {
    return _any_pending;
  }

  bool is_pending(ServiceWorkType type) {
    return _pending[type];
  }

  void set_jvmti_event(JvmtiDeferredEvent jvmti_event) {
    _jvmti_event = jvmti_event;
  }

  JvmtiDeferredEvent* get_jvmti_event() {
    return &_jvmti_event;
  }
};

ServiceWork ServiceThread::wait_for_work(JavaThread* jt) {
  // Need state transition ThreadBlockInVM so that this thread
  // will be handled by safepoint correctly when this thread is
  // notified at a safepoint.

  // This ThreadBlockInVM object is not also considered to be
  // suspend-equivalent because ServiceThread is not visible to
  // external suspension.

  ThreadBlockInVM tbivm(jt);

  MonitorLocker ml(Service_lock, Mutex::_no_safepoint_check_flag);
  ServiceWork pending_work;
  for (;;) {
    pending_work.set_pending(SENSORS_CHANGED, !UseNotificationThread && LowMemoryDetector::has_pending_requests());
    pending_work.set_pending(HAS_JVMTI_EVENTS, _jvmti_service_queue.has_events());
    pending_work.set_pending(HAS_GC_NOTIFICATION_EVENT, !UseNotificationThread && GCNotifier::has_event());
    pending_work.set_pending(HAS_DCMD_NOTIFICATION_EVENT, !UseNotificationThread && DCmdFactory::has_pending_jmx_notification());
    pending_work.set_pending(STRINGTABLE_WORK, StringTable::has_work());
    pending_work.set_pending(SYMBOLTABLE_WORK, SymbolTable::has_work());
    pending_work.set_pending(FINALIZERSERVICE_WORK, FinalizerService::has_work());
    pending_work.set_pending(RESOLVED_METHOD_TABLE_WORK, ResolvedMethodTable::has_work());
    pending_work.set_pending(THREAD_ID_TABLE_WORK, ThreadIdTable::has_work());
    pending_work.set_pending(PROTECTION_DOMAIN_TABLE_WORK, ProtectionDomainCacheTable::has_work());
    pending_work.set_pending(OOPSTORAGE_WORK, OopStorage::has_cleanup_work_and_reset());
    pending_work.set_pending(OOP_HANDLES_TO_RELEASE, JavaThread::has_oop_handles_to_release());
    pending_work.set_pending(CLDG_CLEANUP_WORK, ClassLoaderDataGraph::should_clean_metaspaces_and_reset());
    pending_work.set_pending(JVMTI_TAGMAP_WORK, JvmtiTagMap::has_object_free_events_and_reset());
    if (pending_work.any_pending()) {
      if (pending_work.is_pending(HAS_JVMTI_EVENTS)) {
        // Get the event under the Service_lock
        pending_work.set_jvmti_event(_jvmti_service_queue.dequeue());
        _jvmti_event = pending_work.get_jvmti_event();
      }
      return pending_work;
    }
    // Wait until notified that there is some work to do.
    ml.wait();
  }
}

void ServiceThread::service_thread_entry(JavaThread* jt, TRAPS) {
  while (true) {
    ServiceWork pending_work = wait_for_work(jt);

    if (pending_work.is_pending(STRINGTABLE_WORK)) {
      StringTable::do_concurrent_work(jt);
    }

    if (pending_work.is_pending(SYMBOLTABLE_WORK)) {
      SymbolTable::do_concurrent_work(jt);
    }

    if (pending_work.is_pending(FINALIZERSERVICE_WORK)) {
      FinalizerService::do_concurrent_work(jt);
    }

    if (pending_work.is_pending(HAS_JVMTI_EVENTS)) {
      _jvmti_event->post();
      _jvmti_event = nullptr;  // reset
    }

    if (!UseNotificationThread) {
      if (pending_work.is_pending(SENSORS_CHANGED)) {
        LowMemoryDetector::process_sensor_changes(jt);
      }

      if(pending_work.is_pending(HAS_GC_NOTIFICATION_EVENT)) {
        GCNotifier::sendNotification(CHECK);
      }

      if(pending_work.is_pending(HAS_DCMD_NOTIFICATION_EVENT)) {
        DCmdFactory::send_notification(CHECK);
      }
    }

    if (pending_work.is_pending(RESOLVED_METHOD_TABLE_WORK)) {
      ResolvedMethodTable::do_concurrent_work(jt);
    }

    if (pending_work.is_pending(THREAD_ID_TABLE_WORK)) {
      ThreadIdTable::do_concurrent_work(jt);
    }

    if (pending_work.is_pending(PROTECTION_DOMAIN_TABLE_WORK)) {
      ProtectionDomainCacheTable::unlink();
    }

    if (pending_work.is_pending(OOPSTORAGE_WORK)) {
      cleanup_oopstorages();
    }

    if (pending_work.is_pending(OOP_HANDLES_TO_RELEASE)) {
      JavaThread::release_oop_handles();
    }

    if (pending_work.is_pending(CLDG_CLEANUP_WORK)) {
      ClassLoaderDataGraph::safepoint_and_clean_metaspaces();
    }

    if (pending_work.is_pending(JVMTI_TAGMAP_WORK)) {
      JvmtiTagMap::flush_all_object_free_events();
    }
  }
}

void ServiceThread::enqueue_deferred_event(JvmtiDeferredEvent* event) {
  MutexLocker ml(Service_lock, Mutex::_no_safepoint_check_flag);
  // If you enqueue events before the service thread runs, gc
  // cannot keep the nmethod alive.  This could be restricted to compiled method
  // load and unload events, if we wanted to be picky.
  assert(_instance != nullptr, "cannot enqueue events before the service thread runs");
  _jvmti_service_queue.enqueue(*event);
  Service_lock->notify_all();
 }

void ServiceThread::oops_do_no_frames(OopClosure* f, CodeBlobClosure* cf) {
  JavaThread::oops_do_no_frames(f, cf);
  // The ServiceThread "owns" the JVMTI Deferred events, scan them here
  // to keep them alive until they are processed.
  if (_jvmti_event != nullptr) {
    _jvmti_event->oops_do(f, cf);
  }
  // Requires a lock, because threads can be adding to this queue.
  MutexLocker ml(Service_lock, Mutex::_no_safepoint_check_flag);
  _jvmti_service_queue.oops_do(f, cf);
}

void ServiceThread::nmethods_do(CodeBlobClosure* cf) {
  JavaThread::nmethods_do(cf);
  if (cf != nullptr) {
    if (_jvmti_event != nullptr) {
      _jvmti_event->nmethods_do(cf);
    }
    // Requires a lock, because threads can be adding to this queue.
    MutexLocker ml(Service_lock, Mutex::_no_safepoint_check_flag);
    _jvmti_service_queue.nmethods_do(cf);
  }
}
