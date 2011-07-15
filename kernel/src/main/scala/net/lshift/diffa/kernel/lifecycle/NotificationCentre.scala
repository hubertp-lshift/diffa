/**
 * Copyright (C) 2010-2011 LShift Ltd.
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

package net.lshift.diffa.kernel.lifecycle

import collection.mutable.ListBuffer
import net.lshift.diffa.kernel.events.VersionID
import org.joda.time.DateTime
import net.lshift.diffa.kernel.differencing.{PairScanState, MatchOrigin, PairScanListener, DifferencingListener}

/**
 * Central system component for subscribing to notifications. To prevent dependency loops, consumer components should not
 * directly depend on the NotificationCentre, but instead implement AgentLifecycleAware and listen for the
 * <code>onAgentInstantiationCompleted</code> event, which will contain a reference to the center.
 */
class NotificationCentre
    extends DifferencingListener
    with PairScanListener {
  private val differenceListeners = new ListBuffer[DifferencingListener]
  private val pairScanListeners = new ListBuffer[PairScanListener]

  /**
   * Registers a listener to receive different events.
   */
  def registerForDifferenceEvents(l:DifferencingListener) {
    differenceListeners += l
  }

   /**
   * Registers a listener to receive pair scan events.
   */
  def registerForPairScanEvents(l:PairScanListener) {
    pairScanListeners += l
  }

  //
  // Differencing Listener Multicast
  //

  def onMismatch(id: VersionID, lastUpdated: DateTime, upstreamVsn: String, downstreamVsn: String, origin: MatchOrigin) {
    differenceListeners.foreach(_.onMismatch(id, lastUpdated, upstreamVsn, downstreamVsn, origin))
  }
  def onMatch(id: VersionID, vsn: String, origin: MatchOrigin) {
    differenceListeners.foreach(_.onMatch(id, vsn, origin))
  }

  //
  // Pair Scan Listener Multicast
  //

  def pairScanStateChanged(pairKey: String, scanState: PairScanState) {
    pairScanListeners.foreach(_.pairScanStateChanged(pairKey, scanState))
  }
}